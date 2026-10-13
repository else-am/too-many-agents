#!/usr/bin/env python3
"""Exercise screen placement and persistence through the isolated client's native input handlers."""
import json
from pathlib import Path
import shutil
import subprocess
import sys
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]


def cli(*arguments):
    result = subprocess.run(
        [sys.executable, str(ROOT / 'tools/agents.py'), '--game-dir', str(ROOT / 'run'), *arguments],
        cwd=ROOT, capture_output=True, text=True)
    if result.returncode:
        raise AssertionError(result.stderr or result.stdout)
    return json.loads(result.stdout)


def ui(action, **fields):
    result = cli('ui', action, '--json', json.dumps(fields))
    if action == 'dev_key' and 49 <= fields.get('key', 0) <= 57:
        # The held-slot packet must reach the server before a fixture command replaces mainhand.
        time.sleep(.15)
    return result


def command(text):
    # dev_command checks the exact development-world folder before scheduling the command.
    ui('dev_command', command=text)
    time.sleep(.15)


def aim(x, y, z=168):
    ui('dev_aim', x=x, y=y, z=z)
    time.sleep(.15)


def click(button=1):
    ui('dev_mouse', button=button)
    time.sleep(.2)


def wait_for(predicate, timeout=8):
    deadline = time.monotonic() + timeout
    while True:
        state = ui('screens')
        if predicate(state):
            return state
        if time.monotonic() >= deadline:
            raise AssertionError(json.dumps(state, indent=2))
        time.sleep(.1)


def place(first, second):
    aim(*first)
    click()
    assert ui('screens')['placement']['active'], 'First click must start placement'
    aim(*second)
    preview = ui('screens')['placement']
    assert not preview['invalid'], preview
    click()
    wait_for(lambda state: not state['placement']['active'])


def screen_row(screen_id):
    return next(row for row in ui('screens')['screens'] if row['id'] == screen_id)


def screen_control(screen_id, name):
    at = screen_row(screen_id)['controls'][name]
    aim(at['x'], at['y'], at['z'])
    click(0)


def menu_action(screen_id, action, confirm=False):
    screen_control(screen_id, 'menu')
    assert ui('screens')['menu']['screen'] == screen_id
    at = next(row for row in screen_row(screen_id)['menuRows'] if row['action'] == action)
    aim(at['x'], at['y'], at['z'])
    click(0)
    if confirm:
        assert ui('screens')['menu']['confirmingReset']
        click(0)


def fixture_screens(state):
    def in_fixture(screen):
        x, y, z = map(int, screen['pos'].split(', '))
        return 158 <= x <= 176 and -60 <= y <= -42 and z == 167
    return [screen for screen in state['screens'] if in_fixture(screen)]


