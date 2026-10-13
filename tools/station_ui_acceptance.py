#!/usr/bin/env python3
"""Native station forms and survival one-for-one inventory conversion in the guarded world."""
import json
import time
import uuid

from stations_acceptance import Game, GAME, ROOT, passed, row


def wait(check, timeout=10):
    deadline = time.monotonic() + timeout
    while True:
        value = check()
        if value:
            return value
        assert time.monotonic() < deadline, 'Timed out waiting for native UI'
        time.sleep(.1)


class UI:
    def __init__(self, game):
        self.game = game

    def widgets(self):
        state = self.game.ui('widgets')
        if state.get('inventoryAgents'):
            state['widgets'] = state['inventoryAgents']['widgets']
            state['pointing'] = state['inventoryAgents'].get('pointing')
        return state

    def button(self, label):
        # Scroll the real form; click only a reported visible native widget.
        self.game.ui('scroll', x=100, y=100, delta=100)
        for _ in range(35):
            state = self.widgets()
            match = next((w for w in state.get('widgets', []) if w.get('label') == label and w.get('visible', True)), None)
            # A partially visible form row is reported but clipped above the fixed Done footer.
            bottom = state['height'] - 48 if state['screen'] in ('StationScreen', 'ProjectBlockScreen', 'ProjectScreen') and label != 'Done' else state['height']
            if match and 0 <= match['y'] and match['y'] + match['height'] <= bottom:
                assert match.get('active', True), match
                return self.game.ui('click', x=match['x'] + match['width']/2, y=match['y'] + match['height']/2)
            self.game.ui('scroll', x=state['width']/2, y=state['height']/2, delta=-3)
        raise AssertionError(('Widget not found', label, self.widgets()))

    def slots(self):
        return self.widgets()['stationItems']['slots']

    def slot(self, index):
        return next(s for s in self.slots() if s['slot'] == index)

    def click_slot(self, index):
        slot = self.slot(index)
        self.game.ui('click', x=slot['x']+8, y=slot['y']+8)

    def items(self, label):
        self.button(label)
        wait(lambda: self.widgets().get('stationItems'))


def assignment(game, fixture):
    marker, settings = fixture['marker'], fixture['settings']
    ui = UI(game)
    projects = game.request('/v1/agents/projects')['projects']
    original = next(p for p in projects if p['id'] == settings['projectId'])
    other = next(p for p in projects if p.get('kind') == 'standard' and p['id'] != original['id'])
    game.ui('project_block', pos=marker)
    ui.button(original['name'] + ' ▾')
    assert not any(w['label'] == 'This world' for w in ui.widgets()['widgets'])
    ui.button(other['name'])
    ui.items('Assign station item…')
    # Rebind a configured station and retain every other setting and item component.
    ui.click_slot(30)
    ui.click_slot(0)
    wait(lambda: ui.slot(1)['count'] == 1)
    preview = ui.slot(1)['station']
    for key, value in settings.items():
        assert preview[key] == (other['id'] if key == 'projectId' else value), (key, preview)
    ui.click_slot(1)
    ui.click_slot(30)
    # Blank stations can be processed too; native shift-click must consume one per output.
    ui.click_slot(29)
    ui.click_slot(0)
    wait(lambda: ui.slot(0)['count'] == 2)
    game.ui('dev_inventory_click', slot=1, button=0, click='QUICK_MOVE')
    wait(lambda: ui.slot(0)['count'] == 0 and ui.slot(1)['count'] == 0)
    game.ui('close')
    inventory = game.station('read', pos=marker)['inventory']
    stations = [s for s in inventory if s['item'] == 'too_many_agents:station']
    assert sum(s['count'] for s in stations) == 3, stations
    assert all(s['components']['too_many_agents:station']['projectId'] == other['id'] for s in stations), stations
    assert all('Input marker' in json.dumps(s['components']) for s in stations), stations
    assert sum(s['count'] for s in stations if settings['name'] in json.dumps(s['components'])) == 1
    assert other['id'] in json.dumps(game.station('read', pos=marker)['blockEntity'])
    passed('project form changes its binding; native slot rebinds configured and blank stations with exact shift-click item conservation')


