#!/usr/bin/env python3
"""Exercise a real Minecraft body through installed BB: a provider turn with physical tools, a queued
follow-up, the plugin callback boundary, archive, and optionally an embodied child in a station."""
import argparse
import json
import math
from pathlib import Path
import sys
import time
import urllib.error
import urllib.request
from urllib.parse import quote, urlparse
import uuid

ROOT = Path(__file__).resolve().parents[1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir', type=Path, default=ROOT / 'run')
    parser.add_argument('--provider', default='codex')
    parser.add_argument('--model', required=True)
    parser.add_argument('--reasoning-level', default='high')
    parser.add_argument('--timeout', type=float, default=180)
    parser.add_argument('--agent-id', help='Use an existing unstarted fixture body after inspecting a definite start failure')
    parser.add_argument('--children', action='store_true', help='Also spawn, wait for and archive a child in a station')
    args = parser.parse_args()
    game = args.game_dir.resolve()
    if game != ROOT / 'run':
        parser.error('This fixture only runs in the repository run/ test profile.')
    descriptor = json.loads((game / 'too-many-agents/connection.json').read_text())
    parsed = urlparse(descriptor['url'])
    if descriptor.get('protocol') != 1 or parsed.scheme != 'http' or parsed.hostname != '127.0.0.1':
        raise RuntimeError('Invalid local Minecraft descriptor')

    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *values, **kwargs):
            return None

    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def send(path, data=None):
        body = None if data is None else json.dumps(data).encode()
        message = urllib.request.Request(descriptor['url'].rstrip('/') + path, data=body,
                                         headers={'Content-Type': 'application/json', 'Authorization': 'Bearer ' + descriptor['token']})
        try:
            with opener.open(message, timeout=120) as response:
                return response.status, json.load(response)
        except urllib.error.HTTPError as error:
            return error.code, json.load(error)

    def request(path, data=None):
        status, body = send(path, data)
        if status != 200:
            raise RuntimeError(f'HTTP {status} at {path}: {body}; inspect fixture state before retrying mutations')
        return body

    request('/v1/dev')  # Unavailable unless launched with the development flag.
    world = request('/v1/agents/projects').get('world', {})
    expected = game / 'saves/too-many-agents-development'
    if world.get('directory') != str(expected) or world.get('needsDecision'):
        raise RuntimeError('Open the guarded too-many-agents-development world first.')
    state = request('/v1/state')
    if state.get('state') != 'world' or state.get('paused'):
        raise RuntimeError('The development world must be open and unpaused.')
    catalog = request('/v1/agents/catalog/' + quote(args.provider, safe=''))
    if not catalog:
        raise RuntimeError('Native BB model catalog is empty')
    print('PASS: real native BB provider catalog', flush=True)

    marker = 'tma-' + uuid.uuid4().hex
    first, second = marker + '-observed', marker + '-queued'
    created = request('/v1/agents/' + args.agent_id) if args.agent_id else request('/v1/agents/spawn', {'name': 'BB live smoke', 'providerId': args.provider,
                                          'model': args.model, 'reasoningLevel': args.reasoning_level,
                                          'minecraftAccess': True, 'mode': 'creative'})
    if created.get('threadId'):
        raise RuntimeError('Fixture already has a thread; inspect it instead of starting this check again.')
    agent_id = created['id']
    path = '/v1/agents/' + quote(agent_id, safe='')
    print('Fixture agent: ' + agent_id, flush=True)
    deadline = time.monotonic() + args.timeout
    while time.monotonic() < deadline:
        body = request(path)
        if body.get('bodyLoaded'):
            break
        time.sleep(.5)
    else:
        raise RuntimeError('Body was not observed in the actual world; fixture retained.')
    print('PASS: NPC body observed in the world', flush=True)
    body_uuid = body['body']['entityUuid']
    request(path + '/message', {'text': 'Call minecraft_observe five times sequentially. After all five succeed, reply exactly ' + first + '. Do not call other tools.', 'delivery': 'send'})
    # BB can combine messages during provisioning. Queue only after native execution starts.
    while time.monotonic() < deadline:
        current = request(path)
        if current.get('thread', {}).get('status') == 'active':
            break
        if current.get('status') == 'error' or current.get('error'):
            raise RuntimeError('Provider failed before starting; fixture retained.')
        time.sleep(.25)
    else:
        raise RuntimeError('Provider did not become active; fixture retained.')
    request(path + '/message', {'text': 'Reply exactly ' + second + '. Do not call any tools.', 'delivery': 'queue'})
    seen_first = seen_second = seen_tool = seen_queue = False
    physical_calls = set()
    observed_rows = {}
    last_report = time.monotonic()

    def rows(values):
        for row in values:
            yield row
            yield from rows(row.get('children') or row.get('childRows') or [])

    while time.monotonic() < deadline:
        current = request(path)
        queued = current.get('queuedMessages') or {}
        messages = queued.get('messages', []) if isinstance(queued, dict) else queued
        seen_queue = seen_queue or bool(messages)
        timeline = request(path + '/transcript', {'includeNestedRows': 'true', 'summaryOnly': 'false'})
        for row in rows(timeline.get('rows', [])):
            observed_rows[row['id']] = row
            if row.get('role') == 'assistant':
                seen_first = seen_first or first in row.get('text', '')
                seen_second = seen_second or second in row.get('text', '')
            if row.get('workKind') == 'tool' and 'minecraft_observe' in row.get('toolName', ''):
                try:
                    observation = json.loads(row.get('output', ''))
                except ValueError:
                    observation = {}
                if (row.get('status') == 'completed' and observation.get('session') == state['session']
                        and observation.get('body', {}).get('uuid') == body_uuid):
                    physical_calls.add(row['callId'])
                seen_tool = len(physical_calls) >= 5
        if seen_first and seen_second and seen_tool and current.get('status') not in ('active', 'pending', 'starting', 'stopping'):
            break
        if current.get('status') in ('error', 'disconnected') or current.get('error'):
            raise RuntimeError('Provider or Minecraft connection failed; fixture retained for inspection.')
        if time.monotonic() - last_report >= 20:
            print(f'Waiting for real provider: tool={seen_tool}, first={seen_first}, queuedReply={seen_second}', flush=True)
            last_report = time.monotonic()
        time.sleep(.5)
    else:
        raise RuntimeError('Live turn timed out; inspect the retained agent before retrying.')
    print('PASS: real provider completed a physical Minecraft tool and both assistant replies', flush=True)
    if not seen_queue:
        raise RuntimeError('Both turns completed, but no queued message was observed; fixture retained.')
    print('PASS: native BB queued message observed before delivery', flush=True)

    # The plugin callback boundary, exercised directly against the live game.
    def callback(**fields):
        return send('/v1/bb', {'protocol': 3, 'worldId': world['id'], 'worldSessionId': state['session'],
                               'requestId': str(uuid.uuid4()), 'expiresAt': int(time.time() * 1000) + 10_000, **fields})
    observe = {'op': 'tool', 'agentId': agent_id, 'tool': 'minecraft_observe', 'arguments': {}}
    status, reply = callback(**observe, threadId=str(uuid.uuid4()))
    if status == 200:
        raise RuntimeError('A tool call from a thread that is not the agent\'s conversation was accepted.')
    print('PASS: a tool call from another thread was rejected', flush=True)
    early = str(uuid.uuid4())
    callback(op='cancel', cancelRequestId=early)
    status, reply = callback(**observe, threadId=current['threadId'], requestId=early)
    if status == 200 or 'cancelled' not in json.dumps(reply):
        raise RuntimeError('A tool call cancelled before it arrived still ran: ' + json.dumps(reply))
    print('PASS: a cancel that arrives first prevents the call', flush=True)

    if args.children:
        run_children(request, agent_id, current['projectId'], args.model, args.reasoning_level, args.timeout)

    output = game / 'too-many-agents/bb-smoke-result.json'
    output.write_text(json.dumps({'agentId': agent_id, 'threadId': current.get('threadId'), 'rows': list(observed_rows.values())}, indent=2) + '\n')
    request(path + '/conversation-archive', {})
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        if request(path).get('conversationArchived'):
            break
        time.sleep(.5)
    else:
        raise RuntimeError('Archive did not become visible; fixture retained.')
    request(path + '/remove', {})
    print(f'PASS: native BB archive observed; fixture body removed, history retained in {output}', flush=True)
    return 0