def foundation():
    command('gamemode creative @s')
    command('clear @s too_many_agents:screen')
    command('tp @s 168 -60 164')
    # Let generation settle before leaving these chunks again; rapid out-and-back travel can
    # leave vanilla's shutdown unload queue waiting on unfinished generation.
    time.sleep(3)
    command('kill @e[type=too_many_agents:screen,x=158,y=-61,z=166,dx=20,dy=20,dz=4]')
    command('kill @e[type=minecraft:item,x=158,y=-61,z=166,dx=20,dy=20,dz=4]')
    command('fill 158 -60 166 176 -42 170 air')
    command('fill 158 -60 168 176 -42 168 stone')
    command('weather clear')
    command('time set noon')
    ui('dev_key', key=57)
    command('item replace entity @s weapon.mainhand with too_many_agents:screen')

    aim(167.5, -57.5)
    click()
    assert ui('screens')['placement']['active']
    ui('dev_key', key=256)
    assert not ui('screens')['placement']['active']
    assert ui('widgets')['screen'] == 'game', 'Esc must cancel without opening pause'
    aim(167.5, -57.5)
    click()
    ui('dev_key', key=49)
    assert not ui('screens')['placement']['active']
    ui('dev_key', key=57)
    print('PASS: Esc and slot changes cancel placement', flush=True)

    aim(167.5, -57.5)
    click()
    aim(130.5, -20.5)
    assert ui('screens')['placement']['size'] == '16x16'
    ui('dev_key', key=256)
    print('PASS: Preview is capped at 16 by 16', flush=True)

    aim(167.5, -57.5)
    click()
    command('setblock 167 -58 168 air')
    wait_for(lambda state: bool(state['placement'].get('invalid')))
    click()
    assert ui('screens')['placement']['active']
    assert not fixture_screens(ui('screens'))
    ui('dev_key', key=256)
    command('setblock 167 -58 168 stone')
    print('PASS: Unsupported cells cannot be confirmed', flush=True)

    place((167.5, -57.5), (170.5, -55.5))
    first = fixture_screens(wait_for(lambda state: len(fixture_screens(state)) == 1))[0]
    assert first['blocks'] == '4x3' and not first['browser'] and first['url'] == ''
    screen_id = first['id']
    assert str(uuid.UUID(screen_id)) == screen_id == first['entity']
    assert first['name'].strip() and '-' in first['name']
    print('PASS: Native placement creates one idle 4 by 3 panel', flush=True)

    command('item replace entity @s weapon.mainhand with air')
    aim(168.5, -56.5)
    click(0)
    assert any(screen['id'] == screen_id for screen in ui('screens')['screens'])
    if ui('screens')['input'] in ('address', 'name', 'typing'):
        ui('dev_key', key=256)
    print('PASS: Bare hands cannot break the panel', flush=True)

    # A saved item supplies content; placement supplies a new identity.
    command('item replace entity @s weapon.mainhand with too_many_agents:screen'
            '[too_many_agents:screen={name:"Acceptance copy",url:""}]')
    command('tp @s 164 -60 164')
    place((164.5, -57.5), (161.5, -55.5))
    screens = fixture_screens(wait_for(lambda state: len(fixture_screens(state)) == 2))
    copied = next(screen for screen in screens if screen['id'] != screen_id)
    assert copied['name'] == 'Acceptance copy'
    aim(165.5, -57.5)
    click()
    aim(162.5, -56.5)
    assert ui('screens')['placement']['invalid']
    ui('dev_key', key=256)
    print('PASS: Copied records get fresh IDs; overlapping panels are rejected', flush=True)

    # Remove support and collect the real dropped entity rather than manufacturing a replacement.
    command('item replace entity @s weapon.mainhand with air')
    command('setblock 162 -57 168 air')
    wait_for(lambda state: all(screen['id'] != copied['id'] for screen in state['screens']))
    assert all(screen['id'] != copied['id'] for screen in ui('screens')['index']['screens'])
    command('tp @s 163 -60 167')
    state = wait_for(lambda state: bool(state['items']))
    item = state['items'][0]
    assert not item['name'] and not item['url'] and item.get('saved') is None
    assert 'id' not in item and 'openedBy' not in item
    if item['slot'] < 9:
        ui('dev_key', key=49 + item['slot'])
    else:
        command(f'item replace entity @s weapon.mainhand from entity @s inventory.{item["slot"] - 9}')
    command('setblock 162 -57 168 stone')
    command('tp @s 164 -60 164')
    place((164.5, -57.5), (161.5, -55.5))
    state = wait_for(lambda state: len(fixture_screens(state)) == 2)
    restored = next(screen for screen in fixture_screens(state) if screen['id'] != screen_id)
    assert restored['id'] not in (screen_id, copied['id'])
    assert restored['name'] and restored['name'] != 'Acceptance copy'
    print('PASS: Support loss drops the name; replacing the item allocates a fresh ID and name', flush=True)

    expected = {screen['id']: (screen['name'], screen['url'], screen['blocks'], screen['pos'])
                for screen in fixture_screens(state)}
    ui('dev_leave')
    ui('dev_open_world', name='too-many-agents-development')
    deadline = time.monotonic() + 20
    while True:
        snapshot = json.loads(subprocess.check_output(
            [sys.executable, str(ROOT / 'tools/too_many_agents.py'), '--game-dir', str(ROOT / 'run'), 'state']))
        if snapshot['state'] == 'world':
            break
        if time.monotonic() >= deadline:
            raise AssertionError('Development world did not reopen')
        time.sleep(.2)
    state = wait_for(lambda state: len(fixture_screens(state)) == len(expected), timeout=15)
    actual = {screen['id']: (screen['name'], screen['url'], screen['blocks'], screen['pos'])
              for screen in fixture_screens(state)}
    assert actual == expected
    print('PASS: Save and reopen preserve records, panel dimensions, and UUIDs', flush=True)


