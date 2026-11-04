'use strict';

// Read-only viewer for /api/v1. Log text is untrusted: it is only ever inserted as text nodes.
const TOKEN_KEY = 'mcarplay.logViewer.token';
const PREFS_KEY = 'mcarplay.logViewer.prefs';
const FOLLOW_MS = 5000;
const FOLLOW_LIMIT = 200;
const GAP_SECONDS = 120;
// The bridge scans at most this much of a file per request (MAX_BYTES in agent-api.mjs). A filtered window can be
// empty while older text still matches, so one action follows the cursor through at most SCAN_WINDOWS windows.
const WINDOW_MIB = 2;
const SCAN_WINDOWS = 16;
const ERROR = /error|exception|fatal|fail|crash|失败|错误|异常|崩溃/i;
const WARN = /warn|timeout|retry|超时|重试|警告/i;
const STAMP = /^\[(\d{2}):(\d{2}):(\d{2})(?:\.(\d{1,3}))?\]\s?/;
const TAG = /^([A-Z][A-Z0-9_-]{1,15}):\s?/;
const SESSION_MARKER = '进程启动';
const THEMES = ['auto', 'light', 'dark'];
const THEME_NAMES = { auto: '跟随系统', light: '浅色', dark: '深色' };

const $ = (id) => document.getElementById(id);
const el = {
  progress: $('progress'), auth: $('auth'), token: $('token'), connect: $('connect'), logout: $('logout'), theme: $('theme'), helpOpen: $('help-open'),
  pill: $('device-pill'), pillText: $('device-pill-text'), toolbar: $('toolbar'), subbar: $('subbar'),
  device: $('device'), file: $('file'), limit: $('limit'), contains: $('contains'), refresh: $('refresh'), follow: $('follow'),
  exportTxt: $('export-txt'), exportJsonl: $('export-jsonl'), tagFilter: $('tag-filter'),
  countAll: $('count-all'), countError: $('count-error'), countWarn: $('count-warn'),
  prevIssue: $('prev-issue'), nextIssue: $('next-issue'), wrap: $('wrap'), fontDown: $('font-down'), fontUp: $('font-up'),
  viewport: $('viewport'), empty: $('empty'), emptyTitle: $('empty-title'), emptyText: $('empty-text'),
  older: $('older'), lines: $('lines'), jump: $('jump'), jumpText: $('jump-text'),
  status: $('status'), stats: $('stats'), toast: $('toast'), help: $('help'), helpClose: $('help-close'),
};

const state = {
  token: '', devices: [], rows: [], nextCursor: null, pages: 0, file: null, generation: null,
  level: 'all', tag: null, selected: null, loads: 0, followTimer: null, unseen: 0, updatedAt: null,
  // Bumped by every load that replaces the view; a response from an older generation is dropped.
  view: 0,
  // The file list has its own generation: a log refresh must not make a device's pending list look stale.
  // filesFor is the device whose files fill the file selector; filesPending is the device being listed.
  filesView: 0, filesFor: null, filesPending: null,
  // Windows scanned since the latest load, for the "no match yet" message.
  scanned: 0,
  // Every request of this connection uses this signal; disconnect() aborts it and starts a new one.
  abort: new AbortController(),
  filterTimer: null,
};

class HttpError extends Error {
  constructor(status, message) {
    super(message);
    this.status = status;
  }
}

/* ---------- Storage (each access may throw in private windows) ---------- */
function session(action) {
  try {
    return action(window.sessionStorage);
  } catch {
    return null;
  }
}

function loadPrefs() {
  try {
    return { wrap: true, fontSize: 12.5, theme: 'auto', ...JSON.parse(window.localStorage.getItem(PREFS_KEY) || '{}') };
  } catch {
    return { wrap: true, fontSize: 12.5, theme: 'auto' };
  }
}

const prefs = loadPrefs();

function savePrefs() {
  try {
    window.localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
  } catch { /* Preferences are a convenience only. */ }
}

/* ---------- Small helpers ---------- */
function setStatus(text, isError = false) {
  el.status.textContent = text;
  el.status.title = text;
  el.status.classList.toggle('error', isError);
}

