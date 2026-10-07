/** A small, removable extension beside BB's bundled thread list. */
export type SidebarDock = { container: HTMLDivElement; filter: HTMLStyleElement };
let docks: SidebarDock[] = [];
const listeners = new Set<() => void>();
export const subscribeDocks = (listener: () => void) => {
  listeners.add(listener);
  return () => { listeners.delete(listener); };
};
export const getDocks = () => docks;

export function mountSidebarDock() {
  const attached = new Map<HTMLElement, SidebarDock>();
  const refresh = () => {
    // These attributes belong to the bundled Thread list. If BB changes its
    // markup, leave its list visible rather than hiding inaccessible worlds.
    const lists = new Set(Array.from(document.querySelectorAll<HTMLElement>(
      '[data-sidebar="content"]',
    )).filter(list => list.querySelector('[data-bb-plugin="thread-list"]')));
    let changed = false;
    for (const [list, dock] of attached) {
      if (lists.has(list) && dock.container.isConnected) continue;
      dock.container.remove();
      dock.filter.remove();
      attached.delete(list);
      changed = true;
    }
    for (const list of lists) {
      if (attached.has(list)) continue;
      const container = document.createElement("div");
      container.className = "mc-sidebar-dock";
      const filter = document.createElement("style");
      list.before(container);
      document.head.append(filter);
      attached.set(list, { container, filter });
      changed = true;
    }
    if (changed) {
      docks = [...attached.values()];
      listeners.forEach(listener => listener());
    }
  };
  const observer = new MutationObserver(refresh);
  observer.observe(document.body, { childList: true, subtree: true });
  refresh();
  return () => {
    observer.disconnect();
    attached.forEach(dock => { dock.container.remove(); dock.filter.remove(); });
    attached.clear();
    docks = [];
    listeners.forEach(listener => listener());
  };
}