def facings():
    # Native ray hits exercise the bounding box and viewer-right calculations on every wall orientation.
    cases = [
        ('east', '180 -60 168 180 -52 176', '184 -60 172',
         (181, -57.5, 171.5), (181, -55.5, 174.5), '181, -58, 174'),
        ('south', '168 -60 184 176 -52 184', '172 -60 188',
         (171.5, -57.5, 185), (174.5, -55.5, 185), '171, -58, 185'),
        ('west', '158 -60 180 158 -52 188', '154 -60 184',
         (158, -57.5, 183.5), (158, -55.5, 186.5), '157, -58, 183'),
    ]
    command('kill @e[type=too_many_agents:screen,x=153,y=-61,z=179,dx=25,dy=10,dz=12]')
    command('kill @e[type=too_many_agents:screen,x=179,y=-61,z=167,dx=7,dy=10,dz=10]')
    command('item replace entity @s weapon.mainhand with too_many_agents:screen')
    for facing, wall, player, first, second, position in cases:
        command('fill ' + wall + ' stone')
        command('tp @s ' + player)
        before = {screen['id'] for screen in ui('screens')['screens']}
        place(first, second)
        state = wait_for(lambda state: any(screen['id'] not in before for screen in state['screens']))
        placed = next(screen for screen in state['screens'] if screen['id'] not in before)
        assert (placed['facing'], placed['pos'], placed['blocks']) == (facing, position, '4x3'), placed
        print(f'PASS: Native {facing}-facing placement', flush=True)
    command('tp @s 168 -60 164')


def controls():
    command('gamemode creative @s')
    command('tp @s 168 -60 165')
    command('item replace entity @s weapon.mainhand with air')
    screen = next(row for row in fixture_screens(ui('screens')) if row['blocks'] == '4x3' and row['name'] != 'Acceptance copy')
    screen_id = screen['id']

    def current():
        return next(row for row in ui('screens')['screens'] if row['id'] == screen_id)

    def control(name):
        at = current()['controls'][name]
        aim(at['x'], at['y'], at['z'])
        click(0)

    control('name')
    assert ui('screens')['input'] == 'name'
    ui('dev_char', text='Acceptance board')
    ui('dev_key', key=257)
    wait_for(lambda state: any(row['id'] == screen_id and row['name'] == 'Acceptance board' for row in state['screens']))
    control('name')
    ui('dev_key', key=259)
    ui('dev_key', key=257)
    time.sleep(.2)
    assert screen_row(screen_id)['name'] == 'Acceptance board'
    print('PASS: Native bar renames without changing identity and rejects blank names', flush=True)

    fixture = ROOT / 'run/too-many-agents/screens-acceptance'
    fixture.mkdir(parents=True, exist_ok=True)
    (fixture / 'index.html').write_text('''<!doctype html><title>Screen acceptance</title>
<style>body{background:#eee7d5;font:32px Georgia;margin:20px}
input{position:absolute;left:20px;top:180px;width:300px;height:60px;font:32px Georgia}
a{position:absolute;left:20px;top:280px}</style>
<h1>Shared screen</h1><a href="next.html">Next page</a><input placeholder="Type here">''')
    (fixture / 'next.html').write_text('<!doctype html><title>Second page</title><h1>Second page</h1>')
    url = (fixture / 'index.html').as_uri()
    control('address')
    ui('dev_char', text=url)
    ui('dev_key', key=257)
    wait_for(lambda state: any(row['id'] == screen_id and row.get('painted') and row['url'] == url and not row['loading'] for row in state['screens']), timeout=40)
    # This 4x3 fixture uses 256 pixels per block. Aim at the real HTML input's centre.
    row = current()
    x, y, z = map(float, row['pageCenter'].split(','))
    width, height = map(int, row['pixels'].split('x'))
    aim(x - (160 - width / 2) / 256, y - (210 - height / 2) / 256, z)
    click(0)
    wait_for(lambda state: state['input'] == 'typing')
    ui('dev_char', text='Native typing')
    ui('screenshot')
    ui('dev_key', key=256)
    wait_for(lambda state: state['input'] != 'typing')
    print('PASS: Native page click enters typing; Esc returns control to the game', flush=True)
    control('address')
    ui('dev_char', text=(fixture / 'next.html').as_uri())
    ui('dev_key', key=257)
    wait_for(lambda state: any(row['id'] == screen_id and row['url'].endswith('next.html') and row['back'] for row in state['screens']))
    control('back')
    wait_for(lambda state: any(row['id'] == screen_id and row['url'] == url and row['forward'] for row in state['screens']))
    control('forward')
    wait_for(lambda state: any(row['id'] == screen_id and row['url'].endswith('next.html') for row in state['screens']))
    control('reload')
    wait_for(lambda state: any(row['id'] == screen_id and row.get('painted') and not row['loading'] for row in state['screens']))
    menu_action(screen_id, 'turn_off')
    wait_for(lambda state: any(row['id'] == screen_id and row['url'] == '' and row['openedBy'] == '' and not row['browser'] for row in state['screens']))
    print('PASS: Address, back, forward, reload and menu turn-off work through native clicks', flush=True)

    # A one-block panel still leaves every control an independently clickable span.
    command('tp @s 172 -60 165')
    command('item replace entity @s weapon.mainhand with too_many_agents:screen')
    place((172.5, -57.5), (172.5, -57.5))
    small = next(row for row in fixture_screens(ui('screens')) if row['blocks'] == '1x1')
    screen_id = small['id']
    command('item replace entity @s weapon.mainhand with air')
    control('name')
    assert ui('screens')['input'] == 'name'
    ui('dev_char', text='Tiny')
    ui('dev_key', key=257)
    control('address')
    assert ui('screens')['input'] == 'address'
    ui('dev_char', text=url)
    ui('dev_key', key=257)
    wait_for(lambda state: any(row['id'] == screen_id and row.get('painted') for row in state['screens']))
    menu_action(screen_id, 'turn_off')
    wait_for(lambda state: any(row['id'] == screen_id and row['name'] == 'Tiny' and not row['browser'] for row in state['screens']))
    print('PASS: One-block panel supports name, address and menu turn-off', flush=True)


