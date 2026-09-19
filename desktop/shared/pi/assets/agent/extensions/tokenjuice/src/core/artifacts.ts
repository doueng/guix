import { mkdir, readFile, readdir, writeFile } from "node:fs/promises";
import { homedir } from "node:os";
import { join } from "node:path";
import { randomUUID } from "node:crypto";

import { countTextChars, stripAnsi } from "./text.js";

import type { ArtifactMetadataRef, StoredArtifact, StoredArtifactInput, StoredArtifactMetadata, StoredArtifactRef } from "../types.js";

const ARTIFACT_ID_PATTERN = /^tj_[0-9a-f-]{12}$/iu;

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value);
}

function isStoredArtifactMetadata(value: unknown): value is StoredArtifactMetadata {
  if (!isRecord(value) || typeof value.createdAt !== "string" || typeof value.rawChars !== "number") {
    return false;
  }

  if (!isRecord(value.classification) || typeof value.classification.family !== "string" || typeof value.classification.confidence !== "number") {
    return false;
  }

  if ("matchedReducer" in value.classification && value.classification.matchedReducer !== undefined && typeof value.classification.matchedReducer !== "string") {
    return false;
  }

  if ("toolName" in value && value.toolName !== undefined && typeof value.toolName !== "string") {
    return false;
  }
  if ("command" in value && value.command !== undefined && typeof value.command !== "string") {
    return false;
  }
  if ("exitCode" in value && value.exitCode !== undefined && typeof value.exitCode !== "number") {
    return false;
  }
  if ("reducedChars" in value && value.reducedChars !== undefined && typeof value.reducedChars !== "number") {
    return false;
  }
  if ("ratio" in value && value.ratio !== undefined && typeof value.ratio !== "number") {
    return false;
  }
  if ("filteredTextPath" in value && value.filteredTextPath !== undefined && typeof value.filteredTextPath !== "string") {
    return false;
  }
  if ("diffPath" in value && value.diffPath !== undefined && typeof value.diffPath !== "string") {
    return false;
  }

  return true;
}

function artifactBaseDir(storeDir?: string): string {
  return storeDir ?? join(homedir(), ".tokenjuice", "artifacts");
}

export function isValidArtifactId(id: string): boolean {
  return ARTIFACT_ID_PATTERN.test(id);
}

function buildArtifactPaths(id: string, storeDir?: string): StoredArtifactRef {
  if (!isValidArtifactId(id)) {
    throw new Error(`invalid artifact id: ${id}`);
  }

  const base = artifactBaseDir(storeDir);
  return {
    id,
    storage: "file",
    path: join(base, `${id}.txt`),
    metadataPath: join(base, `${id}.json`),
    filteredTextPath: join(base, `${id}.filtered.txt`),
    diffPath: join(base, `${id}.diff.txt`),
  };
}

function buildMetadataOnlyPath(id: string, storeDir?: string): string {
  if (!isValidArtifactId(id)) {
    throw new Error(`invalid artifact id: ${id}`);
  }
  return join(artifactBaseDir(storeDir), `${id}.meta.json`);
}

function buildFilteredPath(id: string, storeDir?: string): string {
  if (!isValidArtifactId(id)) {
    throw new Error(`invalid artifact id: ${id}`);
  }
  return join(artifactBaseDir(storeDir), `${id}.filtered.txt`);
}

function buildDiffPath(id: string, storeDir?: string): string {
  if (!isValidArtifactId(id)) {
    throw new Error(`invalid artifact id: ${id}`);
  }
  return join(artifactBaseDir(storeDir), `${id}.diff.txt`);
}

type LineChange = {
  value: string;
  added?: boolean;
  removed?: boolean;
};

const FINE_GRAINED_DIFF_MATRIX_LIMIT = 20_000;

function splitLines(value: string): string[] {
  const lines = value.split("\n");
  if (lines[lines.length - 1] === "") {
    lines.pop();
  }
  return lines;
}

function pushLineChange(changes: LineChange[], next: LineChange): void {
  if (!next.value) {
    return;
  }

  const previous = changes[changes.length - 1];
  if (previous && previous.added === next.added && previous.removed === next.removed) {
    previous.value = `${previous.value}\n${next.value}`;
    return;
  }

  changes.push(next);
}

