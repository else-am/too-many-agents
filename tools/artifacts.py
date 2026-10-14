"""Checkout builds frozen into immutable artifacts, and which components differ between two of them.

An artifact is run/dev/artifacts/<id>/: the packaged mod JAR, its launch files, the plugin
staging script and manifest.json. Running clients only reference artifact files, so later
builds in the checkout never change what a running or restarting client uses.
"""
import contextlib
import fcntl
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
import uuid
import zipfile

from bbcli import build_environment

ROOT = Path(__file__).resolve().parents[1]
BUILD = ROOT / 'build'
DEV = ROOT / 'run/dev'
ARTIFACTS = DEV / 'artifacts'
LIBS = DEV / 'libs'
# Everything the JAR and its bundled plugin are built from.
SOURCES = ['src', 'bb-plugin', 'surface', 'build.gradle', 'settings.gradle', 'gradle.properties',
           'gradle', 'tools/build', 'tools/package-plugin', 'tools/bbcli.py']
PLUGIN_ENTRY = 'too_many_agents/bb-plugin.zip'
CONTRACT_ENTRY = 'too_many_agents/surface/tools.json'
KEEP = 3


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def source_fingerprint():
    names = subprocess.run(['git', 'ls-files', '-z', '-c', '-o', '--exclude-standard', '--', *SOURCES],
                           cwd=ROOT, capture_output=True, check=True).stdout.split(b'\0')
    digest = hashlib.sha256()
    for name in sorted(set(filter(None, names))):
        path = ROOT / os.fsdecode(name)
        digest.update(name + b'\0' + (sha256(path.read_bytes()).encode() if path.is_file() else b'-') + b'\n')
    return digest.hexdigest()


def change(running, built):
    """What a client must replace to move from one component set to another."""
    if running is None:
        return 'both'
    game, plugin = running['game'] != built['game'], running['plugin'] != built['plugin']
    # Java and the plugin share the physical tool contract; replace them together.
    if running['contract'] != built['contract'] or (game and plugin):
        return 'both'
    return 'game' if game else 'plugin' if plugin else 'none'


def load(artifact_id):
    manifest = json.loads((ARTIFACTS / artifact_id / 'manifest.json').read_text())
    manifest['dir'] = str(ARTIFACTS / artifact_id)
    return manifest


def artifacts():
    found = []
    for path in ARTIFACTS.glob('*/manifest.json'):
        try:
            found.append(load(path.parent.name))
        except (OSError, ValueError):
            continue
    return sorted(found, key=lambda artifact: artifact['builtAt'], reverse=True)


@contextlib.contextmanager
def build_lock(on_wait=None):
    """Serializes everything that writes or deletes artifacts and frozen JARs in this checkout."""
    DEV.mkdir(parents=True, exist_ok=True)
    with (DEV / 'build.lock').open('a') as lock:
        try:
            fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError:
            if on_wait:
                on_wait()
            fcntl.flock(lock, fcntl.LOCK_EX)
        yield


def build(log_path, on_wait=None, on_ready=None):
    """Returns the artifact for the checkout's current sources, building it if needed.

    Builds are serialized per checkout. Sources are fingerprinted before and after; an
    artifact is only frozen if nothing changed while Gradle ran. on_ready pins the
    selected artifact in client status before pruning can acquire the lock.
    """
    with build_lock(on_wait):
        before = source_fingerprint()
        reused = next((artifact for artifact in artifacts() if artifact['source'] == before), None)
        if reused:
            Path(log_path).write_text(f'Sources unchanged since artifact {reused["id"]}; no build needed.\n')
            if on_ready:
                on_ready(reused)
            return reused
        with open(log_path, 'w') as log:
            done = subprocess.run([str(ROOT / 'tools/build'), 'build', 'devLaunch', '--console=plain'],
                                  cwd=ROOT, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT)
        if done.returncode != 0:
            raise RuntimeError(f'Build failed: {failure_summary(log_path)}')
        if source_fingerprint() != before:
            raise RuntimeError('Sources changed during the build; reload again once edits settle.')
        artifact = freeze(json.loads((BUILD / 'dev/launch.json').read_text()), before)
        with open(log_path, 'a') as log:
            log.write(f'\nFrozen artifact {artifact["id"]}\n')
        if on_ready:
            on_ready(artifact)
        return artifact