def menu_actions():
    command('tp @s 172 -60 166')
    screen_id = next(row['id'] for row in fixture_screens(ui('screens')) if row['name'] == 'Tiny')
    for mode in ('survival', 'creative'):
        command(f'gamemode {mode} @s')
        for item in ('air', 'minecraft:stick', 'minecraft:wooden_axe', 'minecraft:diamond_pickaxe'):
            command(f'item replace entity @s weapon.mainhand with {item}')
            aim(172.5, -57.5, 167.9355)
            # Native button, then the held key mapping, covers initial and continuous attacks.
            click(0)
            if ui('screens')['input'] in ('address', 'name', 'typing'):
                ui('dev_key', key=256)
            ui('dev_press', button=0, ticks=65)
            time.sleep(3.5)
            state = ui('screens')
            assert any(row['id'] == screen_id for row in state['screens'])
            assert 'breaking' not in state and 'stage' not in screen_row(screen_id)
        print(f'PASS: All held items and bare hands leave screens intact in {mode}', flush=True)

    command('clear @s')
    ui('dev_key', key=49)
    screen_control(screen_id, 'menu')
    assert ui('screens')['menu']['screen'] == screen_id
    ui('screenshot')
    ui('dev_key', key=256)
    assert not ui('screens')['menu']['screen'] and ui('widgets')['screen'] == 'game'
    screen_control(screen_id, 'menu')
    ui('dev_key', key=50)
    wait_for(lambda state: not state['menu']['screen'])
    screen_control(screen_id, 'menu')
    ui('dev_pause')
    ui('dev_resume')
    assert not ui('screens')['menu']['screen']
    screen_control(screen_id, 'menu')
    at = screen_row(screen_id)['pageCenter']
    x, y, z = map(float, at.split(','))
    aim(x, y + .3, z)
    click(0)
    assert not ui('screens')['menu']['screen']
    assert ui('screens')['input'] != 'address', 'Outside click must only dismiss the menu'
    print('PASS: Esc, empty-slot switch, GUI and outside click dismiss the menu', flush=True)

    menu_action(screen_id, 'reset')
    assert ui('screens')['menu']['confirmingReset']
    ui('dev_key', key=256)
    assert screen_row(screen_id)['name'] == 'Tiny'
    menu_action(screen_id, 'reset')
    time.sleep(3.3)
    click(0)
    assert screen_row(screen_id)['name'] == 'Tiny', 'An expired reset click must not remove the screen'
    ui('dev_key', key=256)
    print('PASS: Reset cancellation and expired confirmation preserve the screen', flush=True)

    url = (ROOT / 'run/too-many-agents/screens-acceptance/index.html').as_uri()
    screen_control(screen_id, 'address')
    ui('dev_char', text=url)
    ui('dev_key', key=257)
    wait_for(lambda state: any(row['id'] == screen_id and row.get('painted') and row['url'] == url for row in state['screens']))
    saved = {key: screen_row(screen_id)[key] for key in ('name', 'url')}
    carried = {'name': '', 'url': saved['url']}
    command('gamemode survival @s')
    command('tp @s 172 -60 164')
    menu_action(screen_id, 'pick_up')
    state = wait_for(lambda state: all(row['id'] != screen_id for row in state['screens']))
    assert all(row['id'] != screen_id for row in state['index']['screens'])
    assert not state['items'], 'Pickup must pop off the panel, not go straight to inventory'
    dropped = state['droppedItems']
    assert len(dropped) == 1 and dropped[0]['count'] == 1, dropped
    assert {key: dropped[0][key] for key in carried} == carried
    assert 'id' not in dropped[0] and 'openedBy' not in dropped[0]
    command('tp @s 172.5 -60 167')
    state = wait_for(lambda state: bool(state['items']))
    item = state['items'][0]
    assert item['count'] == 1 and {key: item[key] for key in carried} == carried
    assert 'id' not in item and 'openedBy' not in item
    assert item['slot'] < 9
    ui('dev_key', key=49 + item['slot'])
    command('tp @s 172 -60 165')
    place((172.5, -57.5), (172.5, -57.5))
    state = wait_for(lambda state: any(row['blocks'] == '1x1' and row['id'] != screen_id and row.get('painted') for row in fixture_screens(state)))
    restored = next(row for row in fixture_screens(state) if row['blocks'] == '1x1' and row['id'] != screen_id)
    assert restored['id'] != screen_id and not restored['openedBy']
    assert restored['name'] and restored['name'] != saved['name'] and restored['url'] == saved['url']
    screen_id = restored['id']
    assert not state['items'], 'Survival placement must consume the returned item'
    print('PASS: Pickup saves only URL; replacing it creates a fresh name and ID without agent attribution', flush=True)

    command('gamemode creative @s')
    command('tp @s 172 -60 164')
    menu_action(screen_id, 'reset', confirm=True)
    state = wait_for(lambda state: all(row['id'] != screen_id for row in state['screens']))
    assert all(row['id'] != screen_id for row in state['index']['screens'])
    assert not state['items']
    dropped = state['droppedItems']
    assert len(dropped) == 1 and dropped[0]['count'] == 1 and not dropped[0]['name'] and not dropped[0]['url']
    command('tp @s 172.5 -60 167')
    state = wait_for(lambda state: bool(state['items']))
    blank = state['items'][0]
    assert blank['count'] == 1 and not blank['name'] and not blank['url'], blank
    ui('dev_key', key=49 + blank['slot'])
    command('gamemode survival @s')
    command('tp @s 172 -60 165')
    place((172.5, -57.5), (172.5, -57.5))
    state = wait_for(lambda state: any(row['blocks'] == '1x1' and row['id'] != screen_id for row in fixture_screens(state)))
    fresh = next(row for row in fixture_screens(state) if row['blocks'] == '1x1')
    assert fresh['id'] != screen_id and fresh['name'].strip() and not fresh['url'] and not fresh['browser']
    assert not state['items']
    print('PASS: Creative reset drops one blank item; placing it creates a fresh idle screen', flush=True)
    command('gamemode creative @s')