function diffLineSlices(before: string[], after: string[]): LineChange[] {
  if (before.length === 0 && after.length === 0) {
    return [];
  }
  if (before.length === 0) {
    return [{ added: true, value: after.join("\n") }];
  }
  if (after.length === 0) {
    return [{ removed: true, value: before.join("\n") }];
  }

  if (before.length * after.length > FINE_GRAINED_DIFF_MATRIX_LIMIT) {
    return [
      { removed: true, value: before.join("\n") },
      { added: true, value: after.join("\n") },
    ];
  }

  const matrix = Array.from({ length: before.length + 1 }, () => Array<number>(after.length + 1).fill(0));
  for (let leftIndex = before.length - 1; leftIndex >= 0; leftIndex -= 1) {
    for (let rightIndex = after.length - 1; rightIndex >= 0; rightIndex -= 1) {
      matrix[leftIndex][rightIndex] = before[leftIndex] === after[rightIndex]
        ? matrix[leftIndex + 1][rightIndex + 1] + 1
        : Math.max(matrix[leftIndex + 1][rightIndex], matrix[leftIndex][rightIndex + 1]);
    }
  }

  const changes: LineChange[] = [];
  let leftIndex = 0;
  let rightIndex = 0;
  while (leftIndex < before.length && rightIndex < after.length) {
    if (before[leftIndex] === after[rightIndex]) {
      pushLineChange(changes, { value: before[leftIndex] });
      leftIndex += 1;
      rightIndex += 1;
      continue;
    }

    if (matrix[leftIndex + 1][rightIndex] >= matrix[leftIndex][rightIndex + 1]) {
      pushLineChange(changes, { removed: true, value: before[leftIndex] });
      leftIndex += 1;
      continue;
    }

    pushLineChange(changes, { added: true, value: after[rightIndex] });
    rightIndex += 1;
  }

  while (leftIndex < before.length) {
    pushLineChange(changes, { removed: true, value: before[leftIndex] });
    leftIndex += 1;
  }
  while (rightIndex < after.length) {
    pushLineChange(changes, { added: true, value: after[rightIndex] });
    rightIndex += 1;
  }

  return changes;
}

function buildSimpleDiff(rawText: string, filteredText: string): string {
  const before = splitLines(rawText);
  const after = splitLines(filteredText);

  let prefixLength = 0;
  while (prefixLength < before.length && prefixLength < after.length && before[prefixLength] === after[prefixLength]) {
    prefixLength += 1;
  }

  let beforeSuffixIndex = before.length - 1;
  let afterSuffixIndex = after.length - 1;
  while (beforeSuffixIndex >= prefixLength && afterSuffixIndex >= prefixLength && before[beforeSuffixIndex] === after[afterSuffixIndex]) {
    beforeSuffixIndex -= 1;
    afterSuffixIndex -= 1;
  }

  const changes: LineChange[] = [];
  if (prefixLength > 0) {
    changes.push({ value: before.slice(0, prefixLength).join("\n") });
  }

  changes.push(...diffLineSlices(
    before.slice(prefixLength, beforeSuffixIndex + 1),
    after.slice(prefixLength, afterSuffixIndex + 1),
  ));

  if (beforeSuffixIndex + 1 < before.length) {
    changes.push({ value: before.slice(beforeSuffixIndex + 1).join("\n") });
  }

  return changes.map((part) => {
    const prefix = part.added ? "+ " : part.removed ? "- " : "  ";
    return part.value
      .split("\n")
      .map((line) => `${prefix}${line}`)
      .join("\n");
  }).filter(Boolean).join("\n");
}

export async function storeArtifact(input: StoredArtifactInput, storeDir?: string): Promise<StoredArtifactRef> {
  const id = `tj_${randomUUID().slice(0, 12)}`;
  const ref = buildArtifactPaths(id, storeDir);
  await mkdir(artifactBaseDir(storeDir), { recursive: true, mode: 0o700 });

  const filteredTextPath = input.filteredText !== undefined ? buildFilteredPath(id, storeDir) : undefined;
  const diffPath = input.filteredText !== undefined ? buildDiffPath(id, storeDir) : undefined;
  const artifactRef: StoredArtifactRef = {
    ...ref,
    ...(filteredTextPath ? { filteredTextPath } : {}),
    ...(diffPath ? { diffPath } : {}),
  };
  const artifact: StoredArtifact = {
    id,
    rawText: input.rawText,
    metadata: {
      createdAt: new Date().toISOString(),
      classification: input.classification,
      rawChars: input.stats?.rawChars ?? countTextChars(stripAnsi(input.rawText)),
      ...(input.input.toolName ? { toolName: input.input.toolName } : {}),
      ...(input.input.command ? { command: input.input.command } : {}),
      ...(typeof input.input.exitCode === "number" ? { exitCode: input.input.exitCode } : {}),
      ...(input.stats ? { reducedChars: input.stats.reducedChars, ratio: input.stats.ratio } : {}),
      ...(filteredTextPath ? { filteredTextPath } : {}),
      ...(diffPath ? { diffPath } : {}),
    },
  };

  const writes = [
    writeFile(ref.path, input.rawText, { encoding: "utf8", mode: 0o600 }),
    writeFile(ref.metadataPath, JSON.stringify(artifact.metadata, null, 2), { encoding: "utf8", mode: 0o600 }),
  ];
  if (filteredTextPath && input.filteredText !== undefined) {
    writes.push(writeFile(filteredTextPath, input.filteredText, { encoding: "utf8", mode: 0o600 }));
  }
  if (diffPath && input.filteredText !== undefined) {
    writes.push(writeFile(diffPath, buildSimpleDiff(input.rawText, input.filteredText), { encoding: "utf8", mode: 0o600 }));
  }

  await Promise.all(writes);

  return artifactRef;
}

