# Mineflayer API inventory and evidence

This directory contains the pinned PC Minecraft 1.21.1 reference installation,
library comparisons, shared gameplay procedures, and a disposable reference
server harness. The user accepted the Minecraft EULA; the shared gather procedure
has a recorded live reference pass. Wall/crafting differences remain recorded
below. Catalog generation does not start any client,
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
Reviewed exclusions cover connection/account/socket methods, the internal packet client and resource-pack negotiation. The user also chose native Mob mechanics: self-player metadata, hunger/saturation, player respawn and the client player-physics predictor/toggle are inapplicable. Actual body identity, health, item use, motion and dominant-hand settings remain applicable. Exact keys and source reasons are in coverage.json.
The dependency also declares a separate BedrockChunk class. Its 44 own members
are inapplicable to the Java Edition 1.21.1 pin; PCChunk and shared APIs remain
independent obligations. This follows the upstream pc/bedrock factory split and
the selected PC1.18 implementation used for 1.21.1.
Nothing else is silently excluded because it is unimplemented or unclear.

## Locate current implementation

`implementationSites` records direct guest Bot/creative/Pathfinder assignments
and object registrations, with file/line locations and file hashes in
`implementationInputs`. Matching declarations receive `implementationSources`.
These are navigation aids, not implementation or conformance passes. Computed
registrations, inherited methods, dependency factories and indirect installers
may have no site; absence is not evidence that a member is missing. Native Java
behavior and each method’s complete contract still require review.

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
only. Five earlier native reports (Window, raycast, route policy, lifecycle,
and shapes), six later packaged runs (consumption/signs/game, fishing, boat
controls, sleep, wake, observed events), and five more recent runs (direct
following, sounds, presentation, creative actions, accepted entity events),
two `67ebb2c` runs (Fox/body collection and basic horse controls),
and the `c244383` native handedness check
are indexed as narrow passes after
checking their executed code against the scenario files. Later reports identify
the tested revision and retain the independent native summary. They do not
validate newer source. No full API is promoted. Missing scripts or ignored
reports in another worktree are listed explicitly and remain unverified.
`artifactReview` indexes available `*-native.json` reports by hash, and
`scenarioReview` flags new unindexed `observe-*`/`navigate-*` procedures; neither
queue invents passing results or copies report contents.

To record a run, add `result: "passed"` or `result: "failed"`, the executed `sourceSha256`,
and `report: { "path": "run/.../result.json", "sha256": "..." }` to that evidence
record. The generator verifies both hashes; missing or changed artifacts become
`unverified`. A `report.codePath` array optionally locates the executed script
in a structured tool report; its code must match the scenario after trimming
outer whitespace. The report must describe what ran,
its fixture, results, independent observations, and limitations; a file hash
does not evaluate those claims for the reviewer. Failed reports receive the same
integrity checks as passes and retain their original `reportedResult` if an
artifact becomes unavailable. A verified failure is still a failure, not
supporting conformance evidence. Documented upstream defects require an explicit
behavioral comparison; they must not be relabeled as passing reference runs.

To mark a particular API `supported`, retain its stable key under `entries`,
attach `evidence` IDs and executable `scenarios` paths, and review the reports.
Physical actions and native observations require verified passing
**native-integration and live-reference** evidence. Explicitly reviewed fixed
PC1.21.1 data/version members and the explicit source-reviewed Vec3 member list may use `verification: "pinned-library"` with a
verified **library-differential** report. The generator has an explicit list of
eligible keys; marking `dig` or a dynamic/native member as library-only rejects.
Vec3 eligibility covers arithmetic, local mutation and representation of supplied values; it does not establish native position/velocity hydration or movement. The class-wide key and package helper are not automatically eligible. No vector member is promoted by this policy change alone.
Every promotion still requires related subjects, exact scenario/report hashes,
and reviewed scope. A library pass cannot promote a native gameplay API. Missing
supporting reports cause validation to fail rather than silently retaining a
supported claim; point SOURCE_ROOT at the checkout containing the evidence.
Pending members remain pending by default. Applicability changes require explicit review;
the generator permits only the explicitly reviewed native-body and connection exclusions.

Current implementation and native-verification gaps are tracked in
[the handoff checklist](../../MINEFLAYER-STATUS.md). Recent ports awaiting native
checks, raw entity metadata, dynamic registry codecs, broader world/chunk coverage and
Pathfinder execution/lifecycle remain review priorities. Native Mob/player
applicability follows the user-confirmed boundary in the checklist. Source-only fields and declaration contradictions
are in the generated review queues. The shared gather procedure now has an actual live reference pass and historical native outcome match. Wider paired coverage remains incomplete.