def unique_names():
    command('gamemode creative @s')
    command('tp @s 160 -60 164')
    ids = []
    for x in (158, 159):
        before = {row['id'] for row in ui('screens')['screens']}
        command('item replace entity @s weapon.mainhand with too_many_agents:screen'
                '[too_many_agents:screen={name:"Identity board",url:""}]')
        place((x + .5, -59.5), (x + .5, -59.5))
        state = wait_for(lambda state: any(row['id'] not in before for row in state['screens']))
        ids.append(next(row['id'] for row in state['screens'] if row['id'] not in before))
    first, second = ids
    assert screen_row(first)['name'] == 'Identity board'
    assert screen_row(second)['name'] == 'Identity board-2'
    command('item replace entity @s weapon.mainhand with air')
    screen_control(second, 'name')
    ui('dev_char', text='IDENTITY BOARD')
    ui('dev_key', key=257)
    time.sleep(.3)
    assert screen_row(second)['name'] == 'Identity board-2'
    screen_control(first, 'name')
    ui('dev_char', text='Identity renamed')
    ui('dev_key', key=257)
    wait_for(lambda state: any(row['id'] == first and row['name'] == 'Identity renamed' for row in state['screens']))
    print('PASS: Placement suffixes duplicate names; case-insensitive rename conflicts are rejected; UUID survives rename', flush=True)

    # Origin and the main fixture are already generated. Unloading must not release their names.
    command('tp @s 0 -60 0')
    wait_for(lambda state: all(row['id'] not in ids for row in state['screens']), timeout=20)
    assert all(any(row['id'] == sid for row in ui('screens')['index']['screens']) for sid in ids)
    command('fill 6 -60 8 6 -56 8 stone')
    command('tp @s 6 -60 4')
    before = {row['id'] for row in ui('screens')['screens']}
    command('item replace entity @s weapon.mainhand with too_many_agents:screen'
            '[too_many_agents:screen={name:"Identity renamed",url:""}]')
    place((6.5, -57.5, 8), (6.5, -57.5, 8))
    placed = next(row for row in ui('screens')['screens'] if row['id'] not in before)
    assert placed['name'] == 'Identity renamed-2'
    command('item replace entity @s weapon.mainhand with air')
    menu_action(placed['id'], 'reset', confirm=True)
    wait_for(lambda state: all(row['id'] != placed['id'] for row in state['index']['screens']))
    command('fill 6 -60 8 6 -56 8 air')
    time.sleep(3)
    command('tp @s 160 -60 164')
    wait_for(lambda state: all(any(row['id'] == sid for row in state['screens']) for sid in ids), timeout=20)
    assert screen_row(first)['name'] == 'Identity renamed'
    print('PASS: Unloaded screens reserve names and return with the same UUID and name', flush=True)
    for sid in ids:
        menu_action(sid, 'reset', confirm=True)
        wait_for(lambda state: all(row['id'] != sid for row in state['index']['screens']))


