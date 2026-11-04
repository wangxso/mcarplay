import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';
import { createAgentApi, createRedactor } from './agent-api.mjs';

const token = 'read-test-'.repeat(8);
let root, api, server, base;
const request = (route, options = {}) => fetch(base + route, { ...options, headers: { Authorization: `Bearer ${token}`, ...options.headers } });

before(async () => {
  root = await fs.mkdtemp(path.join(os.tmpdir(), 'mcarplay-agent-api-'));
  await fs.writeFile(path.join(root, 'phone.jsonl'), Array.from({ length: 10 }, (_, i) => JSON.stringify({ seq: i + 1, text: `line ${i + 1} 中文 ${i % 2 ? 'error' : 'info'}` }) + '\n').join(''));
  api = createAgentApi({ logsDir: root, token, listDevices: () => [{ deviceId: 'phone', model: 'test phone', online: true, pairingCode: 'MUST_NOT_LEAK' }] });
  server = http.createServer((req, res) => { if (!api.handle(req, res)) res.writeHead(404).end(); });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  base = `http://127.0.0.1:${server.address().port}`;
});
after(async () => {
  await new Promise((resolve) => server.close(resolve));
  await fs.rm(root, { recursive: true, force: true });
});

test('requires independent bearer authentication; query token and phone pairing cannot authenticate', async () => {
  assert.equal((await fetch(base + `/api/v1/devices?token=${token}`)).status, 401);
  assert.equal((await request('/api/v1/devices', { headers: { Authorization: 'Bearer wrong' } })).status, 401);
  assert.equal((await request('/api/v1/devices')).status, 200);
});

test('blocks unrelated browser origins and all mutations', async () => {
  assert.equal((await request('/api/v1/devices', { headers: { Origin: 'https://unrelated.example' } })).status, 403);
  assert.equal((await request('/api/v1/devices', { method: 'POST' })).status, 405);
  assert.equal((await request('/api/v1/command')).status, 404);
});

test('device status omits pairing codes and responses cannot be cached', async () => {
  const response = await request('/api/v1/devices');
  assert.equal(response.headers.get('cache-control'), 'no-store');
  const value = await response.json();
  assert.equal(value.devices[0].online, true);
  assert.equal(JSON.stringify(value).includes('MUST_NOT_LEAK'), false);
});

test('pages backwards without duplicating or losing UTF-8 records', async () => {
  const first = await api.readLogs({ deviceId: 'phone', limit: 3 });
  assert.deepEqual(first.lines.map((l) => l.seq), [8, 9, 10]);
  const second = await api.readLogs({ deviceId: 'phone', limit: 3, cursor: first.nextCursor });
  assert.deepEqual(second.lines.map((l) => l.seq), [5, 6, 7]);
  assert.ok(second.lines.every((l) => l.text.includes('中文')));
  const filtered = await api.readLogs({ deviceId: 'phone', limit: 2, contains: 'ERROR' });
  assert.deepEqual(filtered.lines.map((l) => l.seq), [8, 10]);
});

test('validates device IDs, pagination, limits and file selection', async () => {
  for (const query of ['deviceId=../phone', 'deviceId=phone&file=../.env', 'deviceId=phone&limit=2oops', 'deviceId=phone&limit=2001', 'deviceId=phone&cursor=bad']) {
    assert.ok([400, 404].includes((await request(`/api/v1/logs?${query}`)).status));
  }
});

test('detects rotation rather than applying a stale cursor to a new generation', async () => {
  await fs.writeFile(path.join(root, 'rotating.jsonl'), '{"seq":1,"text":"old"}\n{"seq":2,"text":"old"}\n');
  const first = await api.readLogs({ deviceId: 'rotating', limit: 1 });
  await fs.rename(path.join(root, 'rotating.jsonl'), path.join(root, 'rotating-1793000000000.jsonl'));
  await fs.writeFile(path.join(root, 'rotating.jsonl'), '{"seq":1,"text":"new"}\n');
  await assert.rejects(api.readLogs({ deviceId: 'rotating', cursor: first.nextCursor }), (e) => e.status === 409);
  const files = await api.filesFor('rotating');
  assert.equal(files.length, 2);
  const old = await api.readLogs({ deviceId: 'rotating', file: 'rotating-1793000000000.jsonl' });
  assert.equal(old.lines.length, 2);
});

