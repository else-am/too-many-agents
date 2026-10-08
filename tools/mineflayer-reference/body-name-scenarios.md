# Body username (contract before implementation)

`bot.username` exposes `next.body.name`, the actual native in-world name already
used by body chat and whisper targeting. It is available at initial hydration and
updates when that body is renamed, before public callbacks. It is not a Minecraft
account name, does not add an entry to `bot.players`, and does not change the body
UUID, entity kind or native name. An unnamed Mob uses Minecraft's native name.

Next focused packaged observation should compare the value with independent native
body name evidence, then verify a guarded rename updates it while retaining entity
identity. Self `bot.player` and account/player-only fields remain separate work.