def spawning(game, fixture):
    ui = UI(game)
    game.ui('station', pos=fixture['pos'])
    ui.button('Spawn agent here')
    wait(lambda: any(w['label'] == 'GPT-6.1-Sol ▾' and w['active'] for w in ui.widgets()['widgets']))
    project_name = next(p['name'] for p in game.request('/v1/agents/projects')['projects']
                        if p['id'] == fixture['settings']['projectId'])
    assert any(w['label'] == project_name+' ▾' and not w['active'] for w in ui.widgets()['widgets'])
    assert any(w['label'] == 'fox ▾' for w in ui.widgets()['widgets'])
    ui.button('Message to agent')
    game.ui('text', text='Reply exactly station-ui-started. Do not use tools.')
    ui.button('Send')
    agent_id = wait(lambda: row(game.station('read', pos=fixture['pos']), fixture['pos'])['agentId'])
    fixture['agentId'] = agent_id
    (GAME/'too-many-agents/station-ui-fixture.json').write_text(json.dumps(fixture))
    path = '/v1/agents/' + agent_id
    def replied():
        agent = game.request(path)
        if not agent.get('threadId') or agent['status'] != 'idle':
            return False
        transcript = game.request(path+'/transcript', {'includeNestedRows':'true','summaryOnly':'false'})
        return any('station-ui-started' in r.get('text', '') for r in transcript['rows'] if r.get('role') == 'assistant')
    wait(replied, 120)
    agent = game.request(path)
    assert agent['projectId'] == fixture['settings']['projectId']
    assert agent['settings']['body'] == 'minecraft:fox' and agent['executionOptions']['model'] == 'gpt-6.1-sol'
    transcript = game.request(path+'/transcript', {'includeNestedRows':'true','summaryOnly':'false'})
    assert any('station-ui-started' in r.get('text', '') for r in transcript['rows'] if r.get('role') == 'assistant')
    passed('Spawn agent here opens the ordinary draft with fixed project and station defaults, then starts a real BB turn')
    game.ui('station', pos=fixture['pos'])
    assert any(w['label'] == project_name+' ▾' and not w['active'] for w in ui.widgets()['widgets'])
    assert not any(w['label'] == 'Spawn agent here' for w in ui.widgets()['widgets'])
    passed('occupied station disables project changes and replaces spawning with its occupant')


def editing(game, fixture):
    game.ui('station', pos=fixture['pos'])
    ui = UI(game)
    for label, value in [('Name', fixture['settings']['name']+' edited'), ('Width (odd, 1–99)', '7'),
                         ('Height (1–99)', '5'), ('Instructions', 'Next spawn only.\nSecond line.')]:
        ui.button(label)
        game.ui('text', text=value)
    ui.button('fox ▾')
    ui.button('pig')
    ui.button('GPT-6.1-Sol ▾')
    ui.button('GPT-6-Astra')
    ui.button('Apply')
    updated = wait(lambda: (s if (s := row(game.station('read', pos=fixture['pos']), fixture['pos']))['radius'] == 3 else None))
    assert updated['height'] == 5 and updated['instructions'] == 'Next spawn only.\nSecond line.'
    assert updated['body'] == 'minecraft:pig' and updated['model'] == 'gpt-6-astra'
    agent = game.request('/v1/agents/'+fixture['agentId'])
    assert agent['settings']['body'] == 'minecraft:fox' and agent['executionOptions']['model'] == 'gpt-6.1-sol'
    fixture['station'] = updated
    (GAME/'too-many-agents/station-ui-fixture.json').write_text(json.dumps(fixture))
    passed('native editors save multiline instructions and area changes; occupied execution/body edits affect only future spawns')


