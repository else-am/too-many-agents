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
wired; tabComplete and broader server message sources remain pending. Validate
chat/whisper are synchronous void with successful final control drain, typed
message callbacks, no cross-script replay, and command permission rejection.
Native body speech is unsigned; no signed player identity is fabricated.

Explicit pinned-source corrections: pattern-set IDs identify their registration;
partial sets do not suppress other matches; completed sets retire before user
callbacks; all matches consume a message before reentrant delivery; global/sticky
regex lastIndex does not alternate message matches. Empty sets reject upfront.
