#!/usr/bin/env python3
"""Exercise browser capture through the isolated game's native keyboard and mouse handlers."""
import json
import time
import screens_acceptance as native
from screen_web_fixture import fixture_url

ui, command, aim, click, wait_for = native.ui, native.command, native.aim, native.click, native.wait_for


def main():
    fixture = native.GAME / 'too-many-agents/capture-acceptance.html'
    fixture.parent.mkdir(parents=True, exist_ok=True)
    fixture.write_text('''<!doctype html><meta charset="utf-8"><style>
      body { margin:0; background:#182826; color:#f4e9cd; font:28px monospace; height:100vh; }
      main { padding:40px; } h1 { font-size:42px; } pre { font-size:22px; }
      </style><main><h1>Screen capture</h1><p>Click to lock the pointer. U unlocks.</p><pre></pre></main><script>
      const requestLock = document.body.requestPointerLock.bind(document.body);
      const state = {bridgeAtLoad:!!window.__screenCapture, keys:[], down:[], moves:0, dx:0, dy:0, x:0, y:0, wheels:0, buttons:0, locked:false};
      const show = () => { document.title = JSON.stringify(state); document.querySelector('pre').textContent = JSON.stringify(state,null,2); };
      document.addEventListener('keydown', e => {
        e.preventDefault(); state.keys.push(e.code); state.down = [...new Set([...state.down,e.code])];
        if (e.code === 'KeyU') document.exitPointerLock();
        if (e.code === 'KeyN') location.reload(); show();
      });
      document.addEventListener('keyup', e => { state.down = state.down.filter(k => k !== e.code); show(); });
      document.addEventListener('mousemove', e => { state.moves++; state.dx += e.movementX; state.dy += e.movementY; state.x=e.clientX; state.y=e.clientY; show(); });
      document.addEventListener('mousedown', e => { state.buttons=e.buttons; show(); });
      document.addEventListener('mouseup', e => { state.buttons=e.buttons; show(); });
      document.addEventListener('wheel', e => { state.wheels++; show(); });
      document.addEventListener('click', () => requestLock().catch(e => {state.error=e.name; show();}));
      document.addEventListener('pointerlockchange', () => {state.locked=!!document.pointerLockElement; show();});
      show();</script>''')
    url = fixture_url(fixture)
    command('gamemode creative @s')
    command('tp @s 192 -60 164')
    time.sleep(2)
    command('kill @e[type=too_many_agents:screen,x=189,y=-61,z=166,dx=10,dy=10,dz=4]')
    command('fill 189 -60 166 199 -50 170 air')
    command('fill 189 -60 168 199 -50 168 stone')
    ui('dev_key', key=57)
    command('item replace entity @s weapon.mainhand with too_many_agents:screen')
    native.place((191.5, -57.5), (194.5, -55.5))
    state = ui('screens')
    row = next(s for s in state['screens'] if s['pos'] == '194, -58, 167')
    screen_id = row['id']
    command('item replace entity @s weapon.mainhand with air')
    native.screen_control(screen_id, 'address')
    ui('dev_char', text=url)
    ui('dev_key', key=257)
    wait_for(lambda s: any(r['id'] == screen_id and r['painted'] and r['title'].startswith('{') for r in s['screens']), timeout=60)

    def page():
        return json.loads(native.screen_row(screen_id)['title'])

    def wait_page(predicate):
        wait_for(lambda s: predicate(json.loads(next(r for r in s['screens'] if r['id'] == screen_id)['title'])))
        return page()

    def capture():
        center = native.screen_row(screen_id)['pageCenter']
        aim(*map(float, center.split(',')))
        ui('dev_focus', active=True)
        time.sleep(.3)
        ui('dev_key', key=257, modifiers=3)  # Ctrl+Shift+Enter
        wait_for(lambda s: s['input'] == 'captured')

    assert page()['bridgeAtLoad'], 'Pointer-lock bridge must precede page scripts'
    capture()
    before = ui('screens')['player']
    ui('dev_key', key=87, phase='press')
    ui('dev_key', key=32)
    ui('dev_key', key=340)
    ui('dev_key', key=290)  # F1
    time.sleep(.4)
    assert ui('screens')['player'] == before, 'Browser keys moved the player'
    assert set(page()['keys']) >= {'KeyW', 'Space', 'ShiftLeft', 'F1'}, page()
    ui('dev_key', key=87, phase='release')
    wait_page(lambda p: not p['down'])
    ui('dev_mouse_move', x=70, y=-35)
    wait_page(lambda p: p['moves'] > 0)
    assert ui('screens')['player'] == before, 'Mouse moved the camera'
    ui('dev_scroll', y=-1)
    wait_page(lambda p: p['wheels'] > 0)
    print('PASS: Capture routes movement keys, modifiers, function keys, mouse and scroll without moving the player', flush=True)

    click(0)
    wait_for(lambda s: s['input'] == 'pointer_locked')
    initial = page()
    for _ in range(3):
        ui('dev_mouse_move', x=2000, y=-1000)
    result = wait_page(lambda p: p['dx'] >= initial['dx'] + 6000)
    assert result['dy'] == initial['dy'] - 3000, result
    assert (result['x'], result['y']) == (initial['x'], initial['y']), result
    assert ui('screens')['player'] == before
    ui('dev_key', key=85)  # U
    wait_for(lambda s: s['input'] == 'captured')
    print('PASS: Pointer lock delivers unbounded relative movement and page-initiated unlock works', flush=True)

    ui('dev_key', key=87, phase='press')
    ui('dev_mouse', button=0, phase='press')
    ui('dev_key', key=256)
    assert ui('screens')['input'] == 'captured'
    wait_page(lambda p: 'Escape' in p['keys'])
    ui('dev_key', key=257, modifiers=3)  # The capture toggle releases held inputs too
    wait_page(lambda p: not p['down'] and p['buttons'] == 0)
    ui('dev_key', key=87, phase='release')
    ui('dev_mouse', button=0, phase='release')
    assert ui('widgets')['screen'] == 'game'
    assert ui('screens')['input'] not in ('captured', 'pointer_locked', 'typing')
    print('PASS: Escape reaches the page; capture toggle releases held keys/buttons without opening pause', flush=True)

    capture()
    ui('dev_focus', active=False)
    wait_for(lambda s: s['input'] not in ('captured', 'pointer_locked'))
    capture()
    ui('dev_key', key=82)
    assert ui('screens')['input'] == 'captured'
    assert 'KeyR' in page()['keys'], 'R did not reach the captured browser'
    ui('dev_pause')
    ui('dev_resume')
    assert ui('screens')['input'] not in ('captured', 'pointer_locked')
    print('PASS: Focus loss and native GUI release capture; R reaches the browser', flush=True)
    capture()

    def move_to(x, y):
        ui('dev_mouse_move', x=-10000, y=-10000)
        ui('dev_mouse_move', x=x, y=y)
        time.sleep(.1)

    def control(name):
        row = native.screen_row(screen_id)
        width, height = map(int, row['pixels'].split('x'))
        bounds = row['controls'][name]
        move_to((bounds['start'] + bounds['end']) / 2 / row['pageWidth'] * width,
                (row['pageHeight'] + row['barHeight'] / 2) / row['pageHeight'] * height)
        click(0)
        assert ui('screens')['input'] == 'captured'

    page_keys = page()['keys']
    control('name')
    ui('dev_char', text='Captured toolbar')
    ui('dev_key', key=257)
    wait_for(lambda s: any(r['id'] == screen_id and r['name'] == 'Captured toolbar' for r in s['screens']))
    assert page()['keys'] == page_keys, 'Toolbar typing reached the page'
    control('menu')
    assert ui('screens')['menu']['screen'] == screen_id
    ui('dev_key', key=87)
    assert page()['keys'] == page_keys, 'Menu keys reached the page'
    row = native.screen_row(screen_id)
    width, height = map(int, row['pixels'].split('x'))
    reset = next(r for r in row['menuRows'] if r['action'] == 'reset')
    # This fixture faces north: page x increases toward negative world x.
    center_x, center_y, _ = map(float, row['pageCenter'].split(','))
    move_to(width / 2 + (center_x - reset['x']) / row['pageWidth'] * width,
            height / 2 + (center_y - reset['y']) / row['pageHeight'] * height)
    assert ui('screens')['menu']['hovered'] == 2
    click(0)
    assert ui('screens')['menu']['confirmingReset']
    move_to(20, 20)
    click(0)  # Dismiss without passing the click through to the page.
    assert ui('screens')['menu']['screen'] == ''
    assert not page()['locked']
    control('address')
    ui('dev_char', text=url + '?toolbar=1')
    ui('dev_key', key=257)
    wait_for(lambda s: any(r['id'] == screen_id and r['url'].endswith('?toolbar=1') and not r['loading'] for r in s['screens']))
    assert ui('screens')['input'] == 'captured'
    control('back')
    wait_for(lambda s: any(r['id'] == screen_id and r['url'] == url and not r['loading'] for r in s['screens']))
    control('forward')
    wait_for(lambda s: any(r['id'] == screen_id and r['url'].endswith('?toolbar=1') and not r['loading'] for r in s['screens']))
    control('reload')
    time.sleep(.5)
    assert ui('screens')['input'] == 'captured'
    assert ui('screens')['player'] == before
    print('PASS: Captured cursor operates name, address, history, reload and dropdown without moving the player or leaking keys', flush=True)
    control('address')
    ui('dev_char', text=url)
    ui('dev_key', key=257)
    wait_for(lambda s: any(r['id'] == screen_id and r['url'] == url and not r['loading'] for r in s['screens']))

    # Reopening the real world must preserve the panel without restoring capture.
    ui('dev_leave')
    ui('dev_open_world', name='too-many-agents-development')
    deadline = time.monotonic() + 30
    while True:
        try:
            if any(r['id'] == screen_id for r in ui('screens')['screens']): break
        except AssertionError as error:
            if 'Open a local singleplayer world first' not in str(error): raise
        if time.monotonic() >= deadline: raise AssertionError('Test world did not reopen')
        time.sleep(.2)
    assert ui('screens')['input'] not in ('captured', 'pointer_locked')
    assert native.screen_row(screen_id)['url'] == url
    print('PASS: Panel persists across world reopen; capture does not', flush=True)


if __name__ == '__main__':
    main()
