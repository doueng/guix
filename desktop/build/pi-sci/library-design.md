# Branch-local SCI library

## Contract

The agent writes explicit top-level `defsession` declarations. Definitions live in the `session` namespace. Ordinary `def` remains invocation-local. Durable task data continues to use `store`.

A declaration is either a literal JSON-compatible value or a function. Persistent macros, computed initializers, dynamic namespace mutation, and references to invocation-local namespaces are unsupported.

`session/definitions` returns concise metadata. `session/source` returns declaration source. `session/forget` unbinds a name and journals that operation.

## Execution and ownership

Each invocation owns a fresh worker, SCI context, staged journal, and captured store writes. Replay runs with effects disabled. Executable top-level forms run in order, so declarations take effect at their actual position.

The host reconstructs the ordered journal from the active session branch. The journal format and interpreter fingerprint are explicit. No interpreter objects or invocation API references survive.

A successful invocation appends one `codemode-store` entry with public store changes and an additional `sciEnvironment` delta. Existing Pi store readers ignore the extra field. A reserved VM-only store key carries the delta to the host and is stripped before persistence.

The adapter creates a separate stock executor definition for each invocation, with a local append callback. It captures writes instead of letting the executor append them. Before the single append, it checks the starting session, branch ancestry, environment revision, and latest store entry. A conflict returns an error without committing either data or code. Completed external effects are never rerun or rolled back.

## Alternatives

| Candidate | Benefits | Costs |
|---|---|---|
| Extension with replay and one-entry commits | Retains stock worker isolation and tool pipeline. Uses public extension registration. | Needs an explicit journal protocol and per-invocation commit capture. |
| Pi fork with persistent workers and native transactions | Could retain live interpreter contexts. Could expose richer transaction hooks. | Adds worker lifecycle, capability rebinding, VM cleanup, and recovery responsibilities. |

These are self-generated alternatives. No independent delegation workflow was available.

The SDK integration tests and installed Pi 1.0.0 CLI library test prove commit capture. This version chooses the extension. A fork remains a future option if a measured replay cost warrants changing worker lifetime.

## Recovery and limits

The journal has bounds for operations and source size. Replay shares the VM deadline and memory budget. A changed interpreter fingerprint or malformed journal fails closed.

The operator command `/sci-library reset` records an explicit reset without changing task data. It can recover an incompatible library without evaluating old source. Reset is reversible by branching to its parent.

## Delivery workflow

This task uses a high-rigor, single-owner workflow because interpreter state and durable commits share a contract.

1. Frame the branch-local source and task-data contract.
2. Verify the existing runtime baseline.
3. Validate the journal model and demonstrate a failing persistence test.
4. Add replay and per-invocation commit capture.
5. Verify branches, failures, capabilities, conflicts, recovery, and source semantics.
6. Run the complete installed CLI, TUI, SDK, and existing regression gate.

The decision trail is `decisions.tsv`. No PR or branch rewriting is part of this task. The working copy contains unrelated changes.

### Throughput checkpoint

- Blocking first steps are source replay validation and stock executor commit capture.
- Independent workstreams are not used. The interpreter and transaction changes share their protocol and lifecycle.
- Shared mutable state is the branch log. Each invocation owns its staged writes. The final synchronous revision check and single append serialize the actual commit.
- The smallest safe decomposition is one implementation owner with journal, sandbox, transaction, and real-runtime verification gates.

## Verification predicates

- Helpers, constants, redefinitions, and forgetting survive subsequent invocations and session-file reopening.
- Branches inherit only declarations along their own ancestry.
- Failed, aborted, conflicting, and timed-out invocations commit neither code nor data.
- Successful exit commits declarations already reached, but not later declarations.
- Replay cannot call tools, emit output or images, read or mutate task storage.
- Declarations cannot capture invocation-local bindings or introduce persistent macros.
- Helpers use current capabilities and cannot restore removed tool access.
- Compaction retains the branch library.
- JavaScript remains unsupported.
- Existing guards, model accounting, images, storage, CLI, and TUI regressions pass.
