import type { ExtensionAPI } from "@earendil-works/pi-coding-agent";

import { createTokenjuicePiExtension } from "./pi-extension/runtime.js";

export default function tokenjuiceVendoredExtension(pi: ExtensionAPI) {
  return createTokenjuicePiExtension({ extensionCommand: "tj" })(pi as any);
}