export async function storeArtifactMetadata(input: StoredArtifactInput, storeDir?: string): Promise<ArtifactMetadataRef> {
  const id = `tj_${randomUUID().slice(0, 12)}`;
  const metadataPath = buildMetadataOnlyPath(id, storeDir);
  const metadata: StoredArtifactMetadata = {
    createdAt: new Date().toISOString(),
    classification: input.classification,
    rawChars: input.stats?.rawChars ?? countTextChars(stripAnsi(input.rawText)),
    ...(input.input.toolName ? { toolName: input.input.toolName } : {}),
    ...(input.input.command ? { command: input.input.command } : {}),
    ...(typeof input.input.exitCode === "number" ? { exitCode: input.input.exitCode } : {}),
    ...(input.stats ? { reducedChars: input.stats.reducedChars, ratio: input.stats.ratio } : {}),
  };

  await mkdir(artifactBaseDir(storeDir), { recursive: true, mode: 0o700 });
  await writeFile(metadataPath, JSON.stringify(metadata, null, 2), { encoding: "utf8", mode: 0o600 });

  return {
    id,
    storage: "file",
    metadataPath,
    metadata,
  };
}

export async function getArtifact(id: string, storeDir?: string): Promise<StoredArtifact | null> {
  if (!isValidArtifactId(id)) {
    return null;
  }

  const ref = buildArtifactPaths(id, storeDir);
  try {
    const [rawText, metadataRaw] = await Promise.all([
      readFile(ref.path, "utf8"),
      readFile(ref.metadataPath, "utf8"),
    ]);
    return {
      id,
      rawText,
      metadata: (() => {
        const parsed = JSON.parse(metadataRaw) as unknown;
        if (!isStoredArtifactMetadata(parsed)) {
          throw new Error("invalid artifact metadata");
        }
        return parsed;
      })(),
    };
  } catch {
    return null;
  }
}

export async function listArtifacts(storeDir?: string): Promise<StoredArtifactRef[]> {
  const base = artifactBaseDir(storeDir);
  try {
    const files = await readdir(base);
    return files
      .filter((name) => name.endsWith(".json"))
      .map((name) => name.replace(/\.json$/u, ""))
      .filter((id) => isValidArtifactId(id))
      .sort()
      .reverse()
      .map((id) => buildArtifactPaths(id, storeDir));
  } catch {
    return [];
  }
}

export async function listArtifactMetadata(storeDir?: string): Promise<ArtifactMetadataRef[]> {
  const base = artifactBaseDir(storeDir);
  try {
    const files = await readdir(base);
    const metadata = await Promise.all(
      files
        .filter((name) => name.endsWith(".json"))
        .map(async (name) => {
          const rawId = name.endsWith(".meta.json") ? name.replace(/\.meta\.json$/u, "") : name.replace(/\.json$/u, "");
          if (!isValidArtifactId(rawId)) {
            return null;
          }

          const metadataPath = join(base, name);
          try {
            const raw = await readFile(metadataPath, "utf8");
            const parsed = JSON.parse(raw) as unknown;
            if (!isStoredArtifactMetadata(parsed)) {
              return null;
            }
            const path = name.endsWith(".meta.json") ? undefined : join(base, `${rawId}.txt`);
            return {
              id: rawId,
              storage: "file" as const,
              ...(path ? { path } : {}),
              metadataPath,
              metadata: parsed,
            };
          } catch {
            return null;
          }
        }),
    );

    return metadata
      .filter((entry): entry is ArtifactMetadataRef => entry !== null)
      .sort((left, right) => right.metadata.createdAt.localeCompare(left.metadata.createdAt));
  } catch {
    return [];
  }
}
