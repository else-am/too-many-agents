# ScriptStream byte budgets (preauthored)

Approved contract, before implementation:
- Each immutable UTF-8 state NDJSON line includes its LF and is at most 8 MiB. Accepted queued plus currently writing state payloads total at most 16 MiB. Queue count remains 64; the single in-flight frame is additional to that count, but included in bytes.
- Encode on producer through a bounded accumulator without constructing a complete JSON string. Encoding, HTTP writes and completion callbacks stay outside the short accounting/closure monitor. Producer never waits for consumer capacity or network progress.
- First closure wins. Accepted state lines drain in order before the bounded terminal line; encoding/overflow failures enqueue no prefix. Terminal diagnostics may be truncated to 512 UTF-16 code units plus an ellipsis; terminal is capped at 4 KiB, separate from state budget. Normal diagnostic text/terminal shapes remain unchanged.
- Dequeue retains byte reservation until write and flush finish. Disconnect/interruption releases all stream-owned queued/in-flight bytes and never retries. A terminal death frame follows the same acceptance rules, with no invented delivery on failure.
- Snapshot creation precedes encoding. Bounded transient accumulator/copy/encoder storage is additional to accepted payload bytes. Moving serialization to the producer increases server work; this is no frame-time performance guarantee or cap on native snapshot/getUpdateTag work.

One actual-class transport probe, preauthored boundaries:
1. Gson independently calculates padding for exactly 8 MiB including LF: accepted. One ASCII byte over rejected; escaped HTML/control and multibyte UTF-8 line sizes counted correctly. No partial oversized frame emitted.
2. Actual OutputStream blocks its first state write on a latch. While blocked, producer can accept a second 8 MiB line, then rejects any next state because the first still reserves bytes. Release output; two accepted lines precede error. Separately accept 64 tiny queued lines and reject the 65th.
3. Two revisions followed by failure (including a final dead-state observation followed by body_dead) deliver state/state/error in order. An error never changes earlier accepted snapshots. Finish closes with end. Later fail/offer cannot overwrite first closure.
4. Race close against in-progress encoding using an actual Gson-serialized blocking Number: after close wins, release encoding; no frame can be enqueued after terminal. No fake world or native success asserted.
5. Mutating source JSON after offer cannot change delivered bytes. Disconnect and interrupted blocked output release all queue/in-flight reservations once; completion/error and interrupt status stay correct. Second write invocation rejects without disturbing the first reader.
6. Empty-open stream produces a one-byte keepalive; closure wakes reader. Oversized diagnostic is bounded/truncated while ordinary body_dead remains unchanged.

Native game, host pipeline and death fixture integration remain UNRUN here. Root owns host UTF-8 framing checks and full build.

Actual narrow verification:
- Java21 standalone compilation of ScriptStream, existing JsonState and the preauthored transport probe against pinned Gson2.11 passed.
- Probe passed31 assertions using real Gson UTF-8 output, actual blocked OutputStream/latches, actual encoding-close race and disconnect/interruption. No host or native game behavior inferred.
- Literal actual output retained at `/Users/scott/.bb/thread-storage/thr_6qrtxjgv3m/script-stream-oversize-evidence.ndjson`:144bytes containing revision1, revision2, then `script_state_frame_too_large`. Probe source retained alongside as `ScriptStreamProbe.java` for root's cross-layer parser check.
- Source bounds accumulator capacity and chunks String writes to4096 code units, avoiding StreamEncoder's full-input temporary char-array allocation. Accepted payload accounting still excludes bounded transient encoding/copy storage, terminal bytes, object overhead and external network buffers.
- `git diff --check` passed. Full build, packaged/native death delivery and worst-case server frame-time remain UNRUN.
