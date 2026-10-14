// Bundled controls choose locations through native, human-gesture navigation. Data requests never grant roots.
const $ = id => document.getElementById(id);
const current = decodeURIComponent(location.pathname);
const windows = /^\/[a-z]:\//i.test(current);
const nativePath = value => windows && /^\/[a-z]:\//i.test(value) ? value.slice(1) : value;
const slash = value => windows ? value.replaceAll('\\', '/') : value;
const root = slash(nativePath(current)).replace(/\/$/, '') || '/';
let home = '', viewer, picker;
const bundles = {};
let scope = new AbortController();
let watchId, watchGeneration = 0, pickerTimer = 0;
let pickerRefreshing = false, pickerDirty = false, watchNotice = '';
let lastRevision = 0;
let pageNotice = '';
const notice = text => {
  pageNotice = text;
  $('notice').textContent = [pageNotice, watchNotice].filter(Boolean).join(' - ');
};
const join = (folder, name) => `${folder.replace(/\/$/, '')}/${name}`;
function address(path, pick, hidden) {
  let full = slash(path);
  if (full === '~' || full.startsWith('~/')) full = home + full.slice(1);
  if (!full.startsWith('/') && !/^[a-z]:\//i.test(full)) full = join(root, full);
  const parts = [];
  for (const part of full.split('/')) {
    if (!part || part === '.') continue;
    if (part === '..') parts.pop(); else parts.push(part);
  }
  return `tma://files/${parts.map(encodeURIComponent).join('/')}${pick ? hidden ? '?pick&hidden' : '?pick' : ''}`;
}
async function api(operation, path = '', cursor = '', signal = scope.signal) {
  const params = new URLSearchParams({path});
  if (cursor) params.set('cursor', cursor);
  const response = await fetch(`/_api/${operation}?${params}`, {cache: 'no-store', signal});
  const value = await response.json();
  if (!response.ok || value.error) throw Error(value.error || 'Could not read this folder.');
  return value;
}
$('location').textContent = nativePath(current);
$('location').href = address(root, true);

// Each page mode loads only its own bundle; the picker never downloads syntax highlighting.
function load(name, styles) {
  return Promise.all([
    styles && new Promise((resolve, reject) => {
      const link = document.createElement('link');
      link.rel = 'stylesheet'; link.href = `/_app/${name}.css`;
      link.onload = resolve; link.onerror = () => reject(Error(`Could not load ${name} styles.`));
      document.head.append(link);
    }),
    new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = `/_app/${name}.js`;
      script.onload = resolve; script.onerror = () => reject(Error(`Could not load the ${name}.`));
      document.head.append(script);
    })
  ]);
}
async function bundle(name, styles, init, signal) {
  await (bundles[name] ??= load(name, styles));
  signal.throwIfAborted();
  if (typeof window[init] !== 'function') throw Error(`The ${name} did not initialize.`);
}
function queuePickerRefresh() {
  pickerDirty = true;
  if (pickerTimer || pickerRefreshing || scope.signal.aborted) return;
  pickerTimer = setTimeout(async () => {
    pickerTimer = 0;
    pickerDirty = false;
    pickerRefreshing = true;
    try { await picker?.refresh(); }
    catch (error) { if (error.name !== 'AbortError') notice(error.message); }
    finally {
      pickerRefreshing = false;
      if (pickerDirty) queuePickerRefresh();
    }
  }, 100);
}
function stopWatch() {
  watchGeneration++;
  if (watchId !== undefined) window.cefQueryCancel?.({request_id: watchId});
  watchId = undefined;
  clearTimeout(pickerTimer); pickerTimer = 0; pickerDirty = false;
}
function startWatch(documentEpoch) {
  stopWatch();
  if (typeof window.cefQuery !== 'function' || scope.signal.aborted || !Number.isSafeInteger(documentEpoch)) return;
  const generation = watchGeneration;
  function arm() {
    if (generation !== watchGeneration || scope.signal.aborted) return;
    watchId = window.cefQuery({
      request: `screen-files:watch:${documentEpoch}`,
      persistent: false,
      onSuccess: response => {
        if (generation !== watchGeneration || scope.signal.aborted) return;
        watchId = undefined;
        let change;
        try {
          change = JSON.parse(response);
          if (!Number.isSafeInteger(change.revision)) throw Error('Invalid watch revision');
        } catch {
          watchNotice = 'Live updates unavailable - reopen this page to refresh';
          notice(pageNotice);
          return;
        }
        // JCEF consumes each callback once. Rearm before refreshing, without recursive callbacks.
        queueMicrotask(() => {
          if (generation !== watchGeneration || scope.signal.aborted) return;
          arm();
          if (change.revision <= lastRevision) return;
          lastRevision = change.revision;
          watchNotice = change.limited ? 'Live updates have limited coverage - periodic refresh remains active' : '';
          if (viewer) {
            watchNotice = '';
            viewer.refresh(change);
          } else if (change.reconcile || change.structure || change.rules) {
            queuePickerRefresh();
          }
          notice(pageNotice);
        });
      },
      onFailure: (_code, message) => {
        if (generation !== watchGeneration || scope.signal.aborted) return;
        watchId = undefined;
        watchNotice = message || 'Live updates unavailable - reopen this page to refresh';
        notice(pageNotice);
      }
    });
  }
  arm();
}
async function start() {
  const signal = scope.signal;
  try {
    const state = await api('state', '', '', signal);
    home = slash(state.home);
    notice(state.notice || '');
    if (new URLSearchParams(location.search).has('pick')) {
      $('picker').hidden = false;
      await bundle('picker', false, 'tmaFilesPicker', signal);
      picker = window.tmaFilesPicker({root, home, api, address, notice});
    } else {
      document.body.classList.add('showing-viewer');
      $('viewer').hidden = false;
      notice('Loading viewer…');
      await bundle('viewer', true, 'tmaFilesViewer', signal);
      viewer = window.tmaFilesViewer($('viewer'), state);
      notice('');
    }
    startWatch(state.document);
  } catch (error) { if (error.name !== 'AbortError') notice(error.message); }
}
window.addEventListener('pagehide', () => {
  stopWatch();
  scope.abort();
  viewer?.dispose(); viewer = undefined;
  picker?.dispose(); picker = undefined;
});
window.addEventListener('pageshow', event => {
  if (!event.persisted) return;
  scope = new AbortController();
  void start();
});
void start();
