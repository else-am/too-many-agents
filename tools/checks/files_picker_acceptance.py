#!/usr/bin/env python3
"""Exercise the native folder picker through Minecraft input, including viewer transitions."""
import argparse
import base64
from pathlib import Path
import sys
import time
from urllib.parse import quote
import screens_acceptance as native
from screens_tools_acceptance import call, error, success
from files_view_fixture import create
from screen_web_fixture import fixture_url


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', required=True)
    parser.add_argument('--screen', required=True, help='North-facing fixture screen')
    args = parser.parse_args()
    agent, screen = args.agent, args.screen
    folder = native.GAME / 'too-many-agents/files-fixtures'
    folder.mkdir(parents=True, exist_ok=True)
    fixture = create(folder)
    chooser = Path(fixture['folder']) / 'chooser'
    chooser.mkdir()
    (chooser / 'a-folder').mkdir()
    (chooser / 'a-folder' / 'nested.txt').write_text('Inside the folder\n')
    (chooser / '.hidden-folder').mkdir()
    (chooser / '.hidden.txt').write_text('Hidden by default\n')
    (chooser / 'a-file.txt').write_text('Open this file\n')
    (chooser / 'many').mkdir()
    for index in range(1205):
        (chooser / 'many' / f'file-{index:04}.txt').write_text(f'Entry {index}\n')
    if native.ui('widgets')['screen'] != 'game':
        native.ui('dev_key', key=256)
    native.command('gamemode creative @s')
    native.command('tp @s 168 -60 165')
    native.command('item replace entity @s weapon.mainhand with air')
    native.ui('dev_focus', active=True)

    def row():
        return native.screen_row(screen)

    def uri(path):
        return 'tma://files' + quote(str(Path(path).resolve()), safe='/')

    def wait_picker(path):
        expected = str(Path(path).resolve())
        deadline = time.monotonic() + 30
        while True:
            try:
                item = row()
                picker = item.get('picker') or {}
                if picker.get('path') == expected and not picker['loading'] and item['painted']:
                    assert not item['browser'], 'The native picker must not need a Chromium browser'
                    return
            except StopIteration:
                pass  # Screen entities reach the client after the world opens.
            except AssertionError as failure:
                if 'Open a local singleplayer world first' not in str(failure):
                    raise
            if time.monotonic() >= deadline:
                raise AssertionError(f'Native picker did not reach {expected}')
            time.sleep(.1)

    def wait_viewer(url):
        native.wait_for(lambda state: any(item['id'] == screen and item['url'] == url
            and item.get('fileGrant') and item['browser'] and item['painted'] and not item['loading']
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

    def control(name):
        native.screen_control(screen, 'picker_' + name)

    def path_field(text):
        native.ui('dev_focus', active=True)
        control('path')
        native.ui('dev_key', key=65, modifiers=8 if sys.platform == 'darwin' else 2)
        native.ui('dev_char', text=text)

    def navigate(path):
        path_field(str(path))
        native.ui('dev_key', key=257)
        wait_picker(path)

    def choose(name):
        item = next(item for item in row()['picker']['rows'] if item['name'] == name)
        native.aim(item['x'], item['y'], item['z'])
        native.click(0)

    def shot(name):
        _, envelope = call(agent, 'screen_screenshot', screen=screen)
        image = next(part['data'] for part in envelope['content'] if part['type'] == 'image')
        png = base64.b64decode(image)
        assert png.startswith(b'\x89PNG\r\n\x1a\n')
        (native.GAME / f'too-many-agents/{name}.png').write_bytes(png)

    if row()['url']:
        native.menu_action(screen, 'turn_off')
    native.screen_control(screen, 'blank_files')
    wait_picker(Path.home())
    shot('picker-home')
    error('view_only', agent, 'screen_cdp', screen=screen, method='Runtime.evaluate', params={'expression': '1'})
    relative = str(native.ROOT.relative_to(Path.home()))
    path_field(relative[:-1])
    native.ui('dev_key', key=258)
    time.sleep(.5)
    native.ui('dev_key', key=257)
    wait_picker(native.ROOT)
    control('back')
    wait_picker(Path.home())
    native.screen_control(screen, 'forward')
    wait_picker(native.ROOT)
    print('PASS: Independent native picker, screenshot, path completion, Back/Forward and CDP refusal', flush=True)

    navigate(chooser)
    names = [item['name'] for item in row()['picker']['rows']]
    assert names[0] == 'a-folder' and '.hidden.txt' not in names and '.hidden-folder' not in names, names
    control('hidden')
    native.wait_for(lambda s: any(item['id'] == screen and (item.get('picker') or {}).get('hidden')
                                for item in s['screens']))
    assert '.hidden.txt' in [item['name'] for item in row()['picker']['rows']]
    control('hidden')
    live_file = chooser / 'live-added.txt'
    live_file.write_text('Watch this folder\n')
    native.wait_for(lambda s: any(item['id'] == screen and any(entry['name'] == live_file.name
        for entry in (item.get('picker') or {}).get('rows', [])) for item in s['screens']), timeout=10)
    live_file.unlink()
    native.wait_for(lambda s: any(item['id'] == screen and all(entry['name'] != live_file.name
        for entry in (item.get('picker') or {}).get('rows', [])) for item in s['screens']), timeout=10)
    choose('a-folder')
    wait_picker(chooser / 'a-folder')
    assert native.ui('screens')['input'] == 'typing', 'Clicking the list must focus its keyboard navigation'
    native.ui('dev_key', key=264)  # Down, without R capture.
    native.ui('dev_key', key=257)
    native.wait_for(lambda s: any(item['id'] == screen and item['browser'] and item['painted']
        and not item['loading'] and 'nested.txt' in item['url'] for item in s['screens']), timeout=30)
    native.screen_control(screen, 'back')
    wait_picker(chooser / 'a-folder')
    control('back')
    wait_picker(chooser)
    # Back is history: this returns to the previously typed project, not chooser.parent.
    control('back')
    wait_picker(native.ROOT)
    native.screen_control(screen, 'forward')
    wait_picker(chooser)
    choose('a-file.txt')
    native.wait_for(lambda s: any(item['id'] == screen and item['browser'] and item['painted']
                                and not item['loading'] and 'a-file.txt' in item['url'] for item in s['screens']), timeout=30)
    native.screen_control(screen, 'back')
    wait_picker(chooser)
    control('open_folder')
    wait_viewer(uri(chooser))
    page_click(70, 20)  # The viewer's folder path opens the native picker.
    wait_picker(chooser)
    print('PASS: Hidden files, direct folder/file clicks, history rather than parent, and Open this folder', flush=True)

    path_field(str(chooser / 'does-not-exist'))
    native.ui('dev_key', key=257)
    native.wait_for(lambda s: any(item['id'] == screen and (item.get('fileError') or (item.get('picker') or {}).get('error'))
                                for item in s['screens']))
    assert row()['picker']['path'] == str(chooser.resolve()), 'A failed path discarded the current folder'
    navigate(chooser / 'many')
    shot('picker-many')
    # Captured navigation must reach entries beyond the first directory page and never move the player.
    at = row()['pageCenter']
    native.aim(*map(float, at.split(',')))
    native.ui('dev_scroll', y=-3)
    assert row()['picker']['scroll'] > 0, 'Wheel did not scroll the list'
    native.ui('dev_key', key=82)
    native.wait_for(lambda s: s['input'] == 'captured')
    before = native.ui('screens')['player']
    native.ui('dev_key', key=269)  # End
    native.ui('dev_key', key=257)
    native.wait_for(lambda s: any(item['id'] == screen and item['browser'] and item['painted']
                                and not item['loading'] and 'file-1204.txt' in item['url'] for item in s['screens']), timeout=30)
    assert native.ui('screens')['player'] == before
    if native.ui('screens')['input'] in ('captured', 'typing'):
        native.ui('dev_key', key=256)
    native.screen_control(screen, 'back')
    wait_picker(chooser / 'many')
    print('PASS: Failed paths retain the folder; large directory keyboard navigation in R capture', flush=True)

    native.ui('dev_leave')
    native.ui('dev_open_world', name='too-many-agents-development')
    wait_picker(chooser / 'many')
    assert not row()['openedBy']
    print('PASS: Saved native picker restores after world reopen', flush=True)

    web_file = Path(fixture['folder']) / 'picker-web.html'
    web_file.write_text('<!doctype html><title>Picker boundary</title><p>Browser page</p>')
    web = fixture_url(web_file) + '?pick'
    address(web)
    native.wait_for(lambda s: any(item['id'] == screen and item['url'] == web
        and item['browser'] and item['painted'] and not item['loading']
        and not item.get('picker') for item in s['screens']), timeout=30)
    native.screen_control(screen, 'back')
    wait_picker(chooser / 'many')
    native.screen_control(screen, 'forward')
    native.wait_for(lambda s: any(item['id'] == screen and item['url'] == web
        and item['browser'] and not item['loading'] for item in s['screens']), timeout=30)
    native.screen_control(screen, 'back')
    wait_picker(chooser / 'many')
    print('PASS: Native address navigation to web, and Back/Forward across the boundary', flush=True)

    # Agent replacement still obtains a fresh confined grant and cannot inherit picker authority.
    success(agent, 'screen_open', screen=screen, kind='file', target=fixture['repo'])
    wait_viewer(uri(fixture['repo']))
    assert row()['openedBy']
    address('~/' + relative)
    wait_viewer(uri(native.ROOT))
    assert not row()['openedBy']
    assert not Path(fixture['marker']).exists()
    print('PASS: Agent replacement is scoped; human navigation takes ownership; hostile helpers never ran', flush=True)


if __name__ == '__main__':
    main()
