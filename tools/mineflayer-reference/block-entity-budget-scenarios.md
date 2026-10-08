# Block-entity projection budgets (preauthored)

Scope: native NBT-to-typed-JSON conversion only. No game run authorized here.

Contract proposed before implementation:
- A local 33x17x33 block view shares 1 MiB of typed NBT JSON and 65,536 visited NBT tags/primitive-array elements.
- One complete 5x5 column refresh shares 4 MiB and 262,144 visited tags/elements. Existing per-column 1 MiB block-entity-record, 2 MiB section-wire, 6 MiB column-batch-row and record-count checks remain.
- Maximum NBT depth 64, root depth 1; primitive-array elements count as children. Typed JSON wrappers and signed-long pairs have fixed bounded overhead.
- A preflight traversal charges the shared budget before constructing any JSON for that tag. Byte accounting bounds compact typed JSON, including wrappers, keys, punctuation, numeric text, explicit null values and UTF-8 strings with Gson HTML-safe escaping. Gson configurations omitting null properties or disabling HTML escaping may emit fewer bytes. Unpaired UTF-16 surrogates may conservatively cost 3 bytes. Container record coordinates/keys are outside this projection budget and remain covered by existing column row limits; this is not an aggregate whole-frame cap.
- Overflow fails explicitly, never truncates or returns partial tags. Other ScriptNbt callers keep their existing unbudgeted overload. Native getUpdateTag work precedes projection and is not bounded here.

Focused native-Tag probe, authored before code:
1. Ordinary nested compound/list containing every primitive type, byte/int/long arrays, empty containers, negative numbers, Long.MIN_VALUE/MAX_VALUE, escaped/control/HTML-sensitive/non-ASCII strings and surrogate pairs: budgeted output equals the existing converter; independently inspect long pairs and serialized Gson bytes.
2. Depth 64 accepted, 65 rejected before projection; a large primitive array exceeds the node allowance. Native tag remains unchanged.
3. A small known tag fits its exact independently serialized byte count, fails one byte below; HTML-safe strings must not undercharge. Oversized strings fail explicitly.
4. Two individually fitting tags exhaust a shared batch budget on the second; separate budgets accept each. Local and column allowances are independent. No cache reset may replenish a budget inside one view/refresh.
5. With a live fixture later: ordinary sign/container update tags retain typed shape; oversized block-entity data aborts observation instead of partial publication. Verify no dirty-flag consumption, world mutation or newly loaded chunks. UNRUN here.

Source/standalone evidence:
- Java21 compilation of ScriptNbt, ScriptSnapshot and ScriptColumns passed against existing generated artifacts (deprecated-API warning only).
- Actual native-Tag probe passed 17 assertions for the cases above; the ordinary nested fixture serializes to 837 UTF-8 bytes. Probe retained in thread storage as `thr_6qrtxjgv3m/BlockEntityBudgetProbe.java`.
- The first probe's EndTag boundary used Gson's default null-property omission, which yields fewer bytes than the specified complete typed shape. The probe oracle now enables serializeNulls; product accounting remains conservative and typed output unchanged.
- Source confirms one budget created outside the local cell loop and one outside both column loops, retained through every block-entity conversion. Projection runs only after its whole tag fits. Failure aborts the view; no partial result is published, and no failed budget is reused.
- No full build, live native fixture, frame-time measurement or provider/lifecycle operation ran. Native getUpdateTag allocation/work and overall frame/queue cost are not bounded by this change.
