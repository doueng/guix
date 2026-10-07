import { realpathSync } from "node:fs";
import { dirname, resolve } from "node:path";
import { fileURLToPath, pathToFileURL } from "node:url";
import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

export default async function sciCodemode(pi: ExtensionAPI) {
	const here = dirname(realpathSync(fileURLToPath(import.meta.url)));
	const implementation = pathToFileURL(resolve(here, "../../../../build/pi-sci/src/extension.ts"));
	const factory = (await import(implementation.href)).default;
	return factory(pi);
}
