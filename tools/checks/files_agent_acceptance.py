#!/usr/bin/env python3
"""Exercise an agent's real environment boundary through screen tools and native input."""
import argparse
import base64
from pathlib import Path
import time
from urllib.parse import quote, unquote, urlsplit, parse_qs

import screens_acceptance as native
from screens_tools_acceptance import call, error, success


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', required=True)
    parser.add_argument('--screen', required=True)
    parser.add_argument('--environment', type=Path, required=True,
                        help='Fixture agent environment, nested in a repository with an outside.txt sibling')
    args = parser.parse_args()
    root = args.environment.resolve()
    assert (root / 'inside.txt').is_file() and (root.parent / 'outside.txt').is_file()
    artifacts = native.GAME / 'too-many-agents/files-fixtures' / root.parent.parent.name / 'agent-screenshots'
    artifacts.mkdir(parents=True, exist_ok=True)
    native.command('gamemode creative @s')
    native.command('tp @s 168 -60 165')
    native.command('item replace entity @s weapon.mainhand with air')

    def row():
        return native.screen_row(args.screen)

    def ready():
        native.wait_for(lambda state: any(item['id'] == args.screen and item.get('fileGrant')
            and item['painted'] and not item['loading'] and not item.get('crashed')
            for item in state['screens']), timeout=30)

    def capture(name):
        time.sleep(1)
        _, result = call(args.agent, 'screen_screenshot', screen=args.screen)
        image = next(item['data'] for item in result['content'] if item['type'] == 'image')
        (artifacts / (name + '.png')).write_bytes(base64.b64decode(image))

    def reload_world():
        native.ui('dev_leave')
        native.ui('dev_open_world', name='too-many-agents-development')
        deadline = time.monotonic() + 30
        while native.cli('state').get('state') != 'world':
            assert time.monotonic() < deadline, 'Guarded world did not reopen'
            time.sleep(.2)

    def open_file(target, kind='file', **fields):
        opened = success(args.agent, 'screen_open', screen=args.screen, kind=kind, target=target, **fields)
        ready()
        location = urlsplit(opened['url'])
        assert Path(unquote(location.path)).is_relative_to(root), opened
        assert row()['openedBy'], 'Agent open became human-owned'
        return opened

    opened = open_file(str(root / 'folder/selected.txt'))
    assert Path(unquote(urlsplit(opened['url']).path)) == root, 'Repository promotion escaped the environment'
    assert parse_qs(urlsplit(opened['url']).query)['open'] == ['folder/selected.txt']
    capture('nested-file')
    open_file(str(root / 'inside-link'))
    capture('inside-symlink')
    if (root / 'literal?#.txt').exists():
        opened = open_file(str(root / 'literal?#.txt'))
        assert parse_qs(urlsplit(opened['url']).query)['open'] == ['literal?#.txt']
    shared = 'tma://files' + quote(str(root), safe='/') + '?open=inside.txt#L20-L40'
    opened = open_file(shared)
    assert opened['url'] == shared
    capture('shared-lines')
    selected = root / 'inside.txt'
    original = selected.read_text()
    revision = native.ui('screens')['fileWatches']['revision']
    try:
        selected.write_text(original.replace('Line 20', 'LIVE Line 20'))
        native.wait_for(lambda state: state['fileWatches']['revision'] > revision)
        assert row()['url'] == shared
        capture('agent-live-lines')
    finally:
        selected.write_text(original)
    error('view_only', args.agent, 'screen_cdp', screen=args.screen,
          method='Runtime.evaluate', params={'expression': 'document.body.innerText'})

    for target in [str(root.parent / 'outside.txt'), str(root / '../outside.txt'),
                   str(root / 'escape-link'), str(root / 'escape-dir'),
                   'tma://files' + quote(str(root.parent), safe='/') + '?open=outside.txt',
                   'tma://files' + quote(str(root), safe='/') + '?open=escape-link']:
        value, envelope = call(args.agent, 'screen_open', screen=args.screen, kind='file', target=target,
                               environmentRoot=str(root.parent), fileScope={'root': str(root.parent)})
        assert envelope.get('isError') and value.get('error'), (target, value)
        assert row()['url'] == opened['url'] and row().get('fileGrant'), 'Refusal replaced the authorized page'
    print('PASS: nested repository clamp, shared lines, in-root links, outside paths and forged arguments', flush=True)

    opened = open_file(str(root), kind='changes', base='uncommitted')
    assert parse_qs(urlsplit(opened['url']).query)['base'] == ['uncommitted']
    capture('changes-confined-to-environment')
    owner = row()['openedBy']
    reload_world()
    ready()
    assert row()['url'] == opened['url'] and row()['openedBy'] == owner
    capture('restored-agent-view')
    print('PASS: saved agent view revalidates its environment after world reload', flush=True)

    native.screen_control(args.screen, 'address')
    human = 'tma://files' + quote(str(root.parent), safe='/') + '?open=outside.txt'
    native.ui('dev_char', text=human)
    native.ui('dev_key', key=257)
    native.wait_for(lambda state: any(item['id'] == args.screen and item['url'] == human
        and item.get('fileGrant') and not item['openedBy'] for item in state['screens']), timeout=30)
    capture('human-takeover-outside-agent-root')
    open_file(shared)
    assert row()['openedBy'] == owner
    capture('agent-replacement-confined-again')

    # A saved location must not acquire a broader grant if the environment is replaced by a link.
    held = root.with_name(root.name + '-held')
    outside = root.parent.parent / 'replacement'
    outside.mkdir(exist_ok=True)
    (outside / 'inside.txt').write_text('OUTSIDE REPLACEMENT MUST NOT APPEAR\n')
    assert not held.exists()
    root.rename(held)
    try:
        root.symlink_to(outside, target_is_directory=True)
        time.sleep(2)
        capture('root-replaced-live')
        reload_world()
        native.wait_for(lambda state: any(item['id'] == args.screen and not item.get('fileGrant')
            and item.get('fileError') and item['painted'] for item in state['screens']), timeout=30)
        capture('root-replaced-restore-refused')
    finally:
        if root.is_symlink():
            root.unlink()
        held.rename(root)
    native.screen_control(args.screen, 'reload')
    ready()
    assert row()['url'] == shared and row()['openedBy'] == owner
    print('PASS: replacing the environment with an outside link cannot widen live or restored access', flush=True)
    native.menu_action(args.screen, 'turn_off')
    native.wait_for(lambda state: state['fileWatches']['subscriptions'] == 0, timeout=10)
    print('PASS: human takeover, agent replacement and watcher cleanup; inspect captured content', flush=True)
    print('Screenshots: ' + str(artifacts), flush=True)


if __name__ == '__main__':
    main()
