#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";
import { fileURLToPath } from "node:url";

const scriptsDir = path.dirname(fileURLToPath(import.meta.url));
const skillsDir = path.resolve(scriptsDir, "../..");
const extension = path.resolve(skillsDir, "../../extensions/jev-pstack.ts");
const forbidden = /\bCursor\b|\.cursor(?:\/|\b)|cursor-team-kit|CURSOR_AUTOMATION_ID|api2\.cursor|subagent_type|Task tool|Task schema|pstack-models\.mdc/;
const files = [];
const failures = [];

function walk(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory() && entry.name !== "node_modules") walk(file);
    else if (/\.(md|ts|mjs|json)$/.test(entry.name) && entry.name !== "check-pi-port.mjs") files.push(file);
  }
}

function skillDirectory(file) {
  let directory = path.dirname(file);
  while (directory.startsWith(skillsDir)) {
    if (fs.existsSync(path.join(directory, "SKILL.md"))) return directory;
    directory = path.dirname(directory);
  }
  return path.dirname(file);
}

walk(skillsDir);
files.push(extension);
for (const file of files) {
  const lines = fs.readFileSync(file, "utf8").split(/\r?\n/);
  const fail = (index, reason) => failures.push(`${path.relative(skillsDir, file)}:${index + 1}: ${reason}`);
  lines.forEach((line, index) => {
    if (forbidden.test(line)) fail(index, line.trim());
    if (/(?<!~\/)\.pi\/agent\/(?:skills|extensions|prompts)\//.test(line)) fail(index, "obsolete project skill path");
    if (/pstack\/skills\/|agent-transcripts\//.test(line)) fail(index, "unported host path");
    if (!file.endsWith(".md")) return;
    for (const match of line.matchAll(/\[[^\]]*\]\(([^ )]+)\)/g)) {
      const target = match[1].split("#")[0];
      if (!target || /^[a-z]+:/i.test(target) || /[<>]/.test(target) || target === "url") continue;
      if (!fs.existsSync(path.resolve(path.dirname(file), target))) fail(index, `missing relative link ${target}`);
    }
    for (const match of line.matchAll(/`((?:scripts|references|playbooks)\/[a-zA-Z0-9_./-]+\.(?:md|mjs|ts|sh|tsv))`/g)) {
      if (!fs.existsSync(path.resolve(skillDirectory(file), match[1]))) fail(index, `missing bundled file ${match[1]}`);
    }
  });
}

const routesSource = fs.readFileSync(extension, "utf8").match(/const ROUTES = \{([\s\S]*?)\} as const;/);
const extensionRoutes = routesSource ? [...routesSource[1].matchAll(/^\s*(\w+):/gm)].map((match) => match[1]) : [];
const mapRoutes = [...fs.readFileSync(path.join(skillsDir, "poteto-mode/SKILL.md"), "utf8").matchAll(/^\| `(\w+)` \|/gm)].map((match) => match[1]);
if (extensionRoutes.length === 0) failures.push("jev-pstack.ts: no ROUTES found");
if (extensionRoutes.join(",") !== mapRoutes.join(",")) {
  failures.push(`poteto-mode Route map [${mapRoutes.join(", ")}] differs from jev-pstack.ts ROUTES [${extensionRoutes.join(", ")}]`);
}

const bun = spawnSync("bun", ["--version"], { encoding: "utf8" });
if (bun.status !== 0) failures.push("Bun is unavailable. Install the development Home packages before running pstack tools.");

if (failures.length) {
  console.error(failures.join("\n"));
  process.exit(1);
}
console.log(`Pi-port check passed for ${files.length} files; Bun ${bun.stdout.trim()}.`);
