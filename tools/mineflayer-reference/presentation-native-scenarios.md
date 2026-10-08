# Body-addressed titles and tab list (contract before implementation)

Reference: Mineflayer 4.39.0 title.js/tablist.js/chat.js and generated Minecraft
1.21.1 title, subtitle, timing, clear, action-bar and tab-list packets.

The body has an existing FakePlayer interaction proxy. Observe only packets
addressed to that proxy through a narrow mixin on its existing no-op connection.
Retain the original handler: no socket, network player or human-client UI access.
Unrelated packet types stay ignored. Native calls/serialization stay server-thread
owned. Persistent tab-list fields start as empty ChatMessages, as upstream does.

Focused checks, when grouped with an appropriate native fixture:
- Send a title, subtitle, times (including native -1 keep-current values), and
  clear to the exact body proxy. Receive title(text, 'title'|'subtitle'),
  title_times(fadeIn, stay, fadeOut), title_clear once each, in native order.
  Complex translated/styled components render through the shared ChatMessage
  class; correct the pinned title parser's malformed NBT-object text result.
- Send a tab-list header/footer to that proxy. bot.tablist keeps its identity and
  exposes typed ChatMessages before callbacks; unchanged observations preserve
  values. New script initialization sees the proxy's current header/footer.
- Proxy-addressed action-bar text feeds the existing typed message/messagestr/
  actionBar path once. Sending the same packets to a human or other proxy must
  not appear here. No default/global human tab-list or title state is copied.
- Queued title/action-bar events use existing bounded chat delivery and stop with
  the script lease. No event history is replayed on the next claim. Tab-list
  component storage is bounded separately; oversized inputs fail explicitly.
- These packets do not change world/inventory/controls or create a player. Native
  presentation methods return normally, retaining FakePlayer's no-op send-listener
  semantics. Fixture delivery and packaged mixin validation remain
  required; compilation alone is not native conformance.

Implementation note: NeoForge keeps its FakePlayer handler private, outside the
Minecraft development access-transformer inputs. A send-only mixin preserves the
existing handler rather than replacing/copying it. Both send overloads are empty
in this pinned NeoForge version, so intercepting each does not double-deliver.

Validation so far: Java 21 compilation, plugin typecheck/bundle and packaged JAR
build pass. No native presentation fixture or game launch has run on this change.
The existing performance comparison stays on its previously fixed artifact.
