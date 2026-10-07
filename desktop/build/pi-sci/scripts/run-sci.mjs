import { fsDomain } from "../src/files.ts";
import { harness, output } from "../test/harness.ts";

const source = process.argv[2];
if (!source) throw new Error("Usage: node scripts/run-sci.mjs '<Clojure source>'");
const runtime = await harness([
	(pi) => {
		pi.on("tool_call", (event) => {
			if (event.toolName === "codemode") return;
			if (
				event.toolName === fsDomain.id &&
				fsDomain.operations[event.input.operation]?.mutation === false
			)
				return;
			return {
				block: true,
				reason: "The SCI check runner permits only read-only fs tool operations.",
			};
		});
	},
]);
try {
	const result = await runtime.call(source);
	process.stdout.write(`${output(result)}\n`);
	process.exitCode = result.isError ? 1 : 0;
} finally {
	runtime.close();
}
