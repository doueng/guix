import { execFileSync, spawnSync } from "node:child_process";
import { createRequire } from "node:module";
import { dirname } from "node:path";

const require = createRequire(import.meta.url);
const suffix = process.platform === "win32" ? "biome.exe" : "biome";
const binary = require.resolve(`@biomejs/cli-${process.platform}-${process.arch}/${suffix}`);
const args = process.argv.slice(2);
let result = spawnSync(binary, args, { stdio: "inherit" });
if (result.error?.code === "ENOENT" && process.platform === "linux") {
	const headers = execFileSync("readelf", ["-l", process.execPath], { encoding: "utf8" });
	const loader = headers.match(/Requesting program interpreter: ([^\]]+)/)?.[1];
	if (!loader) throw new Error("Cannot locate Node's ELF interpreter.");
	const directories = [...new Set(process.report.getReport().sharedObjects.map(dirname))];
	result = spawnSync(loader, ["--library-path", directories.join(":"), binary, ...args], {
		stdio: "inherit",
	});
}
if (result.error) throw result.error;
process.exit(result.status ?? 1);