test('does not follow symlinks or include incomplete trailing records', async () => {
  await fs.symlink(path.join(root, 'phone.jsonl'), path.join(root, 'symlink.jsonl'));
  await assert.rejects(api.readLogs({ deviceId: 'symlink' }), (e) => e.status === 404);
  await fs.writeFile(path.join(root, 'partial.jsonl'), '{"seq":1,"text":"complete"}\n{"seq":2,"text":"incomplete');
  assert.deepEqual((await api.readLogs({ deviceId: 'partial' })).lines.map((l) => l.seq), [1]);
});

test('bounded windows can be paged back to the start of a large file', async () => {
  const large = Array.from({ length: 6000 }, (_, i) => JSON.stringify({ seq: i + 1, text: '中文'.repeat(100) }) + '\n').join('');
  await fs.writeFile(path.join(root, 'large.jsonl'), large);
  const seen = [];
  let cursor;
  do {
    const page = await api.readLogs({ deviceId: 'large', limit: 1000, cursor });
    seen.push(...page.lines.map((l) => l.seq));
    cursor = page.nextCursor;
  } while (cursor);
  assert.equal(new Set(seen).size, 6000);
  assert.equal(seen.length, 6000);
});

test('redacts credentials, names, network identifiers, URL secrets and protocol payloads', () => {
  const redact = createRedactor(['pairing-code-value']);
  const original = 'password="private password" token=secret ssid="my home" MAC AA:BB:CC:DD:EE:FF 10.0.0.172 mail@example.com 13800138000 deviceId=abc pairing-code-value https://x.test/?token=hidden';
  const result = redact(original);
  for (const value of ['private password', 'secret', 'my home', 'AA:BB:CC:DD:EE:FF', '10.0.0.172', 'mail@example.com', '13800138000', 'deviceId=abc', 'pairing-code-value', 'hidden']) assert.equal(result.includes(value), false, value);
  assert.equal(redact('RAW rx 01 02 03'), '[wire payload omitted]');
  // Split so the pre-commit PEM scan does not mistake the fixture for a real key.
  assert.equal(redact('-----BEGIN ' + 'PRIVATE KEY-----\nprivate-data\n-----END PRIVATE KEY-----').includes('private-data'), false);
});

test('hides hotspot passwords and names behind CJK keys, as the app logs them', () => {
  const result = createRedactor()('热点密码=abc123 车机密码：xyz789\n>>> 车机热点 "VW Passat 1234" 已断开\nWIFI: 等待切换到 "VW Passat 1234"\nCTRL: 此处对齐到热点 VW Passat 1234');
  for (const value of ['abc123', 'xyz789', 'VW Passat 1234', 'Passat']) assert.equal(result.includes(value), false, value);
  assert.equal(result.match(/<NAME-1>/g).length, 3);
});

test('readLogs hides a name declared in an older record wherever newer records repeat it', async () => {
  const rows = ['WIFI: 拿到车机热点 ssid=MIB3 Car AP channel=48', 'WIFI: 已连在 "MIB3 Car AP"', 'joining MIB3 Car AP'];
  await fs.writeFile(path.join(root, 'learn.jsonl'), rows.map((text, i) => JSON.stringify({ seq: i + 1, text }) + '\n').join(''));
  const page = await api.readLogs({ deviceId: 'learn', limit: 2 });
  assert.deepEqual(page.lines.map((l) => l.seq), [2, 3]);
  assert.equal(JSON.stringify(page).includes('MIB3'), false);
  assert.equal((await api.readLogs({ deviceId: 'learn', contains: 'MIB3' })).lines.length, 0);
});

