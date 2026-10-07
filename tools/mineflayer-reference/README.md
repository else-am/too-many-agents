# Mineflayer API inventory and evidence

This directory contains the pinned PC Minecraft 1.21.1 reference installation,
library comparisons, shared gameplay procedures, and a disposable reference
server harness. **No live reference-server run is recorded. Minecraft EULA
acceptance is still unanswered.** Catalog generation does not start any client,
server, Minecraft instance, or BB process.

## Generate the inventory

With the reference dependencies already installed:

```sh
npm --prefix tools/mineflayer-reference run catalog
```

A worktree without dependencies can read another checkout's installation and
scenario files without modifying it:

```sh
MINEFLAYER_REFERENCE_ROOT=/checkout/tools/mineflayer-reference \
MINEFLAYER_PLUGIN_ROOT=/checkout/bb-plugin \
MINEFLAYER_SOURCE_ROOT=/checkout \
node tools/mineflayer-reference/catalog.mjs
```

`REFERENCE_ROOT` supplies pinned packages and TypeScript; `PLUGIN_ROOT` is the
fallback for bundled dependencies such as `events`. `SOURCE_ROOT` supplies
scenario/report files for the evidence index. All three default to the current
checkout's corresponding directories. Output always goes to the invoking
worktree's ignored `run/mineflayer-reference/catalog.json`.

The generator verifies versions against `upstream.json` and hashes every input
file. Package manifests/locks remain the installation source of truth; the
additional pins assert which dependency contracts this inventory inspected.

## Read the catalog

Schema 2 retains the original declaration keys and `coverage.json.entries`
format. Generated records carry package/file/line provenance, signatures, and
links to documentation headings. In particular:

- `declarations` includes Mineflayer, Pathfinder/goals/Movements, Block, Item,
  Window, Entity, Vec3, Recipe/RecipeItem, World/WorldSync/query iterators, chat,
  biome, registry data, chunk types, NBT shapes, and inherited emitter methods.
- `inheritedFrom` and `canonical` expose inherited call sites without counting
  each projection as another implementation. `baseMembers` preserves overrides.
  Declaration/source inheritance disagreements remain explicit review items.
- `category` distinguishes gameplay members/events, factories, object containers,
  data shapes, source candidates, and documentation-only discoveries. A type
  alias, loader, private helper, or source assignment is not automatically an
  additional required gameplay promise. Unclear applicability is `pending-review`.
- `sourceReview`, `privateDeclarations`, `dependencyReview`, and unlinked
  `documentationReview` headings preserve unresolved discovery. Source scanning
  targets named gameplay objects and Mineflayer plugin assignments/events; it
  does not claim arbitrary runtime reflection or every dynamic assignment is
  statically discoverable. Inaccessible, dynamic, and documentation-only behavior
  still needs review, not exclusion.
- EventEmitter instance methods come from bundled `events@3.3.0` and the pinned
  typed-emitter declarations, not an unrelated newer Node type surface. Its
  static helpers remain separately reviewable. Event payloads/order have their
  own records and evidence requirements.

Counts summarize canonical member/event/factory records, including pending
review; they are **not completion percentages**. Inherited aliases, data-shape
records, and private/source-only candidates are separately indexed. The only
current inapplicable entries remain `createBot`, `Bot.connect`, and `Bot._client`.
Nothing else is silently excluded because it is unimplemented or unclear.

## Evidence and decisions

`coverage.json.evidence` indexes executables by kind:

| Kind | What it can establish |
| --- | --- |
| `library-differential` | Behavior against pinned libraries, including actual QuickJS where the suite uses it; synthetic data is not native observation |
| `serialization` | Wire/component decoding and transport fixtures; source-derived bytes are not Java-generated or live-reference proof |
| `runtime-boundary` | Worker isolation, bridge completion, cancellation and resource bounds |
| `native-integration` | A native fixture/procedure with independent observations, when a run report is actually recorded |
| `live-reference` | An actual reference-server run, when authorized and recorded |

`available` and the current scenario hash are generated from existing files.
`result: "not-recorded"` means availability only; it neither asserts a pass nor
erases results previously discussed in a thread. `relatedEvidence` identifies
relevant suites, **not proof that every member or condition was exercised**.
Library suites and native procedures without durable reports remain available
only. Five supplied native reports (Window, raycast, route policy, lifecycle,
and shapes) are indexed as narrow passes after checking their executed code
against the scenario files. No full API is promoted. Missing scripts or ignored
reports in another worktree are listed explicitly and remain unverified.
`artifactReview` indexes available `*-native.json` reports by hash, and
`scenarioReview` flags new unindexed `observe-*`/`navigate-*` procedures; neither
queue invents passing results or copies report contents.

To record a passing run, add `result: "passed"`, the executed `sourceSha256`,
and `report: { "path": "run/.../result.json", "sha256": "..." }` to that evidence
record. The generator verifies both hashes; missing or changed artifacts become
`unverified`. A `report.codePath` array optionally locates the executed script
in a structured tool report; its code must match the scenario after trimming
outer whitespace. The report must describe what ran,
its fixture, results, independent observations, and limitations; a file hash
does not evaluate those claims for the reviewer.

To mark a particular API `supported`, retain its stable key under `entries`,
attach `evidence` IDs and executable `scenarios` paths, and review the reports.
The generator requires verified passing **native-integration and live-reference**
evidence; a library-only pass cannot promote a full native gameplay API. Pending
members remain pending by default. Applicability changes require explicit review;
the generator permits only the three existing exclusions.

Immediate review priorities are native Entity/recipe/chat/registry observations,
container mutations, remaining world/chunk methods, event ordering, and actual
Pathfinder execution/lifecycle. Source-only fields and declaration contradictions
are in the generated review queues. Paired live-reference runs remain absent.

## Reference server (separate, not authorized by catalog generation)

Use Node 22+ and Java 21. When dependency installation and server preparation
are authorized, `npm ci --prefix tools/mineflayer-reference --ignore-scripts`
and `npm --prefix tools/mineflayer-reference run prepare` prepare the harness.
Preparation downloads the official server, checks its SHA-1, and preserves an
existing EULA choice; otherwise it writes `eula=false`.

Do not start it until the user accepts [Minecraft's EULA](https://www.minecraft.net/en-us/eula)
and sets `eula=true` in `run/mineflayer-reference/eula.txt`. The authorized
`npm --prefix tools/mineflayer-reference run check` command starts its own server
on `127.0.0.1:25575`, uses offline authentication and a disposable world, executes
`gather.js`, independently checks server block/inventory state, writes
`gather-reference.json`, and stops that server. Do not use an already-running
server. This is one scenario, not full conformance; it never opens the user's
world to LAN or manages BB.
