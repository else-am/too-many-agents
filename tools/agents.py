#!/usr/bin/env python3
"""Use Too Many Agents's local agent bridge. No third-party packages or automatic retries."""
import argparse
import base64
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request
from urllib.parse import quote, urlparse


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir', type=Path, default=os.environ.get('TOO_MANY_AGENTS_GAME_DIR'))
    parser.add_argument('--timeout', type=float, default=120, help='HTTP timeout in seconds; timed out mutations must not be retried blindly')
    commands = parser.add_subparsers(dest='action', required=True)
    listing = commands.add_parser('list')
    listing.add_argument('--archived', action='store_true', help='Show only archived agents')
    commands.add_parser('catalog').add_argument('--provider', help='Native BB provider ID, such as codex or claude-code')
    commands.add_parser('stations', help="List the current world's stations and occupants")
    settings = commands.add_parser('settings')
    settings.add_argument('id')
    settings.add_argument('settings', help='JSON object of changed settings')
    dev = commands.add_parser('dev', help='Opt-in isolated development world checks')
    dev.add_argument('operation', choices=['status', 'native-checks', 'pov', 'save'])
    spawn = commands.add_parser('spawn')
    spawn.add_argument('--provider', help='Native BB provider ID; omit to use BB defaults')
    spawn.add_argument('--permission-mode', help='Native BB permission mode; omit to use BB defaults')
    spawn.add_argument('--name', default='', help='Omit for a random starter name')
    spawn.add_argument('--project-id', help='Native BB project ID; omitted uses this world')
    spawn.add_argument('--environment', help='Native BB environment JSON; omitted uses the project default')
    spawn.add_argument('--body', default='', help='Omit for a random starter body')
    spawn.add_argument('--model')
    spawn.add_argument('--reasoning-level', '--effort', dest='reasoning_level', help='Native BB reasoning level')
    spawn.add_argument('--json', default='{}', help='Additional native BB spawn fields as a JSON object')
    spawn.add_argument('--service-tier', help='Native BB speed tier; omit to use BB defaults')
    spawn.add_argument('--mode', choices=['survival', 'creative', 'creative_commands'], default='survival')
    spawn.add_argument('--no-minecraft', action='store_true', help='Keep the body and BB coordination, without physical Minecraft tools')
    transcript = commands.add_parser('transcript', help='Read the native BB conversation timeline')
    transcript.add_argument('id')
    transcript.add_argument('--json', default='{}', help='Native BB timeline query fields')
    get = commands.add_parser('get')
    get.add_argument('id')
    send = commands.add_parser('send')
    send.add_argument('id')
    send.add_argument('text')
    send.add_argument('--model')
    send.add_argument('--reasoning-level', '--effort', dest='reasoning_level')
    send.add_argument('--service-tier', help='Speed tier for a new turn; omitted inherits the agent setting')
    send.add_argument('--delivery', choices=['send', 'steer', 'queue'], default='send')
    cancel = commands.add_parser('cancel-queued')
    cancel.add_argument('id')
    cancel.add_argument('message_id')
    for action, help_text in (
        ('remove', 'Remove the NPC body; keep the conversation'),
        ('archive', 'Archive the agent and its BB thread; requires BB for bound agents'),
        ('conversation-archive', 'Archive the agent and its BB thread; requires BB for bound agents'),
        ('conversation-restore', 'Restore the agent and unarchive its BB thread'),
        ('inventory', 'Inspect body inventory'),
    ):
        commands.add_parser(action, help=help_text).add_argument('id')
    stop = commands.add_parser('stop')
    stop.add_argument('id')
    respond = commands.add_parser('respond')
    respond.add_argument('id')
    respond.add_argument('request')
    respond.add_argument('resolution', help='Native BB interaction resolution JSON object')
    tool = commands.add_parser('tool')
    tool.add_argument('id')
    tool.add_argument('name')
    tool.add_argument('arguments', help='JSON object, quoted as one argument')
    ui = commands.add_parser('ui')
    ui.add_argument('ui_action')
    ui.add_argument('--agent')
    ui.add_argument('--json', default='{}', help='Additional UI action fields as a JSON object')
    projects = commands.add_parser('projects')
    projects.add_argument('--json', help='Project operation JSON; omit to list')
    args = parser.parse_args()
    if not args.game_dir:
        parser.error('supply --game-dir or set TOO_MANY_AGENTS_GAME_DIR to the Minecraft game directory (e.g. run)')
    if args.timeout <= 0:
        parser.error('--timeout must be positive')

    token = ''
    try:
        info = json.loads((Path(args.game_dir) / 'too-many-agents/connection.json').read_text())
        url = urlparse(info['url'])
        if (info.get('protocol') != 1 or url.scheme != 'http' or url.hostname != '127.0.0.1'
                or not url.port or url.username or url.password or url.path not in ('', '/')
                or url.query or url.fragment):
            raise ValueError('unsupported local connection descriptor')
        credential = info['token']
        if not isinstance(credential, str) or not credential:
            raise ValueError('invalid local connection credential')
        token = credential
        # Never forward the credential through a system proxy or an HTTP redirect.
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

        path = '/v1/agents'
        data = None
        if args.action in ('projects', 'stations'):
            path += '/projects'
            if getattr(args, 'json', None):
                data = json.loads(args.json)
        elif args.action == 'catalog':
            path += '/' + args.action
            if args.action == 'catalog' and args.provider:
                path += '/' + args.provider
        elif args.action == 'dev':
            path = '/v1/dev'
            if args.operation != 'status':
                data = {'action': args.operation.replace('-', '_')}
        elif args.action == 'spawn':
            path += '/spawn'
            data = {key: getattr(args, key) for key in ('name', 'body', 'mode')}
            if args.model: data['model'] = args.model
            if args.reasoning_level: data['reasoningLevel'] = args.reasoning_level
            if args.project_id: data['projectId'] = args.project_id
            if args.environment:
                environment = json.loads(args.environment)
                if not isinstance(environment, dict):
                    raise ValueError('environment must be a native BB JSON object')
                data['environment'] = environment
            if args.provider: data['providerId'] = args.provider
            if args.permission_mode:
                data['permissionMode'] = args.permission_mode
            data['minecraftAccess'] = not args.no_minecraft
            if args.service_tier: data['serviceTier'] = args.service_tier
            values = json.loads(args.json)
            if not isinstance(values, dict):
                raise ValueError('spawn fields must be a JSON object')
            data.update(values)
        elif args.action == 'ui':
            path = '/v1/ui'
            data = json.loads(args.json)
            if not isinstance(data, dict):
                raise ValueError('UI action fields must be a JSON object')
            data['action'] = args.ui_action
            if args.agent:
                data['agentId'] = args.agent
        elif args.action != 'list':
            path += '/' + quote(args.id, safe='')
            if args.action == 'settings':
                path += '/settings'
                data = json.loads(args.settings)
                if not isinstance(data, dict):
                    raise ValueError('settings must be a JSON object')
            elif args.action == 'transcript':
                path += '/transcript'
                data = json.loads(args.json)
            elif args.action == 'send':
                path += '/message'
                data = {'text': args.text, 'delivery': args.delivery}
                if args.service_tier is not None:
                    if args.service_tier: data['serviceTier'] = args.service_tier
                if args.model is not None:
                    data['model'] = args.model
                if args.reasoning_level is not None:
                    data['reasoningLevel'] = args.reasoning_level
            elif args.action == 'cancel-queued':
                path += '/cancel-queued'
                data = {'messageId': args.message_id}
            elif args.action in ('remove', 'archive', 'conversation-archive', 'conversation-restore', 'inventory'):
                path += '/' + args.action
                data = {}
            elif args.action == 'stop':
                path += '/interrupt'
                data = {}
            elif args.action == 'respond':
                path += '/respond'
                resolution = json.loads(args.resolution)
                if not isinstance(resolution, dict):
                    raise ValueError('interaction resolution must be a JSON object')
                data = {'requestId': args.request, 'resolution': resolution}
            elif args.action == 'tool':
                path += '/tool'
                arguments = json.loads(args.arguments)
                if not isinstance(arguments, dict):
                    raise ValueError('tool arguments must be a JSON object')
                data = {'tool': args.name, 'arguments': arguments}
        body = None if data is None else json.dumps(data, allow_nan=False).encode()
        request = urllib.request.Request(info['url'].rstrip('/') + path, data=body, headers={
            'Authorization': 'Bearer ' + token, 'Content-Type': 'application/json'})
        with opener.open(request, timeout=args.timeout) as response:
            result = json.load(response)
        if args.action == 'stations':
            result = result.get('world', {}).get('stations', [])
        if args.action == 'list' and args.archived and isinstance(result, list):
            result = [agent for agent in result if agent.get('conversationArchived', False)]
        if isinstance(result, dict) and 'imageDataUrl' in result:
            if args.action == 'dev' and args.operation == 'pov':
                image = result['imageDataUrl']
                if not image.startswith('data:image/png;base64,'):
                    raise ValueError('Unexpected POV image format')
                target = Path(args.game_dir) / 'too-many-agents/development-pov.png'
                target.write_bytes(base64.b64decode(image.split(',', 1)[1], validate=True))
                result['path'] = str(target.resolve())
            result.pop('imageDataUrl')  # PNG already saved by the mod; keep its path and metadata readable.
        print(redact(json.dumps(result, indent=2), token))
    except urllib.error.HTTPError as error:
        response = error.read().decode(errors='replace')
        print(redact(json.dumps({'error': 'http_error', 'status': error.code, 'response': response}, indent=2), token), file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, TypeError, urllib.error.URLError) as error:
        message = str(error)
        if isinstance(error, TimeoutError) or 'timed out' in message:
            message += '; request outcome is unknown, inspect agent state before retrying'
        print(redact(json.dumps({'error': 'too_many_agents_unavailable', 'message': message}, indent=2), token), file=sys.stderr)
        return 1
    return 0


def redact(text, token):
    return text.replace(token, '[redacted]') if token else text


if __name__ == '__main__':
    sys.exit(main())
