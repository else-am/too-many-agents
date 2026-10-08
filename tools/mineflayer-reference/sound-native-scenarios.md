# Native sound observations (before implementation)

Reference: Mineflayer 4.39.0 `lib/plugins/sound.js`, modern `sound_effect` path.
Emit `soundEffectHeard(name, Vec3, volume, pitch)` for native sounds with known
names. Registered vanilla sounds use registry names without `minecraft:`;
inline/custom sound names retain their resource location. Do not invent numeric
fallback events when the native name is known. Include server positional and
entity-attached sounds; the latter fixes the pinned plugin's missing listener.

Read the final sound event after all native listeners have run: canceled/null
sounds are silent and modified name/volume/pitch/range are honored. Filter using
the body's position when the sound occurred and the final native hearing range,
with dimension isolation. No client-only sound history or replay across scripts.
Bound queued records and fail the observation stream explicitly on overflow.

Focused live checks, once the shared plugin can be replaced:

- Play one registered note near the body; check name, typed Vec3, volume/pitch,
  and exactly one event. Move the human client elsewhere: the body is the listener.
- Play a sound outside its native range, then a louder sound whose native range
  reaches the body. Only the second is heard. Other dimensions stay silent.
- Trigger an entity-attached sound. Preserve its actual source position rather
  than the body's position. Check subsequent ordinary frames do not replay it.
- Native cancellation/modification probe: a later listener cancels or replaces
  the event; observe only the finalized result. Release and start a new script
  between sounds; no old event crosses the lease boundary.

Packet-only `/playsound` sends, global level-event sounds and client-local sounds
need separate native sources; this slice does not claim coverage of those paths.

Java/plugin/package build passed. Native cases remain unrun. The per-script
queue is bounded to 256 same-dimension sound candidates between snapshots;
range filtering uses finalized native event fields at delivery.

## Proxy-addressed sound packets (contract before implementation)

Extend the existing body-only packet hook to positional and entity sound packets.
These packets are already addressed to this proxy: retain their native position,
name, volume and pitch without a second distance filter. Entity sounds resolve
only against this proxy's current server level; a missing entity produces no
fabricated position. Registered vanilla names match the world-sound path;
inline/custom names retain their resource location.

Keep both sources in one bounded ordered queue. Deferred world events still
honor later native cancellation/modification; packet values are already final.
Ignore other recipients, drain once, clear on script release/claim, and explicitly
fail malformed/nonfinite observations or overflow. No client sound playback or
human history is read. Global level-event/client-only sources remain pending.

One later grouped native fixture should send positional and entity packets to
this exact proxy (both overloads), and an unrelated recipient negative control.
Verify typed positions/names and one delivery each, no replay; do not rerun earlier
successful workflows to validate this addition. Native packet checks are unrun.
