// Bundled controls choose locations through native, human-gesture navigation. Data requests never grant roots.
const $ = id => document.getElementById(id);
const query = new URLSearchParams(location.search);
const current = decodeURIComponent(location.pathname);
const windows = /^\/[a-z]:\//i.test(current);
const nativePath = value => windows && /^\/[a-z]:\//i.test(value) ? value.slice(1) : value;
const slash = value => windows ? value.replaceAll('\\', '/') : value;
const root = slash(nativePath(current)).replace(/\/$/, '') || '/';
let home = '', entries = [], selected = -1, completion = 0;
const notice = text => { $('notice').textContent = text; };
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
async function api(operation, path = '') {
  const response = await fetch(`/_api/${operation}?path=${encodeURIComponent(path)}`, {cache: 'no-store'});
  const value = await response.json();
  if (!response.ok || value.error) throw Error(value.error || 'Could not read this folder.');
  return value;
}
$('location').textContent = nativePath(current);
$('location').href = address(root, true);
$('path').value = root.endsWith('/') ? root : root + '/';
function select(index) {
  if (!entries.length) return;
  selected = Math.max(0, Math.min(entries.length - 1, index));
  [...$('listing').children].forEach((row, i) => row.classList.toggle('selected', i === selected));
  $('listing').children[selected]?.scrollIntoView({block: 'nearest'});
}
function render(items) {
  entries = items;
  $('listing').replaceChildren();
  for (const [index, item] of items.entries()) {
    const row = document.createElement('a');
    row.className = `item${item.hidden ? ' hidden-file' : ''}`;
    row.href = address(join(root, item.name), item.kind === 'directory' || item.kind === 'link');
    const icon = document.createElement('span');
    icon.className = 'icon';
    icon.textContent = item.kind === 'directory' ? '▸' : item.kind === 'link' ? '↗' : '';
    const label = document.createElement('span');
    label.textContent = item.name;
    row.append(icon, label);
    row.addEventListener('focus', () => select(index));
    $('listing').append(row);
  }
}
$('path-form').addEventListener('submit', event => {
  event.preventDefault();
  // SubmitEvent from keyboard submission is trusted; the resulting navigation retains Chromium's gesture.
  humanNavigate(event, $('path').value, true);
});
$('open').addEventListener('click', event => humanNavigate(event, root, false));
$('listing').addEventListener('keydown', event => {
  if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
    event.preventDefault();
    select(selected + (event.key === 'ArrowDown' ? 1 : -1));
  } else if (event.key === 'Enter' && selected >= 0) {
    event.preventDefault();
    const item = entries[selected];
    humanNavigate(event, join(root, item.name), item.kind === 'directory' || item.kind === 'link');
  }
});
$('path').addEventListener('input', () => { completion++; });
$('path').addEventListener('keydown', async event => {
  if (event.key === 'ArrowDown') { event.preventDefault(); $('listing').focus(); select(0); return; }
  if (event.key !== 'Tab' || !event.isTrusted) return;
  event.preventDefault();
  const version = ++completion;
  const typed = slash($('path').value);
  let full = typed;
  if (full === '~' || full.startsWith('~/')) full = home + full.slice(1);
  if (!full.startsWith('/') && !/^[a-z]:\//i.test(full)) full = join(root, full);
  const cut = full.lastIndexOf('/'), folder = full.slice(0, cut) || '/', prefix = full.slice(cut + 1);
  const relative = folder === root ? '' : folder.startsWith(root === '/' ? '/' : root + '/') ? folder.slice(root === '/' ? 1 : root.length + 1) : null;
  if (relative === null) { notice('Press Enter to enter that folder before completing its names.'); return; }
  try {
    const listing = await api('list', relative);
    if (version !== completion) return;
    const matches = listing.entries.filter(item => item.name.toLowerCase().startsWith(prefix.toLowerCase()));
    if (!matches.length) { notice('No matching names.'); return; }
    let shared = matches[0].name;
    for (const item of matches) while (!item.name.startsWith(shared)) shared = shared.slice(0, -1);
    const tail = matches.length === 1 && matches[0].kind === 'directory' ? '/' : '';
    $('path').value = typed.slice(0, typed.lastIndexOf('/') + 1) + shared + tail;
    notice(matches.length > 1 ? `${matches.length} matching names${listing.truncated ? ' - listing incomplete' : ''}` : '');
  } catch (error) { if (version === completion) notice(error.message); }
});
(async () => {
  try {
    const state = await api('state');
    home = slash(state.home);
    if (state.notice) notice(state.notice);
    if (query.has('pick')) {
      $('picker').hidden = false;
      const listing = await api('list');
      render(listing.entries);
      notice(listing.truncated ? 'Showing the first 2,000 entries - enter a path to open other names.' : listing.entries.length ? '' : 'This folder is empty.');
    } else {
      $('viewer').hidden = false;
      $('selection').textContent = query.get('open') || nativePath(current);
    }
  } catch (error) { notice(error.message); }
})();
