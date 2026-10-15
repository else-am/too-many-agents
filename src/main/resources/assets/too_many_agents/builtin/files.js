// The viewer's header leads to the native picker through human-gesture navigation. Data requests never grant roots.
const $ = id => document.getElementById(id);
const current = decodeURIComponent(location.pathname);
const windows = /^\/[a-z]:\//i.test(current);
const nativePath = value => windows && /^\/[a-z]:\//i.test(value) ? value.slice(1) : value;
const slash = value => windows ? value.replaceAll('\\', '/') : value;
const root = slash(nativePath(current)).replace(/\/$/, '') || '/';
let viewer, bundle;
let scope = new AbortController();
let watchId, watchGeneration = 0, watchNotice = '';
let lastRevision = 0;
let pageNotice = '';
const notice = text => {
  pageNotice = text;
  $('notice').textContent = [pageNotice, watchNotice].filter(Boolean).join(' - ');
};
async function api(operation, path = '', signal = scope.signal) {
  const params = new URLSearchParams({path});
  const response = await fetch(`/_api/${operation}?${params}`, {cache: 'no-store', signal});
  const value = await response.json();
  if (!response.ok || value.error) throw Error(value.error || 'Could not read this folder.');
  return value;
}
$('location').textContent = nativePath(current);
$('location').href = `tma://files/${root.split('/').filter(Boolean).map(encodeURIComponent).join('/')}?pick`;

// The viewer's bundle loads once per document.
function loadViewer() {
  return Promise.all([
    new Promise((resolve, reject) => {
      const link = document.createElement('link');
      link.rel = 'stylesheet'; link.href = '/_app/viewer.css';
      link.onload = resolve; link.onerror = () => reject(Error('Could not load viewer styles.'));
      document.head.append(link);
    }),
    new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = '/_app/viewer.js';
      script.onload = resolve; script.onerror = () => reject(Error('Could not load the viewer.'));
      document.head.append(script);
    })
  ]);
}
function stopWatch() {
  watchGeneration++;
  if (watchId !== undefined) window.cefQueryCancel?.({request_id: watchId});
  watchId = undefined;
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
          watchNotice = '';
          viewer?.refresh(change);
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
    const state = await api('state', '', signal);
    notice(state.notice || '');
    notice('Loading viewer…');
    await (bundle ??= loadViewer());
    signal.throwIfAborted();
    if (typeof window.tmaFilesViewer !== 'function') throw Error('The viewer did not initialize.');
    viewer = window.tmaFilesViewer($('viewer'), state);
    notice(state.notice || '');
    startWatch(state.document);
  } catch (error) { if (error.name !== 'AbortError') notice(error.message); }
}
window.addEventListener('pagehide', () => {
  stopWatch();
  scope.abort();
  viewer?.dispose(); viewer = undefined;
});
window.addEventListener('pageshow', event => {
  if (!event.persisted) return;
  scope = new AbortController();
  void start();
});
void start();
