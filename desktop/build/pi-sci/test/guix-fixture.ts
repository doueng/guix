import { writeFileSync } from "node:fs";

const path = process.env.GUIX_ARGV_FILE;
if (!path) throw new Error("GUIX_ARGV_FILE is required");
writeFileSync(path, JSON.stringify(process.argv.slice(2)));
if (process.env.GUIX_FAIL) {
	process.stderr.write("fixture build failed");
	process.exitCode = 7;
} else process.stdout.write("/gnu/store/fixture-output\n");
