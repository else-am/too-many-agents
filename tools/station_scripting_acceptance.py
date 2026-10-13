#!/usr/bin/env python3
"""Real BB turn placing and configuring station blocks through minecraft_run."""
import json
import time
import uuid

from stations_acceptance import Game, GAME, ROOT, passed, row


def nested(rows):
    for item in rows:
        yield item
        yield from nested(item.get('children') or item.get('childRows') or [])


def run_script(game, agent_id, code, timeout=180):
    path = '/v1/agents/' + agent_id
    before = set()
    if game.request(path).get('threadId'):
        before = {r['id'] for r in nested(game.request(path + '/transcript',
                  {'includeNestedRows': 'true', 'summaryOnly': 'false'}).get('rows', []))}
    marker = 'STATION_SCRIPT_' + uuid.uuid4().hex
    game.request(path + '/message', {'delivery': 'send', 'text':
        'Call minecraft_run exactly once with the following literal JavaScript. Do not use any other tools. '
        'Do not retry failures or edit files. After the result, reply ' + marker + '.\n```js\n' + code + '\n```'})
    deadline = time.monotonic() + timeout
    while True:
        agent = game.request(path)
        rows = list(nested(game.request(path + '/transcript',
                    {'includeNestedRows': 'true', 'summaryOnly': 'false'}).get('rows', [])))
        fresh = [r for r in rows if r['id'] not in before]
        if agent['status'] == 'idle' and any(marker in r.get('text', '') for r in fresh if r.get('role') == 'assistant'):
            calls = [r for r in fresh if r.get('workKind') == 'tool' and 'minecraft_run' in r.get('toolName', '')]
            assert len(calls) == 1, calls
            output = json.loads(calls[0].get('output', '{}'))
            assert calls[0].get('status') == 'completed' and 'error' not in output, output
            assert 'value' in output.get('result', {}), output
            return output['result']['value']
        assert not agent.get('error') and not agent.get('interactions'), agent
        assert time.monotonic() < deadline, (agent['status'], fresh)
        time.sleep(.5)


