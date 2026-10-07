# Structured file reads

## Problem

Pi's display-limited text reader cannot supply a complete JSON document beyond 50 KiB. Its line cursor cannot advance through an oversized JSONL record. Both failures occurred during codemode session reviews.

## Usage

```clojure
(let [r (await (fs/read-json "package.json"))]
	(text (:name (:value r))))

(let [page (await (fs/read-jsonl "session.jsonl"
		{:fields {:type ["type"] :role ["message" "role"]}}))]
	(text (:items page)))
```

## Shape

`structured-files.ts` owns bounded byte reads, parsing, projection, and cursor validation. `files.ts` registers these reads as existing `fs` operations. Pi's normal validation, blocking, redaction, and cancellation apply.

A JSON result contains `path`, `value`, `bytes`, and `truncated: false`. Incomplete input fails instead of becoming a partial value.

A JSONL page contains `path`, `items`, `next_cursor`, and `truncated`. Each item contains its physical `line`, projected `value`, and `value_truncated`. The cursor keeps a byte offset and physical line number together. Projection maps caller-selected names to arrays of property keys. String clipping is separate from page continuation.

Model the Domain keeps the cursor's related values together. Boundary Discipline keeps complete parsing inside the file operation.

## Synthesis decision

These are self-generated alternatives, not independent model reviews. Registered structured reads are the base because they solve both observed failures without changing text reads. A SCI paging helper cannot recover an oversized single line. Raising text limits still returns display text and leaves completeness checks to callers.

## Tradeoffs accepted

- JSON reads accept at most 1 MiB of input and serialized result.
- JSONL records accept at most 2 MiB. A page scans at most 32 MiB and returns at most 48 KiB of item data.
- JSONL strings clip at 2000 UTF-16 code units by default. Callers can select fields and change that limit.
- Cursors support append-only files. They are not snapshots or proof that the file has not been replaced. Resume only against the same unchanged or appended file.

## Open questions and risks

Does a future workload need larger documents or records? The current limits cover the reproduced sessions. Such a workload needs separate evidence before changing the bounds.

## Next implementation step

Exercise both readers through the real SCI tool bridge with large documents, large records, page boundaries, and hook policies.
