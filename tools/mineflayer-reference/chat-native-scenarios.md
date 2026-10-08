# Chat API scenarios (before implementation)

Port pattern registration/removal and awaitMessage from pinned Mineflayer chat.js.
Verify single patterns, multi-message sets, parsed captures, repeat/one-shot,
legacy chatAddPattern/default chat+whisper, and exact-string/regex message waits.
Multiple matching sets must all advance; a partial set must not suppress others.
Returned registration IDs must remove the registered set. Reentrant callbacks may
remove patterns or emit another message without corrupting a completed set.
Global/sticky regex state must not cause alternating missed messages.

Native transport remains separate: public server chat and messages actually
addressed to the body's interaction proxy, never copying the human client's
private whisper history into every body. Commands must preserve the existing
creative_commands/human-permission boundary; plain chat must not impersonate a
signed network player. Events carry ChatMessage objects after state hydration,
with honest sender/position/signature information. No message replay across
scripts/world sessions. Use bounded ordered native queues and surface overflow.

The initial helper and transport source is not yet live-verified. Public server
chat, body speech, targeted whispers, native proxy feedback and actionBar are
wired; broader server message sources remain pending. Validate
chat/whisper are synchronous void with successful final control drain, typed
message callbacks, no cross-script replay, and command permission rejection.
Native body speech is unsigned; no signed player identity is fabricated.

Explicit pinned-source corrections: pattern-set IDs identify their registration;
partial sets do not suppress other matches; completed sets retire before user
callbacks; all matches consume a message before reentrant delivery; global/sticky
regex lastIndex does not alternate message matches. Empty sets reject upfront.

## Command suggestions (before implementation)

Use the current native command dispatcher, body position/entity and permitted
command level. Never execute the input. Return the modern pinned matches shape:
{match, tooltip: typed anonymous NBT or null}. A slash is optional; current
1.21.1 ignores legacy assumeCommand/lookedAtBlock packet fields. Preserve those
public parameters without inventing cursor-dependent behavior. Bound input,
results, output and timeout; poll incomplete futures without blocking the server.
Release/cancel drops the pending result. Native future completion must not read
world/registry data off the owning thread.

Focused check: `/te` under ordinary body mode must not expose privileged teleport
commands; `/tell D` includes the actual connected Dev player without sending a
message. Unknown prefix resolves an empty array. Verify ticks advance and no
world/chat mutation occurs. Privileged suggestions and tooltip content remain
pending unless exercised. Concurrent body actions use the existing busy fence.

Implementation build passed, including native completion polling and typed tooltip encoding. Native verification remains unrun: another connected game prevented installing the chat scripting bundle. No replacement was attempted.

Native completion clarification (35e8a4f): development GameTest registers the
`test` literal without a `requires` predicate. It legitimately remains visible
at permission 0. Require privileged `teleport`, `team` and `tellraw` to be absent;
do not blacklist a permitted name. The first rebuilt check satisfied this but
stopped on the lead's incorrect `test` exclusion. No completion rerun is needed.

## Documented pattern inspection (preauthored follow-up)

Pinned docs/api.md:964 and index.d.ts:507 describe bot.chatPatterns as records
with pattern, type and optional description. Pinned chat.js never publishes it
and drops chatAddPattern's documented third argument. Restore a snapshot view of
active registrations: one record per regex, in registration/set order; preserve
legacy descriptions. Registration/removal and one-shot retirement own the data;
editing a returned array does not register patterns. Multi-pattern sets remain
sequential matches, not independent patterns despite the inspection projection.

Focused contract to check: default patterns are visible; add a described legacy
pattern and a two-pattern set, inspect their actual RegExp/type/description,
match messages in order, remove by returned ID/name, and confirm retired records
are absent. Delivery/parse behavior must remain unchanged. This is a documented
surface correction, not a claim that the pinned runtime exposes this field.
No new native fixture is needed for the inspection-only field.


Packaged inspection — a6d9474 (2026-10-08): exact
`observe-pattern-inspection.js` ran once with Codex gpt-6.1-sol low. Three default
patterns, legacy description/regex identity, parsed two-pattern matching,
one-shot retirement and ID removal passed. Inventory stayed unchanged;
initial snapshot supplied 25 columns and restored right main hand. This used
script-local `messagestr` emissions, not native chat transport. Zero physical
requests/updates, three bridge operations; script 456 ms. Source SHA256
`fc2ec570c41015473d5be0aeec438033007e117447e414617340d1442276a769`.
Raw result: `/Users/scott/.bb/thread-storage/thr_xykqkgui57/pattern-inspection-evidence-a39643f9.json`.
Reopened native inventory/equipment/selection matched the previous saved state.
All dimensions saved, then exact packaged JVM 95862 stopped.