## Reference server (separate, not authorized by catalog generation)

Use Node 22+ and Java 21. When dependency installation and server preparation
are authorized, `npm ci --prefix tools/mineflayer-reference --ignore-scripts`
and `npm --prefix tools/mineflayer-reference run prepare` prepare the harness.
Preparation downloads the official server, checks its SHA-1, and preserves an
existing EULA choice; otherwise it writes `eula=false`.

The user explicitly accepted [Minecraft's EULA](https://www.minecraft.net/en-us/eula) in this thread on October 7, 2026; the local prepared `eula.txt` records that choice. New environments still require an actual accepted EULA file before startup. The authorized
`npm --prefix tools/mineflayer-reference run check` command starts its own server
on `127.0.0.1:25575`, uses offline authentication and a disposable world, executes
`gather.js`, independently checks server block/inventory state, writes
`gather-reference.json`, and stops that server. Do not use an already-running
server. This is one scenario, not full conformance; it never opens the user's
world to LAN or manages BB.

## Creative item encoding

`node tools/mineflayer-reference/item-wire.mjs --encode` additionally checks the
trusted host encoder against the existing literal Slot bytes, reverse registry
mapping, Unicode NBT and malformed values. It does not run Minecraft or establish
native creative-slot behavior. See [creative scenarios](creative-native-scenarios.md).

## Historical native scripts

`recorded/` preserves exact scripts from successful focused packaged runs;
these are not fixture-independent tests or automatic replay instructions. They
refer to the original guarded world, inventory, coordinates and sometimes entity
UUIDs. Re-observe and deliberately prepare prerequisites before any future run.
Some scripts require coordinated native changes while listeners wait. Reports
remain under ignored `run/mineflayer-reference/`; another checkout without them
correctly reports unverified evidence. No live tests are run by catalog generation.

## Recorded live reference outcomes

The shared gather procedure passed on unmodified pinned Mineflayer/Pathfinder and the isolated vanilla 1.21.1 server: ore air, diamond1, pickaxe held; server NBT/block checks independently confirmed it. The identical historical native script produced the same result. The first harness inventory selector missed hotbar slots and was corrected to inspect Inventory NBT.

The exact previously native-tested wall script completed all eight server placements and consumed all stone, but its immediate final inventory assertion failed in Mineflayer; reopening the saved reference world confirmed the blocks and consumption. The gather/craft script also failed its final assertion; saved player data contained axe damage2 and planks7, not the native-tested planks4/sticks8 outcome. These are recorded failed reference procedures, not port regressions or passes. No failed phase was replayed for a better result. Startup also logged an upstream ArmorTrimMaterial PartialReadError; no library schema was silently patched.

`npm run check -- --building` requests both prepared building procedures. `--craft` first independently inspects the preceding saved wall attempt, then runs only the unreached craft procedure. The reports retain exact source and limitations. These flags are fixture-specific, not automatic retries.

The selected registry codec comparison is recorded separately from native runs.
It checks pinned import projections and corrects invalid exports using the real
packet codec in QuickJS; Java capture/custom-datapack startup remains unverified.
A bounded object audit found XP-orb count/type and the remaining sky-mask JSON
assignment defects. Those are corrected; native XP observation remains pending.
World's public sync/async methods are inherited from the selected upstream source,
with guest-local save/raycast overrides; source review is not a runtime pass.

The current static-registry run at cbb5e22 compares every selected static value,
10,045 shared index references, 26,684 block-state links and 838 version names.
Fifty-four individually reviewed fixed-data/version members are recorded as
supported under that PC1.21.1 library contract. Native biome indexes, dynamic
codecs and feature installation are excluded from that promotion. These counts
are not a gameplay-completion percentage. Catalog guard probes rejected a
library-only dig claim, a modified report hash and an unavailable report.

Source review of the retained wall/craft failures, including the limits of the available timing evidence: [reference-failure-review.md](reference-failure-review.md). No scenario was rerun for this review.

`node tools/mineflayer-reference/scenarios.mjs --craft-trace` is an opt-in,
single-attempt diagnosis of the retained gather/craft mismatch. It runs only
that procedure on the isolated reference fixture, records at most 512 selected
click/inventory packets (64 KiB each), and reads server entity NBT on failure
before disconnecting. Timestamped `craft-trace-*.json` files preserve previous
reports. It does not patch upstream, replay clicks, or by itself establish a
paired conformance pass. One diagnostic run reproduced the mismatch with 49 complete transitions; see the source review for the demonstrated prediction/resynchronization ordering. The server saved and stopped normally.

Five selected `b21ce69` runs are now indexed with exact executed-source and raw
report hashes: guest World unload, HorseWindow saddle round trip, inactive
command editing, native boss bars, and disposable NBT-induced terminal death.
The last check intentionally ends with `body_dead`; its passing contract is the
retained hydrated callback order, not successful script return. Release and
archival limitations remain attached. These links do not promote whole APIs or
substitute for missing reference evidence.

The explicit Vec3 member list has a recorded 288-case host/bundled-QuickJS comparison (`vectors.mjs`), including local mutations, returned identity and special numeric results. Native coordinates, movement, class-wide coverage and the package helper are separate; no native members were promoted from it.

Ten explicitly listed ChatMessage rendering/transformation methods also use the
pure-library contract. The recorded existing suite passed292 comparisons plus
two builder-network fixtures in bundled QuickJS. Their synthetic component and
format inputs do not prove native chat reception, permissions, or registry
hydration. Other ChatMessage/MessageBuilder members remain separately reviewed.

The same recorded chat run covers38 explicitly reviewed MessageBuilder methods
and local fields: setter chaining/priority, formatting reset, extras/translation
parameters, hover variants, legacy string parsing, and JSON/string output.
This establishes generated component data, not that a native client executes a
click action, resolves a selector, or renders every format. `fromNetwork` remains
separate because it fills an upstream declaration/source gap.

Fourteen explicit Recipe/RecipeItem data members use the pure-library contract.
The existing suite's recorded run passed6,136 comparisons across all1,470 pinned
recipes/782 result IDs and six independent source corrections in QuickJS.
Actual `bot.craft`, hydrated inventory queries, server recipe books and custom
datapacks are not promoted from this static-data evidence.

Nine explicit Block constructors/calculations/local sign-NBT methods have the
recorded29,675-case pinned/bundled-QuickJS comparison, including every static
state and harvest/dig/sign inputs. `digTime` remains an estimate and
`setSignText` changes only the supplied guest object. Native block acquisition,
lighting, collision changes, mining duration and sign writes require their own
evidence; no live-observation field is promoted from this suite.

Sixteen explicit Item local methods/accessors have the recorded2,784-case
pinned/bundled-QuickJS comparison. Supplied component/NBT conversion and local
anvil predictions do not authorize inventory edits or prove native byte
transport, slot hydration, stacking, transfers, native anvil costs or outputs.
Pinned equality/component-setter quirks remain documented in items.mjs.

Seven local Entity contracts (constructor, setEquipment, getCustomName,
getDroppedItem, heldItem, mobType and objectType) have an explicit pure-library
classification. The recorded existing suite passed 1,396 comparisons, including
all 1,333 dropped Item IDs and shared Item/ChatMessage identity in QuickJS at 64 MiB.
Supplying metadata to this suite does not establish native metadata acquisition,
collection-event timing or real equipment changes. Native Entity fields and
event delivery remain separate obligations.

The same report's preauthored emitter case covers thirteen instance methods:
on, once, prependListener, listenerCount, eventNames, listeners, rawListeners,
emit, off, removeAllListeners, setMaxListeners, prependOnceListener and
getMaxListeners. This verifies local listener behavior, including symbols and
unhandled error emission. It does not verify any Minecraft event producer or
promote static emitter helpers or unexercised methods.

The Window suite records 216 differential scenarios and 20 explicit correction
cases in QuickJS. Reviewed synchronous local-model methods (queries, local slot
updates and click simulation) use that library evidence. Native close/deposit/
withdraw, menu generation, slot predicates and observed contents remain separate.
Calling a local Window method does not submit a server click or prove inventory
mutation. Modes 5/6 retain the pinned implementation's unsupported failures.

The existing all-state Block report also compares the constructor's own fields:
type, metadata, stateId, name, hardness, displayName, shapes, boundingBox,
transparent, diggable, material, harvestTools, drops and isWaterlogged. These
pinned-state mappings are recorded separately from native state acquisition,
position, light and NBT. The suite's mutation case also preserves the PC
getHash stub's undefined result; this is not a Bedrock hash implementation.

The static-data reconciliation also links 59 present Block/Item/Entity/Particle/Window,
collision-shape/tint and Version members to that same complete-table report. The
three registry implementation hashes still match its recorded revision. Version
operators were compared across all 838 recognized PC names and invalid names;
other fields are compared as complete serialized tables. These are minecraft-data
reference descriptions, not live prismarine object state or native physics,
inventory, menu, particle or rendering behavior. Edition-specific optional fields
absent from the selected tables were not promoted by this review. Data-shape
coverage is tracked separately from canonical gameplay-record counts.
