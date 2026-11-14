// Builds the unpacked extension in dist/: the shadow-cljs release builds into dist/js, then the static files from
// ext/ and the vendored autoconsent files the extension loads. Load dist/ in chrome://extensions.
// Java comes from PATH, else from Guix (guix shell openjdk@25:jdk).
import { spawnSync } from 'node:child_process';
import { cpSync, existsSync, mkdirSync, readdirSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';

const ROOT = resolve(import.meta.dirname, '..');
const DIST = join(ROOT, 'dist');
export const BUILDS = ['background', 'popup'];
const VENDOR = ['content.bundle.js', 'autoconsent.esm.js', 'rules.json', 'LICENSE'];

function run(cmd, args) {
  const r = spawnSync(cmd, args, { cwd: ROOT, stdio: 'inherit' });
  if (r.status !== 0) throw new Error(`${cmd} ${args.join(' ')} failed (${r.status ?? r.signal})`);
}

/** Runs shadow-cljs with these arguments, with a JDK from PATH or Guix. */
export function shadow(args) {
  const java = spawnSync('java', ['-version'], { stdio: 'ignore' }).status === 0;
  if (java) run('npx', ['shadow-cljs', ...args]);
  else run('guix', ['shell', 'openjdk@25:jdk', '--', 'npx', 'shadow-cljs', ...args]);
}

if (process.argv.includes('--test')) {
  // Compiles the cljs.test build to out/test.cjs; npm test runs it.
  shadow(['compile', 'test']);
  process.exit(0);
}

rmSync(DIST, { recursive: true, force: true });
mkdirSync(join(DIST, 'js'), { recursive: true });
if (BUILDS.length) {
  shadow(['release', ...BUILDS]);
  rmSync(join(DIST, 'js', 'manifest.edn'), { force: true });
}
cpSync(join(ROOT, 'ext'), DIST, { recursive: true });
for (const f of readdirSync(join(ROOT, 'src')).filter((f) => f.endsWith('.js'))) cpSync(join(ROOT, 'src', f), join(DIST, 'js', f));
mkdirSync(join(DIST, 'vendor/autoconsent'), { recursive: true });
for (const f of VENDOR) cpSync(join(ROOT, 'vendor/autoconsent', f), join(DIST, 'vendor/autoconsent', f));
if (!existsSync(join(DIST, 'manifest.json'))) throw new Error('dist/manifest.json missing');
console.log('built dist/');
