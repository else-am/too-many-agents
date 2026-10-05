#!/usr/bin/env python3
"""Live BB CLI/body lifecycle checks in the isolated development world. Retains fixtures on failure."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', required=True)
    parser.add_argument('--agent-id', help='Resume from an existing idle seed before creating any receiver')
    args = parser.parse_args()
    descriptor = json.loads((ROOT / 'run/too-many-agents/connection.json').read_text())
    assert descriptor['url'].startswith('http://127.0.0.1:')
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, data=None):
        message = urllib.request.Request(descriptor['url'] + path,
            data=None if data is None else json.dumps(data).encode(),
            headers={'Authorization': 'Bearer ' + descriptor['token'], 'Content-Type': 'application/json'})
        with opener.open(message, timeout=120) as response:
            return json.load(response)

    def wait(check, label, timeout=90):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = check()
            if result:
                return result
            time.sleep(.5)
        raise RuntimeError(label + ' timed out; inspect retained fixtures before retrying mutations')

    def bb(*command, actor=None):
        env = dict(os.environ)
        if actor:
            env['BB_THREAD_ID'] = actor
        else:
            env.pop('BB_THREAD_ID', None)
        result = subprocess.run(['bb', *command, '--json'], env=env, cwd=ROOT,
                                capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise RuntimeError('bb ' + ' '.join(command[:2]) + ': ' + result.stderr)
        return json.loads(result.stdout)

    request('/v1/dev')
    world = request('/v1/agents/projects')['world']
    assert world['directory'] == str(ROOT / 'run/saves/too-many-agents-development') and not world.get('needsDecision')
    marker = 'mc-' + uuid.uuid4().hex[:12]
    root = request('/v1/agents/' + args.agent_id) if args.agent_id else request('/v1/agents/spawn', {'name': 'Lifecycle sender', 'providerId': 'codex',
        'model': args.model, 'reasoningLevel': 'low', 'mode': 'creative',
        'initialTask': 'Reply READY. Do not call tools.'})
    root_id = root['id']
    get = lambda agent_id: request('/v1/agents/' + agent_id)
    root = wait(lambda: (a if (a := get(root_id)).get('threadId') and a.get('thread', {}).get('status') == 'idle' else None), 'Seed turn')
    print('Fixture sender:', root_id, root['threadId'], flush=True)
    spawned = bb('minecraft', 'spawn', '--name', 'Independent receiver', '--body', 'minecraft:pig',
        '--provider', 'codex', '--model', args.model, '--reasoning-level', 'low',
        '--environment', root['thread']['environmentId'], '--prompt', 'Reply READY. Do not call tools.', actor=root['threadId'])
    receiver_id, receiver_thread = spawned['agentId'], spawned['threadId']
    receiver = wait(lambda: (a if (a := get(receiver_id))['status'] == 'idle' else None), 'Independent turn')
    assert not receiver['parentThreadId'] and receiver['bodyLoaded']
    assert receiver['thread']['environmentId'] == root['thread']['environmentId']
    print('PASS: independent embodied BB thread and explicit environment reuse', flush=True)

    # Native reparenting must show through without a Minecraft parent tree.
    bb('thread', 'update', receiver_thread, '--parent-thread', root['threadId'])
    wait(lambda: get(receiver_id)['parentThreadId'] == root['threadId'], 'Reparent')
    bb('thread', 'update', receiver_thread, '--clear-parent-thread')
    wait(lambda: not get(receiver_id)['parentThreadId'], 'Clear parent')
    print('PASS: native BB reparenting reflected in Minecraft', flush=True)

    def inventory_contents():
        value = request('/v1/agents/' + receiver_id + '/inventory', {})
        return {key: value[key] for key in ('entityUuid', 'selected', 'slots', 'equipment', 'carried')}

    for queued in (False, True):
        token = marker + ('-queued' if queued else '-direct')
        command = ['thread', 'tell', receiver_thread, 'Reply ACK. Do not use tools. Test marker: ' + token, '--mode', 'auto']
        if queued:
            command.extend(['--send-at', '5s'])
        bb(*command, actor=root['threadId'])
        if queued:
            wait(lambda: any(token in json.dumps(row) for row in get(receiver_id).get('queuedMessages', [])), 'BB queue entry', timeout=4)
        wait(lambda: any(token in row for row in request('/v1/state')['recentChat']), 'Visible communication')
        wait(lambda: get(receiver_id).get('thread', {}).get('status') == 'idle', 'Message completion')
        assert sum(token in row for row in request('/v1/state')['recentChat']) == 1
    print('PASS: BB direct and queued agent messages shown once in world chat', flush=True)

    # Archiving drops inventory; restoring the body must not duplicate those items.
    path = '/v1/agents/' + receiver_id
    bb('thread', 'tell', receiver_thread, 'Use minecraft_action creative_item to create exactly 7 minecraft:diamond once. Poll minecraft_action_status until complete, then reply INVENTORY_READY. Do not repeat an action after an unknown outcome.', '--mode', 'auto')
    wait(lambda: (inv if 'minecraft:diamond' in json.dumps(inv := inventory_contents()) else None), 'Inventory stack')
    wait(lambda: get(receiver_id).get('thread', {}).get('status') == 'idle', 'Inventory turn completion')
    items_before = {e['uuid'] for e in request('/v1/state')['server']['entities'] if e['type'] == 'minecraft:item'}
    bb('thread', 'archive', receiver_thread)
    wait(lambda: get(receiver_id).get('conversationArchived', False) and not get(receiver_id).get('bodyLoaded', False), 'Archive suspension')
    dropped = wait(lambda: next((e for e in request('/v1/state')['server']['entities']
        if e['type'] == 'minecraft:item' and e['name'] == 'Diamond' and e['uuid'] not in items_before), None), 'Dropped diamonds')
    # Move the drop out of automatic pickup range before restoring the NPC.
    position = dropped['position']
    request('/v1/command', {'session': request('/v1/state')['session'],
        'command': f"tp {dropped['uuid']} {position['x'] + 12} {position['y']} {position['z'] + 12}"})
    wait(lambda: any(e['uuid'] == dropped['uuid'] and e['position']['x'] > position['x'] + 10
        for e in request('/v1/state')['server']['entities']), 'Drop moved out of pickup range')
    bb('thread', 'unarchive', receiver_thread)
    wait(lambda: get(receiver_id).get('bodyLoaded', False) and not get(receiver_id).get('conversationArchived', False), 'Unarchive restoration')
    empty_inventory = inventory_contents()
    assert all(slot['count'] == 0 for slot in empty_inventory['slots'])
    assert all(slot['count'] == 0 for slot in empty_inventory['equipment'])
    assert empty_inventory['carried']['count'] == 0
    assert get(receiver_id)['body']['entityUuid'] == receiver['body']['entityUuid']
    print('PASS: archive drops inventory; unarchive preserves body UUID without duplicating items', flush=True)

    # A plugin reload must reconnect the same bodies without replaying chat.
    bb('plugin', 'reload', 'minecraft')
    wait(lambda: get(root_id).get('status') != 'disconnected', 'Plugin reconnect')
    time.sleep(2)
    assert sum(marker + '-queued' in row for row in request('/v1/state')['recentChat']) == 1
    assert get(receiver_id).get('bodyLoaded', False)
    print('PASS: plugin reload retains bodies and does not replay messages', flush=True)

    # Ordinary BB thread operations while Minecraft is disconnected reconcile on return.
    request('/v1/ui', {'action': 'dev_leave'})
    bb('thread', 'archive', receiver_thread)
    request('/v1/ui', {'action': 'dev_open_world', 'name': 'too-many-agents-development'})
    wait(lambda: get(receiver_id).get('conversationArchived', False) and not get(receiver_id).get('bodyLoaded', False), 'Offline archive reconciliation')
    bb('thread', 'unarchive', receiver_thread)
    wait(lambda: get(receiver_id).get('bodyLoaded', False), 'Restore after reconnect')
    assert inventory_contents() == empty_inventory
    print('PASS: offline archive reconciles and does not restore dropped inventory', flush=True)

    bb('thread', 'delete', receiver_thread, '--yes')
    wait(lambda: all(a['id'] != receiver_id for a in request('/v1/agents')), 'Deleted association cleanup')
    wait(lambda: all(e.get('agent', {}).get('id') != receiver_id for e in request('/v1/state')['server']['entities']), 'Deleted body cleanup')
    print('PASS: native BB delete clears the association and physical body', flush=True)
    bb('thread', 'archive', root['threadId'])
    wait(lambda: not get(root_id)['bodyLoaded'], 'Sender archive')
    result = ROOT / 'run/too-many-agents/bb-lifecycle-result.json'
    result.write_text(json.dumps({'sender': root_id, 'threadId': root['threadId'], 'deletedReceiver': receiver_id, 'marker': marker}, indent=2) + '\n')
    print('PASS: BB lifecycle checks complete; archived sender retained in', result, flush=True)


if __name__ == '__main__':
    main()
