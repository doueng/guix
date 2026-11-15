// Checks that docs/guide.html still matches the repo: the version, the paths it tells
// you to use, the npm scripts it names and every command left out of the port.
import { readFileSync, existsSync } from 'node:fs';
import { join, resolve } from 'node:path';

const root = resolve(import.meta.dirname, '..');
const guide = readFileSync(join(root, 'docs/guide.html'), 'utf8');
const manifest = JSON.parse(readFileSync(join(root, 'ext/manifest.json'), 'utf8'));
const pkg = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
const commands = readFileSync(join(root, 'src/vimium_cljs/commands.cljs'), 'utf8');
const problems = [];

if (!guide.includes(`version ${manifest.version}`)) problems.push(`guide does not name version ${manifest.version}`);
if (!guide.includes(manifest.name.replace('(', '(').replace(')', ')'))) problems.push(`guide does not name ${manifest.name}`);
for (const step of ['chrome://extensions', 'Load unpacked', 'Developer mode', 'flatpak override', 'ext-reload load']) {
  if (!guide.includes(step)) problems.push(`guide lacks the step "${step}"`);
}
for (const [, p] of guide.matchAll(/\/home\/engstrand\/guix\/desktop\/build\/chrome\/vimium-cljs\/([\w./-]*)/g)) {
  if (p && !existsSync(join(root, p))) problems.push(`guide names missing path ${p}`);
}
for (const [, s] of guide.matchAll(/npm run ([\w:]+)/g)) {
  if (!pkg.scripts[s]) problems.push(`guide names missing script npm run ${s}`);
}
const leftOut = commands.match(/\(def left-out\s*\{([^}]*)\}/)[1];
const words = { 'LinkHints.activateModeToDownloadLink': 'download', 'LinkHints.activateModeToOpenIncognito': 'incognito',
                'Vomnibar.activateCommandSelection': '<kbd>:</kbd>', 'setZoom': 'setZoom' };
for (const [, c] of leftOut.matchAll(/"([\w.]+)"\s+"/g)) {
  if (!guide.includes(words[c] || c)) problems.push(`guide's Not ported list misses ${c}`);
}
if (problems.length) { console.error(problems.join('\n')); process.exit(1); }
console.log('guide matches the repo');
