#!/usr/bin/env python3
"""Exercise real station blocks/items in the guarded run/ world; never retry mutations."""
import argparse
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / 'run'
MANIFEST = GAME / 'too-many-agents/stations-acceptance.json'


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Game:
    def __init__(self):
        self.connection = json.loads((GAME / 'too-many-agents/connection.json').read_text())
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        from urllib.parse import urlparse
        url = urlparse(self.connection['url'])
        assert url.scheme == 'http' and url.hostname == '127.0.0.1' and url.port
        self.request('/v1/dev')
        world = self.request('/v1/agents/projects')['world']
        assert world['directory'] == str(GAME / 'saves/too-many-agents-development')
        assert not world.get('needsDecision')
        self.session = self.request('/v1/state')['session']

    def request(self, path, data=None, error=False):
        request = urllib.request.Request(self.connection['url'] + path,
            data=None if data is None else json.dumps(data).encode(), headers={
                'Authorization': 'Bearer ' + self.connection['token'],
                'Content-Type': 'application/json'})
        try:
            with self.opener.open(request, timeout=30) as response:
                result = json.load(response)
        except urllib.error.HTTPError as failure:
            result = json.load(failure)
            if error and failure.code == 400:
                return result
            raise AssertionError((failure.code, result)) from None
        assert not error, ('Invalid mutation was accepted', data, result)
        return result

    def ui(self, action, **fields):
        return self.request('/v1/ui', {'action': action, **fields})

    def command(self, command):
        # This endpoint waits for the server to execute, unlike the UI's queued command hook.
        self.request('/v1/command', {'session': self.session, 'command': command})

    def station(self, operation, **fields):
        return self.request('/v1/dev', {'action': 'station', 'operation': operation, **fields})


def row(snapshot, pos):
    return next(station for station in snapshot['index']['stations'] if station['pos'] == pos)


def passed(message):
    print('PASS: ' + message, flush=True)


