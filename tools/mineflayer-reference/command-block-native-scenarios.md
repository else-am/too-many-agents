# Command-block editing (before implementation)

Public contract: Mineflayer 4.39.0 `setCommandBlock(pos, command, options={})`
returns void. Defaults: mode=2 (redstone), trackOutput=false, conditional=false,
alwaysActive=false. Modes 0/1 are sequence/auto. Queue the control and drain its
authoritative native result before later awaited actions or script completion.

Follow native ServerGamePacketListenerImpl.handleSetCommandBlock: preserve facing
and the existing block entity when changing type, write command/output flags,
clear old output when disabled, apply automatic/mode scheduling, and notify the
world. Do not execute the command separately or emulate its eventual result.
Require body creative_commands mode, the actual human player's existing level-2
permission, native command-block enablement, loaded target and ordinary body reach.
Plain creative and survival must not gain command editing. Bind expected state.

Focused guarded-world fixture when installation is available:

- Edit one nearby impulse block with a harmless command and default options.
  Method returns undefined; independent native NBT confirms command/flags/facing
  after the control drain. No command runs merely because the editor returned.
- Change to sequence/auto with explicit conditional/trackOutput/alwaysActive;
  retain block-entity identity, native schedule behavior and preserved facing.
  Disable trackOutput and check LastOutput clears; empty command is accepted.
- Deny survival, plain creative, insufficient operator permission, disabled
  command blocks, stale target, non-command block, unloaded or unreachable target.
  Rejection must not alter any block or command contents.
- Reject invalid modes, oversized command and malformed positions/options before
  editing. Cancellation/session loss retains ordinary queued-action safeguards;
  unknown replies must not replay the edit.

Native checks remain unrun. No command-block minecart API is invented.