def saved_items_and_crafting():
    command('gamemode creative @s')
    command('clear @s')
    ui('dev_key', key=49)
    command('tp @s 172 -60 162')
    pages = [('Twin', 'about:blank'), ('Twin', 'about:blank'),
             ('Twin', (ROOT / 'run/too-many-agents/screens-acceptance/index.html').as_uri()),
             ('Other', 'about:blank')]
    ids = []
    for x, (name, url) in zip(range(171, 175), pages):
        command('tp @s 172 -60 164')
        before = {row['id'] for row in ui('screens')['screens']}
        command('item replace entity @s weapon.mainhand with too_many_agents:screen'
                f'[too_many_agents:screen={{name:{json.dumps(name)},url:{json.dumps(url)}}}]')
        place((x + .5, -59.5), (x + .5, -59.5))
        state = wait_for(lambda state: any(row['id'] not in before for row in state['screens']))
        screen_id = next(row['id'] for row in state['screens'] if row['id'] not in before)
        ids.append(screen_id)
        # Different placed names must not prevent picked-up items with the same URL from stacking.
        command('tp @s 172 -60 162')
        command('item replace entity @s weapon.mainhand with air')
        menu_action(screen_id, 'pick_up')
        wait_for(lambda state: all(row['id'] != screen_id for row in state['screens']))
    assert len(set(ids)) == 4
    state = ui('screens')
    assert not state['items'], 'All four items must drop into the world first'
    for drop in state['droppedItems']:
        if not drop['name'] and drop['url'] in {url for _, url in pages}:
            command(f'tp @s {drop["x"]} -60 {drop["z"]}')
            time.sleep(.3)
    state = wait_for(lambda state: sum(item['count'] for item in state['items']) == 4)
    actual = {(item['name'], item['url']): item['count'] for item in state['items']}
    assert actual == {('', pages[0][1]): 3, ('', pages[2][1]): 1}, actual
    for item in state['items']:
        assert item['saved'] == {'url': item['url']}, item
        assert item['tooltip'] == ['Screen', item['url']], item
    print('PASS: Picked-up screens stack by URL regardless of old names, with no ID/provenance', flush=True)
    print('PASS: Saved-item tooltip shows only URL', flush=True)

    # A real two-by-two crafting interaction: one saved item in, one component-free item out.
    command('gamemode survival @s')
    command('clear @s')
    command('give @s too_many_agents:screen[too_many_agents:screen={name:"Craft test",url:"about:blank"}] 2')
    ui('list')
    widgets = ui('widgets')
    assert widgets['screen'] == 'InventoryScreen', widgets
    recipe = next(row for row in widgets['widgets'] if row['type'] == 'ImageButton' and row['width'] == 20 and row['height'] == 18)
    left, top = recipe['x'] - 104, (widgets['height'] - 166) // 2
    def slot(x, y, button=0):
        ui('click', x=left + x + 8, y=top + y + 8, button=button)
        time.sleep(.2)
    slot(8, 142)      # Pick up the stack of two saved screens.
    slot(98, 18, 1)   # Put one in the crafting grid.
    slot(8, 142)      # Return the other to the hotbar.
    slot(154, 28)     # Take the recipe's actual output.
    slot(26, 142)     # Put the plain screen in the next hotbar slot.
    state = wait_for(lambda state: len(state['items']) == 2)
    plain = next(item for item in state['items'] if not item['name'])
    saved = next(item for item in state['items'] if item['name'] == 'Craft test')
    assert plain['count'] == 1 and plain.get('saved') is None and plain['url'] == '' and plain['tooltip'] == ['Screen'], plain
    assert saved['count'] == 1 and saved['url'] == 'about:blank', saved
    ui('dev_key', key=256)
    print('PASS: Native crafting consumes one saved screen and produces exactly one plain component-free screen', flush=True)
    command('gamemode creative @s')


