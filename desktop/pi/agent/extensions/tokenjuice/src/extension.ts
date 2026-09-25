// Pi entry point for upstream Tokenjuice v0.8.5.
import { createTokenjuicePiExtension } from "./hosts/pi/extension/runtime.js";

export default createTokenjuicePiExtension({ extensionCommand: "tj" });
