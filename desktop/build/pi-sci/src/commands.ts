import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";
import { guixDomain, makeDomain, repoDomain } from "./builds.ts";
import { fsDomain, searchDomain } from "./files.ts";
import { jjDomain } from "./jj.ts";
import { bindings, registerDomain } from "./operations.ts";
import { sysDomain } from "./sys.ts";

export const DOMAINS = [
	jjDomain,
	guixDomain,
	makeDomain,
	searchDomain,
	fsDomain,
	sysDomain,
	repoDomain,
];
export const DOMAIN_COMMANDS = DOMAINS.map(bindings);
export function registerDomainCommands(pi: ExtensionAPI) {
	for (const domain of DOMAINS) registerDomain(pi, domain);
}
