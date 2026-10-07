import { spawn } from "node:child_process";

const mode = process.argv[2];
if (mode === "streams") {
	process.stdout.write("out");
	process.stderr.write("err");
	process.exitCode = 7;
} else if (mode === "args") {
	console.log(
		JSON.stringify({
			args: process.argv.slice(3),
			cwd: process.cwd(),
			env: process.env.SCI_TEST_VALUE,
		}),
	);
} else if (mode === "burst") {
	process.stdout.write("a".repeat(100000));
	process.stderr.write("b".repeat(100000));
} else if (mode === "loud") {
	process.stdout.write("x".repeat(34 * 1024 * 1024));
} else if (mode === "wait") {
	process.on("SIGTERM", () => {});
	console.log("ready");
	setInterval(() => {}, 1000);
} else if (mode === "tree") {
	const child = spawn(process.execPath, [import.meta.filename, "wait"], {
		stdio: ["ignore", "inherit", "inherit"],
	});
	console.log(`child=${child.pid}`);
	setInterval(() => {}, 1000);
}