def foundation(game):
    pos, duplicate, project = [224, -60, 224], [228, -60, 224], [224, -60, 229]
    projects = game.request('/v1/agents/projects')['projects']
    project_id = next(p['id'] for p in projects if p.get('kind') == 'standard' and p.get('folder') == str(ROOT))
    if game.request('/v1/state')['screen'] != 'game':
        game.ui('close')
    game.command('gamemode creative @s')
    game.command('tp @s 224 -60 220')
    time.sleep(2)
    game.command('fill 218 -60 218 233 -54 233 air')
    game.command('fill 218 -61 218 233 -61 233 stone')
    game.command('kill @e[type=minecraft:item,x=218,y=-61,z=218,dx=16,dy=8,dz=16]')
    game.command('clear @s too_many_agents:station')
    game.command('clear @s too_many_agents:project_block')
    name = 'Desk ' + uuid.uuid4().hex[:8]
    settings = {'name': name, 'radius': 3, 'height': 5, 'instructions': 'First line\nKeep these settings.',
        'providerId': 'codex', 'model': 'gpt-6-astra', 'reasoningLevel': 'medium',
        'serviceTier': 'default', 'permissionMode': 'accept-edits', 'body': 'minecraft:fox',
        'behaviors': {'working': {'type': 'stand'}, 'wants_you': {'type': 'look', 'target': 'player'},
            'idle': {'type': 'wander'}}}
    game.station('give', kind='station', slot=0, settings=settings)
    game.command('gamemode survival @s')
    placed = game.station('place', pos=pos, slot=0)
    assert placed['block'] == 'too_many_agents:station', placed
    original = row(placed, pos)
    for key, value in settings.items():
        assert original[key] == value, (key, original)
    assert not original['agentId'] and not original['projectId']
    assert not any(item['item'] == 'too_many_agents:station' for item in placed['inventory'])
    passed('native survival placement consumes the item and retains settings')

    broken = game.station('break', pos=pos)
    assert broken['block'] == 'minecraft:air'
    assert not any(item['id'] == original['id'] for item in broken['index']['stations'])
    assert any(item['item'] == 'too_many_agents:station' for item in broken['dropped'])
    game.command('tp @s 224.5 -60 224.5')
    deadline = time.monotonic() + 5
    while True:
        inventory = game.station('read', pos=pos)['inventory']
        carried = next((item for item in inventory if item['item'] == 'too_many_agents:station'), None)
        if carried:
            break
        assert time.monotonic() < deadline, inventory
        time.sleep(.1)
    assert 0 <= carried['slot'] <= 8, carried
    game.command('tp @s 224 -60 220')
    replaced = row(game.station('place', pos=pos, slot=carried['slot']), pos)
    assert replaced['id'] != original['id']
    for key, value in settings.items():
        assert replaced[key] == value, (key, replaced)
    passed('native break, pickup and replacement preserve settings with a fresh identity')

    game.station('give', kind='station', slot=0, settings=settings)
    copy = row(game.station('place', pos=duplicate, slot=0), duplicate)
    assert copy['name'] != name and copy['name'].startswith(name)
    for invalid in ({'name': ''}, {'name': name.upper()}, {'name': 'x' * 33},
                    {'radius': -1}, {'radius': 50}, {'height': 0}, {'height': 100}):
        game.request('/v1/dev', {'action': 'station', 'operation': 'configure',
            'pos': duplicate, 'settings': invalid}, error=True)
    assert row(game.station('read', pos=duplicate), duplicate) == copy
    passed('duplicate carried names receive suffixes; invalid edits fail without changing the station')

    game.station('occupy', pos=pos, agentId=str(uuid.uuid4()))
    for mode in ('survival', 'creative'):
        game.command('gamemode ' + mode + ' @s')
        protected = game.station('break', pos=pos)
        assert protected['block'] == 'too_many_agents:station'
    game.request('/v1/dev', {'action': 'station', 'operation': 'configure', 'pos': pos,
        'settings': {'projectId': 'another-project'}}, error=True)
    game.station('occupy', pos=pos, agentId='')
    passed('occupied station resists survival and creative breaking and project changes')

    game.command('setblock 223 -60 224 piston[facing=east]')
    game.command('setblock 222 -60 224 redstone_block')
    time.sleep(.4)
    assert game.station('read', pos=pos)['block'] == 'too_many_agents:station'
    assert game.station('read', pos=[225, -60, 224])['block'] == 'minecraft:air'
    game.command('setblock 222 -60 224 air')
    game.command('setblock 223 -60 224 air')
    passed('powered piston cannot move a station')

    game.command('setblock 226 -59 224 glass')
    game.command('summon tnt 225.5 -59 224.5 {Fuse:0s}')
    time.sleep(1)
    assert game.station('read', pos=pos)['block'] == 'too_many_agents:station'
    assert game.station('read', pos=[226, -59, 224])['block'] == 'minecraft:air'
    passed('a real TNT explosion destroys its control block but preserves the station')

    game.station('give', kind='project', slot=0)
    assert game.station('place', pos=project, slot=0)['block'] == 'too_many_agents:project_block'
    game.station('configure_project', pos=project, projectId=project_id, color='#77BFC7')
    project_read = game.station('read', pos=project)
    assert project_id in json.dumps(project_read['blockEntity'])
    game.command('gamemode survival @s')
    dropped = game.station('break', pos=project)
    plain = [item for item in dropped['dropped'] if item['item'] == 'too_many_agents:project_block']
    assert plain and all(project_id not in json.dumps(item) for item in plain)
    passed('project block saves its binding and drops a stateless item')

    game.command('setblock 228 -60 224 air')
    assert not any(item['id'] == copy['id'] for item in game.station('read')['index']['stations'])
    passed('command removal reconciles the station index')
    saved = row(game.station('read', pos=pos), pos)
    MANIFEST.write_text(json.dumps({'station': saved, 'pos': pos}, indent=2) + '\n')
    game.request('/v1/dev', {'action': 'save'})
    assert (GAME / 'saves/too-many-agents-development/data/too_many_agents_stations.dat').is_file()
    passed('persistent index written; fixture retained for reopen check')


def persistence(game):
    saved = json.loads(MANIFEST.read_text())
    current = row(game.station('read', pos=saved['pos']), saved['pos'])
    assert current == saved['station'], (current, saved['station'])
    passed('reopened station identity, settings, position and occupancy match the saved index')


