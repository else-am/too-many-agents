import { createHash } from 'node:crypto';
import { spawn, execFileSync } from 'node:child_process';
import { mkdir, readFile, writeFile, rename } from 'node:fs/promises';
import { resolve, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { homedir } from 'node:os';
import { createInterface } from 'node:readline';

const root = fileURLToPath(new URL('../../', import.meta.url));
export const directory = join(root, 'run/mineflayer-reference');
const pinned = JSON.parse(await readFile(new URL('./upstream.json', import.meta.url), 'utf8'));

async function fetchChecked(url) {
  const response = await fetch(url, { signal: AbortSignal.timeout(120_000) });
  if (!response.ok) throw new Error(`Download returned HTTP ${response.status}: ${url}`);
  return response;
}

export async function prepare() {
  await mkdir(directory, { recursive: true });
  const manifest = await (await fetchChecked('https://piston-meta.mojang.com/mc/game/version_manifest_v2.json')).json();
  const version = manifest.versions.find(entry => entry.id === pinned.minecraft);
  if (!version) throw new Error(`Minecraft ${pinned.minecraft} is absent from the official manifest`);
  const metadata = await (await fetchChecked(version.url)).json();
  const download = metadata.downloads.server;
  const jar = join(directory, `minecraft-server-${pinned.minecraft}.jar`);
  const sha1 = bytes => createHash('sha1').update(bytes).digest('hex');
  let existing;
  try { existing = await readFile(jar); } catch (error) { if (error.code !== 'ENOENT') throw error; }
  if (!existing || sha1(existing) !== download.sha1) {
    const bytes = Buffer.from(await (await fetchChecked(download.url)).arrayBuffer());
    if (bytes.length !== download.size || sha1(bytes) !== download.sha1)
      throw new Error('Official reference server download failed its size/hash check');
    await writeFile(jar + '.part', bytes);
    await rename(jar + '.part', jar);
  }
  await writeFile(join(directory, 'upstream-server.json'), JSON.stringify({
    version: pinned.minecraft, url: download.url, sha1: download.sha1,
  }, null, 2) + '\n');
  await writeFile(join(directory, 'server.properties'), [
    'server-ip=127.0.0.1', 'server-port=25575', 'online-mode=false',
    'level-name=reference-world', 'level-type=minecraft:flat', 'gamemode=creative',
    'difficulty=peaceful', 'spawn-protection=0', 'view-distance=6', 'simulation-distance=6',
    'generate-structures=false', 'spawn-monsters=false', 'spawn-animals=false',
    'enable-rcon=false', 'enable-query=false', 'enable-command-block=true',
    'enforce-secure-profile=false', 'max-players=4', 'max-tick-time=60000',
  ].join('\n') + '\n');
  try {
    await writeFile(join(directory, 'eula.txt'), '# Accept https://www.minecraft.net/en-us/eula before starting this isolated test server.\neula=false\n', { flag: 'wx' });
  } catch (error) { if (error.code !== 'EEXIST') throw error; }
  return { directory, jar, version: pinned.minecraft, sha1: download.sha1 };
}

function java21() {
  const candidates = [
    process.env.JAVA_HOME && join(process.env.JAVA_HOME, 'bin/java'),
    join(homedir(), 'Library/Application Support/PrismLauncher/java/java-runtime-delta/bin/java'),
    join(homedir(), '.local/share/PrismLauncher/java/java-runtime-delta/bin/java'),
    'java',
  ].filter(Boolean);
  for (const candidate of candidates) {
    try {
      const output = execFileSync(candidate, ['--version'], { encoding: 'utf8', stdio: ['ignore', 'pipe', 'pipe'] });
      if (/^(?:openjdk|java) 21\./.test(output)) return candidate;
    } catch { /* Try the next installed runtime. */ }
  }
  throw new Error('Set JAVA_HOME to an installed Java 21 runtime');
}

export async function startServer({ onLine = () => {} } = {}) {
  const eula = await readFile(join(directory, 'eula.txt'), 'utf8');
  if (!/^eula=true\s*$/m.test(eula)) throw new Error(`Minecraft server EULA acceptance is required in ${join(directory, 'eula.txt')}`);
  const record = JSON.parse(await readFile(join(directory, 'upstream-server.json'), 'utf8'));
  const jar = join(directory, `minecraft-server-${pinned.minecraft}.jar`);
  if (record.version !== pinned.minecraft || createHash('sha1').update(await readFile(jar)).digest('hex') !== record.sha1)
    throw new Error('Run prepare: the reference server version or checksum does not match');
  const child = spawn(java21(), ['-Xms512M', '-Xmx1536M', '-jar', jar, 'nogui'], {
    cwd: directory, stdio: ['pipe', 'pipe', 'pipe'],
  });
  let exited = false;
  const closed = new Promise((resolve, reject) => {
    child.once('error', reject);
    child.once('exit', (code, signal) => { exited = true; resolve({ code, signal }); });
  });
  const ready = new Promise((resolve, reject) => {
    const timer = setTimeout(() => reject(new Error('Reference server startup exceeded 120 seconds')), 120_000);
    closed.then(result => { clearTimeout(timer); reject(new Error(`Reference server exited before readiness: ${JSON.stringify(result)}`)); }, reject);
    for (const stream of [child.stdout, child.stderr]) {
      createInterface({ input: stream }).on('line', line => {
        onLine(line);
        if (/Done \(.+\)!/.test(line)) { clearTimeout(timer); resolve(); }
      });
    }
  });
  const stop = async () => {
    if (!exited && child.stdin.writable) child.stdin.end('stop\n');
    return closed;
  };
  try { await ready; } catch (error) { await stop(); throw error; }
  return {
    pid: child.pid, closed, stop,
    command(text) {
      if (exited || !child.stdin.writable) throw new Error('Reference server is stopped');
      if (/[\r\n]/.test(text)) throw new Error('One console command per call');
      child.stdin.write(text + '\n');
    },
  };
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  if (process.argv[2] === 'prepare') console.log(JSON.stringify(await prepare(), null, 2));
  else if (process.argv[2] === 'start') {
    const server = await startServer({ onLine: line => console.log(line) });
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => void server.stop());
    await server.closed;
  } else throw new Error('Usage: node server.mjs prepare|start');
}