def run_children(request, agent_id, project_id, model, reasoning_level, timeout):
    """The parent spawns a child into a station, waits for it, then archives it."""
    path = '/v1/agents/' + quote(agent_id, safe='')
    observation = request('/v1/state')['server']
    position = observation['player']['position']
    x, y, z = [math.floor(position[key]) for key in ('x', 'y', 'z')]
    station = request('/v1/agents/projects', {'operation': 'station-create', 'projectId': project_id,
                      'label': 'BB child smoke', 'dimension': observation['dimension'],
                      'min': [x + 5, y, z + 5], 'max': [x + 11, y + 5, z + 11]})
    observed = set()
    try:
        parent_thread = request(path)['threadId']
        prompt = ('This is an embodied coordination check. Run bb minecraft stations --json. Then run exactly once '
                  f'bb minecraft spawn --parent-self --name "BB child smoke" --body minecraft:pig --station {station["id"]} '
                  f'--model {model} --reasoning-level {reasoning_level} --json '
                  '--prompt "Reply BB_CHILD_OK. Do not call any tools or send messages.". '
                  'Use ordinary bb thread wait/show for the returned threadId until idle with no pending interactions. '
                  'Then run bb thread archive for that thread exactly once. Finally reply BB_COORDINATION_OK. '
                  'Never repeat a spawn or archive after an unknown outcome; inspect bb minecraft bodies instead.')
        request(path + '/message', {'text': prompt, 'delivery': 'auto'})
        deadline = time.monotonic() + timeout + 60
        while time.monotonic() < deadline:
            children = {row['id'] for row in request('/v1/agents') if row.get('parentThreadId') == parent_thread}
            for entity in request('/v1/state').get('server', {}).get('entities', []):
                if entity.get('agent', {}).get('id') in children:
                    point = entity['position']
                    if entity['type'] != 'minecraft:pig' or not all(low <= point[key] <= high + 1 for key, low, high in
                                                                     zip(('x', 'y', 'z'), station['min'], station['max'])):
                        raise RuntimeError('Child body is not a pig inside its station: ' + json.dumps(entity))
                    observed.add(entity['agent']['id'])
            current = request(path)
            if current.get('interactions'):
                raise RuntimeError('Child check needs human approval: ' + json.dumps(current['interactions']))
            timeline = request(path + '/transcript', {})
            if current.get('status') == 'idle' and any(row.get('role') == 'assistant' and 'BB_COORDINATION_OK' in row.get('text', '')
                                                       for row in timeline.get('rows', [])):
                break
            time.sleep(1)
        else:
            raise RuntimeError('Child coordination did not finish; fixture retained.')
        children = [row for row in request('/v1/agents') if row.get('parentThreadId') == parent_thread]
        if len(children) != 1:
            raise RuntimeError('Expected exactly one child: ' + json.dumps(children))
        child = children[0]
        if child['id'] not in observed:
            raise RuntimeError('Child body was never observed in its station.')
        if child['executionOptions'].get('model') != model or child['executionOptions'].get('reasoningLevel') != reasoning_level:
            raise RuntimeError('Child did not preserve execution options: ' + json.dumps(child['executionOptions']))
        if child['bodyLoaded'] or child['bodyRemoved'] or not child['conversationArchived']:
            raise RuntimeError('Child was not suspended and archived: ' + json.dumps(child))
        stations = request('/v1/agents/projects')['world']['stations']
        if next(item for item in stations if item['id'] == station['id']).get('agentId'):
            raise RuntimeError('Child station was not freed.')
        print('PASS: parent spawned, waited for and archived an embodied child in its station', flush=True)
    finally:
        request('/v1/agents/projects', {'operation': 'station-delete', 'stationId': station['id']})


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, RuntimeError) as error:
        print('Live BB smoke: ' + str(error), file=sys.stderr)
        sys.exit(1)
