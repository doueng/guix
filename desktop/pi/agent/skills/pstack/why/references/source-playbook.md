# Source playbooks

The why skill spawns one investigator per available evidence category, each reading a single source-specific playbook below. The playbooks are concrete examples for common MCPs. Adapt them for a different MCP in the same category.

| Category | Playbook | Example MCP it documents |
|---|---|---|
| Source control history | [`code-archaeology.md`](./sources/code-archaeology.md) | git, `gh` |

For issue trackers, documents, team chat, observability, error tracking, and analytics, discover the current session's available tools inside `codemode` with `searchTools()` and `describeTool()`, then call them through `tools.<name>()`. Batch independent read-only searches with `Promise.allSettled()` and check each MCP result's `isError` before using its evidence. This Pi bundle does not include vendor-specific MCP playbooks. Record unavailable sources as evidence gaps.

Cross-cutting:

- [`incident-postmortem.md`](./sources/incident-postmortem.md). Add this if the target code looks defensive (null checks, retry, timeout, rate limit, feature flag, egress guard, OOM handler).
