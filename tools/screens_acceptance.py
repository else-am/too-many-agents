#!/usr/bin/env python3
"""Exercise screen placement and persistence through the isolated client's native input handlers."""
import json
from pathlib import Path
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]


def cli(*arguments):
    result = subprocess.run(
        [sys.executable, str(ROOT / 'tools/agents.py'), '--game-dir', str(ROOT / 'run'), *arguments],
        cwd=ROOT, capture_output=True, text=True)
    if result.returncode:
        raise AssertionError(result.stderr or result.stdout)
    return json.loads(result.stdout)


def ui(action, **fields):
    return cli('ui', action, '--json', json.dumps(fields))


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


def fixture_screens(state):
    def in_fixture(screen):
        x, y, z = map(int, screen['pos'].split(', '))
        return 158 <= x <= 176 and -60 <= y <= -42 and z == 167
    return [screen for screen in state['screens'] if in_fixture(screen)]


def foundation():
    command('gamemode creative @s')
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
    assert screen_id.startswith('s') and screen_id[1:].isdigit()
    print('PASS: Native placement creates one idle 4 by 3 panel', flush=True)

    command('item replace entity @s weapon.mainhand with air')
    aim(168.5, -56.5)
    click(0)
    assert any(screen['id'] == screen_id for screen in ui('screens')['screens'])
    if ui('screens')['input'] in ('address', 'name', 'typing'):
        ui('dev_key', key=256)
    print('PASS: Bare hands cannot break the panel', flush=True)

    # Place a copy of the existing record next to it; the original must keep its identity.
    command(f'item replace entity @s weapon.mainhand with too_many_agents:screen'
            f'[too_many_agents:screen={{id:"{screen_id}",name:"Acceptance copy",url:"",openedBy:""}}]')
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
    command('setblock 162 -57 168 air')
    wait_for(lambda state: all(screen['id'] != copied['id'] for screen in state['screens']))
    assert all(screen['id'] != copied['id'] for screen in ui('screens')['index']['screens'])
    command('tp @s 163 -60 167')
    state = wait_for(lambda state: any(item['id'] == copied['id'] for item in state['items']))
    item = next(item for item in state['items'] if item['id'] == copied['id'])
    assert item['name'] == 'Acceptance copy'
    if item['slot'] < 9:
        ui('dev_key', key=49 + item['slot'])
    else:
        command(f'item replace entity @s weapon.mainhand from entity @s inventory.{item["slot"] - 9}')
    command('setblock 162 -57 168 stone')
    command('tp @s 164 -60 164')
    place((164.5, -57.5), (161.5, -55.5))
    state = wait_for(lambda state: any(screen['id'] == copied['id'] for screen in state['screens']))
    restored = next(screen for screen in state['screens'] if screen['id'] == copied['id'])
    assert restored['name'] == 'Acceptance copy'
    print('PASS: Support loss drops a record that restores its ID and name', flush=True)

    expected = {screen['id']: (screen['name'], screen['url'], screen['blocks'], screen['pos'])
                for screen in fixture_screens(state)}
    counter = state['index']['next']
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
    assert actual == expected and state['index']['next'] == counter
    print('PASS: Save and reopen preserve records, panel dimensions, and ID counter', flush=True)


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
    screen = next(row for row in fixture_screens(ui('screens')) if row['blocks'] == '4x3' and row['name'] == '')
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
    wait_for(lambda state: any(row['id'] == screen_id and row['name'] == '' for row in state['screens']))
    print('PASS: Native bar edits and clears the screen name', flush=True)

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
    control('power')
    wait_for(lambda state: any(row['id'] == screen_id and row['url'] == '' and row['openedBy'] == '' and not row['browser'] for row in state['screens']))
    print('PASS: Address, back, forward, reload and power work through native clicks', flush=True)

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
    control('power')
    wait_for(lambda state: any(row['id'] == screen_id and row['name'] == 'Tiny' and not row['browser'] for row in state['screens']))
    print('PASS: One-block panel supports name, address and power', flush=True)


def breaking():
    command('tp @s 172 -60 166')
    command('gamemode survival @s')
    command('item replace entity @s weapon.mainhand with minecraft:stick')
    aim(172.5, -57.5, 167.9355)
    screen_id = next(row['id'] for row in fixture_screens(ui('screens')) if row['name'] == 'Tiny')
    ui('dev_press', button=0, ticks=5)
    time.sleep(.4)
    assert any(row['id'] == screen_id for row in ui('screens')['screens'])
    print('PASS: Non-tool attacks leave the screen intact', flush=True)

    command('item replace entity @s weapon.mainhand with minecraft:wooden_axe')
    ui('dev_press', button=0, ticks=45)
    state = wait_for(lambda state: state['breaking']['progress'] > .3)
    assert state['breaking']['target'] == screen_id
    assert 0 < state['breaking']['progress'] < 1
    assert next(row for row in state['screens'] if row['id'] == screen_id)['stage'] >= 3
    ui('screenshot')
    ui('dev_mouse', button=0, phase='release')
    wait_for(lambda state: state['breaking']['progress'] == 0)
    assert any(row['id'] == screen_id for row in ui('screens')['screens'])
    print('PASS: Holding a tool builds cracks; releasing resets without breaking', flush=True)

    command('item replace entity @s weapon.mainhand with minecraft:diamond_pickaxe')
    ui('dev_press', button=0, ticks=12)
    wait_for(lambda state: all(row['id'] != screen_id for row in state['screens']))
    assert all(row['id'] != screen_id for row in ui('screens')['index']['screens'])
    print('PASS: A pickaxe completes the ordinary attack and removes the index entry', flush=True)
    time.sleep(.7)
    command('gamemode creative @s')


if __name__ == '__main__':
    foundation()
    facings()
    controls()
    breaking()
