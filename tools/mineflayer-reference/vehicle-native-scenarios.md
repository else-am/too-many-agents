# Vehicle control scenarios (before implementation)

Use an existing boat in a guarded open-water fixture. Mount the actual body in
its controlling seat. moveVehicle(0,1) must return undefined and produce forward
native motion; turn input must change heading, and moveVehicle(0,0) must remove
paddle/input acceleration while allowing ordinary coasting. Compare meaningful
movement, not player-client coordinates. Dismount and script cancellation must
release this body's inputs. Another controlling passenger must not be overridden.

Check clear/manual controls while mounted and reject unsupported mounts truthfully.
Keep body-box, loaded-region and world-border constraints; do not teleport the
vehicle. Verify native vehicle/body positions and rider IDs independently.

First native adapter is for Boat and subclasses, including chest boats. Other
mounts (horses, pigs, striders, minecarts) remain implementation work, not exclusions.