def failure_summary(log_path):
    lines = Path(log_path).read_text(errors='replace').splitlines()
    for marker in ('error:', 'What went wrong:', 'FAILED'):
        for index, line in enumerate(lines):
            if marker in line:
                return ' '.join(part.strip() for part in lines[index:index + 2] if part.strip())[:300]
    return 'see the build log'


def _freeze_jar(path):
    """Content-addressed copy of a JAR from the checkout's build directory."""
    data = path.read_bytes()
    target = LIBS / f'{sha256(data)[:16]}-{path.name}'
    if not target.is_file():
        LIBS.mkdir(parents=True, exist_ok=True)
        temporary = LIBS / f'.{uuid.uuid4().hex}'
        temporary.write_bytes(data)
        temporary.replace(target)
    return target


BUILD_PATH = re.compile(re.escape(str(BUILD)) + r'/[^\s"\':=,;]+')


def _build_files(launch):
    """Files under build/ the launch reads, directly or through other launch files."""
    pending = [launch['vmArgsFile'], launch['programArgsFile'],
               *(entry for entry in launch['classpath'] if Path(entry).is_relative_to(BUILD))]
    found = set()
    while pending:
        path = pending.pop()
        if path not in found:
            found.add(path)
            if not path.endswith('.jar'):
                pending += BUILD_PATH.findall(Path(path).read_text())
    return {path: sha256(Path(path).read_bytes()) for path in sorted(found)}


