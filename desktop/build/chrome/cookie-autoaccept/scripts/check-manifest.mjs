import { existsSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';

export function manifestPaths(m) {
  const out = [];
  const add = (p) => { if (typeof p === 'string' && p) out.push(p); };
  const addAll = (v) => { if (typeof v === 'string') add(v); else if (v) Object.values(v).forEach(add); };
  add(m.background?.service_worker);
  for (const cs of m.content_scripts ?? []) [...(cs.js ?? []), ...(cs.css ?? [])].forEach(add);
  add(m.action?.default_popup);
  addAll(m.action?.default_icon);
  addAll(m.icons);
  add(m.options_page);
  add(m.options_ui?.page);
  add(m.side_panel?.default_path);
  for (const w of m.web_accessible_resources ?? []) (w.resources ?? []).filter((r) => !r.includes('*')).forEach(add);
  for (const r of m.declarative_net_request?.rule_resources ?? []) add(r.path);
  return out;
}

function htmlPaths(html) {
  return [...html.matchAll(/<(?:script[^>]*\ssrc|link[^>]*\shref)="([^"]+)"/g)]
    .map((m) => m[1])
    .filter((p) => !/^[a-z]+:/i.test(p))
    .map((p) => p.replace(/^\//, ''));
}

export function missingFiles(dir) {
  const manifest = join(dir, 'manifest.json');
  if (!existsSync(manifest)) return ['manifest.json'];
  const paths = manifestPaths(JSON.parse(readFileSync(manifest, 'utf8')));
  for (const p of paths.filter((p) => p.endsWith('.html') && existsSync(join(dir, p)))) {
    paths.push(...htmlPaths(readFileSync(join(dir, p), 'utf8')));
  }
  return [...new Set(paths)].filter((p) => !existsSync(join(dir, p)));
}

export function checkExtension(dir) {
  const missing = missingFiles(dir);
  if (missing.length) {
    throw new Error(`${dir} is not a loadable extension; missing: ${missing.join(', ')}. Run npm run build and load ext/.`);
  }
}

if (resolve(process.argv[1] ?? '') === resolve(import.meta.filename)) {
  const dir = resolve(process.argv[2] ?? join(import.meta.dirname, '..', 'ext'));
  try {
    checkExtension(dir);
    console.log(`${dir}: every file the manifest names is present`);
  } catch (e) {
    console.error(e.message);
    process.exit(1);
  }
}