def main():
    game = Game()
    if game.request('/v1/state')['screen'] != 'game':
        game.ui('close')
    game.command('gamemode creative @s')
    game.command('tp @s 260 -60 260')
    game.command('fill 255 -60 255 270 -54 270 air')
    game.command('fill 255 -61 255 270 -61 270 stone')
    game.command('kill @e[type=minecraft:item,x=255,y=-61,z=255,dx=15,dy=8,dz=15]')
    project = next(p for p in game.request('/v1/agents/projects')['projects']
                   if p.get('kind') == 'standard' and p.get('folder') == str(ROOT))
    created = game.request('/v1/agents/spawn', {'projectId': 'minecraft', 'name': 'Station script fixture',
        'body': 'minecraft:fox', 'mode': 'creative', 'providerId': 'codex', 'model': 'gpt-6.1-sol',
        'reasoningLevel': 'medium', 'permissionMode': 'accept-edits'})
    agent_id = created['id']
    (GAME / 'too-many-agents/station-script-fixture.json').write_text(json.dumps({'agentId': agent_id}))
    name = 'Script desk ' + uuid.uuid4().hex[:6]
    settings = {'name': name, 'projectId': project['id'], 'instructions': 'Keep the carried station settings.',
                'radius': 2, 'height': 4, 'body': 'minecraft:pig',
                'behaviors': {'working': {'type': 'stand'}, 'idle': {'type': 'wander'}, 'wants_you': {'type': 'look', 'target': 'player'}}}
    code = '''
await bot.waitForChunksToLoad();
await bot.moveTo(new Vec3(260, -60, 260));
const station = bot.registry.itemsByName['too_many_agents:station'];
const project = bot.registry.itemsByName['too_many_agents:project_block'];
if (!station || !project) throw new Error('Missing native mod items');
await bot.creative.setInventorySlot(36, new Item(station.id, 1));
bot.setQuickBarSlot(0);
await bot.waitForTicks(1);
await bot.placeBlock(bot.blockAt(new Vec3(262, -61, 260)), new Vec3(0, 1, 0));
const configured = await bot.configureStation(new Vec3(262, -60, 260), SETTINGS);
const renamed = await bot.configureStation(configured.id, {name: SETTINGS.name});
await bot.creative.setInventorySlot(36, new Item(project.id, 1));
await bot.placeBlock(bot.blockAt(new Vec3(262, -61, 262)), new Vec3(0, 1, 0));
const marker = await bot.configureProjectBlock(new Vec3(262, -60, 262), {projectId: SETTINGS.projectId});
await bot.waitForTicks(2);
return {configured, renamed, marker, stations: bot.stations(), projects: bot.projectBlocks(),
        stationBlock: bot.blockAt(new Vec3(262, -60, 260)).name};
'''.replace('SETTINGS', json.dumps(settings))
    result = run_script(game, agent_id, code)
    placed = row(game.station('read', pos=[262, -60, 260]), [262, -60, 260])
    for key, value in settings.items():
        assert placed[key] == value, (key, placed)
    assert result['configured']['id'] == placed['id'] and result['renamed']['id'] == placed['id'], result
    assert result['stationBlock'] == 'too_many_agents:station', result
    assert any(s['id'] == placed['id'] for s in result['stations']), result
    assert any(p['projectId'] == project['id'] for p in result['projects']), result
    assert project['id'] in json.dumps(game.station('read', pos=[262, -60, 262])['blockEntity'])
    passed('real script creates native mod items, places both blocks, configures them and reads their observed settings')
    checks = run_script(game, agent_id, '''
const desk = bot.stations().find(s => s.pos.x === 262);
const original = desk.name;
desk.name = 'Local observation edit';
if (bot.stations().find(s => s.id === desk.id).name !== original) throw new Error('Observation is not copied');
const named = await bot.configureStation(original.toUpperCase(), {name: original});
const errors = [];
for (const [target, patch] of [[desk.id, {radius: 50}], [desk.id, {behaviors: {idle: {type: 'look', target: {x: 0,y: 0,z: 0}}}}],
                              [new Vec3(5000, -60, 5000), {name: 'Outside range'}]]) {
  try { await bot.configureStation(target, patch); }
  catch (e) { errors.push(e.message); }
}
return {named: named.id, errors};
''')
    assert checks['named'] == placed['id'] and len(checks['errors']) == 3, checks
    assert row(game.station('read', pos=[262, -60, 260]), [262, -60, 260]) == placed
    passed('script reads are copied, name lookup ignores case, and invalid/out-of-range edits leave state unchanged')
    # The real item drop exercises the station component decoder in entity observations.
    game.command('tp @s 268 -60 268')
    carried = run_script(game, agent_id, '''
await bot.dig(bot.blockAt(new Vec3(262, -60, 260)));
await bot.waitForTicks(4);
return Object.values(bot.entities).filter(e => e.name === 'item').map(e => e.getDroppedItem?.()?.name);
''')
    assert 'too_many_agents:station' in carried, carried
    assert game.station('read', pos=[262, -60, 260])['block'] == 'minecraft:air'
    drops = game.station('read', pos=[262, -60, 260])['dropped']
    assert any(d['item'] == 'too_many_agents:station' and name in json.dumps(d['components']) for d in drops), drops
    passed('scripted breaking drops a configured station whose custom component decodes without breaking the script')
    replaced = run_script(game, agent_id, '''
const carried = Object.values(bot.entities).map(e => e.getDroppedItem?.())
  .find(item => item?.name === 'too_many_agents:station');
if (!carried) throw new Error('Configured station drop is missing');
await bot.creative.setInventorySlot(37, carried);
bot.setQuickBarSlot(1);
await bot.waitForTicks(1);
await bot.placeBlock(bot.blockAt(new Vec3(263, -61, 260)), new Vec3(0, 1, 0));
await bot.waitForTicks(2);
return bot.stations().find(s => s.pos.x === 263 && s.pos.y === -60 && s.pos.z === 260);
''')
    replacement = row(game.station('read', pos=[263, -60, 260]), [263, -60, 260])
    assert replacement['id'] == replaced['id'] and replacement['id'] != placed['id']
    for key, value in settings.items():
        assert replacement[key] == value, (key, replacement)
    passed('custom station component roundtrips from a native drop through scripted inventory and replacement')
    game.request('/v1/agents/' + agent_id + '/archive', {})
    game.station('break', pos=[262, -60, 262])
    game.station('break', pos=[263, -60, 260])


if __name__ == '__main__':
    main()
