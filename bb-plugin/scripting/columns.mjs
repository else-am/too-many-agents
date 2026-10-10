import { Vec3 } from 'vec3';

export function installColumns(bot, ChunkColumn) {
  const sources = new Map();
  let minY = -64, worldHeight = 384, dimension;
  const key = (x, z) => `${x},${z}`;
  const local = p => new Vec3(Math.floor(p.x) & 15, Math.floor(p.y), Math.floor(p.z) & 15);
  const columns = new Map();
  const loadWaiters = new Set();
  const columnAt = p => columns.get(key(Math.floor(p.x / 16), Math.floor(p.z / 16)));
  bot.waitForChunksToLoad = async () => {
    const center = bot.entity.position.floored().divide(new Vec3(16, 1, 16)).floored();
    const wanted = [];
    for (let z = center.z - 2; z <= center.z + 2; z++) for (let x = center.x - 2; x <= center.x + 2; x++) wanted.push(key(x, z));
    // Recheck the whole target; an earlier column may have unloaded.
    const complete = () => wanted.every(id => columns.has(id));
    if (complete()) return;
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        loadWaiters.delete(check);
        reject(new Error('Timeout waiting for complete nearby columns after 10000ms'));
      }, 10000);
      function check() {
        if (!complete()) return;
        clearTimeout(timer); loadWaiters.delete(check); resolve();
      }
      loadWaiters.add(check);
    });
  };
  function getBlock(position, extraInfos = true) {
    if (position.y < minY || position.y >= minY + worldHeight) return null;
    const column = columnAt(position);
    if (!column) return null;
    const block = column.getBlock(local(position));
    block.position = position.floored();
    if (!extraInfos) block.entity = undefined;
    return block;
  }
  // Native state ID at an integer cell, or -1 outside observed columns.
  function stateId(x, y, z) {
    if (y < minY || y >= minY + worldHeight) return -1;
    const column = columns.get(key(x >> 4, z >> 4));
    return column ? column.getBlockStateId({ x: x & 15, y, z: z & 15 }) : -1;
  }
  // State IDs a 16x16x16 section may contain, or null when unknown or unpaletted.
  function sectionStates(x, y, z) {
    const data = columns.get(key(x, z))?.sections[y - minY / 16]?.data;
    if (!data) return null;
    return 'value' in data ? [data.value] : data.palette ?? null;
  }
  function update(view, trackBlocks, localBlocks, trackBlockEntities = false) {
    const changes = [], blockEntities = [];
    if (!view) return { changes, blockEntities };
    if (dimension !== undefined && dimension !== view.dimension) { columns.clear(); sources.clear(); }
    dimension = view.dimension;
    minY = view.minY; worldHeight = view.worldHeight;
    const incoming = view.columns ?? view.changes;
    const removed = view.columns ? [...columns.keys()].filter(id => !Object.hasOwn(view.columns, id)) : view.removed;
    for (const id of removed) { columns.delete(id); sources.delete(id); }
    for (const [id, row] of Object.entries(incoming)) {
      const old = columns.get(id), source = sources.get(id);
      // Initial stream includes a full frame; avoid decoding identical columns twice.
      const encoded = JSON.stringify(row);
      if (old && source?.encoded === encoded) continue;
      const column = ChunkColumn.fromSnapshot(row);
      if (old && trackBlockEntities) for (const entry of row.blockEntities) {
        const position = new Vec3(entry.x, entry.y, entry.z);
        if (JSON.stringify(old.getBlockEntity(position) ?? null) !== JSON.stringify(entry.nbt))
          blockEntities.push(new Vec3(row.x * 16 + entry.x, entry.y, row.z * 16 + entry.z));
      }
      if (old && trackBlocks && source?.data !== row.data) {
        for (let i = 0; i < column.sections.length; i++) {
          if (old.minY === column.minY && old.sections?.[i]?.toJson() === column.sections[i].toJson()) continue;
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
      columns.set(id, column);
      sources.set(id, { encoded, data: row.data });
    }
    for (const check of [...loadWaiters]) check();
    return { changes, blockEntities };
  }
  function patchLocal(blocks) {
    const { min, size, states, biomes, light, entities } = blocks;
    for (let i = 0; i < states.length; i++) {
      const p = new Vec3(min[0] + i % size[0], min[1] + Math.floor(i / (size[0] * size[2])), min[2] + Math.floor(i / size[0]) % size[2]);
      if (states[i] < 0 || p.y < minY || p.y >= minY + worldHeight) continue;
      const column = columnAt(p);
      if (!column) continue;
      const at = local(p);
      if (column.getBlockStateId(at) !== states[i]) column.setBlockStateId(at, states[i]);
      if (column.getBiome(at) !== biomes[i]) column.setBiome(at, biomes[i]);
      if (column.getBlockLight(at) !== (light[i] & 15)) column.setBlockLight(at, light[i] & 15);
      if (column.getSkyLight(at) !== (light[i] >> 4)) column.setSkyLight(at, light[i] >> 4);
      const previous = column.getBlockEntity(at), next = entities[i];
      if ((previous || next) && JSON.stringify(previous ?? null) !== JSON.stringify(next ?? null)) {
        if (next) column.setBlockEntity(at, next);
        else column.removeBlockEntity(at);
      }
    }
  }
  function bounds() {
    if (!columns.size) return null;
    const coordinates = [...columns.keys()].map(id => id.split(',').map(Number));
    return { min: new Vec3(Math.min(...coordinates.map(p => p[0])) * 16, minY, Math.min(...coordinates.map(p => p[1])) * 16),
      max: new Vec3((Math.max(...coordinates.map(p => p[0])) + 1) * 16, minY + worldHeight, (Math.max(...coordinates.map(p => p[1])) + 1) * 16) };
  }
  return { getBlock, stateId, sectionStates, update, patchLocal, bounds };
}
