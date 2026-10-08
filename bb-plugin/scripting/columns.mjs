// Observed WorldSync columns and Mineflayer's complete 5x5 loading wait.
// Upstream contracts: prismarine-world 3.7.0 / Mineflayer 4.39.0 (mineflayer.LICENSE).
import { Vec3 } from 'vec3';
import loadWorld from 'prismarine-world/src/world.js';
import { RaycastIterator } from './world-view.mjs';

const World = loadWorld();
export function createColumnWorld() {
  const asyncWorld = new World(null);
  // Upstream saves by looking up current coordinates later. Native unloads may
  // remove those coordinates first, so retain the queued column and provider.
  let saving;
  asyncWorld.queueSaving = function(chunkX, chunkZ) {
    this.savingQueue.set(`${chunkX},${chunkZ}`, {
      chunkX, chunkZ, column: this.getLoadedColumn(chunkX, chunkZ), provider: this.storageProvider,
    });
  };
  asyncWorld.saveNow = async function() {
    if (saving) { await saving; return; }
    if (!this.savingQueue.size) return;
    const batch = [...this.savingQueue];
    const operation = Promise.resolve().then(async () => {
      for (const [key, entry] of batch) {
        await entry.provider.save(entry.chunkX, entry.chunkZ, entry.column);
        // An edit queued while the callback awaited belongs to a later save.
        if (this.savingQueue.get(key) === entry) this.savingQueue.delete(key);
      }
      for (const [key, { chunkX, chunkZ }] of this.unloadQueue)
        if (!this.savingQueue.has(key)) this.forceUnloadColumn(key, chunkX, chunkZ);
      this.emit('doneSaving');
    });
    saving = operation;
    this.finishedSaving = operation;
    try { await operation; } finally { if (saving === operation) saving = undefined; }
  };
  asyncWorld.waitSaving = async function() {
    while (saving || this.savingQueue.size) await this.saveNow();
    await this.finishedSaving;
  };
  // Async World's matcher filters candidates; sync World's matcher selects hits.
  asyncWorld.raycast = async function(from, direction, range, matcher = null) {
    if (![from.x, from.y, from.z, direction.x, direction.y, direction.z, range].every(Number.isFinite) || range < 0)
      throw new RangeError('Raycast requires finite vectors and a finite nonnegative range');
    const iterator = new RaycastIterator(from, direction, range);
    for (let cell = iterator.block; cell; cell = iterator.next()) {
      const position = new Vec3(cell.x, cell.y, cell.z);
      const block = await this.getBlock(position);
      if (!block || (matcher && !matcher(block))) continue;
      const hit = iterator.intersect(block.shapes, position);
      if (hit) { block.face = hit.face; block.intersect = hit.pos; return block; }
    }
    return null;
  };
  return asyncWorld.sync;
}

export function installColumns(bot, ChunkColumn) {
  const sources = new Map();
  let minY = -64, worldHeight = 384, dimension;
  const key = (x, z) => `${x},${z}`;
  const local = p => new Vec3(Math.floor(p.x) & 15, Math.floor(p.y), Math.floor(p.z) & 15);
  const world = bot.world;
  const cached = () => world.async.columns;
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
    if ([...wanted].every(id => Object.hasOwn(cached(), id))) return;
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        world.off('chunkColumnLoad', loaded);
        reject(new Error('Timeout waiting for complete nearby columns after 10000ms'));
      }, 10000);
      function loaded() {
        // Recheck the whole target; an earlier column may have unloaded.
        if (![...wanted].every(id => Object.hasOwn(cached(), id))) return;
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
      if (world.async.savingQueue.size || world.async.currentlySaving)
        throw new Error('World dimension changed with unsaved guest columns');
      for (const id of Object.keys(cached())) {
        const [x, z] = id.split(',').map(Number);
        if (emit) events.push(['chunkColumnUnload', new Vec3(x * 16, 0, z * 16)]);
      }
      world.async.columns = {}; sources.clear();
    }
    dimension = view.dimension;
    minY = view.minY; worldHeight = view.worldHeight;
    const incoming = view.columns ?? view.changes;
    const removed = view.columns ? Object.keys(cached()).filter(id => !Object.hasOwn(view.columns, id)) : view.removed;
    for (const id of removed) {
      if (!Object.hasOwn(cached(), id)) continue;
      delete cached()[id];
      sources.delete(id);
      const [x, z] = id.split(',').map(Number);
      if (emit) events.push(['chunkColumnUnload', new Vec3(x * 16, 0, z * 16)]);
    }
    for (const [id, row] of Object.entries(incoming)) {
      const old = cached()[id], source = sources.get(id);
      // Initial stream includes a full frame; avoid decoding identical columns twice.
      const encoded = JSON.stringify(row);
      if (old && source?.encoded === encoded) continue;
      const column = ChunkColumn.fromSnapshot(row);
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
      if (old instanceof ChunkColumn) Object.assign(old, column);
      else { cached()[id] = column; if (emit) events.push(['chunkColumnLoad', new Vec3(row.x * 16, 0, row.z * 16)]); }
      world.async.unloadQueue.delete(id);
      if (world.async.storageProvider) world.async.queueSaving(row.x, row.z);
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
      let changed = false;
      if (column.getBlockStateId(at) !== states[i]) { column.setBlockStateId(at, states[i]); changed = true; }
      if (column.getBiome(at) !== biomes[i]) { column.setBiome(at, biomes[i]); changed = true; }
      if (column.getBlockLight(at) !== (light[i] & 15)) { column.setBlockLight(at, light[i] & 15); changed = true; }
      if (column.getSkyLight(at) !== (light[i] >> 4)) { column.setSkyLight(at, light[i] >> 4); changed = true; }
      const previous = column.getBlockEntity(at), next = entities[i];
      if ((previous || next) && JSON.stringify(previous ?? null) !== JSON.stringify(next ?? null)) {
        if (next) column.setBlockEntity(at, next);
        else column.removeBlockEntity(at);
        changed = true;
      }
      if (changed) world.async.saveAt(p);
    }
  }
  function bounds() {
    if (!Object.keys(cached()).length) return null;
    const coordinates = Object.keys(cached()).map(id => id.split(',').map(Number));
    return { min: new Vec3(Math.min(...coordinates.map(p => p[0])) * 16, minY, Math.min(...coordinates.map(p => p[1])) * 16),
      max: new Vec3((Math.max(...coordinates.map(p => p[0])) + 1) * 16, minY + worldHeight, (Math.max(...coordinates.map(p => p[1])) + 1) * 16) };
  }
  return { getBlock, update, patchLocal, bounds };
}
