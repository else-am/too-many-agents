# Snapshot state handoff / preauthored checks

Source: pinned Mineflayer4.39.0 time.js, rain.js, health.js and entities.js air
metadata branch; minecraft-data3.117.0 lib/supportsFeature.js and PC features.json;
generated Minecraft1.21.1 Level/ServerLevel/ClientboundSetTimePacket/Entity APIs.
These checks are authored before implementation; lead owns the final live check.

- Preserve bot.time identity and its clocks object through repeated updates;
  populate exact bigTime/bigAge from decimal Java longs. Check dayTime0,12999,
  13000,23999,24000 and day8 rollover. Game age advances while daylight is frozen.
  Frozen dayTime0 remains native0, avoiding the packet's negative-zero sentinel1.
  BigInt remains exact beyond Number.MAX_SAFE_INTEGER; numeric aliases retain the
  pinned Number conversions/arithmetic. No client physics clock extrapolation.
- Initial state is installed without events; identical snapshots emit none.
  Root emits returned tuples only after all hydration. On changed time/weather/
  health/air, every callback sees complete new fields. Sampled events describe
  observed changes, not every intervening packet/tick or inferred lifecycle.
- Compare native rain strength and Level.isRaining at transition thresholds and
  clearing. Do not turn positive residual rain into an invented boolean. Raw
  thunderLevel is protected; the public getter multiplies it by rain. If raw
  access is unavailable, thunderState is null/pending, never a guessed quotient.
- Compare body health/air directly, including drowning's negative air, recovery
  and mobs with nonplayer maxAirSupply. oxygenLevel uses pinned round(air/15),
  without inventing a player300-air cap. Do not create food or foodSaturation from
  mob health or hands; player hunger remains unavailable.
- Build feature table by evaluating registry.supportFeature(name) for every name
  in installed PC common/features.json against1.21.1. Preserve truthy numeric/
  string results, false unknown features, and avoid Object prototype properties.
  This reports Minecraft-version features, not an implementation coverage claim.

Integration proposed: installState(bot, featureTable) returns updateState(next),
which accepts the complete snapshot and returns event argument arrays. root owns
installation/build table/emission. Native adds worldState with dayTime/gameTime
as decimal strings, doDaylightCycle, isRaining, rainState, thunderState, and adds
body airSupply/maxAirSupply. Existing health/alive fields remain authoritative.

Implemented API: `installState(bot, featureTable)` returns `updateState(snapshot)`.
Initial unavailable weather is null; health/oxygen/isAlive undefined until first
hydration. The complete native snapshot sets truthful `isAlive` as well as health;
this module does not emit guessed spawn/respawn/death lifecycle events or add
control methods. Food/foodSaturation remain absent. `bot.time` and its clocks map
retain identity throughout this installation.

Returned event order for actual sampled changes is time, weatherUpdate(rain
strength), rain(boolean), weatherUpdate(thunder when its supplied value changes),
health, breath. Repeated snapshots return an empty array; initial hydration is
silent. Breath follows raw air changes even when rounding keeps oxygenLevel equal.
Root must emit these arrays only after complete bot hydration. Do not install
another conflicting packet-style state emitter. Event cadence follows snapshots,
not a fabricated20-tick protocol clock or all missed intermediate changes.

For root build integration, evaluate this against the pinned installed data:

```js
const featureTable = Object.fromEntries(
  require('minecraft-data/minecraft-data/data/pc/common/features.json')
    .map(({ name }) => [name, registry.supportFeature(name)]))
```

Pass that one-version table to the installer. Unknown/prototype names return false;
truthy feature values are preserved, not coerced into booleans. This is a version
query, never an assertion that the matching gameplay API is implemented.

Validation performed: node --check passed; Java21 javac compiled owned GameAccess
and Observations against existing generated NeoForge21.1.251 artifacts/project
classes. No expanded test suite, native/live check, game/BB/server lifecycle or
installation ran. Remaining specific gap: raw native thunderLevel requires access
beyond the current public Level API/owned files; thunderState explicitly stays
null. No rain division or weighted-thunder substitution is used.
