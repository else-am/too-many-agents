#!/usr/bin/env python3
"""Stop and lost-start-acknowledgment checks against an existing isolated test body."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request
from urllib.parse import urlparse
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent-id', required=True, help='An idle fixture in the guarded development world')
    args = parser.parse_args()
    setup = json.loads((ROOT / 'run/config/too-many-agents-bb.json').read_text())
    addresses = set()
    for path in (Path.home() / '.too-many-agents/bb').glob('*.json'):
        try:
            record = json.loads(path.read_text())
            url = urlparse(record['serverUrl'])
            if (record.get('instanceId') == setup['instanceId']
                    and record['protocol'] == 1 and record['expiresAt'] > time.time() * 1000
                    and url.scheme == 'http' and url.hostname in ('127.0.0.1', '::1')
                    and not (url.username or url.password or url.query or url.fragment)):
                addresses.add(record['serverUrl'])
        except (OSError, ValueError, KeyError, TypeError, AttributeError):
            continue
    if len(addresses) != 1:
        parser.error('The test game must have one reachable selected BB with its Minecraft plugin enabled')
    server_url = addresses.pop().rstrip('/')
    descriptor = json.loads((ROOT / 'run/too-many-agents/connection.json').read_text())
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, data=None):
        message = urllib.request.Request(descriptor['url'] + path,
            data=None if data is None else json.dumps(data).encode(),
            headers={'Authorization': 'Bearer ' + descriptor['token'], 'Content-Type': 'application/json'})
        with opener.open(message, timeout=120) as response:
            return json.load(response)

    def wait(check, label):
        deadline = time.monotonic() + 100
        while time.monotonic() < deadline:
            result = check()
            if result:
                return result
            time.sleep(.4)
        raise RuntimeError(label + ' timed out; fixture retained, do not repeat mutations')

    def bb(*command):
        env = dict(os.environ, BB_SERVER_URL=server_url)
        env.pop('BB_THREAD_ID', None)
        result = subprocess.run(['bb', *command, '--json'], env=env, capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return json.loads(result.stdout)

    request('/v1/dev')
    world = request('/v1/agents/projects')['world']
    folder = ROOT / 'run/saves/too-many-agents-development'
    assert world['directory'] == str(folder)
    path = '/v1/agents/' + args.agent_id
    agent = request(path)
    assert agent['thread']['status'] == 'idle' and agent['bodyLoaded'] and agent['minecraftAccess']
    thread = agent['threadId']
    observation = request(path + '/tool', {'tool': 'minecraft_observe', 'arguments': {}})
    target = observation['body']['position'].copy()
    target['x'] += 50
    request(path + '/message', {'text': 'Use one minecraft_run script to await bot.moveTo(new Vec3(' + ', '.join(str(target[k]) for k in 'xyz') +
        ')). Do not stop it yourself; this is an external BB stop check.'})
    def action():
        return request(path + '/tool', {'tool': 'minecraft_action_status', 'arguments': {}})
    wait(lambda: (a if (a := action()).get('type') == 'walk' and a.get('status') == 'running' else None), 'Physical walk')
    bb('thread', 'stop', thread)
    stopped = wait(lambda: (a if (a := action()).get('terminal') else None), 'Physical cancellation')
    assert stopped['status'] in ('interrupted', 'cancelled'), stopped
    wait(lambda: request(path).get('thread', {}).get('status') == 'idle', 'BB stopped')
    print('PASS: native BB stop cancels an observed running physical walk', flush=True)

    state = request('/v1/state')
    before = {a['id'] for a in request('/v1/agents')}
    def rejected(label, fields, expected):
        payload = {'protocol': 3, 'connectionId': agent['connectionId'], 'bbInstanceId': agent['bbInstanceId'], 'worldId': world['id'], 'worldSessionId': state['session'],
            'op': 'body.create', 'requestId': str(uuid.uuid4()), 'expiresAt': int(time.time() * 1000) + 10000,
            'agentId': args.agent_id, 'threadId': thread, 'settings': {'name': 'Must not be created'},
            'projectId': agent['projectId'], 'minecraftAccess': True, 'draft': {}, **fields}
        try:
            request('/v1/bb', payload)
        except urllib.error.HTTPError as error:
            assert expected in error.read().decode()
        else:
            raise AssertionError(label + ' was accepted')
    rejected('Wrong thread', {'threadId': 'thr_not_this_body'}, 'not the Minecraft')
    rejected('Stale world', {'worldSessionId': str(uuid.uuid4())}, 'world_session_changed')
    rejected('Expired creation', {'expiresAt': 0}, 'expired_before_execution')
    cancelled = str(uuid.uuid4())
    request('/v1/bb', {'protocol': 3, 'connectionId': agent['connectionId'], 'bbInstanceId': agent['bbInstanceId'], 'worldId': world['id'], 'worldSessionId': state['session'],
        'op': 'cancel', 'requestId': str(uuid.uuid4()), 'expiresAt': int(time.time() * 1000) + 10000,
        'cancelRequestId': cancelled})
    rejected('Cancelled creation', {'requestId': cancelled}, 'tool_cancelled')
    assert before == {a['id'] for a in request('/v1/agents')}
    print('PASS: wrong-thread, stale-world, expired and cancelled body creation produce no body', flush=True)

    # Emulate the exact disk state left if BB created the thread but its bind reply was lost.
    with opener.open(server_url + '/api/v1/threads/' + thread + '/plugin-metadata?pluginId=minecraft', timeout=20) as response:
        metadata = json.load(response)
    assert metadata['agentId'] == args.agent_id and metadata['worldId'] == world['id']
    threads_before = {row['id'] for row in bb('thread', 'list', '--project', agent['projectId'], '--include-hidden')}
    inventory = request(path + '/inventory', {})
    request('/v1/ui', {'action': 'dev_leave'})
    records = folder / 'too-many-agents/bb-bodies.json'
    saved = json.loads(records.read_text())
    backup = ROOT / 'run/too-many-agents/bb-recovery-backup.json'
    backup.write_text(json.dumps(saved, indent=2) + '\n')
    row = next(row for row in saved['agents'] if row['id'] == args.agent_id)
    assert row['threadId'] == thread
    row['threadId'], row['startNonce'] = '', metadata['nonce']
    records.write_text(json.dumps(saved, indent=2) + '\n')
    request('/v1/ui', {'action': 'dev_open_world', 'name': folder.name})
    restored = wait(lambda: (a if (a := request(path)).get('threadId') == thread and a.get('bodyLoaded') else None), 'Lost acknowledgment recovery')
    assert restored['body']['entityUuid'] == agent['body']['entityUuid']
    after_inventory = request(path + '/inventory', {})
    for key in ('entityUuid', 'selected', 'slots', 'equipment', 'carried'):
        assert inventory[key] == after_inventory[key]
    assert threads_before == {row['id'] for row in bb('thread', 'list', '--project', agent['projectId'], '--include-hidden')}
    assert before == {a['id'] for a in request('/v1/agents')}
    print('PASS: lost start acknowledgment rebinds the same BB thread/body on reopen without duplicate creation', flush=True)
    request(path + '/conversation-archive', {})
    print('PASS: recovery fixture archived', flush=True)


if __name__ == '__main__':
    main()
