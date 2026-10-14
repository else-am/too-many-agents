"""Named development clients: each a frozen packaged build of this checkout, its own BB plugin
instance, and one small supervisor process that builds before touching the running game.

Layout (all under this checkout):
  run/play             personal client 'play' (your worlds)
  run                  guarded test client 'test'
  run/clients/<id>     other test clients
  run/dev/clients/<id> supervisor lock, logs, build logs and staged plugin packages
  run/dev/artifacts    frozen builds shared by this checkout's clients

<gameDir>/too-many-agents/development.json is the supervisor's status; the game reads it and
requests reloads through <gameDir>/too-many-agents/reload.request.
"""
import argparse
from dataclasses import dataclass
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import sys
import time
import threading
import urllib.error
import uuid

sys.path.insert(0, str(Path(__file__).resolve().parent))
import artifacts  # noqa: E402
import bbcli  # noqa: E402
from game_client import GameClient  # noqa: E402

ROOT = Path(__file__).resolve().parents[1]
CHECKOUT = ROOT.resolve()
CLIENTS = ROOT / 'run/dev/clients'
ID = re.compile(r'[a-z0-9]+(-[a-z0-9]+)*')
HEARTBEAT = 5
READY_TIMEOUT = 600
EXIT_TIMEOUT = 90
QUIET_OPTIONS = ['version:3955', 'onboardAccessibility:false', 'narrator:0', 'soundCategory_master:0.0',
                 'tutorialStep:none', 'pauseOnLostFocus:false']


@dataclass(frozen=True)
class Client:
    id: str
    game_dir: Path
    state_dir: Path
    plugin_id: str
    test: bool

    @property
    def status_file(self):
        return self.game_dir / 'too-many-agents/development.json'


def client(client_id):
    """Paths and identity for a client id. Pure; creates nothing."""
    if not ID.fullmatch(client_id or '') or len(client_id) > 16:
        raise ValueError(f'Client ids are 1-16 lowercase letters, digits and single hyphens, not {client_id!r}.')
    game_dir = {'play': ROOT / 'run/play', 'test': ROOT / 'run'}.get(client_id, ROOT / 'run/clients' / client_id)
    suffix = hashlib.sha256(f'{CHECKOUT}\n{client_id}'.encode()).hexdigest()[:8]
    return Client(client_id, game_dir, CLIENTS / client_id, f'minecraft-dev-{client_id}-{suffix}', client_id != 'play')


def clients():
    return sorted(path.name for path in CLIENTS.iterdir() if path.is_dir() and ID.fullmatch(path.name)) if CLIENTS.is_dir() else []


def read_status(c):
    try:
        return json.loads(c.status_file.read_text())
    except (OSError, ValueError):
        return {}


def write_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_name(f'.{path.name}.{os.getpid()}.tmp')
    temporary.write_text(json.dumps(data, indent=2) + '\n')
    temporary.replace(path)


