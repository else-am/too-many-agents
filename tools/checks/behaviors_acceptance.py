#!/usr/bin/env python3
"""Observe ambient motion during real BB turns in the guarded development world."""
from game_client import tool_name
import json
import math
import time
import uuid

from stations_acceptance import Game, GAME, ROOT, passed, row
from station_scripting_acceptance import nested


def wait(check, timeout=120):
    deadline = time.monotonic() + timeout
    while True:
        value = check()
        if value:
            return value
        assert time.monotonic() < deadline, 'Timed out waiting for live behavior'
        time.sleep(.25)


def distance(a, b):
    return math.hypot(a['x'] - b['x'], a['z'] - b['z'])


def main():
    game = Game()
    if game.request('/v1/state')['screen'] != 'game':
        game.ui('close')
    game.command('tp @s 300 -60 300')
    game.command('fill 285 -60 290 340 -54 310 air')
    game.command('fill 285 -61 290 340 -61 310 stone')
    agent = game.request('/v1/agents/spawn', {'projectId': 'minecraft', 'name': 'Behavior fixture',
        'body': 'minecraft:fox', 'mode': 'creative', 'behavior': 'follow',
        'providerId': 'codex', 'model': 'gpt-6.1-sol', 'reasoningLevel': 'medium', 'permissionMode': 'accept-edits'})
    path = '/v1/agents/' + agent['id']
    (GAME / 'too-many-agents/behavior-fixture.json').write_text(json.dumps({'agentId': agent['id']}))

    def position():
        return next(e['position'] for e in game.request('/v1/state')['server']['entities']
                    if e.get('agent', {}).get('id') == agent['id'])

    def observed():
        return any(e.get('agent', {}).get('id') == agent['id']
                   for e in game.request('/v1/state')['server']['entities'])

    def behavior():
        return game.request(path + '/tool', {'tool': 'minecraft_behavior', 'arguments': {}})

    def transcript():
        return list(nested(game.request(path + '/transcript', {'includeNestedRows': 'true', 'summaryOnly': 'false'})['rows']))

    def idle():
        return game.request(path)['status'] == 'idle'

    wait(observed, 10)

    # A turn that does not use Minecraft must leave ambient movement running.
    token = 'NO_PHYSICAL_' + uuid.uuid4().hex
    game.request(path + '/message', {'delivery': 'send', 'text':
        'Use your shell tool to run `sleep 20`, then reply exactly ' + token +
        '. Do not use any Minecraft tools or make any file changes.'})
    wait(lambda: game.request(path)['activity'] == 'working')
    before = position()
    game.command('tp @s 313 -60 300')
    wait(lambda: distance(before, position()) > 3, 15)
    assert not behavior()['held'], behavior()
    wait(idle)
    assert any(token in r.get('text', '') for r in transcript() if r.get('role') == 'assistant')
    assert not any('minecraft_' in r.get('toolName', '') for r in transcript()), transcript()
    passed('world follow continues during a real turn that performs no Minecraft action')

    old = {r['id'] for r in transcript()}
    token = 'PHYSICAL_HOLD_' + uuid.uuid4().hex
    game.request(path + '/message', {'delivery': 'send', 'text':
        'First call ' + tool_name('minecraft_run') + ' exactly once with `return {position: bot.entity.position}`. '
        'After it succeeds, use your shell tool to run `sleep 20`. Then reply exactly ' + token +
        '. No other tools, retries or file changes.'})
    wait(lambda: any(r['id'] not in old and tool_name('minecraft_run') in r.get('toolName', '')
                    and r.get('status') == 'completed' for r in transcript()))
    assert game.request(path)['activity'] == 'working'
    assert behavior()['held'], behavior()
    game.command('tp @s 326 -60 300')
    held = position()
    until = time.monotonic() + 8
    while time.monotonic() < until:
        assert game.request(path)['activity'] == 'working'
        assert behavior()['held']
        assert distance(held, position()) < .25, (held, position())
        time.sleep(.3)
    passed('first script holds world behavior through the gap between physical calls for the whole turn')
    wait(idle)
    wait(lambda: not behavior()['held'], 5)
    wait(lambda: distance(held, position()) > 3, 15)
    passed('ambient follow resumes when the real BB turn finishes')
    changed = game.request(path + '/tool', {'tool': 'minecraft_behavior', 'arguments': {'behavior': 'wander'}})
    assert changed == {'behavior': 'wander', 'held': False}, changed
    assert game.request(path)['settings']['behavior'] == 'wander'
    assert 'box' not in game.request(path + '/tool', {'tool': 'minecraft_settings', 'arguments': {}})
    game.request(path + '/tool', {'tool': 'minecraft_behavior', 'arguments': {'behavior': 'spin'}}, error=True)
    passed('world behavior tool persists its setting, rejects project-only behaviors, and settings omit box')
    game.request(path + '/settings', {'behavior': 'stand'})
    game.request(path + '/archive', {})

    project = next(p for p in game.request('/v1/agents/projects')['projects']
                   if p.get('kind') == 'standard' and p.get('folder') == str(ROOT))
    pos = [300, -60, 300]
    game.command('tp @s 305 -60 300')
    game.station('give', kind='station', slot=0, settings={'name': 'Behavior ' + uuid.uuid4().hex[:6],
        'projectId': project['id'], 'radius': 2, 'height': 3, 'body': 'minecraft:pig',
        'behaviors': {s: {'type': 'wander'} for s in ['working', 'wants_you', 'idle']}})
    station = row(game.station('place', pos=pos, slot=0), pos)
    agent = game.request('/v1/agents/spawn', {'stationId': station['id'], 'name': 'Station behavior fixture',
        'providerId': 'codex', 'model': 'gpt-6.1-sol', 'reasoningLevel': 'medium'})
    path = '/v1/agents/' + agent['id']
    wait(observed, 10)
    game.request(path + '/tool', {'tool': 'minecraft_behavior', 'arguments': {}}, error=True)
    def inside():
        p = position()
        return 298 <= p['x'] <= 303 and 298 <= p['z'] <= 303 and -60 <= p['y'] <= -57
    wait(inside, 10)
    until = time.monotonic() + 8
    while time.monotonic() < until:
        assert inside(), position()
        time.sleep(.25)
    passed('stationed project ambient wander remains inside its area; physical behavior tool is denied')
    game.station('configure', pos=pos, settings={'behaviors': {s: {'type': 'follow'} for s in ['working', 'wants_you', 'idle']}})
    game.command('tp @s 318 -60 300')
    wait(lambda: position()['x'] > 310, 20)
    passed('changing station behavior applies immediately and follow can leave the area')
    game.station('configure', pos=pos, settings={'behaviors': {s: {'type': 'stand'} for s in ['working', 'wants_you', 'idle']}})
    wait(inside, 35)
    passed('ending follow returns the occupant to its station area')
    game.request(path + '/archive', {})
    game.station('break', pos=pos)


if __name__ == '__main__':
    main()
