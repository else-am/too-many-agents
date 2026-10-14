#!/usr/bin/env python3
"""Exercise files and changes through native input; save previews for visual review."""
import argparse
import base64
import json
from pathlib import Path
import tempfile
import time
from urllib.parse import quote, urlencode, urlsplit, parse_qs

import screens_acceptance as native
from screens_tools_acceptance import call, error
from files_view_fixture import create


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', required=True)
    parser.add_argument('--screen', required=True, help='North-facing 4 by 3 fixture screen')
    args = parser.parse_args()
    # Keep the non-repository case outside the checkout: ancestor Git discovery is intentional.
    fixture = create(Path(tempfile.gettempdir()))
    artifacts = native.GAME / 'too-many-agents/files-fixtures' / Path(fixture['folder']).name / 'screenshots'
    artifacts.mkdir(parents=True)
    plain = Path(fixture['folder']) / 'plain'
    plain.mkdir()
    for index in range(12_031):
        (plain / f'file-{index:05}.txt').write_text(f'fixture {index}\n')
    native.command('gamemode creative @s')
    native.command('tp @s 168 -60 165')
    native.command('item replace entity @s weapon.mainhand with air')

    def row():
        return native.screen_row(args.screen)

    def wait_url(url):
        native.wait_for(lambda state: any(item['id'] == args.screen and item['url'] == url
            and item.get('fileGrant') and item['painted'] and not item['loading']
            and not item.get('crashed') for item in state['screens']), timeout=30)

    def show(path='', mode='', base='', lines='', folder=None):
        url = 'tma://files' + quote(str(Path(folder or fixture['repo']).resolve()), safe='/')
        values = {key: value for key, value in [('open', path), ('mode', mode), ('base', base)] if value}
        if values:
            url += '?' + urlencode(values, quote_via=quote)
        if lines:
            url += '#' + lines
        native.screen_control(args.screen, 'address')
        native.ui('dev_char', text=url)
        native.ui('dev_key', key=257)
        wait_url(url)
        time.sleep(1)
        return url

    def click(x, y):
        item = row()
        width, height = map(int, item['pixels'].split('x'))
        cx, cy, cz = map(float, item['pageCenter'].split(','))
        native.aim(cx + (.5 - x / width) * item['pageWidth'],
                   cy + (.5 - y / height) * item['pageHeight'], cz)
        native.click(0)

    def capture(name):
        assert not row().get('crashed'), 'The renderer crashed'
        _, result = call(args.agent, 'screen_screenshot', screen=args.screen)
        images = [part['data'] for part in result.get('content', []) if part['type'] == 'image']
        assert images, result
        (artifacts / (name + '.png')).write_bytes(base64.b64decode(images[0]))
        assert 'UNSAFE' not in row().get('title', ''), 'Preview executed fixture script'
        assert not Path(fixture['marker']).exists(), 'A Git helper executed'

    # A real click followed by pushState and a file fetch exercises native input timing,
    # unlike opening a selected file directly through the address bar.
    url = show(folder=plain)
    click(110, 156)
    wait_url(url + '?open=file-00000.txt')
    time.sleep(1)
    capture('tree-selection')
    native.screen_control(args.screen, 'back')
    wait_url(url)
    click(110, 116)
    native.ui('dev_char', text='file-12030')
    time.sleep(2)
    capture('large-search')
    click(110, 156)
    wait_url(url + '?open=file-12030.txt')
    time.sleep(1)
    capture('large-search-selection')
    print('PASS: native tree selection, back and search beyond 10,000 entries', flush=True)

    for path, name in [('src/value.ts', 'syntax'), ('source.html', 'html-source'),
                       ('vector.svg', 'hostile-svg-source'), ('pixel.png', 'image'),
                       ('binary.dat', 'binary'), ('large.txt', 'size-limit'),
                       ('deleted.txt', 'deleted')]:
        show(path)
        capture(name)
    show('README.md')
    capture('markdown-source')
    click(939, 116)
    time.sleep(1)
    capture('markdown-preview')
    shared = show('text.txt', lines='L20-L40')
    capture('line-range')
    error('view_only', args.agent, 'screen_cdp', screen=args.screen,
          method='Runtime.evaluate', params={'expression': 'document.body.innerText'})
    print('PASS: preview requests, script sentinels and built-in CDP refusal; inspect saved previews', flush=True)

    # This human fixture is outside the agent's checkout. A copied location cannot confer access.
    for kind in ('file', 'changes'):
        value, envelope = call(args.agent, 'screen_open', screen=args.screen, kind=kind, target=shared)
        assert envelope.get('isError') and value.get('error'), value
        assert row()['url'] == shared and row().get('fileGrant') and not row()['openedBy']
    error('view_only', args.agent, 'screen_cdp', screen=args.screen,
          method='Runtime.evaluate', params={'expression': 'document.body.innerText'})
    for target in ['tma://files/_api/file', 'tma://files/_app/viewer.js',
                   'tma://user@files/tmp', 'tma://files:42/tmp', 'tma://files/tmp?pick',
                   'tma://files/tmp?open=../secret', 'tma://files/tmp?root=/etc',
                   'tma://files/tmp#L0', 'tma://files/tmp#L40-L20', 'file:///etc/hosts']:
        error('invalid_request', args.agent, 'screen_open', screen=args.screen, kind='file', target=target)
        assert row()['url'] == shared, 'Rejected location changed the screen'
    show('text.txt', lines='L20-L40')
    capture('shared-location-human-authorized')
    selected = Path(fixture['repo']) / 'text.txt'
    original = selected.read_text()
    revision = native.ui('screens')['fileWatches']['revision']
    selected.write_text(original.replace('Line 20\n', 'LIVE selected line 20\n'))
    native.wait_for(lambda state: state['fileWatches']['revision'] > revision)
    time.sleep(1)
    assert urlsplit(row()['url']).fragment == 'L20-L40'
    capture('shared-line-range-after-edit')
    selected.write_text(original)
    print('PASS: copied outside location is refused without changing the human view; line highlights survive edits', flush=True)

    show(mode='changes')
    time.sleep(1)
    capture('branch-changes')
    click(225, 67)
    click(221, 110)
    native.wait_for(lambda state: any(item['id'] == args.screen and
        parse_qs(urlsplit(item['url']).query).get('base') == ['uncommitted']
        for item in state['screens']))
    time.sleep(1)
    capture('uncommitted-changes')
    for base, name in [(fixture['main'], 'explicit-base'), ('not-a-real-ref', 'invalid-base')]:
        show(mode='changes', base=base)
        capture(name)
    show(mode='changes', folder=fixture['unborn'])
    capture('unborn')
    print('PASS: native base selection and safe changes responses; inspect file sets and error previews', flush=True)
    print(json.dumps({'fixture': fixture['folder'], 'screenshots': str(artifacts)}), flush=True)


if __name__ == '__main__':
    main()
