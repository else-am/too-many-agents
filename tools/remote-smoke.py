#!/usr/bin/env python3
"""Live remote-area checks against existing fixtures in the isolated development world.

Supply an idle world agent beyond the player's render distance, an idle project agent
with a conversation, and a remote screen with a saved page. Reopens the test world.
"""
import argparse
import json
from pathlib import Path
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
GAME = ROOT / 'run'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--world-agent', required=True)
    parser.add_argument('--project-agent', required=True)
    parser.add_argument('--screen', required=True)
    parser.add_argument('--long-screen-call', action='store_true', help='Also await a 25-second CDP expression on the idle agent')
    args = parser.parse_args()
    evidence = {}

    def cli(script, *values):
        return json.loads(subprocess.check_output(
            ['python3', str(ROOT / 'tools' / script), '--game-dir', str(GAME), *values], timeout=120))

    def agents(*values):
        return cli('agents.py', *values)

    def state():
        return cli('too_many_agents.py', 'state')

    def tool(agent, name, arguments):
        result = agents('tool', agent, name, json.dumps(arguments))
        if 'content' in result:
            result = json.loads(result['content'][0]['text'])
        assert not result.get('error') and result.get('ok', True), result
        return result

    def wait_for(label, condition, seconds=35):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            if condition():
                print('PASS:', label, flush=True)
                return
            time.sleep(.5)
        raise AssertionError(label + ' did not settle; inspect state before rerunning mutations')

    def resources():
        snapshot = state()
        screens = agents('ui', 'screens')
        return {key: snapshot[key] for key in ('fps', 'heapUsedBytes', 'povResources')} | {
            key: snapshot['server'][key] for key in ('averageTickMs', 'loadedChunks', 'remoteChunks')
        } | {'browsers': screens['browsers'], 'views': screens['views']}

    def released():
        snapshot = resources()
        return (snapshot['remoteChunks']['ticketedChunks'] == 0
                and snapshot['povResources']['chunks'] == 0
                and snapshot['povResources']['meshes'] == 0 and snapshot['browsers'] == 0)

    agents('dev', 'status')
    world = agents('projects')['world']
    assert world['directory'] == str(GAME / 'saves/too-many-agents-development'), world
    assert state()['state'] == 'world' and not state()['paused']
    for agent in (args.world_agent, args.project_agent):
        assert agents('get', agent)['status'] == 'idle', 'Let fixture tasks finish first'
    assert agents('get', args.world_agent)['minecraftAccess']
    assert not agents('get', args.project_agent)['minecraftAccess']
    wait_for('idle resources released', released)
    evidence['baseline'] = resources()

    listed = tool(args.project_agent, 'screen_list', {})
    screen = next(row for row in listed['screens'] if args.screen in (row['id'], row['name']))
    assert not screen['awake']
    assert agents('ui', 'screens')['browsers'] == 0
    print('PASS: listing a sleeping remote screen does not wake it', flush=True)

    observation = tool(args.world_agent, 'minecraft_observe', {})
    position = observation['body']['position']
    player = agents('ui', 'screens')['player']
    assert (position['x'] - player['x']) ** 2 + (position['z'] - player['z']) ** 2 > 128 ** 2
    evidence['position'] = position
    tool(args.world_agent, 'minecraft_blocks', {
        'min': {'x': 10000, 'y': -61, 'z': 10000}, 'max': {'x': 10001, 'y': -60, 'z': 10001},
    })
    evidence['pov'] = tool(args.world_agent, 'minecraft_pov', {})
    assert player == agents('ui', 'screens')['player'], 'POV moved the player camera'
    time.sleep(1)  # Diagnostics are published snapshots.
    evidence['remoteView'] = resources()
    print('PASS: remote observation, unloaded block read, POV and unchanged player camera', flush=True)

    def evaluate(expression, await_promise=False):
        return tool(args.project_agent, 'screen_cdp', {
            'screen': screen['id'], 'method': 'Runtime.evaluate',
            'params': {'expression': expression, 'returnByValue': True, 'awaitPromise': await_promise},
        })['result']['result']

    marker = int(time.time() * 1000)
    evaluate(f'window.remoteSmoke = {marker}')
    if args.long_screen_call:
        assert evaluate('new Promise(resolve => setTimeout(() => resolve(window.remoteSmoke), 25000))', True)['value'] == marker
        print('PASS: remote browser survives a call longer than the idle grace period', flush=True)
    time.sleep(3)
    assert evaluate('window.remoteSmoke')['value'] == marker
    tool(args.project_agent, 'screen_screenshot', {'screen': screen['id']})
    print('PASS: sleeping remote page wakes and retains state between calls', flush=True)
    wait_for('idle terrain, meshes and browsers released', released)
    assert evaluate('typeof window.remoteSmoke')['value'] == 'undefined'
    time.sleep(1)
    assert resources()['remoteChunks']['ticketedChunks'] == 0, 'Screen use loaded unnecessary terrain'
    print('PASS: sleeping page reopens its saved URL with fresh page memory', flush=True)
    print('PASS: remote screen use loads no terrain', flush=True)

    inventory = agents('inventory', args.world_agent)
    inventory = {key: inventory[key] for key in ('entityUuid', 'selected', 'slots', 'equipment', 'carried')}
    agents('ui', 'dev_leave')
    wait_for('world exit releases POV cache', lambda: state()['povResources']['chunks'] == 0)
    agents('ui', 'dev_open_world', '--json', '{"name":"too-many-agents-development"}')
    wait_for('test world reopened', lambda: state()['state'] == 'world')
    assert agents('ui', 'screens')['browsers'] == 0, 'Browsers survived world exit'
    wait_for('agent records restored', lambda: agents('get', args.world_agent).get('currentWorld', False))
    restored = agents('inventory', args.world_agent)
    assert {key: restored[key] for key in inventory} == inventory, 'Saved inventory changed'
    after = tool(args.world_agent, 'minecraft_observe', {})['body']['position']
    assert after == position, (position, after)
    reopened = tool(args.project_agent, 'screen_list', {})
    saved = next(row for row in reopened['screens'] if row['id'] == screen['id'])
    assert saved['url'] == screen['url'] and not saved['awake']
    print('PASS: remote body, inventory and screen URL survive save/reopen', flush=True)
    wait_for('final idle cleanup', released)
    evidence['afterCleanup'] = resources()
    output = GAME / 'too-many-agents/remote-smoke-result.json'
    output.write_text(json.dumps(evidence, indent=2) + '\n')
    print('Live remote checks passed; evidence:', output, flush=True)


if __name__ == '__main__':
    main()