def horizontal_surfaces():
    command('gamemode creative @s')
    command('tp @s 200.5 -60 199.5')
    time.sleep(3)
    command('kill @e[type=too_many_agents:screen,x=195,y=-61,z=195,dx=12,dy=8,dz=12]')
    command('kill @e[type=minecraft:item,x=195,y=-61,z=195,dx=12,dy=8,dz=12]')
    command('fill 195 -60 195 207 -54 207 air')
    command('fill 195 -61 195 207 -61 207 stone')
    command('fill 195 -55 195 207 -55 207 stone')
    ui('dev_key', key=57)
    fixture = ROOT / 'run/too-many-agents/screens-acceptance'
    fixture.mkdir(parents=True, exist_ok=True)
    (fixture / 'horizontal.html').write_text('''<!doctype html><title>Horizontal screen</title>
<style>body{background:#eee7d5;color:#182a39;font:40px Georgia;text-align:center}
input{position:absolute;left:15%;top:40%;width:70%;height:20%;box-sizing:border-box;font:32px Georgia}</style>
<h1>Floor / ceiling</h1><input placeholder="Type here" oninput="document.title=this.value">''')
    url = (fixture / 'horizontal.html').as_uri()
    saved = {}
    for face, y in [('up', -60), ('down', -55)]:
        for heading, dx, dz in [('north', 0, -1), ('east', 1, 0), ('south', 0, 1), ('west', -1, 0)]:
            command(f'tp @s {200.5 - dx * 2} -60 {201.5 - dz * 2}')
            command('item replace entity @s weapon.mainhand with too_many_agents:screen')
            before = {row['id'] for row in ui('screens')['screens']}
            first = (200.5, y, 201.5)
            # Camera-right and forward span a rectangle on either surface.
            second = (200.5 - dz, y, 201.5 + dx)
            second = (second[0] + dx * 2, y, second[2] + dz * 2)
            aim(*first)
            click()
            preview = ui('screens')['placement']
            assert preview['active'] and preview['facing'] == face, preview
            expected_up = heading if face == 'up' else {'north':'south','south':'north','east':'west','west':'east'}[heading]
            assert preview['up'] == expected_up, preview
            aim(*second)
            preview = ui('screens')['placement']
            assert preview['size'] == '2x3' and not preview['invalid'], preview
            click()
            state = wait_for(lambda state: any(row['id'] not in before for row in state['screens']))
            row = next(row for row in state['screens'] if row['id'] not in before)
            sid = row['id']
            assert (row['facing'], row['up'], row['blocks']) == (face, expected_up, '2x3'), row
            command('item replace entity @s weapon.mainhand with air')
            screen_control(sid, 'name')
            assert ui('screens')['input'] == 'name'
            name = f'{face}-{heading}'
            ui('dev_char', text=name)
            ui('dev_key', key=257)
            wait_for(lambda state: any(row['id'] == sid and row['name'] == name for row in state['screens']))
            if heading == 'west':
                screen_control(sid, 'address')
                ui('dev_char', text=url)
                ui('dev_key', key=257)
                wait_for(lambda state: any(row['id'] == sid and row.get('painted') and not row['loading'] for row in state['screens']), timeout=45)
                row = screen_row(sid)
                aim(*map(float, row['pageCenter'].split(',')))
                click(0)
                wait_for(lambda state: state['input'] == 'typing')
                ui('dev_char', text=name)
                wait_for(lambda state: any(row['id'] == sid and row.get('title') == name for row in state['screens']))
                ui('dev_key', key=256)
                shot = ui('screenshot')
                shutil.copyfile(shot['path'], fixture / f'{face}.png')
                saved[sid] = (name, face, expected_up, row['pos'], '2x3', url)
            else:
                menu_action(sid, 'reset', confirm=True)
                wait_for(lambda state: all(row['id'] != sid for row in state['screens']))
            print(f'PASS: {face}-facing panel toward {heading}: 2x3 placement, name field, ' + ('page typing' if heading == 'west' else 'menu reset'), flush=True)
    ui('dev_leave')
    ui('dev_open_world', name='too-many-agents-development')
    deadline = time.monotonic() + 20
    while True:
        snapshot = json.loads(subprocess.check_output([sys.executable, str(ROOT / 'tools/too_many_agents.py'), '--game-dir', str(ROOT / 'run'), 'state']))
        if snapshot['state'] == 'world':
            break
        assert time.monotonic() < deadline, 'Development world did not reopen'
        time.sleep(.2)
    state = wait_for(lambda state: all(any(row['id'] == sid for row in state['screens']) for sid in saved), timeout=15)
    for row in state['screens']:
        if row['id'] in saved:
            assert (row['name'], row['facing'], row['up'], row['pos'], row['blocks'], row['url']) == saved[row['id']], row
    print('PASS: Floor and ceiling screens retain orientation, dimensions, names, pages and UUIDs after reopening', flush=True)

    for sid, (_, face, _, pos, _, _) in saved.items():
        command('tp @s 202.5 -60 201.5')
        command('item replace entity @s weapon.mainhand with too_many_agents:screen')
        surface_y, support_y, cell_y = (-60, -61, -60) if face == 'up' else (-55, -55, -56)
        aim(201.5, surface_y, 201.5)
        click()
        aim(200.5, surface_y, 201.5)
        assert ui('screens')['placement']['invalid'], 'Overlapping screens must be rejected'
        click()
        assert ui('screens')['placement']['active']
        ui('dev_key', key=256)
        for obstruction in ('missing_support', 'occupied_cell'):
            aim(201.5, surface_y, 201.5)
            click()
            if obstruction == 'missing_support':
                command(f'setblock 201 {support_y} 201 air')
            else:
                command(f'setblock 201 {cell_y} 201 stone')
            assert ui('screens')['placement']['invalid'], obstruction
            click()
            assert ui('screens')['placement']['active']
            ui('dev_key', key=256)
            command(f'setblock 201 {support_y} 201 stone')
            command(f'setblock 201 {cell_y} 201 air')
        x, y, z = map(int, pos.split(', '))
        old_drops = {row['entity'] for row in ui('screens')['droppedItems']}
        command('item replace entity @s weapon.mainhand with air')
        command(f'setblock {x} {y - 1 if face == "up" else y + 1} {z} air')
        state = wait_for(lambda state: all(row['id'] != sid for row in state['index']['screens']))
        state = wait_for(lambda state: any(row['entity'] not in old_drops and row['url'] == url
            and (face != 'down' or row['y'] < -55.25) for row in state['droppedItems']))
        print(f'PASS: {face}-facing overlap, missing support and occupied cells rejected; support loss drops the saved page', flush=True)
    command('kill @e[type=minecraft:item,x=195,y=-61,z=195,dx=12,dy=8,dz=12]')


if __name__ == '__main__':
    foundation()
    facings()
    controls()
    menu_actions()
    saved_items_and_crafting()
    unique_names()
    horizontal_surfaces()
