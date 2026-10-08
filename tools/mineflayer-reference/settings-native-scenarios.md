# Native dominant-hand settings (preauthored)

Contract: `bot.settings` has stable identity and exposes only observed native
`mainHand` (`left`/`right`). `setSettings({mainHand})` returns void synchronously;
its queued native control completes only after ordered observation. It does not
optimistically change settings. Empty options do nothing. Other client settings
reject before any mutation. No player, skin, locale, view-distance or world
settings are synthesized.

Root-owned native checks:

- Keep `const settings = bot.settings`; set left, await one tick, observe the
  same object reporting left and actual Mob `LeftHanded` true. Repeat right,
  including script return immediately after setSettings to exercise finish drain.
- With distinct main/offhand items, change dominant hand and use/swing the main
  hand. Native body and AgentHands `getMainArm` agree; inventory HAND/OFF_HAND
  identities do not swap. Setting left must not change nearby player's arm.
- Submit `{mainHand:'left', viewDistance:8}`, an invalid mainHand, and invalid
  non-object options. Catch validation failures; there is no queued mutation
  and original body/settings remain unchanged. Empty `{}` returns undefined.
- Set left, release the script, reopen observation and save/reload through the
  root-owned fixture: native Mob NBT `LeftHanded` and new guest settings still
  report left. Change handedness externally, observe the next state update.

Source contract: pinned Mineflayer `lib/plugins/settings.js` validates mainHand
and sends its bit; native `Mob.setLeftHanded/isLeftHanded/getMainArm` and Mob
NBT save/load own the actual value. This is an explicit supported subset of
setSettings, not full client-settings compatibility. Source/syntax/compilation
checks do not establish these native outcomes.

Focused packaged c244383 check passed once with Codex gpt-6.1-sol low. The ready
script retained settings identity, rejected its invalid options, left the observed
opposite hand selected and preserved inventory through a drained main-hand swing
(2 requests, 9 updates, 7 bridge operations; 1077 ms tool). Independent body NBT
changed LeftHanded 0 -> 1; native Dev mainHand remained right. A separate script
queued the original right hand and returned immediately without await; runner
finish drain completed it (1 request, 2 updates, 5 bridge operations; 644 ms).
Independent body NBT returned to LeftHanded 0 and native inventory/equipment/
selection matched the original baseline. No full settings/reopen matrix ran.