def unloaded(game):
    saved = json.loads(MANIFEST.read_text())
    pos, project_pos = saved['pos'], [224, -60, 229]
    station = row(game.station('read', pos=pos), pos)
    assert not station['agentId'], 'The retained fixture must be free'
    folder = GAME / 'stations-unloaded-project'
    folder.mkdir(exist_ok=True)
    assert not any(p.get('folder') == str(folder) for p in game.request('/v1/agents/projects')['projects'])
    project = game.request('/v1/agents/projects', {'operation': 'create', 'name': 'Unloaded station fixture', 'folder': str(folder)})
    project_id = project['id']
    deadline = time.monotonic() + 10
    while not any(p['id'] == project_id for p in game.request('/v1/agents/projects')['projects']):
        assert time.monotonic() < deadline
        time.sleep(.1)
    game.command('gamemode creative @s')
    game.station('configure', pos=pos, settings={'projectId': project_id})
    game.station('give', kind='project', slot=0)
    game.station('place', pos=project_pos, slot=0)
    game.station('configure_project', pos=project_pos, projectId=project_id)
    # Observe chunk unloading without a read that would itself load the station's chunk.
    game.command('tp @s 5000 -60 5000')
    game.command('setblock 5001 -60 5000 air')
    deadline = time.monotonic() + 35
    while True:
        game.command(f'execute unless loaded {pos[0]} {pos[1]} {pos[2]} run setblock 5001 -60 5000 gold_block')
        if game.station('read', pos=[5001, -60, 5000])['block'] == 'minecraft:gold_block':
            break
        assert time.monotonic() < deadline, 'Fixture chunk did not unload'
        time.sleep(.5)
    assert any(s['id'] == station['id'] for s in game.station('read')['index']['stations'])
    error = game.request('/v1/agents/spawn', {'stationId': station['id']}, error=True)
    assert 'unloaded' in json.dumps(error).lower(), error
    passed('unloaded stations remain listed but cannot accept a spawn')
    game.request('/v1/agents/projects', {'operation': 'remove', 'projectId': project_id})
    assert not next(s for s in game.station('read')['index']['stations'] if s['id'] == station['id'])['projectId']
    game.command('setblock 5001 -60 5000 air')
    game.command('tp @s 224 -60 220')
    current = row(game.station('read', pos=pos), pos)
    assert not current['projectId'] and not current['agentId']
    assert project_id not in json.dumps(game.station('read', pos=project_pos)['blockEntity'])
    game.station('break', pos=project_pos)
    saved['station'] = current
    MANIFEST.write_text(json.dumps(saved, indent=2) + '\n')
    passed('project removal clears unloaded station and project block bindings when they reload')


