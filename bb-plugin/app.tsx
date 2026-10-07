import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { createPortal } from "react-dom";
import * as Menu from "@radix-ui/react-dropdown-menu";
import {
  definePluginApp,
  experimental_useSidebarThreads,
  experimental_useSidebarThreadActions,
  useBbContext,
  useRealtimeConnectionState,
  useSdk,
  type PluginSidebarThread,
} from "@get-bb/plugin-sdk/app";
import { isWorldWorkspace } from "./world-workspace.js";
import { getDocks, mountSidebarDock, subscribeDocks, type SidebarDock } from "./sidebar-dock.js";
import "./app.css";

type WorldProject = { id: string; name: string };

function tone(thread: PluginSidebarThread) {
  if (thread.hasPendingInteraction || thread.indicator === "unread-error" || thread.queuedWork === "failed") return "attention";
  if (["active", "starting", "stopping", "pending"].includes(thread.status)) return "working";
  if (thread.isUnread) return "unread";
  return "idle";
}

function Chevron() {
  return <svg className="mc-chevron" width="12" height="12" viewBox="0 0 12 12" aria-hidden="true">
    <path d="m4.5 2.5 3.5 3.5-3.5 3.5" fill="none" stroke="currentColor" strokeWidth="1.2" />
  </svg>;
}

function ThreadRow({ thread, close }: { thread: PluginSidebarThread; close: () => void }) {
  const actions = experimental_useSidebarThreadActions();
  const context = useBbContext();
  return <Menu.Item asChild textValue={thread.displayTitle}>
    <a className="mc-menu-row mc-thread" href={thread.href}
      aria-current={context.threadId === thread.id ? "page" : undefined} title={thread.displayTitle}
      onClick={event => {
        if (event.button !== 0 || event.shiftKey || event.altKey) return;
        event.preventDefault();
        actions.open(thread.id, { split: event.metaKey || event.ctrlKey });
        close();
      }}>
      <span className="mc-status" data-tone={tone(thread)} role="img"
        aria-label={thread.indicatorLabel ?? (thread.isUnread ? "Unread" : "Idle")} />
      <span className="mc-thread-title" data-unread={thread.isUnread}>{thread.displayTitle}</span>
    </a>
  </Menu.Item>;
}

function WorldCollection({ dock }: { dock: SidebarDock }) {
  const sdk = useSdk();
  const connection = useRealtimeConnectionState();
  const [worlds, setWorlds] = useState<WorldProject[] | null>(null);
  const [error, setError] = useState(false);
  const [open, setOpen] = useState(false);
  const sidebar = experimental_useSidebarThreads();
  const load = useCallback(async (signal?: AbortSignal) => {
    try {
      const projects = await sdk.projects.list({ signal });
      setWorlds(projects.filter(project => project.sources.some(source => isWorldWorkspace(source.path))));
      setError(false);
    } catch { if (!signal?.aborted) setError(true); }
  }, [sdk]);
  useEffect(() => {
    const controller = new AbortController();
    const refresh = () => { void load(controller.signal); };
    refresh();
    const unsubscribe = sdk.subscribe({ event: "project:changed", callback: refresh });
    return () => { controller.abort(); unsubscribe(); };
  }, [sdk, load, connection]);
  useEffect(() => {
    if (!worlds || error || sidebar.status !== "ready") return;
    // Presentation only: no hidden-project preferences, so worlds do not get
    // sent into BB's generic More menu. Unmounting restores the normal list.
    dock.filter.textContent = worlds.map(world =>
      `[data-bb-plugin="thread-list"] [data-sidebar-visibility-group="project:${CSS.escape(world.id)}"] { display: none !important; }`,
    ).join("\n");
    return () => { dock.filter.textContent = ""; };
  }, [dock, worlds, error, sidebar.status]);
  const worldIds = new Set(worlds?.map(world => world.id));
  const threads = sidebar.threads.filter(thread => worldIds.has(thread.projectId) && !thread.isHidden);
  const attention = threads.some(thread => tone(thread) === "attention");
  const working = threads.some(thread => tone(thread) === "working");
  const unread = threads.some(thread => thread.isUnread);
  const projects = (worlds ?? []).map(world => ({
    ...world,
    name: (sidebar.projects.find(project => project.id === world.id)?.name ?? world.name).replace(/^Minecraft:\s*/, ""),
    threads: threads.filter(thread => thread.projectId === world.id).sort((a, b) => b.updatedAt - a.updatedAt),
  })).sort((a, b) => a.name.localeCompare(b.name));
  return <Menu.Root modal={false} open={open} onOpenChange={setOpen}>
    <Menu.Trigger className="mc-collection-trigger">
      <span className="mc-world-mark" aria-hidden="true" />
      <span className="mc-collection-label">Minecraft worlds</span>
      {(attention || working || unread) && <span className="mc-status" data-tone={attention ? "attention" : working ? "working" : "unread"}
        role="img" aria-label={attention ? "Needs attention" : working ? "Working" : "Unread threads"} />}
      <Chevron />
    </Menu.Trigger>
    <Menu.Portal>
      <Menu.Content className="mc-menu" side="right" align="start" sideOffset={8} collisionPadding={12} aria-label="Minecraft worlds">
        {error || sidebar.status === "error" ? <Menu.Item className="mc-menu-row" onSelect={event => { event.preventDefault(); void load(); }}>Couldn’t load worlds · Retry</Menu.Item>
          : worlds === null || sidebar.status === "loading" ? <p className="mc-empty" role="status">Loading worlds…</p>
          : projects.length === 0 ? <p className="mc-empty">No Minecraft world projects yet.</p>
          : projects.map(world => <Menu.Sub key={world.id}>
            <Menu.SubTrigger className="mc-menu-row" textValue={world.name}>
              <span className="mc-world-name">{world.name}</span>
              <span className="mc-count">{world.threads.length}</span>
              <Chevron />
            </Menu.SubTrigger>
            <Menu.Portal>
              <Menu.SubContent className="mc-menu mc-thread-menu" sideOffset={6} collisionPadding={12} aria-label={`${world.name} threads`}>
                <Menu.Label className="mc-subheading">{world.name}</Menu.Label>
                {world.threads.length ? world.threads.map(thread => <ThreadRow key={thread.id} thread={thread} close={() => setOpen(false)} />)
                  : <p className="mc-empty">No active threads</p>}
              </Menu.SubContent>
            </Menu.Portal>
          </Menu.Sub>)}
      </Menu.Content>
    </Menu.Portal>
  </Menu.Root>;
}

function SidebarCollections() {
  const docks = useSyncExternalStore(subscribeDocks, getDocks);
  return docks.map((dock, index) => createPortal(<WorldCollection dock={dock} />, dock.container, String(index)));
}

export default definePluginApp(app => {
  app.contentScripts.register({ id: "world-collection-dock", mount: mountSidebarDock });
  app.slots.experimental_appOverlay({ id: "world-collection", component: SidebarCollections });
});
