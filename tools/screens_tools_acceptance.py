#!/usr/bin/env python3
"""Live screen-tool checks using two dedicated test agents and a north-facing fixture panel.

Launch the isolated development world and create the agents first. This changes the specified
screen and uses the player's native screen menu; it never creates or restarts agent turns.
"""
import argparse
import base64
from concurrent.futures import ThreadPoolExecutor
import json
import struct
import time

import screens_acceptance as native


def call(agent, tool, **arguments):
    envelope = native.cli('tool', agent, tool, json.dumps(arguments))
    if 'content' not in envelope:
        return envelope, envelope
    text = next(part['text'] for part in envelope['content'] if part['type'] == 'text')
    return json.loads(text), envelope


def success(agent, tool, **arguments):
    value, envelope = call(agent, tool, **arguments)
    assert not envelope.get('isError') and 'error' not in value, value
    return value


def error(code, agent, tool, **arguments):
    value, envelope = call(agent, tool, **arguments)
    assert value.get('error', {}).get('code') == code, value
    assert envelope.get('isError', True), envelope
    return value['error']


def evaluate(agent, screen, expression, **params):
    return success(agent, 'screen_cdp', screen=screen, method='Runtime.evaluate',
                   params={'expression': expression, 'returnByValue': True, **params})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', required=True)
    parser.add_argument('--other', required=True, help='Another test agent with access to the same screen')
    parser.add_argument('--screen', required=True, help='A north-facing panel in the native acceptance fixture')
    parser.add_argument('--foreign-screen', help='Optional screen inside another project box')
    parser.add_argument('--check-timeout', action='store_true',
                        help='Check the 30s timeout; requires the calling BB thread to stay active throughout')
    args = parser.parse_args()
    agent, other, screen = args.agent, args.other, args.screen

    listed = success(agent, 'screen_list')
    row = next(row for row in listed['screens'] if row['id'] == screen)
    assert row['available'] and len(row['viewport']) == 2, row
    assert 'project' in row and 'station' in row and 'lease' in row and 'distance' in row
    mine = success(agent, 'screen_list', mine=True)
    assert all(row['yours'] for row in mine['screens']), mine
    if listed['you']['station'] is None:
        missing = error('choose_screen', agent, 'screen_open', kind='url', target='about:blank')
        assert not missing.get('candidates'), missing
    if args.foreign_screen:
        assert all(row['id'] != args.foreign_screen for row in listed['screens'])
        error('no_access', agent, 'screen_open', screen=args.foreign_screen, kind='url', target='about:blank')
    error('not_found', agent, 'screen_close', screen='00000000-0000-0000-0000-000000000000')
    print('PASS: Listing, defaults, and explicit access checks', flush=True)

    fixture = native.ROOT / 'run/too-many-agents/screens-tool-acceptance.html'
    fixture.write_text('''<!doctype html><title>Screen tool acceptance</title>
<body style="background:#e8eddf;color:#173e36;font:32px Georgia;margin:32px">
<h1>Agent and player share this page</h1><p id="value">Initial value</p>
<input id="entry" placeholder="Shared input"><script>window.fixtureReady=true</script>''')
    opened = success(agent, 'screen_open', screen=screen, kind='file', target=str(fixture), name='Tool acceptance')
    assert opened['screen'] == screen and opened['title'] == 'Screen tool acceptance', opened
    assert opened['url'] == fixture.as_uri(), opened
    conflicting = next((r['name'] for r in listed['screens'] if r['id'] != screen), None)
    for invalid_name in ('', '   ', conflicting):
        if invalid_name is None:
            continue
        error('invalid_request', agent, 'screen_open', screen=screen, kind='url', target='about:blank', name=invalid_name.upper())
        unchanged = next(r for r in success(agent, 'screen_list')['screens'] if r['id'] == screen)
        assert unchanged['name'] == 'Tool acceptance' and unchanged['url'] == fixture.as_uri(), unchanged
    result = evaluate(agent, screen, '({answer:42,title:document.title})')
    assert result['result']['result']['value'] == {'answer': 42, 'title': 'Screen tool acceptance'}, result
    awaited = evaluate(agent, screen, 'new Promise(resolve=>setTimeout(()=>resolve(23),100))', awaitPromise=True)
    assert awaited['result']['result']['value'] == 23, awaited
    exception = evaluate(agent, screen, 'throw new Error("fixture exception")')
    assert 'exceptionDetails' in exception['result'], exception
    bad, envelope = call(agent, 'screen_cdp', screen=screen, method='Fixture.noSuchMethod')
    assert envelope.get('isError', False) and 'error' in bad, bad
    error('too_large', agent, 'screen_cdp', screen=screen, method='Runtime.evaluate',
          params={'expression': '"x".repeat(70000)', 'returnByValue': True})
    print('PASS: File open, CDP values/promises/exceptions, protocol errors and result cap', flush=True)

    leased = error('leased', other, 'screen_cdp', screen=screen, method='Runtime.evaluate', params={'expression': '1'})
    details = leased['leased']
    assert details['thread'] and details['secondsLeft'] > 0 and details['url'] == fixture.as_uri(), leased
    view = next(row for row in success(other, 'screen_list')['screens'] if row['id'] == screen)
    assert view['lease']['thread'] == details['thread'], view
    # Wait for a real paint, without requesting a synthetic screenshot frame.
    native.wait_for(lambda state: any(row['id'] == screen and row.get('painted') for row in state['screens']))
    metadata, shot = call(other, 'screen_screenshot', screen=screen)
    assert not shot.get('isError'), metadata
    png = base64.b64decode(next(part['data'] for part in shot['content'] if part['type'] == 'image'))
    assert png[:8] == b'\x89PNG\r\n\x1a\n' and len(png) <= 5 * 1024 * 1024
    assert list(struct.unpack('>II', png[16:24])) == view['viewport']
    (native.ROOT / 'run/too-many-agents/screen-tool-acceptance.png').write_bytes(png)
    assert metadata['title'] == opened['title'] and metadata['url'] == opened['url']
    print('PASS: Another thread is leased out of CDP but can list and capture exact viewport PNG', flush=True)

    success(agent, 'screen_close', screen=screen)
    error('unavailable', other, 'screen_cdp', screen=screen, method='Runtime.evaluate', params={'expression': '1'})
    error('unavailable', other, 'screen_screenshot', screen=screen)
    error('invalid_request', other, 'screen_open', screen=screen, kind='url', target='about:blank', name='')
    success(other, 'screen_open', screen=screen, kind='url', target='about:blank', name='Renamed acceptance')
    row = next(row for row in success(other, 'screen_list')['screens'] if row['id'] == screen)
    assert row['name'] == 'Renamed acceptance', row
    by_name = success(other, 'screen_cdp', screen='RENAMED ACCEPTANCE', method='Runtime.evaluate', params={'expression': '1'})
    assert by_name['screen'] == screen, by_name
    success(other, 'screen_close', screen=screen)
    print('PASS: Close releases the lease; idle CDP/screenshots fail; blank name rejected and unique name resolves to stable UUID', flush=True)

    success(agent, 'screen_open', screen=screen, kind='file', target=str(fixture))
    # A pending CEF call must settle when the player powers off; it must not wait for its 30s timeout.
    with ThreadPoolExecutor(max_workers=1) as executor:
        pending = executor.submit(call, agent, 'screen_cdp', screen=screen, method='Runtime.evaluate',
                                  params={'expression': 'new Promise(()=>{})', 'awaitPromise': True})
        time.sleep(.4)
        native.command('gamemode creative @s')
        native.command('tp @s 168 -60 165')
        native.command('item replace entity @s weapon.mainhand with air')
        native.menu_action(screen, 'turn_off')
        value, envelope = pending.result(timeout=5)
        assert envelope.get('isError') and value['error']['code'] == 'unavailable', value
    native.wait_for(lambda state: any(row['id'] == screen and not row['browser'] and not row['url'] and not row['openedBy'] for row in state['screens']))
    success(other, 'screen_open', screen=screen, kind='file', target=str(fixture))
    success(other, 'screen_close', screen=screen)
    print('PASS: Player menu turn-off closes pending CDP promptly and releases the lease', flush=True)

    success(agent, 'screen_open', screen=screen, kind='file', target=str(fixture))
    error('leased', other, 'screen_close', screen=screen)
    time.sleep(21)
    success(other, 'screen_close', screen=screen)
    print('PASS: Lease expires after the bounded call finishes', flush=True)

    native.ui('dev_pause')
    try:
        opened = success(agent, 'screen_open', screen=screen, kind='file', target=str(fixture))
        assert opened['title'] == 'Screen tool acceptance', opened
        paused = evaluate(agent, screen, '21 * 2')
        assert paused['result']['result']['value'] == 42, paused
        assert success(agent, 'screen_list')['screens']
        metadata, shot = call(other, 'screen_screenshot', screen=screen)
        assert not shot.get('isError'), metadata
        success(agent, 'screen_close', screen=screen)
    finally:
        native.ui('dev_resume')
    print('PASS: All five screen tools work while Minecraft is paused', flush=True)

    if not args.check_timeout:
        return
    success(agent, 'screen_open', screen=screen, kind='file', target=str(fixture))
    started = time.monotonic()
    error('timeout', agent, 'screen_cdp', screen=screen, method='Runtime.evaluate',
          params={'expression': 'new Promise(()=>{})', 'awaitPromise': True})
    assert 29 <= time.monotonic() - started < 38
    state = native.wait_for(lambda state: any(row['id'] == screen and row.get('pendingCalls') == 0 for row in state['screens']))
    assert any(row['id'] == screen and row['browser'] for row in state['screens']), state
    error('leased', other, 'screen_close', screen=screen)
    time.sleep(21)
    success(other, 'screen_close', screen=screen)
    print('PASS: CDP timeout detaches its waiter and starts the 20-second lease expiry', flush=True)


if __name__ == '__main__':
    main()
