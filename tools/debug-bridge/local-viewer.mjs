// Runs the log viewer on this machine and forwards /api/v1 to a remote bridge. Holds no token: the page sends its own.
//   node tools/debug-bridge/local-viewer.mjs [--upstream http://host:port] [--port 8790]
// The upstream defaults to MCARPLAY_BRIDGE_URL, then mcarplay.bridgeUrl in local.properties (ws/wss become http/https).
import fs from 'node:fs';
import http from 'node:http';
import { Readable } from 'node:stream';
import { fileURLToPath, pathToFileURL } from 'node:url';
import { handleViewer } from './agent-api.mjs';

const FORWARDED_HEADERS = ['content-type', 'content-disposition', 'cache-control', 'www-authenticate', 'x-content-type-options'];
const LOCAL_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);
const MAX_EXPORT_PAGES = 5000;

function send(res, status, body) {
  res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
  res.end(JSON.stringify(body));
}

export function upstreamOrigin(value) {
  const url = new URL(String(value).trim().replace(/^ws(s?):\/\//i, 'http$1://'));
  if (url.protocol !== 'http:' && url.protocol !== 'https:') throw new Error(`Unsupported upstream: ${value}`);
  return url.origin;
}

function defaultUpstream() {
  if (process.env.MCARPLAY_BRIDGE_URL) return process.env.MCARPLAY_BRIDGE_URL;
  const file = fileURLToPath(new URL('../../local.properties', import.meta.url));
  const line = fs.existsSync(file) && fs.readFileSync(file, 'utf8').split(/\r?\n/).find((l) => /^\s*mcarplay\.bridgeUrl\s*=/.test(l));
  if (!line) throw new Error('Set --upstream, MCARPLAY_BRIDGE_URL or mcarplay.bridgeUrl in local.properties');
  return line.slice(line.indexOf('=') + 1);
}

/**
 * An older bridge has no /api/v1/export: rebuild the file from /api/v1/logs pages instead. Each page is redacted
 * separately upstream, so <NAME-n> aliases may differ between pages; X-Export-Mode: paged tells the viewer.
 */
async function pagedExport(upstream, authorization, params, res) {
  const format = params.get('format') ?? 'txt';
  if (format !== 'txt' && format !== 'jsonl') return send(res, 400, { error: 'format must be txt or jsonl' });
  const pages = [];
  let cursor = null;
  let file = params.get('file');
  do {
    const query = new URLSearchParams({ deviceId: params.get('deviceId') ?? '', limit: '2000' });
    if (params.get('contains')) query.set('contains', params.get('contains'));
    if (cursor) query.set('cursor', cursor);
    else if (file) query.set('file', file);
    const response = await fetch(`${upstream}/api/v1/logs?${query}`, { headers: { Authorization: authorization } });
    if (!response.ok) {
      res.writeHead(response.status, { 'Content-Type': response.headers.get('content-type') ?? 'application/json', 'Cache-Control': 'no-store' });
      return res.end(Buffer.from(await response.arrayBuffer()));
    }
    const page = await response.json();
    file = page.file;
    pages.push(page.lines);
    cursor = page.nextCursor;
  } while (cursor && pages.length < MAX_EXPORT_PAGES);
  if (cursor) return send(res, 413, { error: 'Log file is too large to export' });
  const lines = pages.reverse().flat();
  const body = lines.map((l) => (format === 'txt' ? `${l.text}\n` : `${JSON.stringify({ seq: l.seq, text: l.text })}\n`)).join('');
  res.writeHead(200, {
    'Content-Type': format === 'txt' ? 'text/plain; charset=utf-8' : 'application/x-ndjson; charset=utf-8',
    'Content-Disposition': `attachment; filename="${String(file).replace(/\.jsonl$/, '').replace(/[^A-Za-z0-9_-]/g, '_')}-redacted.${format}"`,
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'X-Export-Mode': 'paged',
  });
  res.end(body);
}

export function createLocalViewer({ upstream }) {
  const origin = upstreamOrigin(upstream);
  return function handle(req, res) {
    // Only answer to local names, so a rebinding page on another host name cannot drive the proxy.
    const host = String(req.headers.host ?? '').replace(/:\d+$/, '').toLowerCase();
    if (!LOCAL_HOSTS.has(host)) return send(res, 421, { error: 'Use http://127.0.0.1 or http://localhost' });
    const url = new URL(req.url ?? '/', 'http://localhost');
    if (url.pathname === '/') return res.writeHead(302, { Location: '/log-viewer/' }).end();
    if (handleViewer(req, res)) return;
    if (url.pathname !== '/api/v1' && !url.pathname.startsWith('/api/v1/')) return send(res, 404, { error: 'Not found' });
    if (req.method !== 'GET') {
      res.setHeader('Allow', 'GET');
      return send(res, 405, { error: 'This API is read-only' });
    }
    const authorization = typeof req.headers.authorization === 'string' ? req.headers.authorization : '';
    void (async () => {
      const response = await fetch(origin + url.pathname + url.search, { headers: authorization ? { Authorization: authorization } : {} });
      if (url.pathname === '/api/v1/export' && response.status === 404) {
        const body = await response.json().catch(() => ({}));
        if (body.error === 'Unknown endpoint') return pagedExport(origin, authorization, url.searchParams, res);
        return send(res, 404, body);
      }
      const headers = {};
      for (const name of FORWARDED_HEADERS) if (response.headers.has(name)) headers[name] = response.headers.get(name);
      res.writeHead(response.status, headers);
      if (!response.body) return res.end();
      Readable.fromWeb(response.body).on('error', (e) => res.destroy(e)).pipe(res);
    })().catch((e) => {
      console.error(`upstream ${url.pathname} failed: ${e.cause?.code ?? e.cause?.message ?? e.message}`);
      if (res.headersSent) return res.destroy();
      send(res, 502, { error: `Cannot reach the bridge at ${origin}` });
    });
  };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const args = process.argv.slice(2);
  const arg = (name) => {
    const index = args.indexOf(name);
    return index >= 0 ? args[index + 1] : undefined;
  };
  const upstream = upstreamOrigin(arg('--upstream') ?? defaultUpstream());
  const port = Number(arg('--port') ?? process.env.PORT ?? 8790);
  http.createServer(createLocalViewer({ upstream })).on('error', (e) => {
    console.error(e.code === 'EADDRINUSE'
      ? `Port ${port} is already in use (another viewer may be running at http://127.0.0.1:${port}/log-viewer/); pass --port to use another.`
      : e.message);
    process.exit(1);
  }).listen(port, '127.0.0.1', () => {
    console.log(`mcarplay log viewer: http://127.0.0.1:${port}/log-viewer/ -> ${upstream}`);
  });
}
