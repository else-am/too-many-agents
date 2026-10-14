#!/usr/bin/env python3
"""Exercise live file updates and watcher lifecycle; save frames for visual acceptance."""
import argparse
import base64
import json
import os
from pathlib import Path
import subprocess
import tempfile
import time
from urllib.parse import quote, urlencode

import screens_acceptance as native
from screens_tools_acceptance import call


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', required=True)
    parser.add_argument('--screen', required=True, help='North-facing 4 by 3 fixture screen')
    args = parser.parse_args()
    folder = Path(tempfile.mkdtemp(prefix='files-live-')).resolve()
    repo = folder / 'repo'
    repo.mkdir()
    artifacts = native.GAME / 'too-many-agents/files-fixtures' / folder.name / 'screenshots'
    artifacts.mkdir(parents=True)
    environment = {k: v for k, v in os.environ.items() if not k.startswith('GIT_')}
    environment.update(GIT_CONFIG_NOSYSTEM='1', GIT_CONFIG_GLOBAL=os.devnull, GIT_TERMINAL_PROMPT='0')

    def git(*arguments, cwd=repo):
        subprocess.run(['git', '-c', 'user.name=Files acceptance', '-c',
                        'user.email=files-acceptance@example.invalid', *arguments],
                       cwd=cwd, env=environment, check=True, capture_output=True)

    def text(label):
        return ''.join(f'{label} line {line:03}\n' for line in range(1, 181))

    git('init', '--initial-branch=main')
    (repo / 'open.txt').write_text(text('BEFORE'))
    (repo / '.gitignore').write_text('ignored/\n')
    (repo / 'ignored').mkdir()
    (repo / 'ignored/nested.txt').write_text('Ignored content\n')
    git('add', '.')
    git('commit', '-m', 'Initial fixture')
    linked = folder / 'linked'
    git('worktree', 'add', '-b', 'linked', str(linked))
    native.command('tp @s 168 -60 165')
    native.command('item replace entity @s weapon.mainhand with air')

    def row():
        return native.screen_row(args.screen)

    def capture(name):
        _, result = call(args.agent, 'screen_screenshot', screen=args.screen)
        images = [item['data'] for item in result.get('content', []) if item['type'] == 'image']
        assert images, result
        (artifacts / (name + '.png')).write_bytes(base64.b64decode(images[0]))
        assert not row().get('crashed')

    def show(root=repo, path='open.txt', changes=False):
        query = {'open': path}
        if changes:
            query.update(mode='changes', base='uncommitted')
        url = 'tma://files' + quote(str(root), safe='/') + '?' + urlencode(query)
        native.screen_control(args.screen, 'address')
        native.ui('dev_char', text=url)
        native.ui('dev_key', key=257)
        native.wait_for(lambda state: any(item['id'] == args.screen and item['url'] == url
            and item.get('fileGrant') and item['painted'] and not item['loading']
            for item in state['screens']), timeout=30)
        time.sleep(2)

    def update(action, name):
        before = native.ui('screens')['fileWatches']['revision']
        action()
        native.wait_for(lambda state: state['fileWatches']['revision'] > before, timeout=10)
        time.sleep(1)
        capture(name)

    show()
    item = row()
    cx, cy, cz = map(float, item['pageCenter'].split(','))
    native.aim(cx, cy, cz)
    native.click(0)
    native.ui('dev_scroll', y=-12)
    capture('before')
    update(lambda: (repo / 'open.txt').write_text(text('FIRST EDIT')), 'first-edit')
    update(lambda: (repo / 'open.txt').write_text(text('SECOND EDIT')), 'second-edit')
    update(lambda: (repo / 'created.txt').write_text('Created\n'), 'created')
    update(lambda: (repo / 'created.txt').rename(repo / 'renamed.txt'), 'renamed')
    update(lambda: (repo / 'open.txt').unlink(), 'deleted')
    update(lambda: (repo / 'open.txt').write_text(text('RECREATED')), 'recreated')
    show(path='ignored/nested.txt')
    update(lambda: (repo / 'another.txt').write_text('Another\n'), 'ignored-stays-expanded')
    show(changes=True)
    capture('changes-before-commit')

    def commit():
        git('add', '.')
        git('commit', '-m', 'Commit live changes')

    update(commit, 'changes-after-commit')
    show(root=linked, changes=True)
    update(lambda: (linked / 'open.txt').write_text('LINKED EDIT\n'), 'linked-edit')
    update(lambda: git('commit', '-am', 'Commit linked edit', cwd=linked), 'linked-after-commit')
    show(root=linked)
    native.aim(168, -58, 150)
    native.wait_for(lambda state: not next(item for item in state['screens']
        if item['id'] == args.screen)['live'] and state['fileWatches']['threads'] == 0)
    (linked / 'open.txt').write_text('EDITED WHILE ASLEEP\n')
    native.aim(cx, cy, cz)
    native.wait_for(lambda state: state['fileWatches']['threads'] > 0)
    time.sleep(2)
    capture('wake')
    print('PASS: repeated notifications, linked metadata, and sleep/wake lifecycle', flush=True)

    outside = folder / 'outside'
    outside.mkdir()
    (outside / 'open.txt').write_text('OUTSIDE SECRET MUST NOT APPEAR\n')
    linked.rename(folder / 'moved')
    linked.symlink_to(outside, target_is_directory=True)
    time.sleep(3)
    capture('root-replaced')
    native.menu_action(args.screen, 'turn_off')
    native.wait_for(lambda state: state['fileWatches']['threads'] == 0
        and state['fileWatches']['subscriptions'] == 0)
    print('PASS: closing the last files view releases subscriptions and watcher threads', flush=True)
    print(json.dumps({'fixture': str(folder), 'screenshots': str(artifacts)}, indent=2))
    print('Inspect frames: retained line position, edits/tree changes, empty committed diffs, wake content, and no outside secret.')


if __name__ == '__main__':
    main()
