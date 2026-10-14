#!/usr/bin/env python3
"""Exercise web/built-in isolation in a real browser on a guarded development screen."""
import argparse
import base64
import concurrent.futures
import json

import screens_acceptance as native
from screen_web_fixture import fixture_url
from screens_tools_acceptance import call, error, evaluate, success


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--agent', required=True)
    parser.add_argument('--screen', required=True, help='North-facing 4x3 panel at 170,-58,167')
    args = parser.parse_args()
    agent, screen = args.agent, args.screen
    native.command('gamemode creative @s')
    native.command('tp @s 168 -60 165')
    native.command('item replace entity @s weapon.mainhand with air')
    fixture = native.GAME / 'too-many-agents/isolation-acceptance.html'
    fixture.write_text('<!doctype html><title>Isolation web fixture</title><h1>Web fixture</h1>')
    web = fixture_url(fixture)
    target = native.ROOT / 'SCREEN-FILES-VIEW.md'

    def row():
        return native.screen_row(screen)

    def wait_url(url):
        native.wait_for(lambda s: any(r['id'] == screen and r['url'] == url
                        and r['painted'] and not r['loading'] for r in s['screens']), timeout=20)

    def navigate(url):
        native.screen_control(screen, 'address')
        native.ui('dev_char', text=url)
        native.ui('dev_key', key=257)
        wait_url(url)

    success(agent, 'screen_open', screen=screen, kind='url', target=web)
    for method, params in [
        ('Target.getTargets', {}),
        ('Browser.getVersion', {}),
        ('DOM.setFileInputFiles', {'files': [str(target)], 'nodeId': 1}),
        ('Page.setDownloadBehavior', {'behavior': 'allow', 'downloadPath': '/tmp'}),
        ('Input.dispatchDragEvent', {'type': 'dragEnter', 'x': 1, 'y': 1,
          'data': {'items': [], 'dragOperationsMask': 1, 'files': [str(target)]}}),
    ]:
        error('refused', agent, 'screen_cdp', screen=screen, method=method, params=params)
    print('PASS: Cross-browser and local-file CDP capabilities are refused', flush=True)

    for url in (target.as_uri(), 'tma://files/', 'chrome://version/', 'view-source:' + target.as_uri()):
        before = native.ui('screens')['deniedNavigations']
        call(agent, 'screen_cdp', screen=screen, method='Page.navigate', params={'url': url})
        native.wait_for(lambda s: s['deniedNavigations'] > before)
        assert row()['browserUrl'] == web, row()
    result = evaluate(agent, screen,
        "Promise.all(['tma://files/', '" + target.as_uri() + "'].map(u=>fetch(u).then(()=>true,()=>false)))",
        awaitPromise=True)
    assert result['result']['result']['value'] == [False, False], result
    embeds = evaluate(agent, screen, """Promise.all(['iframe','img'].map(tag=>new Promise(resolve=>{
      const el=document.createElement(tag); el.src='tma://files/';
      el.onload=()=>{let readable=false;try{readable=!!el.contentDocument?.body?.textContent}catch{}resolve(readable)};
      el.onerror=()=>resolve(false);document.body.append(el);setTimeout(()=>resolve(false),750);
    })))""", awaitPromise=True)
    assert embeds['result']['result']['value'] == [False, False], embeds
    print('PASS: Web CDP cannot navigate to privileged schemes or fetch local/built-in content', flush=True)

    # Prove the injected script actually works before checking that it cannot enter the built-in page.
    marker = 'CDP SCRIPT MUST STAY ON THE WEB'
    success(agent, 'screen_cdp', screen=screen, method='Page.addScriptToEvaluateOnNewDocument',
            params={'source': 'document.addEventListener("DOMContentLoaded",()=>document.title=' + json.dumps(marker) + ')'})
    success(agent, 'screen_open', screen=screen, kind='url', target=web + '?injected=1')
    native.wait_for(lambda s: any(r['id'] == screen and r['title'] == marker for r in s['screens']))
    web_id = next(r['browserId'] for r in native.ui('screens')['browserViews'] if r['screen'] == screen)
    opened = success(agent, 'screen_open', screen=screen, kind='file', target=str(native.ROOT))
    builtin = opened['url']
    assert builtin.startswith('tma://files/'), opened
    wait_url(builtin)
    current = next(r for r in native.ui('screens')['browserViews'] if r['screen'] == screen)
    assert current['builtin'] and current['browserId'] != web_id, current
    assert row()['title'] != marker, row()
    error('view_only', agent, 'screen_cdp', screen=screen, method='Runtime.evaluate', params={'expression': '1'})
    error('view_only', agent, 'screen_cdp', screen=screen, method='Target.getTargets', params={})
    metadata, shot = call(agent, 'screen_screenshot', screen=screen)
    assert not shot.get('isError'), metadata
    image = base64.b64decode(next(p['data'] for p in shot['content'] if p['type'] == 'image'))
    (native.GAME / 'too-many-agents/isolation-builtin.png').write_bytes(image)
    print('PASS: Built-in uses another browser, refuses CDP, captures normally, and receives no injected script', flush=True)

    # More crossings than a small live-browser cache can retain must still have usable history.
    history = [builtin]
    for i in range(4):
        for url in (web + f'?crossing={i}', builtin + f'#crossing={i}'):
            navigate(url)
            history.append(url)
            if url.startswith('tma:'):
                assert row()['title'] != marker, row()
    for url in reversed(history[:-1]):
        native.screen_control(screen, 'back')
        wait_url(url)
    for url in history[1:]:
        native.screen_control(screen, 'forward')
        wait_url(url)
    print('PASS: Repeated web/built-in crossings retain back/forward URL history', flush=True)

    pages = [web + '?native=' + str(i) for i in range(3)]
    for page in pages:
        navigate(page)
    navigate(builtin)
    for page in reversed(pages):
        native.screen_control(screen, 'back')
        wait_url(page)
    for page in pages[1:] + [builtin]:
        native.screen_control(screen, 'forward')
        wait_url(page)
    print('PASS: Several pages within one web browser survive a built-in crossing', flush=True)

    for expression, suffix in [('history.pushState({}, "", "#push")', '#push'),
                               ('location.hash="hash"', '#hash')]:
        page = web + '?history=' + suffix[1:]
        navigate(page)
        navigate(builtin)
        native.screen_control(screen, 'back')
        wait_url(page)
        evaluate(agent, screen, 'history.replaceState({}, "", "?replaced=1")')
        wait_url(web + '?replaced=1')
        assert row()['forward'], row()
        evaluate(agent, screen, expression, userGesture=True)
        wait_url(web + '?replaced=1' + suffix)
        native.wait_for(lambda s: any(r['id'] == screen and not r['forward'] for r in s['screens']))
        native.screen_control(screen, 'back')
        wait_url(web + '?replaced=1')
        native.screen_control(screen, 'forward')
        wait_url(web + '?replaced=1' + suffix)
    print('PASS: replaceState preserves Forward; pushState and fragment entries clear it', flush=True)

    # A command already awaiting a page result must fail when that browser is replaced.
    with concurrent.futures.ThreadPoolExecutor() as pool:
        pending = pool.submit(call, agent, 'screen_cdp', screen=screen, method='Runtime.evaluate',
                              params={'expression': 'window.pendingIsolation=true;new Promise(()=>{})', 'awaitPromise': True})
        native.wait_for(lambda _: evaluate(agent, screen, 'window.pendingIsolation === true')['result']['result'].get('value'))
        navigate(builtin)
        _, result = pending.result(timeout=15)
        assert result.get('isError'), result
    print('PASS: A pending DevTools command cannot finish on the replacement browser', flush=True)
    native.menu_action(screen, 'turn_off')
    native.wait_for(lambda s: any(r['id'] == screen and not r['browser'] and not r['url'] for r in s['screens']))


if __name__ == '__main__':
    main()
