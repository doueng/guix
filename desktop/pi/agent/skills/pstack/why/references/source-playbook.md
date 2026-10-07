# Source playbooks

The why skill spawns one investigator per available evidence category, each reading a single source-specific playbook below. The playbooks are concrete examples for common MCPs. Adapt them for a different MCP in the same category.

| Category | Playbook | Example MCP it documents |
|---|---|---|
| Source control history | [`code-archaeology.md`](./sources/code-archaeology.md) | git, `gh` |

For issue trackers, documents, team chat, observability, error tracking, and analytics, discover, inspect, and invoke the current session's available tools inside `codemode` in its declared language. Batch independent read-only searches with that language's settled parallel-call helper and check each MCP result's `isError` before using its evidence. This Pi bundle does not include vendor-specific MCP playbooks. Record unavailable sources as evidence gaps.

Cross-cutting:

- [`incident-postmortem.md`](./sources/incident-postmortem.md). Add this if the target code looks defensive (null checks, retry, timeout, rate limit, feature flag, egress guard, OOM handler).