def locked(path, *, record=False):
    """Whether another process holds this lock file. Never blocks."""
    if not path.exists():
        return False
    with path.open('a') as handle:
        locking = fcntl.lockf if record else fcntl.flock
        try:
            locking(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            return True
        locking(handle, fcntl.LOCK_UN)
        return False


def take_lock(path, message, *, record=False):
    path.parent.mkdir(parents=True, exist_ok=True)
    handle = path.open('a')
    try:
        # dev.lock also coordinates with Java's POSIX record lock on Linux/macOS.
        (fcntl.lockf if record else fcntl.flock)(handle, fcntl.LOCK_EX | fcntl.LOCK_NB)
    except BlockingIOError:
        handle.close()
        raise RuntimeError(message) from None
    return handle


# Processes. A PID alone is never trusted: identity is the PID, its start time and our marker argument.

def process_info(pid):
    try:
        out = subprocess.run(['ps', '-ww', '-o', 'lstart=', '-o', 'command=', '-p', str(int(pid))],
                             capture_output=True, text=True, env={**os.environ, 'LC_ALL': 'C'}).stdout.strip()
    except (TypeError, ValueError):
        return None
    if not out:
        return None
    started = time.mktime(time.strptime(' '.join(out.split()[:5]), '%a %b %d %H:%M:%S %Y'))
    return started, ' '.join(out.split()[5:])


def same_process(identity, marker):
    info = identity and process_info(identity.get('pid'))
    return bool(info and abs(info[0] - identity['startedAt']) < 2 and marker in info[1])


class Game:
    """The JVM a supervisor launched, or one it adopted after the supervisor itself restarted."""

    def __init__(self, c, pid, started_at, artifact, child=None):
        self.c, self.pid, self.started_at, self.artifact, self.child = c, pid, started_at, artifact, child

    @property
    def marker(self):
        return f'-Dtoo_many_agents.devPluginId={self.c.plugin_id}'

    def identity(self):
        return {'pid': self.pid, 'startedAt': self.started_at, 'artifact': self.artifact}

    def exit_code(self):
        """None while running; the exit code, or 'unknown' for an adopted process."""
        if self.child:
            return self.child.poll()
        return None if same_process(self.identity(), self.marker) else 'unknown'

    def bridge(self, action, timeout=60, **fields):
        """POSTs a UI action to this exact game's authenticated local bridge."""
        bridge = GameClient(self.c.game_dir, timeout)
        if bridge.info.get('pid') != self.pid:
            raise ConnectionError('The game has not opened its local bridge yet.')
        try:
            return bridge.request('/v1/ui', {'action': action, **fields})
        except urllib.error.HTTPError as failure:
            try:
                detail = json.loads(failure.read()).get('error', '')
            except ValueError:
                detail = ''
            raise RuntimeError(f'The game refused {action}: {detail or failure.code}') from None

    def terminate(self):
        """SIGTERM for an explicit --force only, after re-checking identity."""
        if not same_process(self.identity(), self.marker):
            raise RuntimeError(f'Process {self.pid} is no longer this client\'s game; not signalling it.')
        os.kill(self.pid, signal.SIGTERM)


class Supervisor:
    def __init__(self, c):
        self.c = c
        self.status = {}
        self.game = None
        self.last_beat = 0
        self.stopping = False
        # Builds and installs block the main thread; a heartbeat thread keeps the status fresh meanwhile.
        self.lock = threading.RLock()
        self.beating = False

    # Status

    def publish(self, **changes):
        with self.lock:
            self._publish(changes)

    def _publish(self, changes):
        self.status.update(changes)
        self.status.update(protocol=1, client=self.c.id, pluginId=self.c.plugin_id, checkout=str(CHECKOUT),
                           gameDirectory=str(self.c.game_dir), supervisorPid=os.getpid(),
                           gamePid=self.game.pid if self.game else None,
                           game=self.game.identity() if self.game else None,
                           updatedAt=int(time.time() * 1000))
        write_json(self.c.status_file, self.status)
        self.last_beat = time.monotonic()

    def say(self, message, **changes):
        print(time.strftime('%H:%M:%S ') + message, flush=True)
        self.publish(message=message, **changes)

    def request_update(self, **changes):
        with self.lock:
            self._request_update(changes)

    def _request_update(self, changes):
        self.status['request'] = {**self.status['request'], **changes}
        self.publish()
        if self.status['request']['state'] in ('done', 'failed'):
            # Later requests replace status.request; waiters read their own result here.
            results = self.c.state_dir / 'results'
            write_json(results / f'{self.status["request"]["id"]}.json', self.status['request'])
            for old in sorted(results.glob('*.json'), key=lambda path: path.stat().st_mtime)[:-50]:
                old.unlink(missing_ok=True)

    # Building and installing

    def build(self):
        builds = self.c.state_dir / 'builds'
        builds.mkdir(parents=True, exist_ok=True)
        log = builds / f'{time.strftime("%Y%m%d-%H%M%S")}-{uuid.uuid4().hex[:6]}.log'
        if self.status.get('request'):
            self.request_update(state='building', log=str(log))
        self.say('Building this checkout...', phase='building')
        artifact = artifacts.build(log,
                                   on_wait=lambda: self.say('Waiting for another build in this checkout...'),
                                   on_ready=lambda built: self.request_update(artifact=built['id']))
        for old in sorted(builds.glob('*.log'))[:-20]:
            old.unlink(missing_ok=True)
        return artifact

    def ensure_plugin(self, artifact):
        """Makes BB run this client's plugin from the artifact. Skips BB when it already does."""
        current = self.status.get('plugin') or {}
        if current.get('component') == artifact['components']['plugin']:
            row = bbcli.installed(self.c.plugin_id)
            if row and row['status'] == 'running' and Path(row['rootDir']).resolve() == Path(current['root']).resolve():
                return False
        self.say(f'Installing plugin {self.c.plugin_id}...', phase='installing')
        directory, sidecar = artifacts.stage_plugin(artifact, self.c.state_dir / 'plugins', self.c.plugin_id, self.c.id)
        try:
            bbcli.install(self.c.plugin_id, directory)
        except bbcli.UnknownOutcome:
            # BB may now run either directory; Java verifies the fingerprint before connecting.
            self.publish(plugin={**current, 'state': 'unknown'})
            raise
        setup = bbcli.setup_status(self.c.plugin_id) or {}
        self.publish(fingerprint=sidecar['fingerprint'],
                     plugin={'id': self.c.plugin_id, 'root': str(directory), 'fingerprint': sidecar['fingerprint'],
                             'component': artifact['components']['plugin'], 'artifact': artifact['id'],
                             'generation': setup.get('generation'), 'state': 'running'})
        # Keep the previous package too; BB may still be finishing with it.
        keep = {directory.name, Path(current.get('root', '')).name}
        for old in (self.c.state_dir / 'plugins').iterdir():
            if old.name not in keep and not old.name.startswith('.'):
                shutil.rmtree(old, ignore_errors=True)
        return True

    def refresh_generation(self):
        setup = bbcli.setup_status(self.c.plugin_id)
        if setup and self.status.get('plugin'):
            self.publish(plugin={**self.status['plugin'], 'generation': setup.get('generation')})

    # The game

    def launch(self, artifact):
        foreign = self.foreign_game()
        if foreign:
            raise RuntimeError(f'Another Minecraft (pid {foreign}) is using {self.c.game_dir}. Close it first.')
        mods = self.c.game_dir / 'mods'
        mods.mkdir(parents=True, exist_ok=True)
        # Only ever replace our own JAR; other mods in the folder stay.
        for old in mods.glob('too-many-agents*.jar'):
            old.unlink()
        shutil.copyfile(Path(artifact['dir']) / artifact['jar'], mods / artifact['jar'])
        options = self.c.game_dir / 'options.txt'
        if self.c.test and not options.exists():
            options.write_text('\n'.join(QUIET_OPTIONS) + '\n')
        launch = artifact['launch']
        command = [launch['java'], '@' + launch['vmArgsFile'], '-Dfile.encoding=UTF-8',
                   f'-Dtoo_many_agents.devClient={self.c.id}', f'-Dtoo_many_agents.devPluginId={self.c.plugin_id}',
                   f'-Dtoo_many_agents.devWorld={str(self.c.test).lower()}',
                   '-cp', os.pathsep.join(launch['classpath']), launch['mainClass'], '@' + launch['programArgsFile']]
        env = {key: value for key, value in os.environ.items() if not key.startswith('BB_') and key != 'ELECTRON_RUN_AS_NODE'}
        log = self.c.state_dir / 'game.log'
        if log.exists():
            log.replace(self.c.state_dir / 'game.previous.log')
        with log.open('w') as output:
            child = subprocess.Popen(command, cwd=self.c.game_dir, env=env, stdin=subprocess.DEVNULL,
                                     stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
        info = process_info(child.pid)
        self.game = Game(self.c, child.pid, info[0] if info else time.time(), artifact['id'], child)
        self.publish(running={'artifact': artifact['id'], 'builtAt': artifact['builtAt'], 'version': artifact['version'],
                              **artifact['components']}, ready=False)
        self.say(f'Started Minecraft (pid {child.pid}) from artifact {artifact["id"]}; waiting for it to be ready...',
                 phase='starting', launchedAt=int(time.time() * 1000))
        deadline = time.monotonic() + READY_TIMEOUT
        while time.monotonic() < deadline:
            code = self.game.exit_code()
            if code is not None:
                self.game = None
                self.publish(ready=False)
                raise RuntimeError(f'Minecraft exited while starting (code {code}). Log: {log}')
            if self.probe():
                self.refresh_generation()
                self.say('Minecraft is ready.', phase='running')
                return
            time.sleep(1)
        self.say(f'Minecraft is running but not ready after {READY_TIMEOUT}s; check BB setup in its mod settings.',
                 phase='running')

    def probe(self):
        try:
            reply = self.game.bridge('development_status', timeout=5)
        except (OSError, ValueError, RuntimeError):
            return False
        self.publish(ready=bool(reply.get('ready')))
        return self.status['ready']

    def foreign_game(self):
        """PID of a live game that owns this directory's bridge but isn't ours (e.g. a Gradle run)."""
        try:
            info = json.loads((self.c.game_dir / 'too-many-agents/connection.json').read_text())
        except (OSError, ValueError):
            return None
        pid = info.get('pid')
        if self.game and pid == self.game.pid:
            return None
        found = process_info(pid)
        if not found or 'java' not in found[1].lower():
            return None
        if info.get('startedAt') and abs(found[0] - info['startedAt'] / 1000) > 2:
            return None
        return pid

    def stop_game(self, request_id, force=False):
        """Asks the game to save and quit, and waits for that exact process to exit."""
        game = self.game
        if game.exit_code() is not None:
            self.game = None
            return
        try:
            game.bridge('development_shutdown', request=request_id)
        except (OSError, ValueError, RuntimeError) as failure:
            if not force:
                raise RuntimeError(f'{failure}. The game is still running (pid {game.pid}); '
                                   'close it in game, or use tools/dev stop --force.') from None
            self.say(f'{failure}; sending SIGTERM to pid {game.pid} (--force).')
            game.terminate()
        self.say(f'Waiting for Minecraft (pid {game.pid}) to save and exit...', phase='stopping')
        deadline = time.monotonic() + EXIT_TIMEOUT
        while game.exit_code() is None:
            if time.monotonic() > deadline:
                # Its state is unknown; never escalate on our own.
                raise RuntimeError(f'Minecraft (pid {game.pid}) acknowledged shutdown but is still running after '
                                   f'{EXIT_TIMEOUT}s. Check it before stopping it yourself.')
            time.sleep(0.5)
        self.game = None
        self.publish(ready=False)

    # Requests

    def next_request(self):
        requests = self.c.state_dir / 'requests'
        for path in sorted(requests.glob('*.json'), key=lambda p: p.stat().st_mtime) if requests.is_dir() else []:
            try:
                data = json.loads(path.read_text())
                return {'id': data['id'], 'source': 'cli', 'action': data['action'], 'force': data.get('force', False)}
            except (ValueError, KeyError):
                print(f'Ignoring unreadable request {path.name}', flush=True)
            finally:
                path.unlink(missing_ok=True)
        try:
            data = json.loads((self.c.game_dir / 'too-many-agents/reload.request').read_text())
        except (OSError, ValueError):
            return None
        if data.get('id') and data['id'] != self.status.get('handledGameRequest'):
            with self.lock:
                self.status['handledGameRequest'] = data['id']
            return {'id': data['id'], 'source': 'game', 'action': 'reload'}
        return None

    def handle(self, request):
        self.publish(request={'id': request['id'], 'source': request['source'], 'action': request['action'],
                              'state': 'queued', 'change': None, 'error': '', 'log': '',
                              'requestedAt': int(time.time() * 1000)})
        try:
            if request['action'] == 'stop':
                self.request_update(state='applying')
                if self.game:
                    self.stop_game(request['id'], request.get('force'))
                self.stopping = True
            else:
                self.reload()
            self.request_update(state='done', finishedAt=int(time.time() * 1000))
        except Exception as failure:  # noqa: BLE001 - every failure is reported and leaves the game as it was
            phase = 'running' if self.game else 'failed'
            self.request_update(state='failed', error=str(failure), finishedAt=int(time.time() * 1000))
            self.say(f'{"Reload" if request["action"] == "reload" else "Stop"} failed: {failure}', phase=phase)

    def reload(self):
        artifact = self.build()
        running = self.status.get('running') if self.game else None
        what = artifacts.change(running, artifact['components'])
        self.request_update(change=what, artifact=artifact['id'], state='applying')
        if self.game and what in ('game', 'both'):
            self.say(f'Restarting Minecraft for artifact {artifact["id"]} ({what} changed)...', phase='restarting')
            self.stop_game(self.status['request']['id'])
        plugin_changed = self.ensure_plugin(artifact)
        if not self.game:
            self.launch(artifact)
        else:
            self.publish(running={**self.status['running'], 'artifact': artifact['id'],
                                  'builtAt': artifact['builtAt'], 'plugin': artifact['components']['plugin']})
            if plugin_changed:
                if what == 'none':
                    self.request_update(change='plugin')
                self.say(f'Reloaded the plugin from artifact {artifact["id"]}; the game stayed open.', phase='running')
            else:
                self.say(f'Already running artifact {artifact["id"]}; nothing to reload.', phase='running')
        artifacts.prune(self.artifacts_in_use)

    def artifacts_in_use(self):
        used = set()
        for other in clients():
            status = read_status(client(other))
            used.update(filter(None, [(status.get(key) or {}).get('artifact') for key in ('running', 'plugin', 'game')]))
            request = status.get('request') or {}
            if request.get('state') in ('queued', 'building', 'applying') and request.get('artifact'):
                used.add(request['artifact'])
        return used

    # Lifecycle

    def heartbeat(self):
        while self.beating:
            time.sleep(1)
            if self.beating and time.monotonic() - self.last_beat >= HEARTBEAT:
                self.publish()

    def check_exit(self):
        code = self.game.exit_code()
        if code is None:
            return
        self.game = None
        if code == 0:
            self.say('Minecraft closed; supervisor finished.', phase='exited', ready=False)
            self.stopping = True
        else:
            reports = sorted((self.c.game_dir / 'crash-reports').glob('*.txt'), key=lambda p: p.stat().st_mtime)
            recent = [str(path) for path in reports if path.stat().st_mtime * 1000 >= self.status.get('launchedAt', 0)]
            detail = f' Crash report: {recent[-1]}.' if recent else ''
            self.say(f'Minecraft exited unexpectedly (code {code}).{detail} Log: {self.c.state_dir / "game.log"}. '
                     'Fix and run tools/dev reload, or tools/dev stop.', phase='crashed', ready=False, exitCode=code)

    def run(self):
        self.c.state_dir.mkdir(parents=True, exist_ok=True)
        own = take_lock(self.c.state_dir / 'supervisor.lock', f'Client {self.c.id} already has a supervisor.')
        self.c.game_dir.mkdir(parents=True, exist_ok=True)
        directory = take_lock(self.c.game_dir / 'dev.lock', f'Another development session is using {self.c.game_dir}.', record=True)
        signal.signal(signal.SIGTERM, lambda *_: setattr(self, 'stopping', True))
        signal.signal(signal.SIGINT, signal.SIG_IGN)
        # Requests sent to an earlier supervisor were never accepted.
        shutil.rmtree(self.c.state_dir / 'requests', ignore_errors=True)
        self.status = {key: value for key, value in read_status(self.c).items()
                       if key in ('running', 'plugin', 'fingerprint', 'handledGameRequest')}
        self.beating = True
        threading.Thread(target=self.heartbeat, name='heartbeat', daemon=True).start()
        try:
            previous = read_status(self.c).get('game')
            if previous and same_process(previous, f'-Dtoo_many_agents.devPluginId={self.c.plugin_id}'):
                self.game = Game(self.c, previous['pid'], previous['startedAt'], previous.get('artifact'))
                self.say(f'Reattached to running Minecraft (pid {self.game.pid}).', phase='running', request=None)
            else:
                with self.lock:
                    self.status.pop('running', None)
                # A reload request written before this start has already been answered or abandoned.
                try:
                    pending = json.loads((self.c.game_dir / 'too-many-agents/reload.request').read_text())
                    self.publish(handledGameRequest=pending.get('id'))
                except (OSError, ValueError):
                    pass
                self.say('Starting...', phase='starting',
                         request={'id': str(uuid.uuid4()), 'source': 'cli', 'action': 'start', 'state': 'queued',
                                  'change': None, 'error': '', 'log': '', 'requestedAt': int(time.time() * 1000)})
                try:
                    self.reload()
                    self.request_update(state='done', finishedAt=int(time.time() * 1000))
                except Exception as failure:  # noqa: BLE001
                    self.request_update(state='failed', error=str(failure), finishedAt=int(time.time() * 1000))
                    self.say(f'Start failed: {failure}', phase='failed')
                    return 1
            while not self.stopping:
                if self.game:
                    self.check_exit()
                if self.stopping:
                    break
                request = self.next_request()
                if request:
                    self.handle(request)
                    continue
                if self.game and time.monotonic() - self.last_beat >= HEARTBEAT:
                    self.probe()
                time.sleep(0.5)
            self.beating = False
            if self.game:
                self.say(f'Supervisor stopped; Minecraft (pid {self.game.pid}) keeps running. '
                         'tools/dev start reattaches.', phase='detached')
            elif self.status.get('phase') not in ('exited',):
                self.say('Stopped.', phase='stopped')
            return 0
        finally:
            self.beating = False
            directory.close()
            own.close()


# Command line

def supervisor_alive(c):
    return locked(c.state_dir / 'supervisor.lock')


def summary(c):
    status = read_status(c)
    alive = supervisor_alive(c)
    game = status.get('game')
    phase = status.get('phase', 'never started')
    if not alive and phase not in ('never started', 'stopped', 'exited', 'failed', 'detached'):
        phase += ' (supervisor gone)'
    try:
        game_alive = bool(game and same_process(game, f'-Dtoo_many_agents.devPluginId={c.plugin_id}'))
        process_error = ''
    except (OSError, ValueError) as failure:
        # A sandboxed caller can read a successful request but cannot always inspect host processes.
        # Keep that outcome; process-control paths still require their own identity check.
        game_alive, process_error = None, str(failure)
    return {**status, 'client': c.id, 'pluginId': c.plugin_id, 'gameDirectory': str(c.game_dir),
            'stateDirectory': str(c.state_dir), 'supervisorAlive': alive, 'phase': phase,
            'gameAlive': game_alive, 'processCheckError': process_error}


def show(row):
    game = f'pid {row["gamePid"]}' if row.get('gameAlive') else '-'
    artifact = (row.get('running') or {}).get('artifact', '-')
    return f'{row["client"]:<16} {row["phase"]:<20} {game:<12} {artifact:<12} {row["gameDirectory"]}'


def wait(c, until, supervisor=None, quiet=False):
    """Follows status messages until `until(status)` holds or the supervisor goes away."""
    last = None
    while True:
        gone = supervisor.poll() is not None if supervisor else not supervisor_alive(c)
        status = read_status(c)
        current = supervisor is None or status.get('supervisorPid') == supervisor.pid
        if current and status.get('message') != last:
            last = status.get('message')
            if last and not quiet:
                print('[dev] ' + last, file=sys.stderr, flush=True)
        if current and until(status):
            return status
        if gone:
            if supervisor and not quiet:
                tail = (c.state_dir / 'supervisor.log').read_text(errors='replace').strip().splitlines()[-3:]
                print('\n'.join('[dev] ' + line for line in tail), file=sys.stderr, flush=True)
            return None
        time.sleep(0.5)


def send(c, action, force=False):
    request_id = str(uuid.uuid4())
    write_json(c.state_dir / 'requests' / f'{time.time_ns()}-{request_id}.json',
               {'id': request_id, 'action': action, 'force': force})
    return request_id


def request_result(c, request_id):
    try:
        return json.loads((c.state_dir / 'results' / f'{request_id}.json').read_text())
    except (OSError, ValueError):
        return None


def finished(c, request_id):
    return lambda status: request_result(c, request_id) is not None


def command_start(c, args):
    if supervisor_alive(c):
        status = read_status(c)
        if status.get('phase') in ('crashed', 'failed'):
            return command_request(c, args, 'reload')
        raise RuntimeError(f'Client {c.id} is already running ({status.get("phase")}). Use tools/dev reload {c.id}.')
    if locked(c.game_dir / 'dev.lock', record=True):
        raise RuntimeError(f'Another development session is using {c.game_dir}.')
    c.state_dir.mkdir(parents=True, exist_ok=True)
    log = (c.state_dir / 'supervisor.log').open('a')
    supervisor = subprocess.Popen([sys.executable, '-u', str(Path(__file__).resolve()), 'supervise', c.id],
                                  cwd=ROOT, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                  start_new_session=True)
    if not args.json:
        print(f'[dev] Supervisor for {c.id} started (pid {supervisor.pid}); log {c.state_dir / "supervisor.log"}',
              file=sys.stderr, flush=True)
    if args.no_wait:
        return 0
    status = wait(c, lambda s: s.get('supervisorPid') == supervisor.pid and
                  s.get('phase') in ('running', 'crashed', 'failed', 'exited'), supervisor, args.json)
    return result(c, args, status is not None and status.get('phase') == 'running')


def command_request(c, args, action):
    if not supervisor_alive(c):
        if action == 'stop':
            return stop_without_supervisor(c, args)
        raise RuntimeError(f'Client {c.id} has no supervisor. Start it with tools/dev start {c.id}.')
    request_id = send(c, action, getattr(args, 'force', False))
    if args.no_wait:
        return result(c, args, True)
    wait(c, finished(c, request_id), quiet=args.json)
    outcome = request_result(c, request_id)
    if action == 'stop' and outcome:
        # The supervisor exits after a stop; let it publish its final state.
        deadline = time.monotonic() + 10
        while supervisor_alive(c) and time.monotonic() < deadline:
            time.sleep(0.2)
    return result(c, args, bool(outcome and outcome['state'] == 'done'), outcome)


def stop_without_supervisor(c, args):
    """The supervisor is gone but its game may not be: shut that game down directly."""
    status = read_status(c)
    if not status.get('game') or not same_process(status['game'], f'-Dtoo_many_agents.devPluginId={c.plugin_id}'):
        return result(c, args, True)
    supervisor = Supervisor(c)
    own = take_lock(c.state_dir / 'supervisor.lock', f'Client {c.id} already has a supervisor.')
    try:
        supervisor.status = status
        game = status['game']
        supervisor.game = Game(c, game['pid'], game['startedAt'], game.get('artifact'))
        request_id = str(uuid.uuid4())
        supervisor.status['request'] = {'id': request_id, 'source': 'cli', 'action': 'stop', 'state': 'applying',
                                        'change': None, 'error': '', 'log': '', 'requestedAt': int(time.time() * 1000)}
        supervisor.publish()
        supervisor.beating = True
        threading.Thread(target=supervisor.heartbeat, name='heartbeat', daemon=True).start()
        try:
            supervisor.stop_game(request_id, args.force)
        except RuntimeError as failure:
            supervisor.request_update(state='failed', error=str(failure))
            raise
        supervisor.request_update(state='done')
        supervisor.beating = False
        supervisor.say('Stopped.', phase='stopped')
    finally:
        supervisor.beating = False
        own.close()
    return result(c, args, True)


def command_dispose(c, args):
    message = f'Stop client {c.id} first (tools/dev stop {c.id}).'
    with take_lock(c.state_dir / 'supervisor.lock', message), \
            take_lock(c.game_dir / 'dev.lock', message, record=True):
        if summary(c)['gameAlive'] is not False:
            raise RuntimeError(message)
        removed = bbcli.remove(c.plugin_id)
        shutil.rmtree(c.state_dir / 'plugins', ignore_errors=True)
        c.status_file.unlink(missing_ok=True)
    message = (f'Removed BB plugin {c.plugin_id}.' if removed else f'BB had no plugin {c.plugin_id}.') + \
        f' Game directory kept: {c.game_dir}'
    if args.json:
        print(json.dumps({'client': c.id, 'pluginId': c.plugin_id, 'pluginRemoved': removed, 'gameDirectory': str(c.game_dir)}))
    else:
        print(message)
    return 0


def result(c, args, ok, request=None):
    row = summary(c)
    if request:
        row['request'] = request
    if args.json:
        print(json.dumps(row, indent=2))
    else:
        request = row.get('request') or {}
        print(show(row))
        if request.get('error'):
            print('error: ' + request['error'])
        if request.get('log'):
            print('build log: ' + request['log'])
    return 0 if ok else 1


COMMANDS = ('start', 'list', 'status', 'reload', 'stop', 'dispose', 'supervise')


def main(argv):
    if not argv or argv[0] not in COMMANDS and argv[0] not in ('-h', '--help'):
        argv = ['start', *argv]
    parser = argparse.ArgumentParser(prog='tools/dev', description='Run named development clients of this checkout.')
    commands = parser.add_subparsers(dest='command', required=True)
    for name, about in [('start', 'build, install the plugin and start a client (default)'),
                        ('list', 'list clients of this checkout'), ('status', 'show one client'),
                        ('reload', 'build and apply changes: none, plugin only, game restart, or both'),
                        ('stop', 'save, quit and stop the supervisor'),
                        ('dispose', "remove a stopped client's BB plugin and staged packages; keeps worlds"),
                        ('supervise', None)]:
        sub = commands.add_parser(name, **({'help': about} if about else {}))
        if name != 'list':
            sub.add_argument('id', nargs='?', help='client id (default play, or test with --test)')
            sub.add_argument('--test', action='store_true', help='the guarded test client in run/')
        sub.add_argument('--json', action='store_true')
        if name in ('start', 'reload', 'stop'):
            sub.add_argument('--no-wait', action='store_true')
        if name == 'stop':
            sub.add_argument('--force', action='store_true', help='SIGTERM the verified game process if it will not quit')
    args = parser.parse_args(argv)
    if args.command == 'list':
        rows = [summary(client(name)) for name in clients()]
        print(json.dumps(rows, indent=2) if args.json else '\n'.join(map(show, rows)) or 'No clients yet. Run tools/dev start.')
        return 0
    if args.test and args.id not in (None, 'test'):
        raise ValueError('--test names the client test; pass either --test or an id.')
    c = client(args.id or ('test' if args.test else 'play'))
    if args.command == 'supervise':
        try:
            return Supervisor(c).run()
        except RuntimeError as failure:
            print('error: ' + str(failure), flush=True)
            return 1
    if args.command == 'status':
        return result(c, args, True)
    if args.command == 'start':
        return command_start(c, args)
    if args.command == 'dispose':
        return command_dispose(c, args)
    return command_request(c, args, args.command)


def run():
    try:
        sys.exit(main(sys.argv[1:]))
    except (RuntimeError, ValueError, OSError) as error:
        print('[dev] ' + str(error), file=sys.stderr, flush=True)
        sys.exit(1)
    except KeyboardInterrupt:
        print('\n[dev] Stopped waiting; the supervisor keeps running. tools/dev status shows progress.', file=sys.stderr)
        sys.exit(130)


if __name__ == '__main__':
    run()