def survey_pointing(game, fixture):
    ui = UI(game)
    if game.request('/v1/state')['screen'] != 'game':
        game.ui('close')
    game.command('gamemode creative @s')
    game.command('tp @s 168 -60 165')
    game.command('setblock 171 -61 167 stone')
    pos = [171, -60, 167]
    game.station('give', kind='station', slot=0, settings={'name':'Survey '+uuid.uuid4().hex[:6],
        'radius':1, 'height':5, 'projectId':fixture['settings']['projectId']})
    linked = row(game.station('place', pos=pos, slot=0), pos)
    time.sleep(.5)
    game.ui('dev_key', key=66)
    wait(lambda: ui.widgets()['survey']['on'])
    links = wait(lambda: [link for link in ui.widgets()['survey']['screenLinks'] if link['stationId'] == linked['id']])
    screen_id = 'db64971c-2f20-4588-8163-dc8c24933fcc'
    assert any(link['screenId'] == screen_id for link in links), links
    shared = game.request('/v1/agents/projects')['world']['screenLinks']
    assert ui.widgets()['survey']['screenLinks'] == shared
    game.ui('dev_aim', x=169, y=-56.385, z=167.9355)
    time.sleep(.3)
    game.ui('screenshot')
    (GAME/'too-many-agents/station-survey.png').write_bytes((GAME/'too-many-agents/screen.png').read_bytes())
    passed('native B survey shows shared station-screen links from the same snapshot used by screen tools')
    game.ui('chat', agentId=fixture['agentId'])
    pointing = ui.widgets()['pointing']
    assert pointing['target']['type'] == 'screen' and pointing['target']['id'] == screen_id, pointing
    ui.button('Message to agent')
    game.ui('text', text='Reply only with the exact UUID of the screen I am pointing at from the user context. Do not use tools.')
    ui.button('Send')
    path = '/v1/agents/'+fixture['agentId']
    def answered():
        if game.request(path)['status'] != 'idle':
            return False
        return any(screen_id in r.get('text','') for r in game.request(path+'/transcript',
            {'includeNestedRows':'true','summaryOnly':'false'})['rows'] if r.get('role')=='assistant')
    wait(answered,120)
    passed('pointing captures screen type/id/name and reaches a real project-agent message')
    game.ui('close')
    game.ui('survey', on=False)
    game.station('break', pos=pos)


def create_project(game, fixture):
    game.command('tp @s 351 -60 347')
    time.sleep(.5)
    game.ui('project_block', pos=fixture['marker'])
    ui = UI(game)
    ui.button('Create project…')
    name = 'Native project ' + uuid.uuid4().hex[:6]
    folder = GAME / ('station-ui-project-' + uuid.uuid4().hex[:6])
    folder.mkdir()
    for label, value in [('Name', name), ('Project folder', str(folder)), ('Color', '#77BFC7')]:
        ui.button(label)
        game.ui('text', text=value)
    ui.button('Create project')
    wait(lambda: ui.widgets()['screen'] == 'ProjectBlockScreen')
    project = wait(lambda: next((p for p in game.request('/v1/agents/projects')['projects'] if p.get('folder') == str(folder)), None))
    wait(lambda: project['id'] in json.dumps(game.station('read', pos=fixture['marker'])['blockEntity']))
    assert '#77BFC7' in json.dumps(game.station('read', pos=fixture['marker'])['blockEntity'])
    ui.button('Project settings…')
    assert ui.widgets()['screen'] == 'ProjectScreen'
    assert any(w['label'].startswith('Show station areas') for w in ui.widgets()['widgets'])
    game.ui('close')
    game.ui('close')
    game.request('/v1/agents/projects', {'operation':'remove','projectId':project['id']})
    wait(lambda: project['id'] not in json.dumps(game.station('read', pos=fixture['marker'])['blockEntity']))
    passed('project block can create a colored project, open its settings, and becomes blank after removal')


def discard_edits(game, fixture):
    ui = UI(game)
    pos = fixture['pos']
    game.command('tp @s 351 -60 347')
    time.sleep(1)
    before = row(game.station('read', pos=pos), pos)
    game.ui('station', pos=pos)
    ui.button('Width (odd, 1–99)')
    game.ui('text', text='2')
    game.ui('close')
    assert ui.widgets()['screen'] == 'StationScreen'
    ui.button('Close without saving')
    wait(lambda: game.request('/v1/state')['screen'] == 'game')
    assert row(game.station('read', pos=pos), pos) == before

    # Loaded, but outside native block-edit range: a rejected save must still let us leave.
    game.command('tp @s 380 -60 350')
    time.sleep(.5)
    game.ui('station', id=before['id'])
    ui.button('Name')
    game.ui('text', text='Must not be saved')
    game.ui('close')
    wait(lambda: any(w['label'] == 'Close without saving' and w['active'] for w in ui.widgets()['widgets']))
    assert ui.widgets()['screen'] == 'StationScreen'
    ui.button('Close without saving')
    wait(lambda: game.request('/v1/state')['screen'] == 'game')
    assert row(game.station('read', pos=pos), pos) == before

    game.command('tp @s 351 -60 347')
    gone = [351, -60, 345]
    game.command('setblock 351 -60 345 too_many_agents:project_block')
    time.sleep(.5)
    game.ui('project_block', pos=gone)
    ui.button('Unassigned ▾')
    project = next(p for p in game.request('/v1/agents/projects')['projects'] if p.get('folder') == str(ROOT))
    ui.button(project['name'])
    game.command('setblock 351 -60 345 air')
    game.ui('close')
    wait(lambda: any(w['label'] == 'Close without saving' and w['active'] for w in ui.widgets()['widgets']))
    assert ui.widgets()['screen'] == 'ProjectBlockScreen'
    ui.button('Close without saving')
    wait(lambda: game.request('/v1/state')['screen'] == 'game')
    passed('invalid area, out-of-range save and removed block all allow discarding edits without a mutation')


