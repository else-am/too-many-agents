#!/usr/bin/env python3
"""Archive a dedicated station fixture and observe its persisted screen records."""
import argparse
import json
import subprocess
import time

import screens_acceptance as native
from stations_acceptance import Game


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', required=True, help='Dedicated test occupant to archive')
    parser.add_argument('--screen', required=True, help='Its station-linked built-in screen')
    parser.add_argument('--web', required=True, help='A web screen linked to the same station')
    parser.add_argument('--loose', required=True, help='An unlinked built-in screen')
    parser.add_argument('--via', choices=['bb', 'game'], default='bb')
    parser.add_argument('--unloaded', action='store_true')
    args = parser.parse_args()
    game = Game()

    def records():
        return {row['id']: row for row in native.ui('screens')['index']['screens']}

    before = records()
    assert before[args.screen]['url'].startswith('tma://'), before[args.screen]
    assert before[args.loose]['url'].startswith('tma://'), before[args.loose]
    assert before[args.web]['url'].startswith(('http://', 'https://')), before[args.web]
    world = game.request('/v1/agents/projects')['world']
    station = next(row for row in world['stations'] if row.get('agentId') == args.agent)
    links = {row['screenId']: row['stationId'] for row in world['screenLinks']}
    assert links[args.screen] == links[args.web] == station['id']
    assert args.loose not in links
    agent = game.request('/v1/agents/' + args.agent)
    assert not agent.get('conversationArchived'), 'Use an active fixture occupant'
    player = native.ui('screens')['player']
    if args.unloaded:
        game.command('tp @s 1000 -60 1000')
        native.wait_for(lambda state: all(row['id'] != args.screen for row in state['screens']), timeout=30)

    if args.via == 'bb':
        assert agent.get('threadId'), 'BB archive needs a bound fixture thread'
        result = subprocess.run(['bb', 'thread', 'archive', agent['threadId'], '--json'],
                                capture_output=True, text=True, timeout=30)
        assert result.returncode == 0, result.stderr
    else:
        game.request('/v1/agents/' + args.agent + '/conversation-archive', {})

    deadline = time.monotonic() + 15
    while records()[args.screen]['url']:
        assert time.monotonic() < deadline, records()[args.screen]
        time.sleep(.1)
    after = records()
    assert not after[args.screen]['openedBy'], after[args.screen]
    for screen in [args.web, args.loose]:
        assert after[screen]['url'] == before[screen]['url'], (before[screen], after[screen])
    game.command(f"tp @s {player['x']} {player['y']} {player['z']}")
    native.wait_for(lambda state: any(row['id'] == args.screen and not row['url']
        and not row['browser'] for row in state['screens']), timeout=30)
    restored = game.station('read', pos=station['pos'])
    assert not restored['blockEntity']['station'].get('agentId'), restored['blockEntity']
    print('PASS: archive closes linked built-ins; linked web and loose built-ins keep their locations', flush=True)
    if args.unloaded:
        print('PASS: unloaded screen record stays closed when its chunk returns', flush=True)
    print(json.dumps({'agent': args.agent, 'thread': agent.get('threadId'), 'station': station['id']}))


if __name__ == '__main__':
    main()
