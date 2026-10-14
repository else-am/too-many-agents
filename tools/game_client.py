"""Private local-game transport shared by diagnostics and live acceptance checks."""
import json
import os
from pathlib import Path
import urllib.error
import urllib.request
from urllib.parse import urlparse


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def game_directory(client_id=None, directory=None):
    if client_id:
        from launcher import client
        return client(client_id).game_dir.resolve()
    if directory:
        return Path(directory).resolve()
    selected = os.environ.get('TOO_MANY_AGENTS_GAME_DIR')
    if selected:
        return Path(selected).resolve()
    raise ValueError('Select a managed --client ID or an explicit --game-dir PATH.')


class GameClient:
    def __init__(self, directory, timeout=120):
        self.directory = Path(directory).resolve()
        self.timeout = timeout
        self.info = json.loads((self.directory / 'too-many-agents/connection.json').read_text())
        self.token = self.info.get('token', '')
        url = urlparse(self.info.get('url', ''))
        if (self.info.get('protocol') != 1 or url.scheme != 'http' or url.hostname != '127.0.0.1'
                or not url.port or url.username or url.password or url.path not in ('', '/')
                or url.query or url.fragment or not isinstance(self.token, str) or not self.token):
            raise ValueError('Unsupported local Minecraft connection descriptor.')
        if self.info.get('gameDirectory') and Path(self.info['gameDirectory']).resolve() != self.directory:
            raise ValueError('Connection descriptor belongs to another game directory.')
        self.url = self.info['url'].rstrip('/')
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())

    def request(self, path, data=None):
        body = None if data is None else json.dumps(data, allow_nan=False).encode()
        message = urllib.request.Request(self.url + path, data=body, headers={
            'Authorization': 'Bearer ' + self.token, 'Content-Type': 'application/json'})
        with self.opener.open(message, timeout=self.timeout) as response:
            result = json.load(response)
        if path == '/v1/state' and result.get('pid') and result['pid'] != self.info.get('pid'):
            raise ValueError('Connection descriptor belongs to another game process.')
        return result

    def redact(self, value):
        return str(value).replace(self.token, '[redacted]')

    def require_fixture(self):
        """Check the game's own development guard before a test can mutate its world."""
        self.request('/v1/dev')
        world = self.request('/v1/agents/projects')['world']
        expected = self.directory / 'saves/too-many-agents-development'
        if world.get('directory') != str(expected) or world.get('needsDecision'):
            raise ValueError('Open the guarded development fixture world in the selected client.')
        return world


def fixture_directory():
    """Checks get their target only from tools/check or an explicit environment selection."""
    return game_directory()


def plugin_id(directory=None):
    folder = Path(directory) if directory else fixture_directory()
    status = folder / 'too-many-agents/development.json'
    return json.loads(status.read_text())['pluginId'] if status.is_file() else 'tma'


def tool_name(name, directory=None):
    plugin = plugin_id(directory)
    if plugin in ('tma', 'minecraft'): return name
    suffix = name.removeprefix('minecraft_')
    prefix = 'minecraft' + plugin[3:] if plugin == 'tma' or plugin.startswith('tma-') else plugin
    return prefix.replace('-', '_') + '_' + suffix
