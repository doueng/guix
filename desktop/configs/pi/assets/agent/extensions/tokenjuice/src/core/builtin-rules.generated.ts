import fallback from "../rules/generic/fallback.json" with { type: "json" };
import help from "../rules/generic/help.json" with { type: "json" };
import rg from "../rules/search/rg.json" with { type: "json" };
import grep from "../rules/search/grep.json" with { type: "json" };
import journalctl from "../rules/service/journalctl.json" with { type: "json" };
import make from "../rules/task/make.json" with { type: "json" };

export const BUNDLED_BUILTIN_RULES = [fallback, help, rg, grep, journalctl, make] as const;
