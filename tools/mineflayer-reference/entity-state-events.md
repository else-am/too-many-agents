# Entity state event scenarios (before implementation)

Native observations must hydrate completely before emitting events. Add a timed
Speed effect to the actual body, wait several ordinary ticks, refresh/change its
amplifier, then remove it. Expect entityEffect for observed additions/changes and
entityEffectEnd on removal; ordinary duration countdown must not emit new effects.
Event handlers must see the updated entity.effects and stable entity reference.
Use the entity's own tick count, not wall time, to recognize normal countdown.
Keep the effects map identity stable and retain unchanged effect identities.

Observe a newly visible affected entity: spawn precedes its observed effects.
Initial script hydration stays silent. Losing an entity from the observed region
emits entityGone, not invented effect-removal events. A full apply/remove between
snapshots is unobserved and is not reconstructed; packet-exact event cadence is
not promised by this state-observation adapter.

Change only yaw/pitch: entityMoved must still emit (as Mineflayer entity_look does).
Self movement emits move with the previous Vec3 position. Toggle native crouch: entityCrouch/entityUncrouch handlers see the new flag. Do not
infer entityHurt, death, hit animations or collections from these state deltas.
Those native event transports remain separate pending work.
