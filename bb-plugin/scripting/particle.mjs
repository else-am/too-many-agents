import { Vec3 } from 'vec3';

export function createParticleClass(registry) {
  return class Particle {
    constructor(id, position, offset, count = 1, movementSpeed = 0, longDistanceRender = false) {
      this.id = id;
      Object.assign(this, registry.particles[id] || registry.particlesByName[id]);
      this.position = position;
      this.offset = offset;
      this.count = count;
      this.movementSpeed = movementSpeed;
      this.longDistanceRender = longDistanceRender;
    }

    static fromNetwork(packet) {
      return new Particle(packet.particle.type,
        new Vec3(packet.x, packet.y, packet.z),
        new Vec3(packet.offsetX, packet.offsetY, packet.offsetZ),
        packet.amount, packet.velocityOffset, packet.longDistance);
    }
  };
}
