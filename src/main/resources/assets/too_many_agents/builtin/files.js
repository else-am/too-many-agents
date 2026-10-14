// Bundled controls choose locations through native, human-gesture navigation. Data requests never grant roots.
const $ = id => document.getElementById(id);
const current = decodeURIComponent(location.pathname);
const windows = /^\/[a-z]:\//i.test(current);
const nativePath = value => windows && /^\/[a-z]:\//i.test(value) ? value.slice(1) : value;
const slash = value => windows ? value.replaceAll('\\', '/') : value;
const root = slash(nativePath(current)).replace(/\/$/, '') || '/';
const ROW = 36, MAX_ENTRIES = 50_000;
const order = (a, b) => Number(a.kind !== 'directory') - Number(b.kind !== 'directory')
  || a.name.localeCompare(b.name, undefined, {sensitivity: 'base'}) || a.name.localeCompare(b.name);
let home = '', entries = [], selected = -1, completion = 0, viewer, viewerBundle;
let scope = new AbortController(), completing;
let listingFrame = 0, watchId, watchGeneration = 0, pickerTimer = 0;
let pickerRefreshing = false, pickerDirty = false, watchNotice = '';
let lastRevision = 0;
let pageNotice = '';
const notice = text => {
  pageNotice = text;
  $('notice').textContent = [pageNotice, watchNotice].filter(Boolean).join(' - ');
};
const join = (folder, name) => `${folder.replace(/\/$/, '')}/${name}`;
function address(path, pick) {
  let full = slash(path);
  if (full === '~' || full.startsWith('~/')) full = home + full.slice(1);
  if (!full.startsWith('/') && !/^[a-z]:\//i.test(full)) full = join(root, full);
  const parts = [];
  for (const part of full.split('/')) {
    if (!part || part === '.') continue;
    if (part === '..') parts.pop(); else parts.push(part);
  }
  return `tma://files/${parts.map(encodeURIComponent).join('/')}${pick ? '?pick' : ''}`;
}
function humanNavigate(event, path, pick) {
  if (!event.isTrusted) return;
  location.assign(address(path, pick));
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
$('path').value = root.endsWith('/') ? root : root + '/';

// Keep the DOM bounded while every name remains reachable by scrolling or keyboard.
function renderRows() {
  const listing = $('listing');
  const start = Math.max(0, Math.floor(listing.scrollTop / ROW) - 6);
  const end = Math.min(entries.length, start + Math.min(128, Math.ceil(listing.clientHeight / ROW) + 12));
  const fragment = document.createDocumentFragment();
  const before = document.createElement('div');
  before.style.height = `${start * ROW}px`;
  fragment.append(before);
  for (let index = start; index < end; index++) {
    const item = entries[index];
    const row = document.createElement('a');
    row.className = `item${item.hidden ? ' hidden-file' : ''}${index === selected ? ' selected' : ''}`;
    row.href = address(join(root, item.name), item.kind === 'directory' || item.kind === 'link');
    row.tabIndex = -1;
    row.title = item.name;
    row.setAttribute('aria-selected', String(index === selected));
    const icon = document.createElement('span');
    icon.className = 'icon';
    icon.textContent = item.kind === 'directory' ? '▸' : item.kind === 'link' ? '↗' : '';
    const label = document.createElement('span');
    label.className = 'name';
    label.textContent = item.name;
    row.append(icon, label);
    row.addEventListener('mousedown', event => {
      // Keep keyboard focus on the stable list node while rows are recycled; the trusted click still follows href.
      if (event.button === 0) { event.preventDefault(); listing.focus({preventScroll: true}); selected = index; }
    });
    fragment.append(row);
  }
  const after = document.createElement('div');
  after.style.height = `${(entries.length - end) * ROW}px`;
  fragment.append(after);
  listing.replaceChildren(fragment);
}
function select(index) {
  if (!entries.length) return;
  selected = Math.max(0, Math.min(entries.length - 1, index));
  const listing = $('listing'), top = selected * ROW;
  if (top < listing.scrollTop) listing.scrollTop = top;
  else if (top + ROW > listing.scrollTop + listing.clientHeight) listing.scrollTop = top + ROW - listing.clientHeight;
  renderRows();
}
function append(items) {
  const listing = $('listing');
  const chosen = entries[selected]?.name;
  const anchor = entries[Math.floor(listing.scrollTop / ROW)]?.name;
  const offset = listing.scrollTop % ROW;
  // The server orders each batch; the full directory must stay folder-first as more batches arrive.
  const names = new Map(entries.map(item => [item.name, item]));
  let limited = false;
  for (const item of items) {
    if (names.size >= MAX_ENTRIES && !names.has(item.name)) { limited = true; break; }
    names.set(item.name, item);
  }
  entries = [...names.values()].sort(order);
  selected = chosen === undefined ? -1 : entries.findIndex(item => item.name === chosen);
  const anchorIndex = anchor === undefined ? -1 : entries.findIndex(item => item.name === anchor);
  renderRows();
  if (anchorIndex >= 0) { listing.scrollTop = anchorIndex * ROW + offset; renderRows(); }
  return limited;
}
$('listing').addEventListener('scroll', () => {
  if (listingFrame) return;
  listingFrame = requestAnimationFrame(() => { listingFrame = 0; renderRows(); });
}, {passive: true});
const resizing = new ResizeObserver(renderRows);
resizing.observe($('listing'));
$('path-form').addEventListener('submit', event => {
  event.preventDefault();
  const item = entries[selected];
  if (item) humanNavigate(event, join(root, item.name), item.kind === 'directory' || item.kind === 'link');
  else humanNavigate(event, $('path').value, true);
});
$('open').addEventListener('click', event => humanNavigate(event, root, false));
$('listing').addEventListener('keydown', event => {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    select(selected + (event.key === 'ArrowDown' ? 1 : -1));
  } else if (event.key === 'Home' || event.key === 'End') {
    event.preventDefault(); select(event.key === 'Home' ? 0 : entries.length - 1);
  } else if (event.key === 'PageDown' || event.key === 'PageUp') {
    event.preventDefault(); select(selected + Math.floor($('listing').clientHeight / ROW) * (event.key === 'PageDown' ? 1 : -1));
  } else if (event.key === 'Enter' && selected >= 0) {
    event.preventDefault();
    const item = entries[selected];
    humanNavigate(event, join(root, item.name), item.kind === 'directory' || item.kind === 'link');
  }
});
$('path').addEventListener('input', () => { completion++; completing?.abort(); selected = -1; renderRows(); });
$('path').addEventListener('keydown', async event => {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    // Keep the editable path focused so uncaptured in-world keyboard input continues reaching the page.
    event.preventDefault(); select(selected + (event.key === 'ArrowDown' ? 1 : -1)); return;
  }
  if (event.key !== 'Tab' || !event.isTrusted) return;
  event.preventDefault();
  selected = -1; renderRows();
  completing?.abort();
  completing = new AbortController();
  const signal = completing.signal, version = ++completion;
  const typed = slash($('path').value);
  let full = typed;
  if (full === '~' || full.startsWith('~/')) full = home + full.slice(1);
  if (!full.startsWith('/') && !/^[a-z]:\//i.test(full)) full = join(root, full);
  const cut = full.lastIndexOf('/'), folder = full.slice(0, cut) || '/', prefix = full.slice(cut + 1);
  const relative = folder === root ? '' : folder.startsWith(root === '/' ? '/' : root + '/') ? folder.slice(root === '/' ? 1 : root.length + 1) : null;
  if (relative === null) { notice('Press Enter to enter that folder before completing its names.'); return; }
  try {
    let cursor = '', shared = '', count = 0, only;
    notice('Completing names…');
    do {
      const listing = await api('list', relative, cursor, signal);
      if (version !== completion) return;
      for (const item of listing.entries) {
        if (!item.name.toLowerCase().startsWith(prefix.toLowerCase())) continue;
        if (!count++) { shared = item.name; only = item; }
        else while (!item.name.startsWith(shared)) shared = shared.slice(0, -1);
      }
      if (listing.cursor && listing.cursor === cursor) throw Error('Folder listing stopped advancing. Reopen the folder to refresh.');
      cursor = listing.cursor || '';
      if (listing.truncated && !cursor) throw Error('Completion is incomplete. Enter the full name or reopen the folder.');
    } while (cursor);
    if (!count) { notice('No matching names.'); return; }
    const tail = count === 1 && only.kind === 'directory' ? '/' : '';
    // A case-insensitive match with differing case may have no shared spelling; keep the user's prefix.
    $('path').value = typed.slice(0, typed.lastIndexOf('/') + 1) + (shared || prefix) + tail;
    notice(count > 1 ? `${count} matching names` : '');
  } catch (error) { if (version === completion && error.name !== 'AbortError') notice(error.message); }
});

async function loadViewer(signal) {
  // Picker navigation does not need to download the larger syntax-highlighting bundle.
  viewerBundle ??= Promise.all([
    new Promise((resolve, reject) => {
      const link = document.createElement('link');
      link.rel = 'stylesheet'; link.href = '/_app/viewer.css';
      link.onload = resolve; link.onerror = () => reject(Error('Could not load viewer styles.'));
      document.head.append(link);
    }),
    new Promise((resolve, reject) => {
      const script = document.createElement('script');
      script.src = '/_app/viewer.js';
      script.onload = resolve; script.onerror = () => reject(Error('Could not load the file viewer.'));
      document.head.append(script);
    })
  ]);
  await viewerBundle;
  signal.throwIfAborted();
  if (typeof window.tmaFilesViewer !== 'function') throw Error('The file viewer did not initialize.');
}
async function loadListing(refresh, signal = scope.signal) {
  let cursor = '', limited = false, truncated = false;
  const next = new Map();
  do {
    const listing = await api('list', '', cursor, signal);
    for (const item of listing.entries) {
      if (next.size >= MAX_ENTRIES && !next.has(item.name)) { limited = true; break; }
      next.set(item.name, item);
    }
    if (!refresh) append(listing.entries);
    if (listing.cursor && listing.cursor === cursor) throw Error('Folder listing stopped advancing. Reopen the folder to refresh.');
    cursor = listing.cursor || '';
    truncated = listing.truncated;
    if (limited || next.size >= MAX_ENTRIES && (cursor || truncated)) { limited = true; break; }
    if (!refresh && cursor) notice(`${next.size.toLocaleString()} names - loading…`);
    if (cursor) await new Promise(resolve => setTimeout(resolve, 0));
    signal.throwIfAborted();
  } while (cursor);
  signal.throwIfAborted();
  if (refresh) {
    const listing = $('listing');
    const chosen = entries[selected]?.name;
    const anchor = entries[Math.floor(listing.scrollTop / ROW)]?.name;
    const scroll = listing.scrollTop, offset = scroll % ROW;
    entries = [...next.values()].sort(order);
    selected = chosen === undefined ? -1 : entries.findIndex(item => item.name === chosen);
    const anchorIndex = anchor === undefined ? -1 : entries.findIndex(item => item.name === anchor);
    renderRows();
    listing.scrollTop = anchorIndex >= 0 ? anchorIndex * ROW + offset : scroll;
    renderRows();
  }
  notice(limited ? `Showing ${MAX_ENTRIES.toLocaleString()} names - listing incomplete. Type a path or use Tab completion to reach other names.`
    : truncated ? 'Listing incomplete - enter a path or reopen the folder.' : entries.length ? '' : 'This folder is empty.');
}
function queuePickerRefresh() {
  pickerDirty = true;
  if (pickerTimer || pickerRefreshing || scope.signal.aborted) return;
  pickerTimer = setTimeout(async () => {
    pickerTimer = 0;
    pickerDirty = false;
    pickerRefreshing = true;
    try { await loadListing(true); }
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
      await loadListing(entries.length > 0, signal);
    } else {
      document.body.classList.add('showing-viewer');
      $('viewer').hidden = false;
      notice('Loading viewer…');
      await loadViewer(signal);
      viewer = window.tmaFilesViewer($('viewer'), state);
      notice('');
    }
    startWatch(state.document);
  } catch (error) { if (error.name !== 'AbortError') notice(error.message); }
}
window.addEventListener('pagehide', () => {
  stopWatch();
  scope.abort(); completing?.abort(); completion++;
  viewer?.dispose(); viewer = undefined;
  cancelAnimationFrame(listingFrame); listingFrame = 0;
  resizing.disconnect();
});
window.addEventListener('pageshow', event => {
  if (!event.persisted) return;
  scope = new AbortController();
  resizing.observe($('listing'));
  void start();
});
void start();
