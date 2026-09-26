#!/usr/bin/env node
import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptsDir = path.dirname(fileURLToPath(import.meta.url));
const skillsDir = path.resolve(scriptsDir, "../..");
const extension = path.resolve(skillsDir, "../../extensions/jev-pstack.ts");
const forbidden = /\bCursor\b|\.cursor(?:\/|\b)|cursor-team-kit|CURSOR_AUTOMATION_ID|api2\.cursor|subagent_type|Task tool|Task schema|pstack-models\.mdc/;
const files = [];

function walk(directory) {
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const file = path.join(directory, entry.name);
    if (entry.isDirectory()) walk(file);
    else if (/\.(md|ts|mjs|json)$/.test(entry.name) && entry.name !== "check-pi-port.mjs") files.push(file);
  }
}

walk(skillsDir);
files.push(extension);
const failures = [];
for (const file of files) {
  const lines = fs.readFileSync(file, "utf8").split(/\r?\n/);
  lines.forEach((line, index) => {
    if (forbidden.test(line)) failures.push(`${path.relative(skillsDir, file)}:${index + 1}: ${line.trim()}`);
  });
}

if (failures.length) {
  console.error(failures.join("\n"));
  process.exit(1);
}
console.log(`Pi-port check passed for ${files.length} files.`);
