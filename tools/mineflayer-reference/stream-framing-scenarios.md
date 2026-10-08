# Ordered state stream framing — preauthored

Exercise the actual minecraftWorlds callback parser with a controlled Response
body; no Minecraft or BB lifecycle and no game actions. Retain source/result
hashes. This proves host transport handling, not native snapshot construction.

1. Two individually valid large frames delivered in one chunk, collectively over
8 MiB, both arrive in order. Limits apply to each UTF-8 line, excluding LF.
2. One state line at exactly8 MiB is accepted; one byte over rejects before its
snapshot callback. Multibyte payload exceeding8 MiB while under8 Mi characters
also rejects; do not count JavaScript UTF-16 units as wire bytes.
3. UTF-8 characters split across reads decode exactly. Keepalive blank lines
remain silent. Malformed UTF-8 rejects instead of corrupting observed text.
4. A callback held pending prevents delivery of the next frame/terminal error;
after acknowledgement the queued state precedes the terminal failure. No retry.
5. EOF with partial line or without a terminal frame rejects; reader cancellation
still occurs. An explicit end closes normally. No post-terminal frame is emitted.

The parser cannot bound allocations already made by fetch for a response chunk.
It bounds retained frame content before decoding/appending and before host
snapshot transformation. Native frame/queue limits are a separate contract.

Executed host probe: ten cases passed with actual minecraftWorlds bundled by
esbuild and controlled Response bytes. The first harness incorrectly expected
the underlying source cancel callback even when prefetch had already closed it;
the oracle now accepts either cancellation or prior source closure. Parser
behavior was unchanged by this harness correction. Evidence retains exact
source/host/bundle hashes. No game, native producer or runner was exercised.
