#!/usr/bin/env python3
"""Exercise the human picker through native Minecraft input, without built-in CDP."""
import argparse
import base64
from pathlib import Path
import struct
import sys
import time
from urllib.parse import quote
import zlib
import screens_acceptance as native
from screens_tools_acceptance import call, error, success
from files_view_fixture import create


def corner(png):
    """Read one background pixel from the screenshot's first PNG scanline."""
    channels = {2: 3, 6: 4}[png[25]]
    compressed = bytearray()
    offset = 8
    while offset < len(png):
        length = struct.unpack_from('>I', png, offset)[0]
        if png[offset + 4:offset + 8] == b'IDAT':
            compressed.extend(png[offset + 8:offset + 8 + length])
        offset += length + 12
    scan = zlib.decompress(compressed)
    row = []
    for index, value in enumerate(scan[1:1 + channels * 5]):
        left = row[index - channels] if index >= channels else 0
        predictor = left if scan[0] in (1, 4) else left // 2 if scan[0] == 3 else 0
        row.append((value + predictor) & 255)
    return row[channels * 4:channels * 4 + 3]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', required=True)
    parser.add_argument('--screen', required=True, help='North-facing fixture screen')
    args = parser.parse_args()
    agent, screen = args.agent, args.screen
    folder = native.GAME / 'too-many-agents/files-fixtures'
    folder.mkdir(parents=True, exist_ok=True)
    fixture = create(folder)
    native.command('gamemode creative @s')
    native.command('tp @s 168 -60 165')
    native.command('item replace entity @s weapon.mainhand with air')

    def row():
        return native.screen_row(screen)

    def wait(url, grant=True):
        native.wait_for(lambda state: any(item['id'] == screen and item['url'] == url
            and item.get('fileGrant') == grant and item['painted'] and not item['loading']
            for item in state['screens']), timeout=30)

    def address(text):
        native.screen_control(screen, 'address')
        native.ui('dev_char', text=text)
        native.ui('dev_key', key=257)

    def page_click(x, y):
        item = row()
        width, height = map(int, item['pixels'].split('x'))
        cx, cy, cz = map(float, item['pageCenter'].split(','))
        native.aim(cx + (.5 - x / width) * item['pageWidth'],
                   cy + (.5 - y / height) * item['pageHeight'], cz)
        native.click(0)

    def path_field(text):
        _, shot = call(agent, 'screen_screenshot', screen=screen)
        png = base64.b64decode(next(part['data'] for part in shot['content'] if part['type'] == 'image'))
        assert max(corner(png)) < 80, 'Picker stylesheet did not render'
        page_click(180, 84)
        native.ui('dev_key', key=65, modifiers=8 if sys.platform == 'darwin' else 2)
        native.ui('dev_char', text=text)

    def uri(path):
        return 'tma://files' + quote(str(Path(path).resolve()), safe='/')

    if row()['url']:
        native.menu_action(screen, 'turn_off')
    native.screen_control(screen, 'blank_files')
    home = uri(Path.home()) + '?pick'
    wait(home)
    time.sleep(.5)
    _, shot = call(agent, 'screen_screenshot', screen=screen)
    image = next(part['data'] for part in shot['content'] if part['type'] == 'image')
    (native.GAME / 'too-many-agents/picker-home.png').write_bytes(base64.b64decode(image))
    relative = str(native.ROOT.relative_to(Path.home()))
    path_field(relative[:-1])
    native.ui('dev_key', key=258)
    time.sleep(.5)
    native.ui('dev_key', key=257)
    project_picker = uri(native.ROOT) + '?pick'
    wait(project_picker)
    native.screen_control(screen, 'back')
    wait(home)
    native.screen_control(screen, 'forward')
    wait(project_picker)
    print('PASS: Files button, native path input, Tab completion and folder history', flush=True)

    path_field(str(Path(fixture['repo']) / 'src/value.ts'))
    native.ui('dev_key', key=257)
    wait(uri(fixture['repo']) + '?open=src%2Fvalue.ts')
    error('view_only', agent, 'screen_cdp', screen=screen, method='Runtime.evaluate', params={'expression': '1'})
    print('PASS: File selection promotes the human repository root; agent CDP remains refused', flush=True)
    success(agent, 'screen_open', screen=screen, kind='file', target=fixture['repo'])
    native.wait_for(lambda state: any(item['id'] == screen and not item.get('fileGrant') and item['openedBy']
                                    for item in state['screens']))
    address('~/' + relative)
    expected = uri(native.ROOT)
    wait(expected)
    assert not row()['openedBy']
    print('PASS: Agent replacement drops the human grant; typed home path explicitly takes ownership', flush=True)

    native.ui('dev_leave')
    native.ui('dev_open_world', name='too-many-agents-development')
    deadline = time.monotonic() + 30
    while True:
        try:
            if row().get('fileGrant'):
                break
        except AssertionError as failure:
            if 'Open a local singleplayer world first' not in str(failure):
                raise
        if time.monotonic() >= deadline:
            raise AssertionError('Human files location did not restore')
        time.sleep(.2)
    assert row()['url'] == expected
    assert not Path(fixture['marker']).exists()
    print('PASS: Saved human location reauthorizes after reload; hostile helpers never ran', flush=True)


if __name__ == '__main__':
    main()