test('matches the app redactor on names, identifiers and app constants', () => {
  const redact = createRedactor([], ['FamilyCar', 'CarPlay']);
  const names = redact('ssid=Private Family WiFi channel=48 security=2\njoining Private Family WiFi\n目标「Family Pad」\nFamily Pad disconnected\n连接 FamilyCar 失败');
  for (const value of ['Private Family WiFi', 'Family Pad', 'FamilyCar']) assert.equal(names.includes(value), false, value);
  assert.ok(names.includes('channel=48 security=2'));
  const tokens = redact('CarPlayService started\nconnecting CarPlay now');
  assert.ok(tokens.includes('CarPlayService started'));
  assert.ok(/connecting <NAME-\d+> now/.test(tokens));
  const constants = createRedactor()('广播 _carplay-ctrl._tcp name=sender id=x\nVideoSender: sender ready\nserviceName=Lobby lobby');
  assert.ok(constants.includes('name=sender') && constants.includes('VideoSender: sender ready') && constants.includes('serviceName=Lobby'));
  assert.equal(constants.includes('<NAME-'), false);
  assert.equal(createRedactor()('[12:34:56.000] RAW tx: 70617373776f7264'), '[12:34:56.000] [wire payload omitted]');
  const video = 'VIDEO: CarWith 参数集 profile=66 constraint=0x80 level=31\nENV: Wi-Fi rssi=-48dBm 5745MHz 速率=433Mbps\nEVENT: forceKeyFrame=60 requestUI=20 at 12:34:56.789 port=7240';
  assert.equal(createRedactor()(video), video);
});

test('serves the viewer without data or token, under a strict CSP', async () => {
  const page = await fetch(base + '/log-viewer/');
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-security-policy'), /default-src 'none'.*connect-src 'self'/);
  const html = await page.text();
  assert.ok(html.includes('viewer.js') && !html.includes(token));
  assert.equal((await fetch(base + '/log-viewer/viewer.js')).headers.get('content-type'), 'text/javascript; charset=utf-8');
  assert.equal((await fetch(base + '/log-viewer', { redirect: 'manual' })).status, 308);
  for (const route of ['/log-viewer/../agent-api.mjs', '/log-viewer/%2e%2e/agent-api.mjs', '/log-viewer/viewer.test.js']) {
    assert.equal((await fetch(base + route)).status, 404, route);
  }
  assert.equal((await fetch(base + '/log-viewer/', { method: 'POST' })).status, 405);
});

test('exports a whole file with one redactor, so aliases match across every line', async () => {
  const rows = ['WIFI: ssid=Home Car AP channel=36', ...Array.from({ length: 3000 }, (_, i) => `line ${i} joining Home Car AP 中文`), 'RAW rx 01 02'];
  await fs.writeFile(path.join(root, 'export.jsonl'), rows.map((text, i) => JSON.stringify({ seq: i + 1, text }) + '\n').join('') + '{"seq":9999,"te');
  assert.equal((await fetch(base + '/api/v1/export?deviceId=export')).status, 401);
  const response = await request('/api/v1/export?deviceId=export');
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('content-disposition'), 'attachment; filename="export-redacted.txt"');
  const lines = (await response.text()).trimEnd().split('\n');
  assert.equal(lines.length, rows.length);
  assert.equal(lines.join('\n').includes('Home Car'), false);
  assert.ok(lines.slice(1, -1).every((l) => l.includes('joining <NAME-1>')));
  assert.equal(lines.at(-1), '[wire payload omitted]');
  const jsonl = await request('/api/v1/export?deviceId=export&format=jsonl&contains=LINE 299');
  const records = (await jsonl.text()).trimEnd().split('\n').map((l) => JSON.parse(l));
  assert.deepEqual(records.map((r) => r.seq), [301, ...Array.from({ length: 10 }, (_, i) => 2992 + i)]);
  for (const query of ['deviceId=export&format=html', 'deviceId=export&file=../.env', 'deviceId=../export']) {
    assert.ok([400, 404].includes((await request(`/api/v1/export?${query}`)).status), query);
  }
});

test('a download cancelled while waiting for drain still frees its read slot', async () => {
  await fs.writeFile(path.join(root, 'cancel.jsonl'), Array.from({ length: 50 }, (_, i) => JSON.stringify({ seq: i + 1, text: `line ${i}` }) + '\n').join(''));
  class ClosingResponse extends EventEmitter {
    destroyed = false;
    writeHead() { return this; }
    write() {
      setImmediate(() => { this.destroyed = true; this.emit('close'); });
      return false;
    }
    end() {}
  }
  for (let i = 0; i < 5; i++) await api.exportLog({ deviceId: 'cancel' }, new ClosingResponse());
  assert.equal((await request('/api/v1/logs?deviceId=cancel&limit=5')).status, 200);
});
