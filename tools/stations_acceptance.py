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
    game.station('configure_project', pos=project, projectId='proj_2mstpt8yi2', color='#77BFC7')
    project_read = game.station('read', pos=project)
    assert 'proj_2mstpt8yi2' in json.dumps(project_read['blockEntity'])
    game.command('gamemode survival @s')
    dropped = game.station('break', pos=project)
    plain = [item for item in dropped['dropped'] if item['item'] == 'too_many_agents:project_block']
    assert plain and all('proj_2mstpt8yi2' not in json.dumps(item) for item in plain)
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


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('phase', choices=['foundation', 'persistence'])
    args = parser.parse_args()
    game = Game()
    (foundation if args.phase == 'foundation' else persistence)(game)


if __name__ == '__main__':
    main()
