import { createHash } from "node:crypto";
import { mkdirSync, readFileSync, renameSync, writeFileSync } from "node:fs";

const base = new URL(
	"../node_modules/@earendil-works/pi-coding-agent/dist/core/tools/",
	import.meta.url,
);
const output = new URL("../dist/", import.meta.url);
const hashes = {
	grep: "641ae520a88c7eb86406d9a18880f31412cb41cdc15a7ee3e1da5de62979ca7c",
	find: "cbb5e3a76e962899f833452ff26670c1fdbf333e7efdecb9953b07f6ebd14ed7",
	read: "a5493a6cc7a3a03ba23d345f603bc7ee839ead2fe0ea79a096107fb9ebaa03eb",
};
function replace(source, before, after, count = 1) {
	if (source.split(before).length !== count + 1)
		throw new Error(`Pi adapter anchor changed: ${before.slice(0, 100)}`);
	return source.split(before).join(after);
}
mkdirSync(output, { recursive: true });
for (const [name, hash] of Object.entries(hashes)) {
	const input = new URL(`${name}.js`, base);
	let source = readFileSync(input, "utf8");
	if (createHash("sha256").update(source).digest("hex") !== hash)
		throw new Error(`Pinned Pi ${name} implementation changed; review the adapter before building`);
	if (name === "grep") {
		source = replace(
			source,
			'const args = ["--json", "--line-number", "--color=never", "--hidden"];',
			'const args = ["--json", "--line-number", "--color=never", "--hidden", "--no-require-git", "--glob", "!.git", "--glob", "!.jj"];',
		);
		source = replace(
			source,
			"matches.push({ filePath, lineNumber, lineText });",
			"matches.push({ filePath, lineNumber, lineText, columns: (event.data.submatches ?? []).map(match => match.start + 1) });",
		);
		source = replace(
			source,
			'content: [{ type: "text", text: "No matches found" }], details: undefined',
			'content: [{ type: "text", text: "No matches found" }], details: undefined, structuredContent: {items: [], truncated: false, root: searchPath}',
		);
		source = replace(
			source,
			'content: [{ type: "text", text: output }],\n                                details:',
			`content: [{ type: "text", text: output }],
                                structuredContent: (() => {
                                    const items = []; let bytes = 0;
                                    for (const match of matches) {
                                        const text = truncateLine((match.lineText ?? "").replace(/\\r?\\n$/, "")).text;
                                        const item = {path: match.filePath, line: match.lineNumber, column: match.columns[0] ?? 1, columns: match.columns, column_unit: "utf8-byte", text};
                                        const size = Buffer.byteLength(JSON.stringify(item));
                                        if (bytes + size > DEFAULT_MAX_BYTES) break;
                                        items.push(item); bytes += size;
                                    }
                                    return {items, root: searchPath, truncated: matchLimitReached || truncation.truncated || linesTruncated || items.length < matches.length};
                                })(),
                                details:`,
		);
	}
	if (name === "find") {
		source = replace(
			source,
			'const args = ["--glob", "--color=never", "--hidden"];',
			'const args = ["--glob", "--color=never", "--hidden", "--exclude", ".git", "--exclude", ".jj"];',
		);
		source = replace(
			source,
			'content: [{ type: "text", text: "No files found matching pattern" }],\n                                    details: undefined',
			'content: [{ type: "text", text: "No files found matching pattern" }],\n                                    details: undefined, structuredContent: {items: [], truncated: false, root: searchPath}',
			2,
		);
		source = replace(
			source,
			'content: [{ type: "text", text: resultOutput }],\n                                details:',
			`content: [{ type: "text", text: resultOutput }],
                                structuredContent: (() => {
                                    const items = []; let bytes = 0;
                                    for (const item of relativized) {
                                        const size = Buffer.byteLength(JSON.stringify(item));
                                        if (bytes + size > DEFAULT_MAX_BYTES) break;
                                        items.push(item); bytes += size;
                                    }
                                    return {items, root: searchPath, truncated: resultLimitReached || truncation.truncated || items.length < relativized.length};
                                })(),
                                details:`,
			2,
		);
	}
	if (name === "read") {
		source = replace(
			source,
			'content = [{ type: "text", text: outputText }];',
			`content = [{ type: "text", text: outputText }];
                            const consumedLines = truncation.truncated ? truncation.outputLines : (userLimitedLines ?? allLines.length - startLine);
                            details = {...details, sciRead: {
                                kind: "text", path: absolutePath, text: truncation.content,
                                start_line: startLineDisplay, end_line: startLine + consumedLines,
                                total_lines: totalFileLines,
                                truncated: truncation.truncated || startLine + consumedLines < allLines.length,
                                next_line: truncation.firstLineExceedsLimit ? null : (startLine + consumedLines < allLines.length ? startLine + consumedLines + 1 : null)
                            }};`,
		);
		source = replace(
			source,
			"structuredContent: toReadOutput(result.content)",
			"structuredContent: result.details?.sciRead ?? (() => {const image = toReadOutput(result.content); return typeof image === 'string' ? {kind: 'image-omitted', note: image} : {kind: 'image', image};})()",
		);
	}
	source = source
		.replace(
			/from (["'])(\.[^"']+)\1/g,
			(_all, _quote, spec) => `from ${JSON.stringify(new URL(spec, input).href)}`,
		)
		.replace(/\/\/# sourceMappingURL=.*$/m, "");
	const pending = new URL(`pi-${name}.pending.mjs`, output);
	writeFileSync(
		pending,
		`// Adapted from MIT-licensed Pi 1.0.4. See PI-LICENSE and scripts/prepare-search.mjs.\n${source}`,
	);
	renameSync(pending, new URL(`pi-${name}.mjs`, output));
}
