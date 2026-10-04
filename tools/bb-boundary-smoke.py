#!/usr/bin/env python3
"""Live native UI and plugin-owned BB project/draft integration checks. Never retries mutations."""
import argparse
import json
import os
from pathlib import Path
import subprocess
import time
import urllib.request
import urllib.error
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--model', required=True)
    args = parser.parse_args()
    descriptor = json.loads((ROOT / 'run/too-many-agents/connection.json').read_text())
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def request(path, data=None):
        message = urllib.request.Request(descriptor['url'] + path,
            data=None if data is None else json.dumps(data).encode(),
            headers={'Authorization': 'Bearer ' + descriptor['token'], 'Content-Type': 'application/json'})
        try:
            with opener.open(message, timeout=120) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            raise RuntimeError(error.read().decode()) from error

    def wait(check, label, timeout=100):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            result = check()
            if result:
                return result
            time.sleep(.4)
        raise RuntimeError(label + ' timed out; inspect retained fixtures, do not repeat creation')

    def bb(*command):
        env = dict(os.environ)
        env.pop('BB_THREAD_ID', None)
        result = subprocess.run(['bb', *command, '--json'], env=env, capture_output=True, text=True, timeout=120)
        if result.returncode:
            raise RuntimeError(result.stdout + result.stderr)
        return json.loads(result.stdout)

    def ui(action, **fields):
        return request('/v1/ui', {'action': action, **fields})

    def widget(label):
        state = ui('widgets')
        return next((row for row in state.get('inventoryAgents', {}).get('widgets', [])
                     if row.get('panel') == 'chat' and row['label'] == label and row['visible']), None)

    def click(label):
        row = wait(lambda: (w if (w := widget(label)) and w['active'] else None), label + ' widget')
        ui('click', x=row['x'] + row['width'] / 2, y=row['y'] + row['height'] / 2)

    def type_into(label, text):
        click(label)
        ui('text', text=text)

    def get(agent):
        return request('/v1/agents/' + agent)

    def idle(agent):
        return wait(lambda: (a if (a := get(agent)).get('thread', {}).get('status') == 'idle' else None), 'Real provider turn')

    def archive(agent):
        request('/v1/agents/' + agent + '/conversation-archive', {})
        wait(lambda: not get(agent).get('bodyLoaded'), 'Body suspension')

    request('/v1/dev')
    projects = request('/v1/agents/projects')
    assert projects['world']['directory'] == str(ROOT / 'run/saves/too-many-agents-development')
    own = next(row['id'] for row in projects['projects'] if row.get('kind') == 'world')
    marker = 'boundary-' + uuid.uuid4().hex[:8]
    ui('spawn', projectId=own)
    type_into('Agent name', marker)
    type_into('Message to agent', 'Reply UI_FIRST_READY. Do not call tools.')
    click('Send')
    agent = wait(lambda: next((a for a in request('/v1/agents') if a.get('name') == marker), None), 'UI body creation')['id']
    print('Native UI fixture:', agent, flush=True)
    first = idle(agent)
    type_into('Message to agent', 'Reply UI_SECOND_READY. Do not call tools.')
    click('Send')
    wait(lambda: 'UI_SECOND_READY' in json.dumps(request('/v1/agents/' + agent + '/transcript', {})), 'Second native UI message')
    second = idle(agent)
    assert second['threadId'] == first['threadId'] and second['body']['entityUuid'] == first['body']['entityUuid']
    assert second['projectId'] == own
    print('PASS: actual native UI creation, first and subsequent messages keep one body/thread', flush=True)

    # An unstarted body keeps BB draft choices through physical archive and plugin reload.
    draft = request('/v1/agents/spawn', {'name': marker + '-draft', 'providerId': 'codex',
        'model': args.model, 'reasoningLevel': 'low', 'mode': 'creative'})['id']
    assert not get(draft).get('threadId')
    request('/v1/agents/' + draft + '/settings', {'reasoningLevel': 'medium', 'name': marker + '-draft-edited'})
    archive(draft)
    bb('plugin', 'reload', 'minecraft')
    def attached():
        env = dict(os.environ, BB_THREAD_ID=first['threadId'])
        result = subprocess.run(['bb', 'minecraft', 'bodies', '--json'], env=env, capture_output=True, text=True, timeout=15)
        return result.returncode == 0
    wait(attached, 'Plugin world reattachment')
    request('/v1/agents/' + draft + '/conversation-restore', {})
    wait(lambda: get(draft).get('bodyLoaded'), 'Unstarted body restoration')
    assert not get(draft).get('threadId')
    request('/v1/agents/' + draft + '/message', {'text': 'Reply DRAFT_READY. Do not call tools.'})
    started = idle(draft)
    assert started['name'] == marker + '-draft-edited' and started['executionOptions']['reasoningLevel'] == 'medium'
    archive(draft)
    print('PASS: unstarted body, opaque execution draft, archive/restore and reload survive before first send', flush=True)

    # Only this test's own repository and BB project are created or changed.
    folder = ROOT / 'run' / marker
    folder.mkdir()
    subprocess.run(['git', 'init', '--initial-branch=main', str(folder)], check=True, capture_output=True)
    (folder / 'README.md').write_text('Minecraft BB boundary integration fixture.\n')
    subprocess.run(['git', '-C', str(folder), 'add', 'README.md'], check=True)
    subprocess.run(['git', '-C', str(folder), '-c', 'user.name=Integration Fixture',
                    '-c', 'user.email=fixture@example.invalid', 'commit', '-m', 'Fixture'], check=True, capture_output=True)
    project = request('/v1/agents/projects', {'operation': 'create', 'name': marker, 'folder': str(folder)})['id']
    print('Isolated project fixture:', project, flush=True)
    wait(lambda: any(p['id'] == project for p in request('/v1/agents/projects')['projects']), 'Project UI projection')
    ui('spawn', projectId=project)
    wait(lambda: widget('Worktree'), 'Worktree availability')
    click('Worktree')
    type_into('Agent name', marker + '-worktree')
    type_into('Message to agent', 'Reply WORKTREE_READY. Do not call tools.')
    click('Send')
    worktree = wait(lambda: next((a for a in request('/v1/agents') if a.get('name') == marker + '-worktree'), None), 'Worktree body')['id']
    state = idle(worktree)
    environment = bb('environment', 'show', state['thread']['environmentId'])
    assert environment['projectId'] == project and environment['path'] != str(folder) and environment['branchName'] != 'main'
    archive(worktree)
    checkout = request('/v1/agents/spawn', {'name': marker + '-checkout', 'projectId': project,
        'worktree': False, 'providerId': 'codex', 'model': args.model, 'reasoningLevel': 'low',
        'initialTask': 'Reply CHECKOUT_READY. Do not call tools.'})['id']
    state = idle(checkout)
    environment = bb('environment', 'show', state['thread']['environmentId'])
    assert environment['path'] == str(folder)
    archive(checkout)
    print('PASS: native Worktree checkbox and project-folder choice use BB environments correctly', flush=True)
    personal = next(p['id'] for p in projects['projects'] if p.get('kind') == 'personal')
    no_project = request('/v1/agents/spawn', {'name': marker + '-personal', 'projectId': personal,
        'providerId': 'codex', 'model': args.model, 'reasoningLevel': 'low', 'minecraftAccess': False,
        'initialTask': 'Reply PERSONAL_READY. Do not call tools.'})['id']
    state = idle(no_project)
    assert state['projectId'] == personal and not state['minecraftAccess'] and state['bodyLoaded']
    archive(no_project)
    print('PASS: Personal project uses BB defaults with Minecraft access disabled', flush=True)
    result = ROOT / 'run/too-many-agents/bb-boundary-result.json'
    result.write_text(json.dumps({'agentId': agent, 'threadId': first['threadId'], 'projectId': project,
        'fixtures': [draft, worktree, checkout, no_project]}, indent=2) + '\n')
    print('PASS: boundary checks complete; native UI fixture remains for stop/recovery checks:', agent, flush=True)


if __name__ == '__main__':
    main()
