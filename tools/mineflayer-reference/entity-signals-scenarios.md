# Entity animation/status signals (before implementation)

Pinned Mineflayer entities.js emits typed entity events from animation/status
packets. Capture actual server emissions, using the existing nearby, bounded,
lease-scoped entity-event queue and final hydration, without using human-client
packet history. Preserve native throttling: requesting another swing does not
necessarily produce another native animation.

- A real main-hand or offhand body swing emits entitySwingArm with the SAME
  observed Entity; handlers see hydrated equipment/position before action return.
  Native canceled/suppressed swings emit nothing. Other nearby native swings and
  critical/magic-critical effects identify the packet's entity, not its sender.
- Taming/tamed, shaking water, eating grass and hand-swap status emissions use
  their pinned event names. Out-of-range/different-world signals are not observed.
  Existing damage/death/wake sources retain ownership to prevent duplicates.
- Deliberate source correction: animation code 3 is SWING_OFF_HAND in native
  1.21.1; pinned Mineflayer incorrectly labels it entityEat. Report it as a swing.
  entityEat instead reports actual completed EAT/DRINK use from the native Finish
  event (which retains a copy of the original item). Interrupted use and shield
  activation do not fabricate consumption. Existing consume inventory barrier is
  unchanged, and handlers run after the observed inventory/effects update.
- No initial or unchanged-frame replay. Release clears signals; unknown outcomes
  do not justify replay. Native broad signal/consumption verification remains
  pending a grouped check; compilation is not runtime proof.
