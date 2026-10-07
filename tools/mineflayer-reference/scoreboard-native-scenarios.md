# Scoreboard/team scenarios (before implementation)

Expose stable bot.scoreboards, bot.scoreboard display slots, bot.teams and
bot.teamMap, with ScoreBoard and Team instance behavior from pinned Mineflayer.
Read objectives shown in native display slots and all public teams; do not expose
undisplayed server-only objectives just because the integrated server can read
them. Include color-specific sidebar slots, score values and native component
names/prefixes/suffixes. Cache native serialization until scoreboard dirtiness.

Focused live fixture: create/display an objective and team containing the body's
scoreboard name, set a score, prefix and color, then update/remove. Handlers must
see hydrated maps and stable board/team/unchanged score identities; displayName
reflects the current team. Compare actual scoreboard commands/NBT independently.
Removing one member/objective must not erase unrelated entries. Initial hydration
is silent; no synthetic events for an unchanged snapshot. Two changes entirely
between snapshots remain an explicit observation limitation, not packet parity.

Review source defects explicitly: teamRemoved currently emits undefined after
deleting its object; scoreboard deletion only clears one display slot; title
parsing does not understand modern typed NBT; declaration memberMap differs from
source membersMap. Correct these instead of preserving missing/wrong observations.
Use own-key dictionaries for arbitrary player/objective/team names, including
prototype-like names. Bound total records/output; do not silently truncate.

Implementation Java/plugin/package build passed. Native fixture remains unrun.
The adapter preserves object methods and corrects the source defects above;
score ties retain insertion order, and removed/cleared positions are hydrated
before events. A cached snapshot is bounded to 4096 score/team/member records
and 1 MiB serialized text. Caches are discarded on world shutdown. Team/scoreboard
observations do not yet reconstruct changes wholly between server snapshots.
