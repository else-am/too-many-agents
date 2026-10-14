import { FileTree } from "@pierre/trees";

// The picker: a path field and Open around a Pierre tree of one folder. Folders list lazily as they expand.
// Choosing only selects; navigation happens on the player's own Enter or Open, through native human navigation.

type Entry = { name: string; kind: string };
type Listing = { entries: Entry[]; truncated: boolean; cursor?: string };
type Context = {
  root: string;
  home: string;
  api: (operation: string, path?: string, cursor?: string, signal?: AbortSignal) => Promise<Listing>;
  address: (path: string, pick: boolean, hidden?: boolean) => string;
  notice: (text: string) => void;
};

const MAX_PATHS = 50_000;

function picker({ root, home, api, address, notice }: Context) {
  const field = document.getElementById("path") as HTMLInputElement;
  const showHidden = document.getElementById("show-hidden") as HTMLInputElement;
  const mount = document.getElementById("listing")!;
  const join = (folder: string, name: string) => `${folder.replace(/\/$/, "")}/${name}`;
  showHidden.checked = new URLSearchParams(location.search).has("hidden");

  // Listed entries by tree path (relative, folders end in "/"). open is the player's current expansion;
  // loaded and loading only say which folders' children are in entries.
  let entries = new Map<string, Entry>();
  const open = new Set<string>(), loading = new Set<string>();
  let loaded = new Set<string>([""]);
  let limited = false, truncated = false, updating = false, disposed = false;
  // scope covers folder loads and is replaced when a refresh lands; life ends with the page, taking every listener with it.
  let scope = new AbortController(), refreshing: AbortController | undefined;
  const life = new AbortController(), listening = { signal: life.signal };
  const windows = /^[a-z]:/i.test(root);
  const fieldRoot = root.endsWith("/") ? root : root + "/";
  let completing: AbortController | undefined, completion = 0;

  const hidden = (path: string) => path.split("/").some((part) => part.startsWith("."));
  const shown = (path: string) => showHidden.checked || !hidden(path);
  const parentsOpen = (path: string) => {
    for (let slash = path.lastIndexOf("/", path.length - 2); slash > 0; slash = path.lastIndexOf("/", slash - 1))
      if (!open.has(path.slice(0, slash + 1))) return false;
    return true;
  };

  const tree = new FileTree({
    paths: [],
    initialExpansion: "closed",
    dragAndDrop: false,
    renaming: false,
    onSelectionChange: (selected) => {
      if (updating || selected[0] === undefined) return;
      // The path field follows the choice, so Enter enters a folder or opens a file.
      field.value = join(root, selected[0]);
      completion++;
      completing?.abort();
    },
  });
  tree.render({ containerWrapper: mount });
  tree.subscribe(expanded);

  /** Lists a folder's pages into a map. Returns false when the budget ran out. */
  async function list(folder: string, into: Map<string, Entry>, signal: AbortSignal) {
    let cursor = "";
    do {
      const page = await api("list", folder.replace(/\/$/, ""), cursor, signal);
      signal.throwIfAborted();
      for (const entry of page.entries) {
        const key = folder + entry.name + (entry.kind === "directory" ? "/" : "");
        if (!into.has(key) && into.size >= MAX_PATHS) return false;
        into.set(key, entry);
      }
      truncated ||= page.truncated && !page.cursor;
      if (!page.cursor || page.cursor === cursor) break;
      cursor = page.cursor;
      await new Promise((resolve) => setTimeout(resolve, 0));
      signal.throwIfAborted();
    } while (cursor);
    return true;
  }

  /** Records the player's expansion from the tree. Folders not in the tree, such as hidden ones, keep theirs. */
  function expanded() {
    if (updating || disposed) return;
    for (const [path, entry] of entries) {
      const item = entry.kind === "directory" ? tree.getItem(path) : null;
      if (!item || !("isExpanded" in item)) continue;
      if (item.isExpanded()) open.add(path);
      else open.delete(path);
    }
    load();
  }

  /** Lists open folders whose children aren't loaded, adding them without disturbing the tree. */
  function load() {
    for (const path of open) {
      if (loaded.has(path) || loading.has(path) || !entries.has(path) || !shown(path) || !parentsOpen(path)) continue;
      if (loading.size >= 4) return;
      if (entries.size >= MAX_PATHS) { limited = true; report(); return; }
      loading.add(path);
      const signal = scope.signal, found = new Map<string, Entry>();
      list(path, found, signal).then((complete) => {
        if (signal.aborted) return;
        loading.delete(path);
        loaded.add(path);
        limited ||= !complete;
        // Merged here, so concurrent folders never add each other's names.
        const added: string[] = [];
        for (const [key, entry] of found) {
          if (entries.has(key)) continue;
          if (entries.size >= MAX_PATHS) { limited = true; break; }
          entries.set(key, entry);
          if (shown(key)) added.push(key);
        }
        change(() => {
          tree.batch(added.map((key) => ({ type: "add" as const, path: key })));
        });
        report();
        load();
      }, (error) => {
        if (signal.aborted) return;
        loading.delete(path);
        open.delete(path);
        const item = tree.getItem(path);
        if (item && "collapse" in item) change(() => item.collapse());
        failed(error);
        load();
      });
    }
  }

  /** Replaces the tree, keeping expansion, selection, focus and scroll where those paths remain. */
  function apply() {
    const selected = tree.getSelectedPaths()[0];
    const focused = mount.contains(document.activeElement) ? tree.getFocusedPath() : null;
    const scroller = tree.getFileTreeContainer()?.shadowRoot?.querySelector<HTMLElement>("[data-file-tree-virtualized-scroll]");
    const scroll = scroller?.scrollTop ?? 0;
    const paths = [...entries.keys()].filter(shown);
    change(() => {
      tree.resetPaths(paths, { initialExpandedPaths: [...open].filter((path) => entries.has(path) && shown(path) && parentsOpen(path)) });
      if (selected && entries.has(selected) && shown(selected)) tree.getItem(selected)?.select();
      // A choice that is no longer visible no longer fills the path field.
      else if (selected) field.value = fieldRoot;
      if (focused && entries.has(focused) && shown(focused)) tree.focusPath(focused);
    });
    requestAnimationFrame(() => { if (scroller) scroller.scrollTop = scroll; });
    report();
  }

  function change(work: () => void) {
    updating = true;
    try { work(); } finally { updating = false; }
  }

  function report() {
    const visible = [...entries.keys()].some((path) => !path.slice(0, -1).includes("/") && shown(path));
    notice(limited ? `Showing ${MAX_PATHS.toLocaleString()} names - listing incomplete. Type a path or use Tab completion to reach other names.`
      : truncated ? "Listing incomplete - enter a path or reopen the folder."
      : visible ? "" : entries.size ? "This folder has only hidden files." : "This folder is empty.");
  }

  function failed(error: Error) {
    if (error.name !== "AbortError" && !disposed) notice(error.message);
  }

  /**
   * Lists the root and every open folder again. Expansion is read live, so the player's changes while it runs win;
   * folders opened meanwhile load once it lands.
   */
  async function refresh() {
    refreshing?.abort();
    const run = refreshing = new AbortController();
    const next = new Map<string, Entry>(), folders = new Set<string>([""]);
    let incomplete = false;
    truncated = false;
    for (const folder of ["", ...[...open].sort((a, b) => a.length - b.length)]) {
      if (folder && !(open.has(folder) && next.get(folder)?.kind === "directory" && parentsOpen(folder))) continue;
      if (!(await list(folder, next, run.signal))) { incomplete = true; break; }
      folders.add(folder);
    }
    run.signal.throwIfAborted();
    // Folder loads begun against the old entries are discarded; their folders load again below.
    scope.abort();
    scope = new AbortController();
    loading.clear();
    entries = next;
    loaded = folders;
    limited = incomplete;
    apply();
    load();
  }

  showHidden.addEventListener("change", () => {
    // Presentation only: the flag survives picker navigation and restores, and never changes access.
    history.replaceState(history.state, "", address(root, true, showHidden.checked));
    apply();
    load();
  }, listening);
  document.getElementById("path-form")!.addEventListener("submit", (event) => {
    event.preventDefault();
    if (event.isTrusted) location.assign(address(field.value, true, showHidden.checked));
  }, listening);
  // Enter on the tree's focused row enters a folder or opens a file; Pierre keeps its own arrow keys.
  mount.addEventListener("keydown", (event) => {
    const chosen = tree.getFocusedPath() ?? tree.getSelectedPaths()[0];
    if (event.key !== "Enter" || !event.isTrusted || !chosen) return;
    event.preventDefault();
    location.assign(address(join(root, chosen), chosen.endsWith("/"), showHidden.checked));
  }, listening);
  const up = document.getElementById("up") as HTMLButtonElement;
  up.disabled = root === "/" || /^[a-z]:$/i.test(root);
  up.addEventListener("click", (event) => {
    if (event.isTrusted && !up.disabled) {
      const parent = root.slice(0, root.lastIndexOf("/")) || "/";
      location.assign(address(/^[a-z]:$/i.test(parent) ? parent + "/" : parent, true, showHidden.checked));
    }
  }, listening);
  document.getElementById("open")!.addEventListener("click", (event) => {
    // The field holds the choice: the current folder, a selected row, or what the player typed.
    if (event.isTrusted) location.assign(address(field.value, false));
  }, listening);
  field.value = fieldRoot;
  field.addEventListener("input", () => {
    completion++;
    completing?.abort();
    change(() => { for (const path of tree.getSelectedPaths()) tree.getItem(path)?.deselect(); });
  }, listening);
  field.addEventListener("keydown", async (event) => {
    if (event.key !== "Tab" || !event.isTrusted) return;
    event.preventDefault();
    completing?.abort();
    completing = new AbortController();
    const signal = completing.signal, version = ++completion;
    // Backslashes are separators only on Windows; elsewhere they're part of names.
    const typed = windows ? field.value.replaceAll("\\", "/") : field.value;
    let full = typed;
    if (full === "~" || full.startsWith("~/")) full = home + full.slice(1);
    if (!full.startsWith("/") && !/^[a-z]:\//i.test(full)) full = join(root, full);
    const cut = full.lastIndexOf("/"), folder = full.slice(0, cut) || "/", prefix = full.slice(cut + 1);
    const inside = root === "/" ? "/" : root + "/";
    const relative = folder === root ? "" : folder.startsWith(inside) ? folder.slice(inside.length) : null;
    if (relative === null) { notice("Press Enter to enter that folder before completing its names."); return; }
    // Hidden names complete when shown, or when the typed name starts with a dot.
    const includeHidden = showHidden.checked || prefix.startsWith(".");
    try {
      let cursor = "", shared = "", count = 0, only: Entry | undefined;
      notice("Completing names…");
      do {
        const page = await api("list", relative, cursor, signal);
        if (version !== completion) return;
        for (const item of page.entries) {
          if (!includeHidden && item.name.startsWith(".")) continue;
          if (!item.name.toLowerCase().startsWith(prefix.toLowerCase())) continue;
          if (!count++) { shared = item.name; only = item; }
          else while (!item.name.startsWith(shared)) shared = shared.slice(0, -1);
        }
        if (page.cursor && page.cursor === cursor) throw Error("Folder listing stopped advancing. Reopen the folder to refresh.");
        cursor = page.cursor || "";
        if (page.truncated && !cursor) throw Error("Completion is incomplete. Enter the full name or reopen the folder.");
      } while (cursor);
      if (!count) { notice("No matching names."); return; }
      const tail = count === 1 && only?.kind === "directory" ? "/" : "";
      // A case-insensitive match with differing case may have no shared spelling; keep the typed prefix.
      field.value = typed.slice(0, typed.lastIndexOf("/") + 1) + (shared || prefix) + tail;
      notice(count > 1 ? `${count} matching names` : "");
    } catch (error) {
      if (version === completion) failed(error as Error);
    }
  }, listening);

  void refresh().catch(failed);
  return {
    refresh: () => refresh().catch(failed),
    dispose() {
      disposed = true;
      life.abort();
      scope.abort();
      refreshing?.abort();
      completing?.abort();
      completion++;
      tree.cleanUp();
    },
  };
}

(window as unknown as { tmaFilesPicker: typeof picker }).tmaFilesPicker = picker;
