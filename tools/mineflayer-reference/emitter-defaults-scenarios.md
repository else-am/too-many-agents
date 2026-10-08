# EventEmitter default limit — preauthored

Compare Node's Mineflayer EventEmitter with the actual production bot constructor
in bounded QuickJS, using synthetic initialization and no native request.

Exercise default limits 0, 1, 2.5 and Infinity; existing instances without a local
override must observe each change, while a local override stays independent.
Attempt negative, NaN, string, null, undefined and object assignments; record
exception class and unchanged previous default. Restore the original default
in finally. Verify the exported EventEmitter self-alias and readable/writable
accessor behavior. Do not compare incidental exception text or warning stacks.

This establishes only local default-limit semantics. It does not assert native
event production, warning delivery, Node-only options, or removeListener payload
identity (whose existing difference remains separately recorded).

Observed: valid assignments, independent overrides, unchanged defaults after
rejection and self-alias matched. Negative/NaN reject with RangeError in both;
nonnumeric values reject with RangeError in pinned events3 versus TypeError in
Node24. The initial Node-only oracle wrongly expected RangeError for all values
and failed before guest evaluation; its source is retained in thread storage.
The completed comparison retains both raw classifications. No production change
or exact error-class parity/member promotion follows from this result.
