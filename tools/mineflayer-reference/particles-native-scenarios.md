# Native particle observation (contract before implementation)

Mineflayer 4.39.0 particle.js emits particle(Particle.fromNetwork(packet)). The
public class includes registry identity, Vec3 position/offset, count, speed and
long-distance flag; the pinned class does not expose type-specific payloads.

- A real server broadcast near the body emits a shared Particle/Vec3 instance
  with native name/count/position and packet-float offset/speed. Zero count is
  retained. No human player needs to stand near the body.
- Native 32-block broadcast range uses the listener block center, matching
  ServerLevel.sendParticles. Another dimension and an out-of-range body receive
  nothing. Targeted particles for another player remain private; a targeted
  proxy delivery uses that body only and native 32/512-block range.
- One broadcast is recorded once per scripted body, regardless of human player
  count. Initial snapshots and subsequent unchanged frames do not replay it.
- Events are bounded and lease-scoped; release clears the queue and overflow
  fails observation explicitly. Native particle rendering and return values
  remain untouched. Client-only/level-event particle synthesis is not inferred.
- Validate one ordinary broadcast plus no replay in a grouped packaged check;
  targeted/range/multi-body edge matrix remains pending. Compilation alone does
  not establish that the mixin runs or a native event is received.
