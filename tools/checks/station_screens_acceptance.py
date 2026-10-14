#!/usr/bin/env python3
"""Check live station geometry/access against the native north-wall screen fixture."""
import argparse
import json
import time
import uuid

from stations_acceptance import Game, ROOT, row, passed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--observer', required=True, help='A loose test agent with a BB thread')
    parser.add_argument('--screen', required=True, help='The native 4x3 panel at 167,-58,167')
    args = parser.parse_args()
    game = Game()

    def tool(agent, name, **arguments):
        result = game.request('/v1/agents/' + agent + '/tool', {'tool': name, 'arguments': arguments})
        return json.loads(next(p['text'] for p in result['content'] if p['type'] == 'text')) if 'content' in result else result

    def visible(agent):
        return tool(agent, 'screen_list', limit=50)

    def link():
        links = game.request('/v1/agents/projects')['world']['screenLinks']
        return next((s['stationId'] for s in links if s['screenId'] == args.screen), None)

    def wait_link(expected):
        deadline = time.monotonic() + 5
        while link() != expected:
            assert time.monotonic() < deadline, (link(), expected)
            time.sleep(.1)

    assert any(s['id'] == args.screen for s in visible(args.observer)['screens']), 'Fixture must start unlinked'
    project = next(p for p in game.request('/v1/agents/projects')['projects']
                   if p.get('kind') == 'standard' and p.get('folder') == str(ROOT))
    if game.request('/v1/state')['screen'] != 'game':
        game.ui('close')
    game.command('gamemode creative @s')
    game.command('tp @s 168 -60 163')
    positions = [[171, -60, 167], [166, -60, 167]]
    stations = []
    for i, pos in enumerate(positions):
        assert game.station('read', pos=pos)['block'] == 'minecraft:air', 'Occupied fixture position'
        game.station('give', kind='station', slot=i, settings={'name': 'Screen desk ' + uuid.uuid4().hex[:6],
                     'projectId': project['id'], 'radius': 0, 'height': 3, 'body': 'minecraft:fox'})
        stations.append(row(game.station('place', pos=pos, slot=i), pos))
    wait_link(None)
    game.station('configure', pos=positions[0], settings={'radius': 1})
    wait_link(stations[0]['id'])
    assert not any(s['id'] == args.screen for s in visible(args.observer)['screens'])
    denied = tool(args.observer, 'screen_open', screen=args.screen, kind='url', target='about:blank')['error']
    assert denied['code'] == 'no_access' and denied['station']['id'] == stations[0]['id'], denied
    passed('partial panel overlap links a free station and excludes every other agent')

    for pos in positions:
        game.station('configure', pos=pos, settings={'radius': 3})
    # Centers at 171.5 and 166.5 are equally far from the panel center at x=169.
    winner = min(stations, key=lambda s: s['id'])
    wait_link(winner['id'])
    passed('overlapping equidistant stations use the lower UUID consistently')
    winner, winner_pos = stations[0], positions[0]
    game.station('configure', pos=positions[1], settings={'radius': 0})
    game.station('configure', pos=winner_pos, settings={'radius': 1})
    wait_link(winner['id'])
    agent = game.request('/v1/agents/spawn', {'stationId': winner['id'], 'providerId': 'codex',
                         'model': 'gpt-6.1-sol', 'reasoningLevel': 'medium',
                         'name': 'Screen station occupant', 'initialTask': 'Reply exactly screen-fixture-ready. Do not use tools.'})
    path = '/v1/agents/' + agent['id']
    deadline = time.monotonic() + 90
    while True:
        current = game.request(path)
        if current.get('threadId') and current.get('status') == 'idle':
            break
        assert time.monotonic() < deadline, 'Fixture thread failed to start; inspect the retained body'
        time.sleep(.2)
    mine = visible(agent['id'])
    owned = next(s for s in mine['screens'] if s['id'] == args.screen)
    assert owned['yours'] and mine['you']['station']['id'] == winner['id'], mine
    opened = tool(agent['id'], 'screen_open', kind='url', target='about:blank')
    assert opened.get('screen') == args.screen, opened
    assert next(s for s in visible(agent['id'])['screens'] if s['id'] == args.screen)['openedByYou']
    passed('spawned occupant can list and open its single linked screen with screen omitted')
    tool(agent['id'], 'screen_close', screen=args.screen)
    game.station('configure', pos=winner_pos, settings={'radius': 10})
    multiple = tool(agent['id'], 'screen_open', kind='url', target='about:blank')['error']
    assert multiple['code'] == 'choose_screen' and len(multiple['candidates']) > 1, multiple
    passed('several linked screens require an explicit choice and list candidates')
    game.station('configure', pos=winner_pos, settings={'height': 1})
    wait_link(None)
    assert any(s['id'] == args.screen for s in visible(args.observer)['screens'])
    missing = tool(agent['id'], 'screen_open', kind='url', target='about:blank')['error']
    assert missing['code'] == 'choose_screen' and not missing['candidates'], missing
    passed('live height change unlinks the screen and makes explicit access public again')
    game.request(path + '/archive', {})
    for pos in positions:
        game.station('break', pos=pos)


if __name__ == '__main__':
    main()
