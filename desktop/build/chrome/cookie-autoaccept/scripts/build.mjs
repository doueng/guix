// Builds the unpacked extension in ext/: the shadow-cljs release builds into ext/js, then the static files from
// static/ and the vendored autoconsent files the extension loads. Load ext/ in chrome://extensions. ext/ is committed,
// so a checkout loads without a build; the build is deterministic, so rebuilding changes ext/ only when src/ changed.
// Java comes from PATH, else from Guix (guix shell openjdk@25:jdk).
import { spawnSync } from 'node:child_process';
import { cpSync, mkdirSync, rmSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { checkExtension } from './check-manifest.mjs';

const ROOT = resolve(import.meta.dirname, '..');
const OUT = join(ROOT, 'ext');
export const BUILDS = ['background', 'popup', 'early', 'page-start', 'fallback'];
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

rmSync(OUT, { recursive: true, force: true });
mkdirSync(join(OUT, 'js'), { recursive: true });
if (BUILDS.length) {
  shadow(['release', ...BUILDS]);
  rmSync(join(OUT, 'js', 'manifest.edn'), { force: true });
}
cpSync(join(ROOT, 'static'), OUT, { recursive: true });
mkdirSync(join(OUT, 'vendor/autoconsent'), { recursive: true });
for (const f of VENDOR) cpSync(join(ROOT, 'vendor/autoconsent', f), join(OUT, 'vendor/autoconsent', f));
checkExtension(OUT);
console.log('built ext/');
