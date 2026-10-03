#!/usr/bin/env python3
"""Small local client. No third-party packages, retries, or credential output."""
import argparse
import json
import os
from pathlib import Path
import sys
import urllib.error
import urllib.request
from urllib.parse import urlparse


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--game-dir', type=Path, default=os.environ.get('TOO_MANY_AGENTS_GAME_DIR'))
    commands = parser.add_subparsers(dest='action', required=True)
    commands.add_parser('state')
    run = commands.add_parser('command')
    run.add_argument('text', help='Minecraft command, quoted as one argument')
    run.add_argument('--session', help='Require the world session from a previous observation')
    args = parser.parse_args()
    if not args.game_dir:
        parser.error('supply --game-dir or set TOO_MANY_AGENTS_GAME_DIR to the Minecraft game directory (e.g. run)')
    try:
        info = json.loads((Path(args.game_dir) / 'too-many-agents/connection.json').read_text())
        url = urlparse(info['url'])
        if info.get('protocol') != 1 or url.scheme != 'http' or url.hostname != '127.0.0.1':
            raise ValueError('unsupported local connection descriptor')
        # Do not forward this token through a configured system proxy or redirects.
        class NoRedirect(urllib.request.HTTPRedirectHandler):
            def redirect_request(self, *args, **kwargs):
                return None
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

        def request(path, data=None):
            body = None if data is None else json.dumps(data).encode()
            req = urllib.request.Request(info['url'] + path, data=body, headers={
                'Authorization': 'Bearer ' + info['token'], 'Content-Type': 'application/json'})
            with opener.open(req, timeout=6) as response:
                return json.load(response)

        if args.action == 'state':
            result = request('/v1/state')
        else:
            session = args.session or request('/v1/state').get('session')
            if not session:
                raise ValueError('no singleplayer world is open')
            result = request('/v1/command', {'session': session, 'command': args.text})
        print(json.dumps(result, indent=2))
    except urllib.error.HTTPError as error:
        print(f'HTTP {error.code}: {error.read().decode()}', file=sys.stderr)
        return 1
    except (OSError, ValueError, KeyError, urllib.error.URLError) as error:
        print(f'Too Many Agents unavailable: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
