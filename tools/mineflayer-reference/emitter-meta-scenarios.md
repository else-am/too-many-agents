# EventEmitter metadata — preauthored

Use the actual production bot bundle's EventEmitter constructor in QuickJS with
synthetic initial state, compared with Node's EventEmitter used by Mineflayer.
No native request may run. Cover new instances with independent listener state,
defaultMaxListeners propagation/local override/restoration, EventEmitter static
self alias and static listenerCount. Cover newListener before insertion, once
listener original identity, reentrant registration order, removeListener after
removal and original once identity, and removeAllListeners notifications. Record
exact event/count/order traces and compare the same procedure in both runtimes.
No warning emission, Node-only captureRejections/options or native world event
claim. This supplements the existing ordinary registration/dispatch comparison;
do not rerun its1396 Entity cases or infer all event producers from local emit.

Observed comparison: constructor defaults/overrides, static aliases/counts, independent listeners, reentrant insertion and callback order matched. The initial original-callback oracle failed in Node before guest execution; the classifier now distinguishes the real once wrapper. Node supplies that wrapper during multi-listener once self-removal, while bundled events supplies the original callback. Both exact traces and source/runtime hashes are retained. This is an explicit identity difference, not full metadata-event parity. No production change or coverage promotion.