def main():
    game = Game()
    if game.request('/v1/state')['screen'] != 'game':
        game.ui('close')
    game.command('gamemode creative @s')
    game.command('tp @s 351 -60 347')
    game.command('fill 346 -60 344 358 -53 354 air')
    game.command('fill 346 -61 344 358 -61 354 stone')
    project = next(p for p in game.request('/v1/agents/projects')['projects']
                   if p.get('kind') == 'standard' and p.get('folder') == str(ROOT))
    name = 'UI desk ' + uuid.uuid4().hex[:6]
    settings = {'name': name, 'projectId': project['id'], 'radius': 2, 'height': 4,
        'body': 'minecraft:fox', 'instructions': 'Copied from the native station screen.',
        'behaviors': {'idle': {'type': 'spin'}}, 'model': 'gpt-6.1-sol', 'providerId': 'codex'}
    pos, marker = [350, -60, 350], [354, -60, 350]
    game.station('give', kind='station', slot=0, settings=settings)
    source = row(game.station('place', pos=pos, slot=0), pos)
    game.station('give', kind='project', slot=0)
    game.station('place', pos=marker, slot=0)
    game.station('configure_project', pos=marker, projectId=project['id'])
    game.command('clear @s too_many_agents:station')
    game.command('item replace entity @s hotbar.0 with too_many_agents:station[minecraft:custom_name=\'{"text":"Input marker"}\'] 3')
    game.command('item replace entity @s hotbar.1 with air')
    game.command('gamemode survival @s')
    # Let the native placement/teleport packets reach the client before its right-click.
    time.sleep(.5)
    game.ui('dev_aim', x=350.5, y=-59.5, z=350.5)
    wait(lambda: game.request('/v1/state')['crosshair'].get('block') == 'too_many_agents:station')
    game.ui('dev_mouse', button=1)
    wait(lambda: game.ui('widgets')['screen'] == 'StationScreen')
    assert next(w['text'] for w in game.ui('widgets')['widgets'] if w['label'] == 'Name') == name
    assert game.ui('widgets')['survey']['visibleBoxes'] >= 1
    passed('native right-click opens the station form and its area outline')
    ui = UI(game)
    ui.items('Copy station item…')
    wait(lambda: ui.slot(29)['count'] == 3)
    ui.click_slot(29)
    ui.click_slot(0)
    wait(lambda: ui.slot(0)['count'] == 3 and ui.slot(1)['count'] == 1)
    preview = ui.slot(1)['station']
    for key, value in settings.items():
        assert preview[key] == value, (key, preview)
    assert not preview['id'] and not preview['agentId'], preview
    ui.click_slot(1)
    wait(lambda: ui.slot(0)['count'] == 2)
    ui.click_slot(30)
    wait(lambda: ui.slot(30)['count'] == 1)
    game.ui('close')
    inventory = wait(lambda: game.station('read', pos=pos)['inventory'])
    stations = [s for s in inventory if s['item'] == 'too_many_agents:station']
    assert sum(s['count'] for s in stations) == 3, stations
    copies = [s for s in stations if name in json.dumps(s['components'])]
    assert sum(s['count'] for s in copies) == 1, stations
    assert all('Input marker' in json.dumps(s['components']) for s in stations), stations
    passed('native survival copy slot consumes one input, preserves unrelated components and returns unused input on close')
    fixture = {'pos':pos,'marker':marker,'station':source,'settings':settings}
    (GAME/'too-many-agents/station-ui-fixture.json').write_text(json.dumps(fixture))
    assignment(game, fixture)
    spawning(game, fixture)
    editing(game, fixture)
    survey_pointing(game, fixture)
    create_project(game, fixture)
    discard_edits(game, fixture)
    game.request('/v1/agents/'+fixture['agentId']+'/archive', {})


if __name__ == '__main__':
    main()
