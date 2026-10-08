// Observed WorldSync columns and Mineflayer's complete 5x5 loading wait.
// Upstream contracts: prismarine-world 3.7.0 / Mineflayer 4.39.0 (mineflayer.LICENSE).
import { Vec3 } from 'vec3';

export function installColumns(bot, ChunkColumn) {
  const columns = new Map(), sources = new Map();
  let minY = -64, worldHeight = 384, dimension;
  const key = (x, z) => `${x},${z}`;
  const local = p => new Vec3(Math.floor(p.x) & 15, Math.floor(p.y), Math.floor(p.z) & 15);
  const world = bot.world;
  world.getColumn = (x, z) => columns.get(key(x, z));
  world.getColumnAt = p => world.getColumn(Math.floor(p.x / 16), Math.floor(p.z / 16));
  world.getColumns = () => [...columns].map(([id, column]) => {
    const [chunkX, chunkZ] = id.split(',');
    return { chunkX, chunkZ, column };
  });
  for (const name of ['getBlockStateId', 'getBlockType', 'getBlockData', 'getBlockLight', 'getSkyLight', 'getBiome']) {
    const fallback = world[name].bind(world);
    world[name] = position => {
      if (![position.x, position.y, position.z].every(Number.isFinite)) throw new RangeError('Block position must be finite');
      const column = world.getColumnAt(position);
      if (!column) return fallback(position);
      if (position.y < minY || position.y >= minY + worldHeight) return null;
      return column[name](local(position));
    };
  }
  bot.waitForChunksToLoad = async () => {
    const center = bot.entity.position.floored().divide(new Vec3(16, 1, 16)).floored();
    const wanted = new Set();
    for (let z = center.z - 2; z <= center.z + 2; z++) for (let x = center.x - 2; x <= center.x + 2; x++) wanted.add(key(x, z));
    if ([...wanted].every(id => columns.has(id))) return;
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        world.off('chunkColumnLoad', loaded);
        reject(new Error('Timeout waiting for complete nearby columns after 10000ms'));
      }, 10000);
      function loaded() {
        // Recheck the whole target; an earlier column may have unloaded.
        if (![...wanted].every(id => columns.has(id))) return;
        clearTimeout(timer); world.off('chunkColumnLoad', loaded); resolve();
      }
      world.on('chunkColumnLoad', loaded);
    });
  };
  function getBlock(position, extraInfos = true) {
    if (position.y < minY || position.y >= minY + worldHeight) return null;
    const column = world.getColumnAt(position);
    if (!column) return null;
    const block = column.getBlock(local(position));
    block.position = position.floored();
    if (!extraInfos) block.entity = undefined;
    return block;
  }
  function update(view, emit, trackBlocks, localBlocks) {
    const events = [], changes = [];
    if (!view) return { events, changes };
    if (dimension !== undefined && dimension !== view.dimension) {
      for (const id of columns.keys()) {
        const [x, z] = id.split(',').map(Number);
        if (emit) events.push(['chunkColumnUnload', new Vec3(x * 16, 0, z * 16)]);
      }
      columns.clear(); sources.clear();
    }
    dimension = view.dimension;
    minY = view.minY; worldHeight = view.worldHeight;
    const incoming = view.columns ?? view.changes;
    const removed = view.columns ? [...columns.keys()].filter(id => !Object.hasOwn(view.columns, id)) : view.removed;
    for (const id of removed) {
      if (!columns.delete(id)) continue;
      sources.delete(id);
      const [x, z] = id.split(',').map(Number);
      if (emit) events.push(['chunkColumnUnload', new Vec3(x * 16, 0, z * 16)]);
    }
    for (const [id, row] of Object.entries(incoming)) {
      const old = columns.get(id), source = sources.get(id);
      // Initial stream includes a full frame; avoid decoding identical columns twice.
      const encoded = JSON.stringify(row);
      if (source?.encoded === encoded) continue;
      const column = ChunkColumn.fromSnapshot(row);
      if (old && trackBlocks && source.data !== row.data) {
        for (let i = 0; i < column.sections.length; i++) {
          if (old.sections[i].toJson() === column.sections[i].toJson()) continue;
          for (let y = 0; y < 16; y++) for (let z = 0; z < 16; z++) for (let x = 0; x < 16; x++) {
            const position = new Vec3(row.x * 16 + x, minY + i * 16 + y, row.z * 16 + z);
            if (position.x >= localBlocks.min[0] && position.x < localBlocks.min[0] + localBlocks.size[0]
                && position.y >= localBlocks.min[1] && position.y < localBlocks.min[1] + localBlocks.size[1]
                && position.z >= localBlocks.min[2] && position.z < localBlocks.min[2] + localBlocks.size[2]) continue;
            const p = new Vec3(x, position.y, z);
            if (old.getBlockStateId(p) === column.getBlockStateId(p)) continue;
            const before = old.getBlock(p); before.position = position;
            changes.push([before, position]);
          }
        }
      }
      if (old) Object.assign(old, column);
      else { columns.set(id, column); if (emit) events.push(['chunkColumnLoad', new Vec3(row.x * 16, 0, row.z * 16)]); }
      sources.set(id, { encoded, data: row.data });
    }
    return { events, changes };
  }
  function patchLocal(blocks) {
    const { min, size, states, biomes, light, entities } = blocks;
    for (let i = 0; i < states.length; i++) {
      const p = new Vec3(min[0] + i % size[0], min[1] + Math.floor(i / (size[0] * size[2])), min[2] + Math.floor(i / size[0]) % size[2]);
      if (states[i] < 0 || p.y < minY || p.y >= minY + worldHeight) continue;
      const column = world.getColumnAt(p);
      if (!column) continue;
      const at = local(p);
      if (column.getBlockStateId(at) !== states[i]) column.setBlockStateId(at, states[i]);
      if (column.getBiome(at) !== biomes[i]) column.setBiome(at, biomes[i]);
      if (column.getBlockLight(at) !== (light[i] & 15)) column.setBlockLight(at, light[i] & 15);
      if (column.getSkyLight(at) !== (light[i] >> 4)) column.setSkyLight(at, light[i] >> 4);
      if (entities[i]) column.setBlockEntity(at, entities[i]);
      else column.removeBlockEntity(at);
    }
  }
  function bounds() {
    if (!columns.size) return null;
    const coordinates = [...columns.keys()].map(id => id.split(',').map(Number));
    return { min: new Vec3(Math.min(...coordinates.map(p => p[0])) * 16, minY, Math.min(...coordinates.map(p => p[1])) * 16),
      max: new Vec3((Math.max(...coordinates.map(p => p[0])) + 1) * 16, minY + worldHeight, (Math.max(...coordinates.map(p => p[1])) + 1) * 16) };
  }
  return { getBlock, update, patchLocal, bounds };
}
