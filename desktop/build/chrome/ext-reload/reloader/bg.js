const HUB = 'ws://127.0.0.1:7357/reloader';
let ws = null;

function connect() {
  if (ws && ws.readyState <= WebSocket.OPEN) return;
  const sock = new WebSocket(HUB);
  ws = sock;
  sock.onopen = () => sock.send(JSON.stringify({ op: 'hello', version: chrome.runtime.getManifest().version }));
  sock.onmessage = async (e) => {
    const msg = JSON.parse(e.data);
    if (msg.op === 'ping') return sock.send(JSON.stringify({ op: 'pong' }));
    if (msg.op === 'self-reload') return chrome.runtime.reload();
    let reply;
    try { reply = await handle(msg); } catch (err) { reply = { ok: false, error: String(err?.message ?? err) }; }
    sock.send(JSON.stringify({ id: msg.id, ...reply }));
  };
  sock.onclose = () => { if (ws === sock) ws = null; setTimeout(connect, 2000); };
  sock.onerror = () => {};
}

function patternRegex(p) {
  if (p === '<all_urls>') return /^(https?|wss?|file|ftp):\/\//;
  const m = /^(\*|[a-z-]+):\/\/([^/]*)(\/.*)$/.exec(p);
  if (!m) return /^$/;
  const esc = (s) => s.replace(/[.+?^${}()|[\]\\]/g, '\\$&');
  const scheme = m[1] === '*' ? 'https?' : esc(m[1]);
  const host = m[2] === '*' ? '[^/]*' : m[2].startsWith('*.') ? `([^/]*\\.)?${esc(m[2].slice(2))}` : esc(m[2]);
  const path = m[3].split('*').map(esc).join('.*');
  return new RegExp(`^${scheme}://${host}(:\\d+)?${path}$`);
}

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const dev = async () => (await chrome.management.getAll()).filter((x) => x.installType === 'development' && x.id !== chrome.runtime.id);
const brief = (x) => ({ id: x.id, name: x.name, version: x.version, enabled: x.enabled });

// An extension is named by its id when its manifest has a key (the id is then the same wherever it was
// loaded from), else by its name. Copies with the same name and another id are stale, as from an old folder.
async function handle({ op, extId, url, name, matches = [], tabs = 'all' }) {
  if (op === 'list') return { ok: true, extensions: (await dev()).map(brief) };
  if (op === 'open') {
    const t = await chrome.tabs.create({ url, active: true });
    await chrome.windows.update(t.windowId, { focused: true });
    return { ok: true };
  }
  if (op !== 'reload') throw new Error(`unknown op ${op}`);
  const all = await dev();
  const same = all.filter((x) => (extId ? x.id === extId : x.name === name));
  if (!same.length) return { ok: false, error: 'not-installed', name, stale: all.filter((x) => x.name === name).map(brief) };
  const running = same.filter((x) => x.enabled);
  const targets = running.length ? running : same;
  for (const x of targets) {
    if (x.enabled) await chrome.management.setEnabled(x.id, false);
    await chrome.management.setEnabled(x.id, true);
  }
  const after = await Promise.all(targets.map((x) => chrome.management.get(x.id)));
  const failed = after.filter((x) => !x.enabled);
  let reloaded = 0;
  if (!failed.length && tabs !== 'none') {
    await sleep(300);
    const res = matches.map(patternRegex);
    for (const t of await chrome.tabs.query(tabs === 'active' ? { active: true } : {})) {
      if (!t.discarded && t.url && res.some((r) => r.test(t.url))) { await chrome.tabs.reload(t.id); reloaded++; }
    }
  }
  return { ok: !failed.length, extensions: after.map(brief), activated: running.length === 0, tabs: reloaded,
           ...(failed.length && { error: 'did-not-load' }) };
}

chrome.alarms.create('hub', { periodInMinutes: 0.5 });
chrome.alarms.onAlarm.addListener(connect);
chrome.runtime.onStartup.addListener(connect);
chrome.runtime.onInstalled.addListener(connect);
connect();
