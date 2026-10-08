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

Native fixture delivery will use a guarded `dev presentation --json` action in
only the isolated development world. It requires the exact body's currently
completed look-action ID while its script lease is active. The guest installs
listeners before that look; the coordinator observes its new ID before delivery.
Send actual native packets through both no-op connection overloads, plus one
negative-control packet to an unregistered unrelated FakePlayer. Do not emit
anything to the human client or mutate blocks/inventory. Preserve timing and
known/unknown outcome rules; no automatic repeated delivery.

Ready guest script: `observe-presentation.js`. It installs listeners before its
look, waits at most 1200 ticks, checks native order, shared ChatMessage identities,
fully hydrated tab-list text, five-tick no-replay behavior and unchanged inventory.
It has only been syntax-checked, not executed.

Coordinator command (substitute the newly observed exact UUID/action ID):

```sh
python3 tools/agents.py --game-dir run dev presentation \
  --json '{"bodyUuid":"BODY_UUID","readyAction":"NEW_LOOK_UUID"}'
```

The native gate rejects absent bodies, inactive scripts and anything other than
the current completed look. The fixture sends six actual packets to the existing
proxy and one negative-control title to a temporary unregistered FakePlayer. It
neither moves the body nor opens a screen. Both send overloads are exercised.
