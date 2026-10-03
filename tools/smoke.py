#!/usr/bin/env python3
"""Exercise the installed mod over real HTTP; optionally verify a command in-game."""
import argparse
import json
from pathlib import Path
import time
import urllib.error
import urllib.request
import uuid

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('game_dir', type=Path)
parser.add_argument('--command', action='store_true', help='send a private tellraw message in the open world')
args = parser.parse_args()
info = json.loads((args.game_dir / 'too-many-agents/connection.json').read_text())
assert info['url'].startswith('http://127.0.0.1:')
opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def request(path='/v1/state', body=None, headers=None, auth=True):
    values = {'Content-Type': 'application/json'}
    if auth:
        values['Authorization'] = 'Bearer ' + info['token']
    values.update(headers or {})
    req = urllib.request.Request(info['url'] + path, data=body, headers=values)
    try:
        with opener.open(req, timeout=6) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as error:
        return error.code, json.load(error)


def expect(name, status, **kwargs):
    actual, body = request(**kwargs)
    assert actual == status, (name, actual, body)
    print('PASS:', name)
    return body


state = expect('authenticated snapshot', 200)
assert state['protocol'] == 1 and state['state'] in ('starting', 'menu', 'world', 'multiplayer_unsupported')
expect('missing token rejected', 401, auth=False)
expect('browser origin rejected', 401, headers={'Origin': 'https://example.invalid'})
expect('unknown route rejected', 404, path='/v1/missing')
expect('malformed JSON rejected', 400, path='/v1/command', body=b'{')
expect('oversized request rejected', 413, path='/v1/command', body=b' ' * 8193)
expect('stale world rejected', 409, path='/v1/command', body=json.dumps({
    'session': 'not-the-current-world', 'command': 'tellraw @s "must not execute"'}).encode())

if args.command:
    assert state['state'] == 'world' and not state['paused'], 'Open and unpause a singleplayer world first'
    marker = 'Too Many Agents smoke ' + str(uuid.uuid4())
    expect('command dispatched', 200, path='/v1/command', body=json.dumps({
        'session': state['session'], 'command': 'tellraw @s ' + json.dumps({'text': marker})}).encode())
    for _ in range(25):
        _, current = request()
        if marker in current['recentChat']:
            print('PASS: command result observed in real game chat')
            break
        time.sleep(0.2)
    else:
        raise AssertionError('Command receipt returned, but game feedback was not observed')
print('Live smoke checks passed; no mock game or HTTP server used.')
