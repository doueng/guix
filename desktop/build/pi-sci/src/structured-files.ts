import { constants } from "node:fs";
import { open } from "node:fs/promises";

const JSON_BYTES = 1024 * 1024;
const LINE_BYTES = 2 * 1024 * 1024;
const SCAN_BYTES = 32 * 1024 * 1024;
const PAGE_BYTES = 48 * 1024;
const decoder = new TextDecoder("utf-8", { fatal: true });
type Cursor = { offset: number; line: number };
type Fields = Record<string, string[]>;
type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

function parse(bytes: Buffer, path: string, line?: number): Json {
	try {
		return JSON.parse(decoder.decode(bytes), (_key, value) => {
			if (typeof value === "number" && !Number.isFinite(value))
				throw new Error("JSON numbers must be finite");
			return value;
		});
	} catch (error) {
		throw new Error(
			`Invalid JSON in ${path}${line === undefined ? "" : ` at line ${line}`}: ${error instanceof Error ? error.message : String(error)}`,
			{ cause: error },
		);
	}
}

export async function readJson(path: string, signal?: AbortSignal) {
	signal?.throwIfAborted();
	const file = await open(path, constants.O_RDONLY | constants.O_NONBLOCK);
	try {
		if (!(await file.stat()).isFile()) throw new Error("Structured reads require a regular file");
		const chunks: Buffer[] = [];
		let bytes = 0;
		const stream = file.createReadStream({ autoClose: false, signal });
		try {
			for await (const chunk of stream) {
				bytes += chunk.length;
				if (bytes > JSON_BYTES) throw new Error("JSON input exceeds the 1 MiB limit");
				chunks.push(chunk);
			}
		} finally {
			stream.destroy();
		}
		const result = { path, value: parse(Buffer.concat(chunks), path), bytes, truncated: false };
		if (Buffer.byteLength(JSON.stringify(result)) > JSON_BYTES)
			throw new Error("JSON result exceeds the 1 MiB limit");
		return result;
	} finally {
		await file.close();
	}
}

async function* records(path: string, cursor: Cursor, signal?: AbortSignal) {
	signal?.throwIfAborted();
	const file = await open(path, constants.O_RDONLY | constants.O_NONBLOCK);
	try {
		const stat = await file.stat();
		if (!stat.isFile()) throw new Error("Structured reads require a regular file");
		if (cursor.offset > stat.size) throw new Error("JSONL cursor is beyond the file");
		if (cursor.offset) {
			const byte = Buffer.alloc(1);
			await file.read(byte, 0, 1, cursor.offset - 1);
			if (byte[0] !== 10) throw new Error("JSONL cursor must point to a record boundary");
		}
		const stream = file.createReadStream({ start: cursor.offset, autoClose: false, signal });
		let pending: Buffer = Buffer.alloc(0),
			offset = cursor.offset,
			line = cursor.line,
			scanned = 0;
		try {
			for await (const chunk of stream) {
				signal?.throwIfAborted();
				scanned += chunk.length;
				if (scanned > SCAN_BYTES) throw new Error("JSONL scan exceeds the 32 MiB page limit");
				pending = Buffer.concat([pending, chunk]);
				let end = pending.indexOf(10);
				while (end !== -1) {
					if (end > LINE_BYTES) throw new Error(`JSONL line ${line} exceeds the 2 MiB limit`);
					yield { bytes: pending.subarray(0, end), offset, line };
					offset += end + 1;
					line++;
					pending = pending.subarray(end + 1);
					end = pending.indexOf(10);
				}
				if (pending.length > LINE_BYTES)
					throw new Error(`JSONL line ${line} exceeds the 2 MiB limit`);
			}
			if (pending.length) yield { bytes: pending, offset, line };
		} finally {
			stream.destroy();
		}
	} finally {
		await file.close();
	}
}

function project(value: Json, fields: Fields | undefined, maxChars: number) {
	let truncated = false;
	function clip(value: Json, depth = 0): Json {
		if (depth > 64) throw new Error("JSONL projection exceeds the nesting limit");
		if (typeof value === "string" && value.length > maxChars) {
			truncated = true;
			return value.slice(0, maxChars);
		}
		if (Array.isArray(value)) return value.map((item) => clip(item, depth + 1));
		if (value && typeof value === "object")
			return Object.fromEntries(
				Object.entries(value).map(([key, item]) => [key, clip(item, depth + 1)]),
			);
		return value;
	}
	if (fields) {
		const selected: Record<string, Json> = Object.create(null);
		for (const [name, path] of Object.entries(fields)) {
			let item: Json | undefined = value;
			for (const key of path)
				item =
					item && typeof item === "object" && Object.hasOwn(item, key)
						? (item as Record<string, Json>)[key]
						: undefined;
			if (item !== undefined) selected[name] = item;
		}
		value = selected;
	}
	return { value: clip(value), value_truncated: truncated };
}

export async function readJsonl(
	path: string,
	options: { cursor?: Cursor; fields?: Fields; limit?: number; max_string_chars?: number },
	signal?: AbortSignal,
) {
	const items = [];
	let bytes = 0;
	for await (const record of records(path, options.cursor ?? { offset: 0, line: 1 }, signal)) {
		if (record.bytes.every((byte) => byte === 9 || byte === 13 || byte === 32)) continue;
		const cursor = { offset: record.offset, line: record.line };
		if (items.length >= (options.limit ?? 100))
			return { path, items, next_cursor: cursor, truncated: true };
		const item = {
			line: record.line,
			...project(
				parse(record.bytes, path, record.line),
				options.fields,
				options.max_string_chars ?? 2000,
			),
		};
		const size = Buffer.byteLength(JSON.stringify(item));
		if (size > PAGE_BYTES)
			throw new Error(
				`JSONL projection at line ${record.line} exceeds 48 KiB; select fewer fields or reduce max_string_chars`,
			);
		if (bytes + size > PAGE_BYTES) return { path, items, next_cursor: cursor, truncated: true };
		items.push(item);
		bytes += size;
	}
	return { path, items, next_cursor: null, truncated: false };
}
