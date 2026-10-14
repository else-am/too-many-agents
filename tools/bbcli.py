"""The user's installed BB CLI, and the development plugin instances we own in it."""
import json
import os
from pathlib import Path
import plistlib
import shutil
import subprocess
import sys
import time
import urllib.request


def build_environment():
    env = dict(os.environ)
    # Finder does not inherit the terminal's Homebrew/user PATH.
    if sys.platform == 'darwin':
        env['PATH'] = os.pathsep.join([env.get('PATH', ''), '/opt/homebrew/bin',
                                     '/usr/local/bin', str(Path.home() / '.local/bin')])
    return env


def bb_command(env):
    executable = env.get('BB_CLI') or shutil.which('bb', path=env.get('PATH'))
    candidates = [Path(executable)] if executable else []
    if not executable and sys.platform == 'darwin':
        candidates += [Path('/Applications/BB.app'), Path.home() / 'Applications/BB.app']
    for candidate in candidates:
        path = candidate.resolve()
        app = next((parent for parent in [path, *path.parents] if parent.suffix == '.app'), None)
        if app and (app / 'Contents/Info.plist').is_file():
            script = app / 'Contents/Resources/app.asar.unpacked/node_modules/bb-app/host-daemon/dist/bb'
            with (app / 'Contents/Info.plist').open('rb') as source:
                binary = plistlib.load(source)['CFBundleExecutable']
            runtime = app / 'Contents/MacOS' / binary
            if script.is_file() and runtime.is_file():
                # Use BB's bundled runtime without opening another desktop app.
                env.pop('BB_CLI', None)
                env['ELECTRON_RUN_AS_NODE'] = '1'
                return [str(runtime), str(script)]
        elif path.is_file():
            return [str(path)]
    raise RuntimeError('Could not find the installed BB CLI. Install BB in Applications, add bb to PATH, or set BB_CLI.')


class UnknownOutcome(RuntimeError):
    """BB may or may not have applied a change. Never retry it automatically."""


def _cli():
    env = build_environment()
    command = bb_command(env)
    # A launcher may run inside a BB thread. Its context must not redirect plugin management.
    for key in [key for key in env if key.startswith('BB_')]:
        del env[key]
    return command, env


def run(*args, timeout=60):
    command, env = _cli()
    try:
        done = subprocess.run([*command, *args, '--json'], env=env, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        raise UnknownOutcome(f"BB did not finish 'bb {' '.join(args[:3])}' in {timeout}s; check BB before retrying.") from None
    if done.returncode != 0:
        detail = (done.stderr or done.stdout).strip().splitlines()
        raise RuntimeError(f"'bb {' '.join(args[:3])}' failed: {detail[-1] if detail else 'exit ' + str(done.returncode)}")
    return json.loads(done.stdout or '{}')


def own(plugin_id):
    if not plugin_id.startswith('minecraft-dev-'):
        raise RuntimeError(f'Refusing to manage plugin {plugin_id}; development clients only own minecraft-dev-* plugins.')


def installed(plugin_id):
    return next((row for row in run('plugin', 'list')['plugins'] if row['id'] == plugin_id), None)


def install(plugin_id, directory):
    """Points our plugin instance at an immutable staged directory and waits until it runs there."""
    own(plugin_id)
    directory = Path(directory).resolve()
    current = installed(plugin_id)
    if current and not current['source'].startswith('path:'):
        raise RuntimeError(f'{plugin_id} is installed from {current["source"]}; remove it in BB first.')
    try:
        if current and Path(current['rootDir']).resolve() == directory:
            if current['status'] != 'running':
                run('plugin', 'reload', plugin_id)
        else:
            run('plugin', 'install', f'path:{directory}', '--yes', timeout=180)
        # A path move preserves a disabled plugin's state.
        run('plugin', 'enable', plugin_id)
    except RuntimeError as failure:
        raise UnknownOutcome(f'{failure}. Inspect {plugin_id} in BB, then reload this client to retry.') from None
    deadline = time.monotonic() + 30
    while True:
        row = installed(plugin_id)
        if row and Path(row['rootDir']).resolve() == directory and row['status'] == 'running':
            return row
        if time.monotonic() > deadline:
            state = 'missing' if not row else f"{row['status']} at {row['rootDir']}"
            raise UnknownOutcome(f'BB did not confirm {plugin_id} running from {directory} ({state}).')
        time.sleep(0.5)


def remove(plugin_id):
    own(plugin_id)
    if installed(plugin_id):
        run('plugin', 'remove', plugin_id)
        return True
    return False


def setup_status(plugin_id):
    """The plugin's own setup report through its BB discovery record, or None if unavailable."""
    now = time.time() * 1000
    for record in sorted((Path.home() / '.too-many-agents/bb').glob('*.json')):
        try:
            data = json.loads(record.read_text())
            if data.get('pluginId') != plugin_id or data.get('expiresAt', 0) <= now:
                continue
            url = data['serverUrl'].rstrip('/')
            if not url.startswith(('http://127.0.0.1:', 'http://[::1]:')):
                continue
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
            with opener.open(f'{url}/api/v1/plugins/{plugin_id}/http/v1/setup', timeout=5) as response:
                return json.loads(response.read())
        except (OSError, ValueError, KeyError):
            continue
    return None
