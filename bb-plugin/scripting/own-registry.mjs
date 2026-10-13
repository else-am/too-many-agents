// Our registrations only. Guest item IDs are stable extensions of the pinned vanilla table;
// native IDs are mapped by full resource name by item-wire.mjs.
export function ownItems(vanilla) {
  const first = Math.max(...vanilla.map(item => item.id)) + 1;
  return ['screen', 'station', 'project_block'].map((name, index) => ({
    id: first + index, name: `too_many_agents:${name}`, stackSize: 64,
    displayName: { screen: 'Screen', station: 'Station', project_block: 'Project' }[name],
  }));
}

// The two stateless cube blocks use observed native state IDs, not guessed load-order IDs.
export function withOwnBlocks(data, observed) {
  const names = new Set(['too_many_agents:station', 'too_many_agents:project_block']);
  if (!Array.isArray(observed) || observed.length !== names.size) throw new Error('Missing native station block registry');
  const blocksArray = data.blocksArray.slice();
  const shapes = { ...data.blockCollisionShapes.shapes };
  const blocks = { ...data.blockCollisionShapes.blocks };
  for (const row of observed) {
    if (!names.delete(row.name) || !Number.isInteger(row.id) || !Number.isInteger(row.stateId)
      || row.id < 0 || row.stateId < 0 || blocksArray.some(block => block.id === row.id
        || row.stateId >= block.minStateId && row.stateId <= block.maxStateId))
      throw new Error('Invalid native station block registry');
    const item = data.itemsArray.find(item => item.name === row.name);
    const shape = Math.max(...Object.keys(shapes).map(Number)) + 1;
    shapes[shape] = [[0, 0, 0, 1, 1, 1]];
    blocks[row.name] = shape;
    blocksArray.push({
      id: row.id, name: row.name, displayName: item.displayName, hardness: row.hardness,
      minStateId: row.stateId, maxStateId: row.stateId, defaultState: row.stateId,
      states: [], drops: [item.id], diggable: true, material: 'mineable/axe',
      transparent: false, filterLight: 15, emitLight: 0, boundingBox: 'block', stackSize: 64,
    });
  }
  return { ...data, blocksArray, blockCollisionShapes: { shapes, blocks } };
}
