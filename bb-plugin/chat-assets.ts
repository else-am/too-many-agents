import type { BbPluginApi } from '@get-bb/plugin-sdk';
import { Worker } from 'node:worker_threads';
import { join, isAbsolute, relative, resolve } from 'node:path';
import { lookup } from 'node:dns/promises';
import { isIP } from 'node:net';
import { request as httpRequest } from 'node:http';
import { request as httpsRequest } from 'node:https';

const LIMIT = 5 * 1024 * 1024;
type Asset = {base64:string; mimeType:string};

export function chatAssets(bb: BbPluginApi) {
  const workers = new Set<Worker>();
  const cache = new Map<string, Asset>();
  let cacheBytes = 0;
  bb.onDispose(async () => { cache.clear(); await Promise.all([...workers].map(worker => worker.terminate())); });

  async function diagram(source: string): Promise<Asset> {
    if (source.length > 20000) throw new Error('Diagram exceeds 20,000 characters.');
    const cached = cache.get(source);
    if (cached) { cache.delete(source); cache.set(source, cached); return cached; }
    if (workers.size >= 2) throw new Error('Diagram renderer is busy. Click Retry shortly.');
    const plugin = (await bb.sdk.plugins.list()).plugins.find(item => item.id === bb.pluginId);
    if (!plugin) throw new Error('Minecraft plugin directory is unavailable.');
    return new Promise((accept, reject) => {
      const worker = new Worker(join(plugin.rootDir, 'diagram-worker.mjs'), {
        workerData:{source}, resourceLimits:{maxOldGenerationSizeMb:128,stackSizeMb:4},
      });
      workers.add(worker);
      const timeout = setTimeout(() => { reject(new Error('Diagram rendering exceeded 5 seconds.')); void worker.terminate(); }, 5000);
      worker.once('message', (result: Asset & {error?:string}) => {
        if (result.error) reject(new Error(result.error));
        else {
          cache.set(source, result); cacheBytes += result.base64.length;
          while (cache.size > 24 || cacheBytes > 32 * 1024 * 1024) {
            const key = cache.keys().next().value!; cacheBytes -= cache.get(key)!.base64.length; cache.delete(key);
          }
          accept(result);
        }
        void worker.terminate();
      });
      worker.once('error', reject);
      worker.once('exit', () => { clearTimeout(timeout); workers.delete(worker); reject(new Error('Diagram renderer stopped.')); });
    });
  }

  async function image(threadId: string, source: string): Promise<Asset> {
    if (/^https?:\/\//i.test(source)) {
      const response = await remote(source);
      return pack(response.bytes, response.mimeType);
    }
    if (/^[a-z][\w+.-]*:/i.test(source)) throw new Error('Only HTTP(S), workspace files and BB attachments can be displayed.');
    source = decodeURIComponent(source);
    const thread = await bb.sdk.threads.get({threadId});
    // BB attachment paths are opaque. Let BB validate ownership before trying workspace paths.
    try {
      const attachment = await bb.sdk.projects.attachments.read({projectId:thread.projectId, path:source, signal:AbortSignal.timeout(8000)});
      return pack(attachment.bytes, attachment.mimeType);
    } catch { /* Not a BB attachment; resolve in this thread's workspace or storage below. */ }
    const storage = await bb.sdk.threads.storageLocation({threadId});
    const absolute = isAbsolute(source) ? resolve(source) : '';
    if (absolute && inside(storage.storageRootPath, absolute)) {
      const file = await bb.sdk.files.read({hostId:storage.hostId, rootPath:storage.storageRootPath, path:absolute, signal:AbortSignal.timeout(8000)});
      return pack(Buffer.from(file.content, file.contentEncoding === 'base64' ? 'base64' : 'utf8'), file.mimeType ?? mimeFor(source));
    }
    if (absolute) {
      if (!thread.environmentId) throw new Error('Image is outside this thread\'s workspace and storage.');
      const environment = await bb.sdk.environments.get({environmentId:thread.environmentId});
      if (!environment.path || !inside(environment.path, absolute)) throw new Error('Image is outside this thread\'s workspace and storage.');
      // Project file APIs take relative paths; BB still validates the resolved file and symlinks.
      source = relative(environment.path, absolute);
    }
    const args = {projectId:thread.projectId,path:source,signal:AbortSignal.timeout(8000)};
    const file = await bb.sdk.projects.fileContent(thread.environmentId ? {...args,environmentId:thread.environmentId} : args);
    return pack(Buffer.from(file.content, file.contentEncoding === 'base64' ? 'base64' : 'utf8'), file.mimeType);
  }

  async function open(threadId: string, target: string) {
    if (target.startsWith('@thread:')) return bb.sdk.threads.open({threadId:target.slice(8),file:null});
    const match = /^(.*?)(?::(\d+)(?::\d+)?|#L(\d+)(?:-L?\d+)?)?$/.exec(target)!;
    const path = decodeURIComponent(match[1]!);
    if (!path || /^[a-z][\w+.-]*:/i.test(path)) throw new Error('Unsupported link target.');
    const storage = await bb.sdk.threads.storageLocation({threadId});
    const inStorage = isAbsolute(path) && inside(storage.storageRootPath, path);
    const opened = await bb.sdk.threads.open({threadId,file:{path:inStorage?relative(storage.storageRootPath,path):path,
      source:inStorage?'thread-storage':'workspace',lineNumber:match[2] || match[3] ? Math.max(1,Number(match[2] || match[3])) : null}});
    if (!opened.delivered) throw new Error('Open a BB window to view this file.');
    return opened;
  }

  return {diagram, image, open};
}

function inside(root: string, path: string) {
  const rel = relative(resolve(root),resolve(path));
  return rel !== '..' && !rel.startsWith('../') && !isAbsolute(rel);
}

function pack(bytes: Uint8Array, mimeType: string): Asset {
  if (bytes.length === 0 || bytes.length > LIMIT) throw new Error('Images must be between 1 byte and 5 MB.');
  if (!/^image\/(png|jpeg|gif|webp)(?:;|$)/i.test(mimeType)) throw new Error('Use PNG, JPEG, GIF, or WebP images.');
  return {base64:Buffer.from(bytes).toString('base64'),mimeType};
}

function mimeFor(path: string): string {
  return /\.png$/i.test(path) ? 'image/png' : /\.jpe?g$/i.test(path) ? 'image/jpeg'
    : /\.gif$/i.test(path) ? 'image/gif' : /\.webp$/i.test(path) ? 'image/webp' : '';
}

async function remote(source: string): Promise<{bytes:Uint8Array;mimeType:string}> {
  let url = new URL(source);
  const signal = AbortSignal.timeout(10000);
  for (let redirect = 0; redirect < 4; redirect++) {
    if (!['http:','https:'].includes(url.protocol) || url.username || url.password) throw new Error('Unsupported image URL.');
    const host = url.hostname.replace(/^\[|\]$/g,'');
    const addresses = isIP(host) ? [{address:host}] : await lookup(host,{all:true});
    if (!addresses.length || addresses.some(({address}) => privateAddress(address)))
      throw new Error('Remote images cannot access local or private network addresses.');
    // Connect to the checked address, preserving Host and TLS identity; do not resolve it again.
    const response = await new Promise<{bytes:Uint8Array;mimeType:string;location?:string}>((accept,reject) => {
      const request = (url.protocol === 'https:' ? httpsRequest : httpRequest)({
        protocol:url.protocol,hostname:addresses[0]!.address,servername:host,port:url.port || undefined,
        method:'GET',path:url.pathname+url.search,signal,
        headers:{Host:url.host,Accept:'image/png,image/jpeg,image/gif,image/webp'},
      }, incoming => {
        if (incoming.statusCode! >= 300 && incoming.statusCode! < 400 && incoming.headers.location) {
          incoming.destroy(); accept({bytes:new Uint8Array(),mimeType:'',location:incoming.headers.location}); return;
        }
        if (incoming.statusCode! < 200 || incoming.statusCode! >= 300 || Number(incoming.headers['content-length'] ?? 0) > LIMIT) {
          incoming.destroy(); reject(new Error(`Image request failed or exceeds 5 MB (${incoming.statusCode}).`)); return;
        }
        const chunks:Buffer[] = []; let size = 0;
        incoming.on('data',(chunk:Buffer) => {
          size += chunk.length;
          if (size > LIMIT) { incoming.destroy(); reject(new Error('Image exceeds 5 MB.')); }
          else chunks.push(chunk);
        });
        incoming.on('error',reject);
        incoming.on('end',() => accept({bytes:Buffer.concat(chunks),mimeType:incoming.headers['content-type'] ?? ''}));
      });
      request.on('error',reject); request.end();
    });
    if (response.location) { url = new URL(response.location,url); continue; }
    return response;
  }
  throw new Error('Too many image redirects.');
}

function privateAddress(address: string): boolean {
  if (address.includes(':')) return /^(::|fc|fd|fe[89ab]|ff)/i.test(address) || address.includes('.');
  const [a,b] = address.split('.').map(Number);
  return a === 0 || a === 10 || a === 127 || a === 169 && b === 254 || a === 172 && b! >= 16 && b! <= 31
    || a === 192 && b === 168 || a! >= 224 || a === 100 && b! >= 64 && b! <= 127;
}