def freeze(launch, source):
    jar_bytes = Path(launch['jar']).read_bytes()
    with zipfile.ZipFile(launch['jar']) as archive:
        entries = {info.filename: archive.read(info) for info in archive.infolist() if not info.is_dir()}
    if PLUGIN_ENTRY not in entries or CONTRACT_ENTRY not in entries:
        raise RuntimeError('The built JAR has no bundled plugin or tool contract.')
    for entry in launch['classpath']:
        path = Path(entry)
        if path.is_relative_to(BUILD) and path.suffix != '.jar' or \
                path.is_relative_to(ROOT) and not path.is_relative_to(BUILD) and not path.is_relative_to(ROOT / '.gradle-user'):
            raise RuntimeError(f'Unexpected checkout file on the packaged classpath: {path}')

    # Fingerprints use content and stable original paths, never the artifact's own paths.
    files = _build_files(launch)
    game = hashlib.sha256()
    for name in sorted(entries):
        if name != PLUGIN_ENTRY:
            game.update(name.encode() + b'\0' + sha256(entries[name]).encode() + b'\n')
    game.update(json.dumps([launch['java'], launch['mainClass'], launch['classpath'], files], sort_keys=True).encode())
    components = {'game': game.hexdigest(), 'plugin': sha256(entries[PLUGIN_ENTRY]),
                  'contract': sha256(entries[CONTRACT_ENTRY])}
    artifact_id = sha256((sha256(jar_bytes) + components['game']).encode())[:12]
    final = ARTIFACTS / artifact_id
    if (final / 'manifest.json').is_file():
        return load(artifact_id)

    # Gradle rewrites build/ in place. Point the launch at copies inside the artifact instead.
    pending = ARTIFACTS / f'.tmp-{uuid.uuid4().hex}'
    (pending / 'launch').mkdir(parents=True)
    copies = {}

    def frozen(original):
        if original.endswith('.jar'):
            return str(_freeze_jar(Path(original)))
        if original not in copies:
            name = Path(original).name
            copies[original] = str(final / 'launch' / name)
            text = BUILD_PATH.sub(lambda match: frozen(match.group(0)), Path(original).read_text())
            (pending / 'launch' / name).write_text(text)
        return copies[original]

    jar_name = f'too-many-agents-{launch["version"]}.jar'
    (pending / jar_name).write_bytes(jar_bytes)
    shutil.copyfile(ROOT / 'bb-plugin/stage-development.mjs', pending / 'stage-development.mjs')
    manifest = {
        'id': artifact_id, 'version': launch['version'], 'builtAt': int(time.time() * 1000), 'source': source,
        'jar': jar_name, 'components': components,
        'launch': {'java': launch['java'], 'mainClass': launch['mainClass'],
                   'classpath': [frozen(entry) if Path(entry).is_relative_to(BUILD) else entry for entry in launch['classpath']],
                   'vmArgsFile': frozen(launch['vmArgsFile']), 'programArgsFile': frozen(launch['programArgsFile'])},
    }
    (pending / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
    try:
        pending.rename(final)
    except OSError:
        # Another client froze the same build first.
        shutil.rmtree(pending)
    return load(artifact_id)


def plugin_bundle(artifact):
    with zipfile.ZipFile(Path(artifact['dir']) / artifact['jar']) as archive:
        return archive.read(PLUGIN_ENTRY)


def stage_plugin(artifact, directory, plugin_id, client_id):
    """Extracts the artifact's plugin as this client's own instance in an immutable directory."""
    directory.mkdir(parents=True, exist_ok=True)
    pending = directory / f'.stage-{uuid.uuid4().hex}'
    try:
        with zipfile.ZipFile(io.BytesIO(plugin_bundle(artifact))) as archive:
            for info in archive.infolist():
                target = (pending / info.filename).resolve()
                if not target.is_relative_to(pending.resolve()):
                    raise RuntimeError(f'Invalid bundled plugin path {info.filename}')
            archive.extractall(pending)
        env = build_environment()
        node = env.get('TOO_MANY_AGENTS_NODE') or shutil.which('node', path=env.get('PATH'))
        if not node:
            raise RuntimeError('Staging the plugin requires Node.js. Add node to PATH or set TOO_MANY_AGENTS_NODE.')
        done = subprocess.run([node, str(Path(artifact['dir']) / 'stage-development.mjs'), str(pending), plugin_id, client_id],
                              env=env, capture_output=True, text=True, timeout=120)
        if done.returncode != 0:
            raise RuntimeError('Plugin staging failed: ' + (done.stderr.strip().splitlines() or ['unknown error'])[-1])
        sidecar = json.loads(done.stdout)
        if sidecar.get('pluginId') != plugin_id or not sidecar.get('fingerprint'):
            raise RuntimeError('Plugin staging returned unexpected metadata.')
        final = directory / sidecar['fingerprint'][:16]
        if final.is_dir():
            shutil.rmtree(pending)
        else:
            pending.rename(final)
        return final, sidecar
    finally:
        if pending.exists():
            shutil.rmtree(pending)


def prune(in_use):
    """Removes artifacts no client uses, keeping the newest few, then unreferenced frozen JARs.

    `in_use` is called under the build lock, so no freeze or client pin can race the deletion.
    """
    with build_lock():
        _prune(in_use())


def _prune(keep_ids):
    known = artifacts()
    keep = set(keep_ids) | {artifact['id'] for artifact in known[:KEEP]}
    for artifact in known:
        if artifact['id'] not in keep:
            shutil.rmtree(artifact['dir'], ignore_errors=True)
    used = set()
    for path in ARTIFACTS.glob('*/manifest.json'):
        used.update(json.loads(path.read_text())['launch']['classpath'])
        for launch_file in (path.parent / 'launch').iterdir():
            used.update(re.findall(re.escape(str(LIBS)) + r'/[^\s"\':=,;]+', launch_file.read_text()))
    for path in LIBS.glob('*.jar'):
        if str(path) not in used:
            path.unlink(missing_ok=True)
