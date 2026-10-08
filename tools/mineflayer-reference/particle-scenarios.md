# Particle object — contract before implementation

Use Mineflayer 4.39.0 lib/particle.js and PC 1.21.1 particle registry.
Compare constructors by numeric ID, registry name and unknown ID, including
explicit count/speed/distance and retained position/offset identity. Compare
fromNetwork against the real pinned loader using a current-version packet;
coordinates must be the shared Vec3 class. Preserve upstream's omission of
particle-specific packet data rather than inventing a different public shape.

This is a public payload-class port. Native particle event capture, recipients,
visibility and bounds remain pending; no event or live-render claim follows
from constructor comparisons. Use a small direct upstream comparison only;
no gameplay suite rerun is necessary.