def spawning(game):
    saved = json.loads(MANIFEST.read_text())
    pos = saved['pos']
    folder = GAME / 'stations-acceptance-project'
    folder.mkdir(exist_ok=True)
    projects = game.request('/v1/agents/projects')
    assert not any(p.get('folder') == str(folder) for p in projects['projects']), \
        'An earlier fixture project remains; inspect it before rerunning mutations.'
    project = game.request('/v1/agents/projects', {'operation': 'create',
        'name': 'Stations acceptance', 'folder': str(folder), 'color': '#77BFC7'})
    project_id = project['id']
    saved['projectId'] = project_id
    MANIFEST.write_text(json.dumps(saved, indent=2) + '\n')
    deadline = time.monotonic() + 10
    while not any(p['id'] == project_id for p in game.request('/v1/agents/projects')['projects']):
        assert time.monotonic() < deadline, 'New project did not sync to Minecraft'
        time.sleep(.2)
    game.station('configure', pos=pos, settings={'projectId': project_id, 'providerId': 'codex',
        'model': 'gpt-6.1-sol', 'reasoningLevel': 'medium', 'permissionMode': 'accept-edits',
        'instructions': 'For the first reply, say exactly station-instructions-arrived. Do not use tools.'})
    station = row(game.station('read', pos=pos), pos)
    project_pos = [224, -60, 229]
    game.station('give', kind='project', slot=0)
    game.station('place', pos=project_pos, slot=0)
    game.station('configure_project', pos=project_pos, projectId=project_id)
    created = game.request('/v1/agents/spawn', {'stationId': station['name'].upper(), 'name': 'Station acceptance occupant'})
    agent_id = created['id']
    saved['agentId'] = agent_id
    MANIFEST.write_text(json.dumps(saved, indent=2) + '\n')
    path = '/v1/agents/' + agent_id
    agent = game.request(path)
    assert agent['projectId'] == project_id and agent['minecraftAccess'] is False, agent
    assert not {'stationId', 'minecraftAccess', 'notifyInChat', 'mode', 'behavior'}.intersection(agent['settings']), agent['settings']
    assert agent['settings']['body'] == 'minecraft:fox', agent['settings']
    assert row(game.station('read', pos=pos), pos)['agentId'] == agent_id
    assert agent['executionOptions']['model'] == 'gpt-6.1-sol'
    assert agent['executionOptions']['permissionMode'] == 'accept-edits'
    passed('station name resolves its project, claims occupancy, and supplies execution settings')

    game.request('/v1/agents/spawn', {'stationId': station['id'], 'name': 'Must not spawn'}, error=True)
    game.request('/v1/agents/spawn', {'stationId': station['id'],
        'projectId': projects['world']['worldProjectId']}, error=True)
    game.request('/v1/dev', {'action': 'station', 'operation': 'configure', 'pos': pos,
        'settings': {'projectId': ''}}, error=True)
    game.request(path + '/tool', {'tool': 'minecraft_settings', 'arguments': {}}, error=True)
    passed('occupied station, project mismatch, project changes and project-agent physical access are rejected')

    game.station('configure', pos=pos, settings={'model': 'gpt-6-astra', 'radius': 2})
    assert game.request(path)['executionOptions']['model'] == 'gpt-6.1-sol'
    game.request(path + '/message', {'text': 'Follow the station instructions.', 'delivery': 'send'})
    deadline = time.monotonic() + 120
    while True:
        agent = game.request(path)
        transcript = game.request(path + '/transcript', {'includeNestedRows': 'true', 'summaryOnly': 'false'})
        replies = [r.get('text', '') for r in transcript.get('rows', []) if r.get('role') == 'assistant']
        if any('station-instructions-arrived' in text for text in replies) and agent['status'] == 'idle':
            break
        assert not agent.get('error'), agent.get('error')
        assert time.monotonic() < deadline, (agent['status'], replies)
        time.sleep(.5)
    assert agent['executionOptions']['model'] == 'gpt-6.1-sol'
    passed('real BB turn receives station instructions; later station edits leave its execution unchanged')

    game.request(path + '/archive', {})
    assert not row(game.station('read', pos=pos), pos)['agentId']
    game.request(path + '/conversation-restore', {})
    assert not row(game.station('read', pos=pos), pos)['agentId']
    restored = game.request(path)
    assert not restored['bodyRemoved'] and not restored['conversationArchived'], restored
    game.request('/v1/agents/projects', {'operation': 'remove', 'projectId': project_id}, error=True)
    passed('archive releases the station; restore is loose; remaining body prevents project removal')
    game.request(path + '/archive', {})
    game.request('/v1/agents/projects', {'operation': 'remove', 'projectId': project_id})
    deadline = time.monotonic() + 10
    while row(game.station('read', pos=pos), pos)['projectId']:
        assert time.monotonic() < deadline, 'Removed project remained bound to its station'
        time.sleep(.2)
    game.request('/v1/dev', {'action': 'station', 'operation': 'configure', 'pos': pos,
        'settings': {'projectId': projects['world']['worldProjectId']}}, error=True)
    game.request('/v1/agents/spawn', {'stationId': station['id']}, error=True)
    assert project_id not in json.dumps(game.station('read', pos=project_pos)['blockEntity'])
    game.request('/v1/dev', {'action': 'station', 'operation': 'configure_project', 'pos': project_pos,
        'projectId': projects['world']['worldProjectId']}, error=True)
    game.station('break', pos=project_pos)
    saved['station'] = row(game.station('read', pos=pos), pos)
    MANIFEST.write_text(json.dumps(saved, indent=2) + '\n')
    passed('project removal leaves the station blank/inert; world-project binding is rejected')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['foundation', 'persistence', 'spawning', 'unloaded'])
    args = parser.parse_args()
    game = Game()
    {'foundation': foundation, 'persistence': persistence, 'spawning': spawning, 'unloaded': unloaded}[args.phase](game)


if __name__ == '__main__':
    main()
