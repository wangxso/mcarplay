import assert from 'node:assert/strict';
import fs from 'node:fs/promises';
import http from 'node:http';
import os from 'node:os';
import path from 'node:path';
import { after, before, test } from 'node:test';
import { createAgentApi } from './agent-api.mjs';
import { createLocalViewer, upstreamOrigin } from './local-viewer.mjs';

const token = 'local-test-'.repeat(4);
let root, servers = [], current, legacy;

async function listen(handler) {
  const server = http.createServer(handler);
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  servers.push(server);
  return `http://127.0.0.1:${server.address().port}`;
}

before(async () => {
  root = await fs.mkdtemp(path.join(os.tmpdir(), 'mcarplay-local-viewer-'));
  const rows = ['ssid=Home Car AP channel=36', ...Array.from({ length: 4500 }, (_, i) => `line ${i}`)];
  await fs.writeFile(path.join(root, 'phone.jsonl'), rows.map((text, i) => JSON.stringify({ seq: i + 1, text }) + '\n').join(''));
  const api = createAgentApi({ logsDir: root, token });
  const upstream = await listen((req, res) => { if (!api.handle(req, res)) res.writeHead(404).end(); });
  // An older bridge: the same API without /api/v1/export.
  const old = await listen((req, res) => {
    if (req.url.startsWith('/api/v1/export') && api.authorize(req, res)) {
      res.writeHead(404, { 'Content-Type': 'application/json' }).end('{"error":"Unknown endpoint"}');
    } else if (!req.url.startsWith('/api/v1/export') && !api.handle(req, res)) res.writeHead(404).end();
  });
  current = await listen(createLocalViewer({ upstream: upstream.replace('http', 'ws') + '/bridge' }));
  legacy = await listen(createLocalViewer({ upstream: old }));
});
after(async () => {
  await Promise.all(servers.map((s) => new Promise((resolve) => s.close(resolve))));
  await fs.rm(root, { recursive: true, force: true });
});

const get = (base, route, headers = { Authorization: `Bearer ${token}` }) => fetch(base + route, { headers, redirect: 'manual' });

test('maps bridge WebSocket URLs to their HTTP origin', () => {
  assert.equal(upstreamOrigin('ws://nas.example:8787/ws?key=x'), 'http://nas.example:8787');
  assert.equal(upstreamOrigin('wss://bridge.example/path'), 'https://bridge.example');
  assert.throws(() => upstreamOrigin('file:///etc/passwd'));
});

test('serves the viewer locally and forwards the caller token, never one of its own', async () => {
  assert.equal((await get(current, '/', {})).headers.get('location'), '/log-viewer/');
  assert.equal((await get(current, '/log-viewer/', {})).status, 200);
  assert.equal((await get(current, '/api/v1/devices', {})).status, 401);
  assert.equal((await get(current, '/api/v1/devices')).status, 200);
  assert.equal((await fetch(current + '/api/v1/devices', { method: 'POST', headers: { Authorization: `Bearer ${token}` } })).status, 405);
  assert.equal((await get(current, '/elsewhere')).status, 404);
});

test('refuses requests addressed to a non-local host name', async () => {
  const status = await new Promise((resolve, reject) => {
    http.get(current + '/log-viewer/', { headers: { Host: 'rebind.example' } }, (res) => resolve(res.statusCode)).on('error', reject);
  });
  assert.equal(status, 421);
});

test('streams the bridge export when the bridge has one', async () => {
  const response = await get(current, '/api/v1/export?deviceId=phone');
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('x-export-mode'), null);
  const lines = (await response.text()).trimEnd().split('\n');
  assert.equal(lines.length, 4501);
  assert.equal(lines.join('\n').includes('Home Car'), false);
});

test('falls back to paging an older bridge, complete and in order', async () => {
  assert.equal((await get(legacy, '/api/v1/export?deviceId=phone', {})).status, 401);
  const response = await get(legacy, '/api/v1/export?deviceId=phone&format=jsonl');
  assert.equal(response.status, 200);
  assert.equal(response.headers.get('x-export-mode'), 'paged');
  assert.equal(response.headers.get('content-disposition'), 'attachment; filename="phone-redacted.jsonl"');
  const seqs = (await response.text()).trimEnd().split('\n').map((l) => JSON.parse(l).seq);
  assert.deepEqual(seqs, Array.from({ length: 4501 }, (_, i) => i + 1));
  assert.equal((await get(legacy, '/api/v1/export?deviceId=phone&format=html')).status, 400);
  assert.equal((await get(legacy, '/api/v1/export?deviceId=../phone')).status, 400);
});
