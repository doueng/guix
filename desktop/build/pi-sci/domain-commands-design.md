# Workstation API design

## Contract and organizing shape

The public interface expresses domain operations and returns data SCI can transform. Examples are `jj/log` with revisions and limits, `search/text` with queries and paths, `fs/read` with line bounds, and verified `repo` actions. `Operation` in `src/operations.ts` owns input schema, handler, positional shorthand, scalar selection, and mutation metadata. Domain registries group operations. Schemas, bindings, and descriptions derive from those records.

One registered Pi tool per domain dispatches a validated `operation`. Every public effect enters Pi's normal tool pipeline before the host handler. There is no model-visible generic executable selector, git namespace, rg namespace, or hidden SCI host executor. Fixed-program raw fallbacks remain lower priority for operations not yet modeled. Bash is the existing exceptional interface.

## Grounding and alternatives

The previous registry selected command prefixes and returned stdout/stderr. It preserved the effect boundary but did not hide CLI syntax or output parsing. Pi's grep/find implementations retain match/path records internally, then discard them when formatting tool output. Pi read already computes offsets and truncation, but its result text includes display notices. The source journal resolves only current capabilities and disables effects during replay.

| Self-generated candidate | Benefit | Cost |
|---|---|---|
| One Pi tool per semantic function | Each operation has a standalone schema and tool name. | Dozens of registrations and descriptions repeat lifecycle wiring. |
| One Pi tool per domain with typed dispatch | A discriminated schema exposes operations and hooks see semantic intent. | TypeScript needs a checked registry-to-union schema boundary. |
| Trusted wrappers over an unregistered generic host executor | Few tool registrations. | Bypasses Pi policy unless another pipeline is recreated. Rejected. |

Choose typed domain dispatch. Model the Domain puts operations and their schemas in records. Boundary Discipline keeps validation and effects in Pi's registered tools, with pure record normalization after command completion. The design hides argument construction and machine output behind the same handler rather than exposing compiler stages as separate APIs.

No independent design runners are available. These alternatives are self-generated. A single owner integrates the shared capability and schema contracts.

## Search and file implementation

Use checked build-time adaptations of pinned Pi 1.0.4 grep, find, and read implementations. SHA-256 guards and exact source anchors fail the build on upstream drift. Generated modules keep upstream helper imports and licensing. They add structured output before display formatting and leave built-in tool behavior available to existing callers. Grep also honors ignore files outside Git repositories to support JJ checkouts.

Search is a semantic capability, not an executable wrapper. No rg function or argv API is registered. Match columns retain the upstream UTF-8 byte convention. The stock filename finder remains line-oriented; filenames containing newlines are not guaranteed to round-trip. Unsupported binary-path behavior remains an upstream limitation.

Directory listing and stat are small guarded filesystem operations. Listing bounds results, bytes, depth, and directory visits and does not recurse through symlinks. File read retains Pi image processing and returns clean text/line metadata. Oversized single lines have no fabricated advancing cursor.

## Data and errors

Collections have `items` and `truncated`. Defaults and hard maximums bound result counts. Byte limits remain explicit. No continuation token claims stable paging over changing filesystem state. JJ JSON is validated before normalization; unsupported or transport-truncated output fails rather than being parsed as display text. Status pins its diff to the resolved working-copy commit ID.

Command actions retain status, diagnostic, deadline, signal, duration, and log metadata. Guix build outputs are certified only for successful complete stdout containing store paths. Nonzero action exits return data; malformed machine reads and aborts reject. Result helpers check command success, not collection completeness.

Public read operations and mutations are distinct. Mutation markers are conservative intent metadata, not authorization. Make and configuration files can execute programs. Permission for irreversible operations remains required. No interpreter-name ban is introduced.

## Workflow and throughput checkpoint

1. Inspect registry, pipeline, replay, and Pi search interfaces.
2. Add typed domain operations and capability-derived aliases.
3. Preserve structured Pi search/read data through checked adaptations.
4. Verify temporary-repository reads/mutations, path quoting, search limits, ignore behavior, file continuation, images, lifecycle, hooks, schemas, and helper replay.
5. Verify installed CLI/TUI and full regression gate, then update usage guidance.

- Blocking first steps are the Pi effect boundary, structured search feasibility, and existing verification gate.
- Domain and file/search backends have separate tests; integration remains one owner because aliases and schemas share a contract.
- Each command owns its process and logs. Directory listing owns its queue. Existing branch transactions remain the only durable shared writer.
- The smallest safe decomposition is separate domain implementation modules with one registration/binding owner.

No commits, PR, or branch rewriting are part of this task. Tests prove execution and data behavior, not improved live-model decisions. Frontend and bundle changes require reload together; incompatible cached descriptors report that requirement.
