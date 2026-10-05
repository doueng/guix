#!/usr/bin/env node
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { fileURLToPath } from "node:url";

export function sessionDirectory(workspace = process.cwd(), env = process.env, explicit) {
  const cwd = path.resolve(workspace);
  const home = env.HOME || os.homedir();
  const resolve = (value) => path.resolve(cwd, value === "~" ? home : value.replace(/^~\//, `${home}/`));
  const agent = resolve(env.PI_CODING_AGENT_DIR || path.join(home, ".pi/agent"));
  if (explicit || env.PI_CODING_AGENT_SESSION_DIR) return resolve(explicit || env.PI_CODING_AGENT_SESSION_DIR);

  let configured;
  for (const file of [path.join(agent, "settings.json"), path.join(cwd, ".pi/settings.json")]) {
    try {
      const settings = JSON.parse(fs.readFileSync(file, "utf8"));
      if (settings.sessionDir !== undefined) {
        if (typeof settings.sessionDir !== "string" || !settings.sessionDir.trim()) {
          throw new Error(`Invalid sessionDir in ${file}`);
        }
        configured = settings.sessionDir;
      }
    } catch (error) {
      if (error.code !== "ENOENT") throw error;
    }
  }
  if (configured !== undefined) return resolve(configured);
  const slug = cwd.replace(/^[/\\]/, "").replace(/[/\\:]/g, "-");
  return path.join(agent, "sessions", `--${slug}--`);
}

if (process.argv[1] && fs.realpathSync(process.argv[1]) === fileURLToPath(import.meta.url)) {
  try {
    console.log(sessionDirectory(process.argv[2], process.env, process.argv[3]));
  } catch (error) {
    console.error(error.message);
    process.exitCode = 1;
  }
}
