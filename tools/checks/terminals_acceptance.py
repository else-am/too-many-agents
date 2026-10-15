#!/usr/bin/env python3
"""Exercise native picker actions, a real BB shell, and terminal screen permissions in the guarded client."""
import argparse
import base64
import json
from pathlib import Path
import subprocess
import time
from urllib.parse import quote
import screens_acceptance as native
from screens_tools_acceptance import call, error, evaluate, success
from screen_web_fixture import fixture_url


def bb(*args):
    result = subprocess.run(['bb', *args, '--json'], check=True, capture_output=True, text=True)
    return json.loads(result.stdout)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', required=True)
    parser.add_argument('--screen', required=True, help='North-facing fixture screen')
    parser.add_argument('--environment', required=True, help="The fixture agent's BB environment")
    args = parser.parse_args()
    screen, agent = args.screen, args.agent
    folder = native.GAME / 'too-many-agents/terminal-fixtures' / str(time.time_ns())
    (folder / 'child').mkdir(parents=True)
    (folder / 'note.txt').write_text('Terminal screen acceptance fixture\n')
    artifacts = folder / 'screenshots'
    artifacts.mkdir()
    terminals = []

    def row():
        return native.screen_row(screen)

    def wait(predicate, label, seconds=30):
        deadline = time.monotonic() + seconds
        while time.monotonic() < deadline:
            try:
                item = row()
                if predicate(item): return item
            except StopIteration: pass
            except AssertionError as failure:
                if 'Open a local singleplayer world first' not in str(failure): raise
            time.sleep(.1)
        raise AssertionError(label)

    def picker(path):
        return wait(lambda r: r.get('picker') and r['picker']['path'] == str(path.resolve())
                    and not r['picker']['loading'] and r['painted'], 'Picker did not open')

    def address(url):
        native.ui('dev_focus', active=True)
        native.screen_control(screen, 'address')
        assert native.ui('screens')['input'] == 'address'
        native.ui('dev_char', text=url)
        native.ui('dev_key', key=257)
        return wait(lambda r: r['url'] == url and r['painted'] and not r['loading'], 'Location did not load')

    def uri(path): return 'tma://files' + quote(str(path.resolve()), safe='/')

    def capture():
        native.aim(*map(float, row()['pageCenter'].split(',')))
        native.ui('dev_focus', active=True)
        native.ui('dev_key', key=82)
        assert native.ui('screens')['input'] == 'captured'

    def release(): native.ui('dev_key', key=256)

    def command(text):
        native.ui('dev_char', text=text)
        native.ui('dev_key', key=257)

    def screenshot(name):
        _, result = call(agent, 'screen_screenshot', screen=screen)
        data = next(item['data'] for item in result['content'] if item['type'] == 'image')
        (artifacts / (name + '.png')).write_bytes(base64.b64decode(data))

    def read(terminal):
        data = bb('terminal', 'output', terminal, '--tail-bytes', '262144')
        return b''.join(base64.b64decode(chunk['dataBase64']) for chunk in data['chunks']).decode('utf-8', errors='replace')

    def output(terminal, marker):
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            if marker in read(terminal): return
            time.sleep(.2)
        raise AssertionError('Shell output did not contain ' + marker)

    def click_box(box):
        native.ui('dev_focus', active=True)
        native.aim(box['x'], box['y'], box['z'])
        native.click(0)

    if native.ui('widgets')['screen'] != 'game': release()
    elif native.ui('screens')['input'] in ('captured', 'typing', 'address'): release()
    native.command('item replace entity @s weapon.mainhand with air')
    panel = row()
    assert panel['facing'] == 'north' and panel['up'] == 'up', 'Use an upright north-facing fixture panel'
    x, y, z = map(int, panel['pos'].split(','))
    width = int(panel['blocks'].split('x')[0])
    native.command(f'tp @s {x + 1 - width / 2} {y - 2} {z - 2}')
    native.ui('dev_focus', active=True)
    time.sleep(1)
    wait(lambda r: r.get('view') and 'pageCenter' in r, 'Fixture screen did not wake')
    try:
        address(uri(folder) + '?pick')
        picker(folder)
        child = next(item for item in row()['picker']['rows'] if item['name'] == 'child')
        native.ui('dev_focus', active=True)
        native.aim(child['x'], child['y'], child['z'])
        wait(lambda r: any(item['name'] == 'child' and item['hovered'] for item in r['picker']['rows']),
             'Folder hover did not reach the picker')
        child = next(item for item in row()['picker']['rows'] if item['name'] == 'child')
        assert child['files']['visible'] and child['terminal']['visible']
        screenshot('picker-actions')
        click_box(child['files'])
        wait(lambda r: r['url'] == uri(folder / 'child') and r['browser'] and r['painted'], 'Row Files did not open')
        native.screen_control(screen, 'back')
        picker(folder)
        capture()
        native.ui('dev_key', key=268)  # Home selects the first folder.
        native.ui('dev_key', key=258)  # Files.
        assert row()['picker']['action'] == 'files'
        native.ui('dev_key', key=258)  # Terminal.
        assert row()['picker']['action'] == 'terminal'
        native.ui('dev_key', key=257)
        opened = wait(lambda r: r['url'].startswith('tma://terminal/') and r['painted'] and not r['loading'], 'Row Terminal did not open')
        child_terminal = opened['url'].rsplit('/', 1)[1]
        terminals.append(child_terminal)
        assert bb('terminal', 'show', child_terminal)['initialCwd'] == str((folder / 'child').resolve())
        if native.ui('screens')['input'] == 'captured': release()
        native.screen_control(screen, 'back')
        picker(folder)
        assert bb('terminal', 'show', child_terminal)['status'] == 'running'
        print('PASS: hovered Files action, keyboard Terminal action, chosen cwd, Back detaches without stopping', flush=True)

        native.screen_control(screen, 'picker_terminal')
        opened = wait(lambda r: r['url'].startswith('tma://terminal/') and r['painted'] and not r['loading'], 'Footer Terminal did not open')
        terminal = opened['url'].rsplit('/', 1)[1]
        terminals.append(terminal)
        session = bb('terminal', 'show', terminal)
        assert session['initialCwd'] == str(folder.resolve())
        time.sleep(1)
        capture()
        command("printf 'NATIVE_%s\\n' INPUT_OK; pwd")
        output(terminal, 'NATIVE_INPUT_OK\r\n')
        output(terminal, str(folder.resolve()) + '\r\n')
        command('sleep 20')
        time.sleep(.3)
        native.ui('dev_key', key=67, modifiers=2)  # Ctrl-C must signal the shell.
        command("printf 'SIGNAL_%s\\n' OK")
        output(terminal, 'SIGNAL_OK\r\n')
        screenshot('terminal-shell')
        release()
        error('view_only', agent, 'screen_cdp', screen=screen, method='Runtime.evaluate', params={'expression': '1'})
        previous = row()['url']
        error('no_access', agent, 'screen_open', screen=screen, kind='terminal', target=terminal)
        assert row()['url'] == previous
        print('PASS: native input, Ctrl-C, output and machine-scoped terminal agent refusal', flush=True)

        # The shell survives world save/reopen; no new terminal is created to restore the screen.
        native.ui('dev_leave')
        native.ui('dev_open_world', name='too-many-agents-development')
        wait(lambda r: r['url'] == previous and r['browser'] and r['painted'] and not r['loading'], 'Terminal restore failed')
        time.sleep(1)
        capture()
        command("printf 'RESTORED_%s\\n' OK")
        output(terminal, 'RESTORED_OK\r\n')
        release()
        print('PASS: world reopen reconnects to the same running terminal', flush=True)

        owned = bb('terminal', 'create', '--environment', args.environment, '--title', 'TMA terminal acceptance')
        owned_id = owned['id']
        terminals.append(owned_id)
        success(agent, 'screen_open', screen=screen, kind='terminal', target=owned_id)
        error('view_only', agent, 'screen_cdp', screen=screen, method='Input.insertText', params={'text': 'echo forbidden'})
        error('view_only', agent, 'screen_cdp', screen=screen, method='Target.getTargets')
        success(agent, 'screen_close', screen=screen)
        assert bb('terminal', 'show', owned_id)['status'] in ('running', 'starting')
        success(agent, 'screen_open', screen=screen, kind='terminal', target='tma://terminal/' + owned_id)
        time.sleep(1)
        capture()
        command("printf 'HUMAN_%s\\n' ON_AGENT_VIEW")
        output(owned_id, 'HUMAN_ON_AGENT_VIEW\r\n')
        command('exit')
        deadline = time.monotonic() + 10
        while bb('terminal', 'show', owned_id)['status'] != 'exited':
            assert time.monotonic() < deadline
            time.sleep(.2)
        time.sleep(.5)
        screenshot('terminal-closed')
        release()
        assert row()['url'] == 'tma://terminal/' + owned_id
        print('PASS: environment-scoped agent attach, CDP refusals, screen close preserves session, human input, exited output retained', flush=True)

        web = folder / 'web.html'
        web.write_text('<!doctype html><title>Terminal boundary fixture</title>')
        success(agent, 'screen_open', screen=screen, kind='url', target=fixture_url(web))
        request = json.dumps({'op': 'input', 'terminalId': terminal, 'dataBase64': base64.b64encode(b'echo UNSAFE\n').decode()})
        refused = evaluate(agent, screen, '''new Promise(resolve => {
            if (!window.cefQuery) return resolve('absent');
            cefQuery({request: 'screen-terminal:' + %s,
                onSuccess: () => resolve('UNSAFE'), onFailure: () => resolve('refused')});
        })''' % json.dumps(request), awaitPromise=True)
        assert refused['result']['result']['value'] in ('absent', 'refused'), refused
        print('PASS: web scripts cannot use the terminal input bridge', flush=True)
        address(uri(folder) + '?pick')
        print(json.dumps({'fixture': str(folder), 'screenshots': str(artifacts)}), flush=True)
    finally:
        for terminal in terminals:
            try: bb('terminal', 'close', terminal)
            except subprocess.CalledProcessError: pass


if __name__ == '__main__': main()