let toastTimer = null;
function toast(text) {
  el.toast.textContent = text;
  el.toast.hidden = false;
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => { el.toast.hidden = true; }, 1600);
}

function setLoading(delta) {
  state.loads += delta;
  el.progress.hidden = state.loads <= 0;
}

function formatBytes(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`;
}

function formatDuration(seconds) {
  const s = Math.round(seconds);
  if (s < 3600) return `${Math.floor(s / 60)} 分 ${s % 60} 秒`;
  return `${Math.floor(s / 3600)} 小时 ${Math.floor((s % 3600) / 60)} 分`;
}

function option(value, label) {
  const node = document.createElement('option');
  node.value = value;
  node.textContent = label;
  return node;
}

function make(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text != null) node.textContent = text;
  return node;
}

function query() {
  return { deviceId: el.device.value, file: el.file.value, limit: el.limit.value, contains: el.contains.value.trim() };
}

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
  } catch {
    // Plain-HTTP deployments are not a secure context, so the async clipboard API is unavailable there.
    const area = make('textarea');
    area.value = text;
    area.setAttribute('readonly', '');
    area.className = 'clipboard';
    document.body.append(area);
    area.select();
    document.execCommand('copy');
    area.remove();
  }
  toast('已复制');
}

/* ---------- HTTP ---------- */
async function request(route, params = {}) {
  const url = new URL(route, window.location.origin);
  for (const [key, value] of Object.entries(params)) if (value !== '' && value != null) url.searchParams.set(key, value);
  const response = await fetch(url, { headers: { Authorization: `Bearer ${state.token}` }, cache: 'no-store', signal: state.abort.signal });
  if (response.status === 401) {
    disconnect('令牌无效或已过期，请重新连接');
    throw new HttpError(401, '令牌无效或已过期');
  }
  if (!response.ok) {
    let message = `HTTP ${response.status}`;
    try {
      message = (await response.json()).error || message;
    } catch { /* Non-JSON error body. */ }
    throw new HttpError(response.status, message);
  }
  return response;
}

const getJson = async (route, params) => (await request(route, params)).json();

/* ---------- Parsing & rendering ---------- */
function parse(text) {
  let body = text;
  let time = '';
  let seconds = null;
  const stamp = STAMP.exec(body);
  if (stamp) {
    time = `${stamp[1]}:${stamp[2]}:${stamp[3]}${stamp[4] ? `.${stamp[4].padEnd(3, '0')}` : ''}`;
    seconds = Number(stamp[1]) * 3600 + Number(stamp[2]) * 60 + Number(stamp[3]);
    body = body.slice(stamp[0].length);
  }
  let tag = '';
  const tagged = TAG.exec(body);
  if (tagged) {
    tag = tagged[1];
    body = body.slice(tagged[0].length);
  }
  const level = ERROR.test(text) ? 'error' : WARN.test(text) ? 'warn' : '';
  return { time, seconds, tag, body, level, session: text.includes(SESSION_MARKER) };
}

function tagClass(tag) {
  let hash = 0;
  for (const ch of tag) hash = (hash * 31 + ch.charCodeAt(0)) >>> 0;
  return `tag-${hash % 8}`;
}

function highlight(parent, text, term) {
  if (!term) {
    parent.textContent = text;
    return;
  }
  const lower = text.toLowerCase();
  const needle = term.toLowerCase();
  let from = 0;
  for (let at = lower.indexOf(needle); at >= 0; at = lower.indexOf(needle, from)) {
    parent.append(text.slice(from, at), make('mark', '', text.slice(at, at + needle.length)));
    from = at + needle.length;
  }
  parent.append(text.slice(from));
}

function separator(kind, text) {
  const node = make('li', `sep ${kind}`, text);
  node.setAttribute('role', 'separator');
  return node;
}

function buildRow(line, term) {
  const p = parse(line.text);
  if (p.session) {
    return { seq: line.seq, text: line.text, seconds: p.seconds, sep: true, el: separator('session', `进程启动${p.time ? ` · ${p.time}` : ''}`) };
  }
  const li = make('li', `row${p.level ? ` ${p.level}` : ''}`);
  li.dataset.seq = line.seq;
  const tag = make('span', p.tag ? `tag ${tagClass(p.tag)}` : 'tag empty', p.tag);
  if (p.tag) tag.title = `只看 ${p.tag}`;
  const msg = make('span', 'msg');
  highlight(msg, p.body, term);
  const copy = make('button', 'copy', '复制');
  copy.type = 'button';
  copy.tabIndex = -1;
  li.append(make('span', 'ln', line.seq), make('span', 'time', p.time), tag, msg, copy);
  const row = { seq: line.seq, text: line.text, seconds: p.seconds, tag: p.tag, level: p.level, el: li };
  li.row = row;
  return row;
}

/** Builds rows plus gap separators; `previous` is the row just before this batch, if contiguous. */
function renderBatch(lines, term, previous = null) {
  const fragment = document.createDocumentFragment();
  const rows = [];
  let last = previous;
  for (const line of lines) {
    const row = buildRow(line, term);
    if (last?.seconds != null && row.seconds != null && !row.sep) {
      let gap = row.seconds - last.seconds;
      if (gap < 0) gap += 86400; // Timestamps carry no date: assume midnight passed.
      if (gap >= GAP_SECONDS) fragment.append(separator('gap', `间隔 ${formatDuration(gap)}`));
    }
    fragment.append(row.el);
    rows.push(row);
    last = row;
  }
  return { fragment, rows };
}

const realRows = () => state.rows.filter((r) => !r.sep);
const visibleRows = () => state.rows.filter((r) => !r.sep && !r.el.hidden);

function applyFilters() {
  const filtered = state.level !== 'all' || Boolean(state.tag);
  el.lines.classList.toggle('filtered', filtered);
  let visible = 0;
  for (const row of state.rows) {
    if (row.sep) continue;
    const show = (state.level === 'all' || row.level === state.level) && (!state.tag || row.tag === state.tag);
    row.el.hidden = !show;
    if (show) visible++;
  }
  let none = el.lines.querySelector('.no-match');
  if (filtered && !visible && state.rows.length) {
    if (!none) el.lines.append(none = make('li', 'no-match'));
    none.textContent = '当前筛选下没有日志。按 Esc 或点「全部」清除筛选。';
  } else {
    none?.remove();
  }
  for (const chip of el.subbar.querySelectorAll('.chip[data-level]')) chip.classList.toggle('active', chip.dataset.level === state.level);
  el.tagFilter.hidden = !state.tag;
  el.tagFilter.textContent = state.tag ? `标签 ${state.tag}` : '';
  if (state.selected?.el.hidden) select(null);
}

function updateCounts() {
  const rows = realRows();
  el.countAll.textContent = rows.length;
  el.countError.textContent = rows.filter((r) => r.level === 'error').length;
  el.countWarn.textContent = rows.filter((r) => r.level === 'warn').length;
  const parts = [];
  if (state.file) parts.push(state.file);
  parts.push(`${rows.length} 行`);
  if (state.pages > 1) parts.push(`${state.pages} 页`);
  if (state.updatedAt) parts.push(`更新于 ${state.updatedAt.toLocaleTimeString()}`);
  el.stats.textContent = parts.join(' · ');
}

function showEmpty(title, text, { older = false } = {}) {
  el.empty.hidden = false;
  el.emptyTitle.textContent = title;
  el.emptyText.textContent = text;
  el.lines.hidden = true;
  el.older.hidden = !older;
}

/** No rows yet: final only once the cursor reaches the file start; until then older windows can still match. */
function showNoMatch(term) {
  if (state.nextCursor) {
    showEmpty(term ? '最近的日志里没有匹配' : '最近的日志里没有可读记录',
      `已向前扫描约 ${state.scanned * WINDOW_MIB} MiB${term ? `，仍没有包含「${term}」的行` : ''}。点「加载更早的日志」继续向前搜索。`,
      { older: true });
  } else if (term) {
    showEmpty('没有匹配的日志', `当前文件里没有包含「${term}」的行。`);
  } else {
    showEmpty('这个文件还是空的', '手机上报新日志后，刷新或打开跟随即可看到。');
  }
}

/** True for a request that disconnect() aborted; its caller stays silent. */
function cancelled(error) {
  return error?.name === 'AbortError';
}

/* ---------- Scrolling, selection & navigation ---------- */
const atBottom = () => el.viewport.scrollTop + el.viewport.clientHeight >= el.viewport.scrollHeight - 48;
const scrollToBottom = () => { el.viewport.scrollTop = el.viewport.scrollHeight; };

function updateJump() {
  const far = el.viewport.scrollHeight - el.viewport.scrollTop - el.viewport.clientHeight > el.viewport.clientHeight * 0.75;
  if (atBottom()) state.unseen = 0;
  el.jump.hidden = !(state.unseen > 0 || far) || el.lines.hidden;
  el.jumpText.textContent = state.unseen > 0 ? `${state.unseen} 条新日志` : '回到底部';
}

function select(row, { reveal = false, flash = false, block = 'nearest' } = {}) {
  state.selected?.el.classList.remove('selected');
  state.selected = row;
  if (!row) return;
  row.el.classList.add('selected');
  if (reveal) row.el.scrollIntoView({ block });
  if (flash) {
    row.el.classList.remove('flash');
    void row.el.offsetWidth; // Restart the animation.
    row.el.classList.add('flash');
  }
}

function moveSelection(step) {
  const rows = visibleRows();
  if (!rows.length) return;
  let index = state.selected ? rows.indexOf(state.selected) : -1;
  if (index < 0) {
    const top = el.viewport.scrollTop;
    index = rows.findIndex((r) => r.el.offsetTop >= top) - (step > 0 ? 1 : 0);
  }
  const next = rows[Math.min(rows.length - 1, Math.max(0, index + step))];
  select(next, { reveal: true });
}

function jumpIssue(step) {
  const rows = visibleRows();
  const issues = rows.filter((r) => r.level);
  if (!issues.length) return toast('没有错误或警告');
  let target;
  if (state.selected && rows.includes(state.selected)) {
    const at = rows.indexOf(state.selected);
    target = step > 0 ? issues.find((r) => rows.indexOf(r) > at) : issues.findLast((r) => rows.indexOf(r) < at);
  } else {
    const middle = el.viewport.scrollTop + el.viewport.clientHeight / 2;
    target = step > 0 ? issues.find((r) => r.el.offsetTop > middle) : issues.findLast((r) => r.el.offsetTop < middle);
  }
  if (!target) return toast(step > 0 ? '已是最后一个问题' : '已是第一个问题');
  select(target, { reveal: true, flash: true, block: 'center' });
}

/* ---------- Loading ---------- */
/** Reads a page; while it holds no rows but a cursor remains, keeps reading older windows, at most SCAN_WINDOWS in all. */
async function readMatches(params, view) {
  let page = await getJson('/api/v1/logs', params);
  let windows = 1;
  while (!page.lines.length && page.nextCursor && windows < SCAN_WINDOWS && view === state.view) {
    setStatus(`约 ${windows * WINDOW_MIB} MiB 内没有匹配，继续向前搜索…`);
    page = await getJson('/api/v1/logs', { deviceId: params.deviceId, limit: params.limit, contains: params.contains, cursor: page.nextCursor });
    windows++;
  }
  return { page, windows };
}

/** True once the file selector holds the current device's files; otherwise starts listing them unless already in flight. */
function filesReady() {
  if (state.filesFor === el.device.value) return true;
  if (state.filesPending !== el.device.value) void loadFiles();
  return false;
}

async function loadLatest() {
  if (!el.device.value || !filesReady() || !el.file.value) return;
  const q = query();
  const keepSeq = state.selected?.seq;
  const stick = state.pages === 0 || atBottom();
  const view = ++state.view;
  state.nextCursor = null; // An older page must not use the previous selection's cursor meanwhile.
  setLoading(1);
  try {
    const { page, windows } = await readMatches(q, view);
    if (view !== state.view) return;
    const { fragment, rows } = renderBatch(page.lines, q.contains);
    state.rows = rows;
    state.selected = null;
    state.file = page.file;
    state.generation = page.generation;
    state.nextCursor = page.nextCursor;
    state.pages = 1;
    state.scanned = windows;
    state.unseen = 0;
    state.updatedAt = new Date();
    el.lines.replaceChildren(fragment);
    if (!page.lines.length) {
      showNoMatch(q.contains);
    } else {
      el.empty.hidden = true;
      el.lines.hidden = false;
      el.older.hidden = !state.nextCursor;
    }
    applyFilters();
    updateCounts();
    const keep = keepSeq != null && rows.find((r) => r.seq === keepSeq && !r.sep);
    if (keep) select(keep);
    if (stick) scrollToBottom();
    updateJump();
    const scannedBack = windows > 1 ? `，向前扫描了约 ${windows * WINDOW_MIB} MiB` : '';
    if (page.lines.length) setStatus(`显示最近 ${realRows().length} 行（已脱敏）${scannedBack}${state.nextCursor ? '，向上可加载更早' : ''}`);
    else if (state.nextCursor) setStatus(`约 ${windows * WINDOW_MIB} MiB 内没有匹配，可继续向前搜索`);
    else setStatus(q.contains ? '整个文件都没有匹配' : '没有日志');
  } catch (e) {
    if (view === state.view) setStatus(`读取失败：${e.message}`, true);
  } finally {
    setLoading(-1);
  }
}

/** Follow mode: append only records newer than the last one shown, keeping scroll position and selection. */
async function appendLatest() {
  if (!filesReady()) return;
  if (!state.pages) return loadLatest();
  const q = query();
  const view = state.view;
  try {
    const page = await getJson('/api/v1/logs', { ...q, limit: FOLLOW_LIMIT });
    if (view !== state.view) return;
    if (page.file !== state.file || page.generation !== state.generation) return loadLatest();
    if (!state.rows.length) {
      // Nothing matched yet, perhaps after a long backward search: repeat that search only once the newest window matches.
      state.updatedAt = new Date();
      return page.lines.length ? loadLatest() : undefined;
    }
    const lastSeq = state.rows.at(-1).seq;
    const fresh = page.lines.filter((l) => l.seq > lastSeq);
    if (fresh.length && fresh.length === page.lines.length && page.nextCursor) return loadLatest(); // More arrived than one poll covers.
    state.updatedAt = new Date();
    if (fresh.length) {
      const stick = atBottom();
      const { fragment, rows } = renderBatch(fresh, q.contains, state.rows.at(-1));
      el.lines.append(fragment);
      state.rows.push(...rows);
      applyFilters();
      if (stick) scrollToBottom();
      else state.unseen += rows.filter((r) => !r.el.hidden && !r.sep).length;
    }
    updateCounts();
    updateJump();
  } catch (e) {
    if (view === state.view) setStatus(`跟随失败：${e.message}`, true);
  }
}

async function loadOlder() {
  if (!state.nextCursor || el.older.disabled) return;
  el.older.disabled = true;
  const view = state.view;
  setLoading(1);
  try {
    const q = query();
    const { page, windows } = await readMatches({ deviceId: q.deviceId, limit: q.limit, contains: q.contains, cursor: state.nextCursor }, view);
    if (view !== state.view) return;
    state.nextCursor = page.nextCursor;
    state.scanned += windows;
    if (!page.lines.length) {
      if (!state.rows.length) showNoMatch(q.contains);
      el.older.hidden = !state.nextCursor;
      setStatus(state.nextCursor ? `更早的约 ${windows * WINDOW_MIB} MiB 里也没有匹配，可继续向前搜索` : '已到文件开头，没有更早的匹配');
      return;
    }
    const { fragment, rows } = renderBatch(page.lines, q.contains);
    if (state.rows.length) {
      fragment.append(separator('page', '以上为更早一页 · <NAME-n> 等别名只在同一页内一致'));
      const before = el.viewport.scrollHeight;
      el.lines.prepend(fragment);
      el.viewport.scrollTop += el.viewport.scrollHeight - before;
    } else {
      // The first rows found after an empty window: show the list in place of the "no match yet" message.
      el.lines.prepend(fragment);
      el.empty.hidden = true;
      el.lines.hidden = false;
      scrollToBottom();
    }
    state.rows = rows.concat(state.rows);
    state.pages++;
    el.older.hidden = !state.nextCursor;
    applyFilters();
    updateCounts();
    updateJump();
    setStatus(`已加载 ${state.pages} 页${state.nextCursor ? '' : '，已到文件开头'}`);
  } catch (e) {
    if (view !== state.view) return;
    if (e.status === 409) {
      setStatus('日志已轮转，已重新读取最新内容', true);
      await loadFiles();
    } else {
      setStatus(`读取失败：${e.message}`, true);
    }
  } finally {
    el.older.disabled = false;
    setLoading(-1);
  }
}

async function loadFiles() {
  const deviceId = el.device.value;
  if (!deviceId) return;
  const filesView = ++state.filesView;
  state.filesFor = null;
  state.filesPending = deviceId;
  state.view++; // Log responses still in flight belong to the previous device or file.
  state.nextCursor = null;
  setLoading(1);
  try {
    const { files } = await getJson('/api/v1/files', { deviceId });
    if (filesView !== state.filesView) return;
    el.file.replaceChildren(...files.map((f) => option(f.file,
      `${f.file === `${deviceId}.jsonl` ? '当前文件' : new Date(f.modifiedAt).toLocaleString()} · ${formatBytes(f.bytes)}`)));
    const current = files.find((f) => f.file === `${deviceId}.jsonl`);
    if (current) el.file.value = current.file;
    state.filesFor = deviceId;
    state.pages = 0;
    if (!files.length) {
      state.rows = [];
      el.lines.replaceChildren();
      showEmpty('该设备还没有日志', '手机连上调试桥并上报日志后会出现在这里。');
      setStatus('该设备还没有日志文件');
      updateCounts();
      return;
    }
    await loadLatest();
  } catch (e) {
    if (filesView === state.filesView) setStatus(`读取文件列表失败：${e.message}`, true);
  } finally {
    if (filesView === state.filesView) state.filesPending = null;
    setLoading(-1);
  }
}

function updatePill() {
  const device = state.devices.find((d) => d.deviceId === el.device.value);
  el.pill.hidden = !device;
  if (!device) return;
  el.pill.classList.toggle('online', Boolean(device.online));
  el.pillText.textContent = [device.online ? '在线' : '离线', device.model || device.deviceId, device.appVersion].filter(Boolean).join(' · ');
}

/** Resolves false when disconnect() cancelled this connection meanwhile. */
async function connect() {
  const { signal } = state.abort;
  setStatus('正在连接…');
  setLoading(1);
  try {
    const { devices } = await getJson('/api/v1/devices');
    if (signal.aborted) return false;
    devices.sort((a, b) => Number(b.online) - Number(a.online) || a.deviceId.localeCompare(b.deviceId));
    state.devices = devices;
    const previous = el.device.value;
    el.device.replaceChildren(...devices.map((d) => option(d.deviceId,
      [d.online ? '● 在线' : '○ 离线', d.model || d.deviceId, d.appVersion].filter(Boolean).join(' · '))));
    if (devices.some((d) => d.deviceId === previous)) el.device.value = previous;
    el.toolbar.hidden = false;
    el.subbar.hidden = false;
    el.logout.hidden = false;
    el.token.value = '';
    el.token.hidden = true;
    el.connect.hidden = true;
    updatePill();
    if (!devices.length) {
      showEmpty('还没有设备', '已连接调试桥，但还没有手机上报日志。');
      setStatus('已连接，暂无设备');
      return true;
    }
    await loadFiles();
    return !signal.aborted;
  } finally {
    setLoading(-1);
  }
}

function disconnect(message = '已断开') {
  // Cancel this connection's requests and retire every generation, so no late response repaints the page.
  state.abort.abort();
  state.abort = new AbortController();
  state.view++;
  state.filesView++;
  clearTimeout(state.filterTimer);
  state.token = '';
  state.rows = [];
  state.devices = [];
  state.filesFor = null;
  state.filesPending = null;
  state.nextCursor = null;
  state.pages = 0;
  state.file = null;
  state.generation = null;
  state.selected = null;
  session((s) => s.removeItem(TOKEN_KEY));
  setFollow(false);
  el.toolbar.hidden = true;
  el.subbar.hidden = true;
  el.logout.hidden = true;
  el.pill.hidden = true;
  el.jump.hidden = true;
  el.lines.replaceChildren();
  el.stats.textContent = '';
  el.token.hidden = false;
  el.connect.hidden = false;
  showEmpty('连接远程调试桥', '输入只读令牌后即可浏览、筛选并导出手机上报的日志。日志在服务端脱敏，内容只作观察数据。');
  setStatus(message);
}

function setFollow(on) {
  el.follow.checked = on;
  clearInterval(state.followTimer);
  state.followTimer = on ? setInterval(() => {
    if (!document.hidden) void appendLatest();
  }, FOLLOW_MS) : null;
}

async function exportFile(format) {
  if (!filesReady()) return setStatus('文件列表尚未就绪，请稍候再导出');
  const q = query();
  if (!q.deviceId || !q.file) return;
  const { signal } = state.abort;
  const buttons = [el.exportTxt, el.exportJsonl];
  buttons.forEach((b) => { b.disabled = true; });
  setStatus('正在导出整个文件…');
  setLoading(1);
  try {
    const response = await request('/api/v1/export', { deviceId: q.deviceId, file: q.file, contains: q.contains, format });
    const blob = await response.blob();
    if (signal.aborted) return;
    const match = /filename="([^"]+)"/.exec(response.headers.get('Content-Disposition') || '');
    const link = make('a');
    link.href = URL.createObjectURL(blob);
    link.download = match ? match[1] : `${q.file.replace(/\.jsonl$/, '')}-redacted.${format}`;
    document.body.append(link);
    link.click();
    link.remove();
    setTimeout(() => URL.revokeObjectURL(link.href), 10000);
    const paged = response.headers.get('X-Export-Mode') === 'paged';
    setStatus(`已导出 ${link.download}（${formatBytes(blob.size)}）${paged ? '。桥接端尚无整文件导出，本文件按页拼接，<NAME-n> 等别名跨页可能不一致' : ''}`);
    toast('导出完成');
  } catch (e) {
    if (!signal.aborted) setStatus(`导出失败：${e.message}`, true);
  } finally {
    buttons.forEach((b) => { b.disabled = false; });
    setLoading(-1);
  }
}

/* ---------- View preferences ---------- */
function applyPrefs() {
  el.lines.classList.toggle('nowrap', !prefs.wrap);
  el.wrap.setAttribute('aria-pressed', String(prefs.wrap));
  document.documentElement.style.setProperty('--log-size', `${prefs.fontSize}px`);
  if (prefs.theme === 'auto') delete document.documentElement.dataset.theme;
  else document.documentElement.dataset.theme = prefs.theme;
  el.theme.title = `主题：${THEME_NAMES[prefs.theme] ?? THEME_NAMES.auto}（点击切换）`;
}

function changeFont(step) {
  prefs.fontSize = Math.min(18, Math.max(10, prefs.fontSize + step));
  applyPrefs();
  savePrefs();
}

function toggleWrap() {
  prefs.wrap = !prefs.wrap;
  applyPrefs();
  savePrefs();
  state.selected?.el.scrollIntoView({ block: 'nearest' });
}

function setLevel(level) {
  state.level = level;
  applyFilters();
}

function setTag(tag) {
  state.tag = state.tag === tag ? null : tag;
  applyFilters();
  state.selected?.el.scrollIntoView({ block: 'center' });
}

/* ---------- Events ---------- */
el.auth.addEventListener('submit', (event) => {
  event.preventDefault();
  const value = el.token.value.trim();
  if (!value) return;
  state.token = value;
  connect().then(
    (connected) => connected && session((s) => s.setItem(TOKEN_KEY, state.token)),
    (e) => cancelled(e) || setStatus(`连接失败：${e.message}`, true),
  );
});
el.logout.addEventListener('click', () => disconnect());
el.theme.addEventListener('click', () => {
  prefs.theme = THEMES[(THEMES.indexOf(prefs.theme) + 1) % THEMES.length];
  applyPrefs();
  savePrefs();
  toast(`主题：${THEME_NAMES[prefs.theme]}`);
});
el.helpOpen.addEventListener('click', () => el.help.showModal());
el.helpClose.addEventListener('click', () => el.help.close());
el.device.addEventListener('change', () => {
  updatePill();
  void loadFiles();
});
el.file.addEventListener('change', () => {
  state.pages = 0;
  void loadLatest();
});
el.limit.addEventListener('change', () => void loadLatest());
el.refresh.addEventListener('click', () => void loadLatest());
el.older.addEventListener('click', () => void loadOlder());
el.follow.addEventListener('change', () => {
  setFollow(el.follow.checked);
  if (el.follow.checked) {
    scrollToBottom();
    void appendLatest();
  }
});
el.exportTxt.addEventListener('click', () => void exportFile('txt'));
el.exportJsonl.addEventListener('click', () => void exportFile('jsonl'));
el.subbar.addEventListener('click', (event) => {
  const chip = event.target.closest('.chip[data-level]');
  if (chip) setLevel(chip.dataset.level);
});
el.tagFilter.addEventListener('click', () => setTag(state.tag));
el.prevIssue.addEventListener('click', () => jumpIssue(-1));
el.nextIssue.addEventListener('click', () => jumpIssue(1));
el.wrap.addEventListener('click', toggleWrap);
el.fontDown.addEventListener('click', () => changeFont(-1));
el.fontUp.addEventListener('click', () => changeFont(1));
el.jump.addEventListener('click', () => {
  el.viewport.scrollTo({ top: el.viewport.scrollHeight, behavior: 'smooth' });
  state.unseen = 0;
});
el.viewport.addEventListener('scroll', updateJump, { passive: true });

el.lines.addEventListener('click', (event) => {
  const li = event.target.closest('.row');
  if (!li?.row) return;
  if (event.target.closest('.copy')) return void copyText(li.row.text);
  if (event.target.closest('.tag') && li.row.tag) {
    select(li.row);
    return setTag(li.row.tag);
  }
  if (window.getSelection()?.toString()) return; // Let a text selection stand instead of toggling the row.
  select(state.selected === li.row ? null : li.row);
});

el.contains.addEventListener('input', () => {
  clearTimeout(state.filterTimer);
  state.filterTimer = setTimeout(() => void loadLatest(), 400);
});

document.addEventListener('keydown', (event) => {
  if (event.metaKey || event.ctrlKey || event.altKey || el.help.open) return;
  const typing = event.target.closest?.('input, select, textarea');
  if (typing) {
    if (event.key === 'Escape') event.target.blur();
    return;
  }
  if (el.toolbar.hidden && event.key !== '?') return;
  const actions = {
    '/': () => el.contains.focus(),
    '?': () => el.help.showModal(),
    Escape: () => (state.selected ? select(null) : state.tag ? setTag(state.tag) : state.level !== 'all' && setLevel('all')),
    n: () => jumpIssue(1),
    p: () => jumpIssue(-1),
    N: () => jumpIssue(-1),
    j: () => moveSelection(1),
    k: () => moveSelection(-1),
    ArrowDown: () => state.selected && moveSelection(1),
    ArrowUp: () => state.selected && moveSelection(-1),
    c: () => state.selected && copyText(state.selected.text),
    g: () => { el.viewport.scrollTop = 0; },
    G: scrollToBottom,
    r: () => void loadLatest(),
    f: () => { el.follow.click(); },
    w: toggleWrap,
    '+': () => changeFont(1),
    '=': () => changeFont(1),
    '-': () => changeFont(-1),
    1: () => setLevel('all'),
    2: () => setLevel('error'),
    3: () => setLevel('warn'),
  };
  const action = actions[event.key];
  if (!action) return;
  if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && !state.selected) return;
  event.preventDefault();
  action();
});

/* ---------- Start ---------- */
applyPrefs();
const saved = session((s) => s.getItem(TOKEN_KEY));
if (saved) {
  state.token = saved;
  connect().catch((e) => cancelled(e) || setStatus(`连接失败：${e.message}`, true));
}
