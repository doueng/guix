// Loads ext/ the way chrome://extensions > Load unpacked does (Extensions.loadUnpacked in the
// headless flatpak Chrome, or $CHROME) and exits nonzero with Chrome's error when it would not load.
// First checks that every file manifest.json names exists, since a missing ext/js/ (not built)
// is the usual cause and Chrome's message for it is only "Could not load background script ''".
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { launch, available, ROOT } from '../test/lib/chrome.mjs';

const ext = join(ROOT, 'ext');
const m = JSON.parse(readFileSync(join(ext, 'manifest.json'), 'utf8'));
const files = [
  m.background?.service_worker,
  m.side_panel?.default_path,
  ...Object.values(m.icons ?? {}),
  ...(m.content_scripts ?? []).flatMap((c) => [...(c.js ?? []), ...(c.css ?? [])]),
  ...(m.web_accessible_resources ?? []).flatMap((w) => w.resources),
].filter(Boolean);
const missing = files.filter((f) => !existsSync(join(ext, f)));
if (missing.length) {
  console.error(`ext/manifest.json names files that do not exist: ${missing.join(', ')}\nRun bb build first.`);
  process.exit(1);
}
if (!available) {
  console.log('manifest files present; no Chrome to load it in, skipped the load check');
  process.exit(0);
}
let b;
try {
  b = await launch();
  console.log(`Chrome loaded ${ext} as ${b.extensionId}`);
} catch (e) {
  console.error(`Chrome would not load ${ext}: ${e.message}`);
  process.exitCode = 1;
} finally {
  b?.close();
  setTimeout(() => process.exit(), 2000);
}
