# Mineflayer core checkpoint

Pathfinder has been removed on `feat/mineflayer-core`, at the user's request. The original branch `feat/mineflayer-api` preserves the full implementation, detailed historical reports and navigation fixtures.

Core scripting, state streaming, gameplay actions, inventory/windows, observation objects and shared ownership/cancellation safeguards remain. Existing native navigation and physical walking tools remain. No replacement script navigation API has been added.

Historical focused native checks passed for ordinary gameplay, specialized menus, fishing, selected events, mounted controls and storage persistence. They do not establish the current combined package or full API compatibility. Evidence dependent on deleted navigation fixtures is removed from the active catalog rather than reclassified as a core pass.

Removal checks passed: full Java/plugin/JAR build, TypeScript checking, the existing worker-boundary checks, production-bundle plugin-helper comparison, and core catalog validation. Packaged inspection confirms the route executor and route-only hooks are absent; the vehicle guard remains.

Remaining work: verify core execution on the current packaged client, reconcile core contract gaps, and measure worst-case frame cost. The previous native client launch was blocked by missing primary display; no live success is inferred from compilation.
