import { FileTree, type GitStatusEntry } from "@pierre/trees";
import {
  File as CodeFile,
  FileDiff,
  type SelectedLineRange,
} from "@pierre/diffs";
import DOMPurify from "dompurify";
import { marked } from "marked";
import "./viewer.css";

type State = {
  document?: number;
  root: string;
  git?: boolean;
  notice?: string;
  notices?: string[];
};
type Content = {
  base64?: string;
  size?: number;
  tooLarge?: boolean;
  deleted?: boolean;
  notice?: string;
  image?: {
    mime: string;
    width: number;
    height: number;
    decodedPixels: number;
    safe: boolean;
  };
};
type TreeData = {
  paths: string[];
  directories: string[];
  ignored: string[];
  partial?: boolean;
  notices?: string[];
  git?: boolean;
  cursor?: string;
};
type Invalidation = {
  content?: boolean;
  structure?: boolean;
  git?: boolean;
  rules?: boolean;
  reconcile?: boolean;
  limited?: boolean;
  paths?: string[];
};
type Change = { path: string; kind: string; notice?: string };
type DiffRow = {
  change: Change;
  section: HTMLElement;
  content: HTMLElement;
  note: HTMLElement;
  view?: FileDiff;
  request?: AbortController;
  ready: boolean;
  dirty: boolean;
};
const MAX_DIFF_REQUESTS = 3;
const MAX_RENDERED_DIFFS = 12;
const MAX_TREE_PATHS = 100_000;
const TREE_LIMIT_NOTICE = "Tree limit reached - narrow the search to continue";
const MAX_MARKDOWN_PIXELS = 32_000_000;
const el = <K extends keyof HTMLElementTagNameMap>(
  tag: K,
  className = "",
  text = "",
) => {
  const node = document.createElement(tag);
  node.className = className;
  node.textContent = text;
  return node;
};
const bytes = (content: Content) =>
  Uint8Array.from(atob(content.base64 ?? ""), (c) => c.charCodeAt(0));
const text = (content: Content) =>
  new TextDecoder("utf-8", { fatal: true }).decode(bytes(content));
const imageType = (path: string) =>
  ({
    png: "image/png",
    jpg: "image/jpeg",
    jpeg: "image/jpeg",
    gif: "image/gif",
    webp: "image/webp",
    svg: "image/svg+xml",
    avif: "image/avif",
  })[path.split(".").pop()?.toLowerCase() ?? ""];
const safeImage = (content: Content) => {
  const image = content.image;
  return (
    image?.safe === true &&
    /^image\/(png|jpeg|gif|webp|svg\+xml|avif)$/.test(image.mime) &&
    Number.isInteger(image.width) &&
    Number.isInteger(image.height) &&
    image.width > 0 &&
    image.height > 0 &&
    image.width <= 8192 &&
    image.height <= 8192 &&
    image.width * image.height <= 16_000_000 &&
    Number.isSafeInteger(image.decodedPixels) &&
    image.decodedPixels >= image.width * image.height &&
    image.decodedPixels <= 16_000_000
  );
};
const rangeFromURL = (): SelectedLineRange | null => {
  const m = /^#L(\d+)(?:-L?(\d+))?$/.exec(location.hash);
  if (!m) return null;
  const start = Number(m[1]);
  const end = Number(m[2] ?? m[1]);
  // A 2 MiB preview cannot contain more than this many lines.
  if (
    ![start, end].every(
      (line) => Number.isSafeInteger(line) && line > 0 && line <= 2_097_153,
    )
  )
    return null;
  return { start, end };
};

/** Returns lifecycle hooks for the picker shell and its native watcher. */
function mount(element: HTMLElement, state: State) {
  let disposed = false,
    mode = "",
    base = "",
    currentPath = "",
    split = false,
    preview = false;
  let tree: FileTree | undefined, observer: IntersectionObserver | undefined;
  let scope = new AbortController(),
    contentScope = new AbortController(),
    searchScope = new AbortController();
  let fileView: CodeFile | undefined,
    searchTimer = 0,
    refreshTimer = 0,
    treeRequest = 0;
  let pendingRefresh: Invalidation | undefined;
  let refreshing = false;
  let contentScrollVersion = 0;
  let expectedContentScroll = 0;
  const contentPaths = new Set<string>();
  const diffs = new Map<string, DiffRow>();
  const nearDiffs = new Set<DiffRow>();
  const activeDiffRequests = new Set<AbortController>();
  let diffFrame = 0;
  let pinnedDiff: DiffRow | undefined;
  let diffAnchor: { element: HTMLElement; top: number } | undefined;
  let lineScrollPending = false;
  const knownPaths = new Set<string>();
  const expandedTreePaths = new Set<string>();
  let updatingTree = false;
  let treeSelectionPending = false;
  let treeScrollPending = false;
  let treeRevealFrame = 0;
  let treeRefreshAnchor: { path: string; offset: number } | undefined;
  let treeInteraction = 0;
  let treeLimitReached = false;
  const directoryRequests = new Map<string, AbortController>();
  const pendingDirectories = new Set<string>();
  let directoryBusy = false;
  const blobs = new Set<string>(),
    directories = new Set<string>(),
    loaded = new Set<string>();
  const wrapper = el("div", "files-view");
  const header = el("header", "fv-header"),
    modes = el("div", "fv-controls");
  const allButton = el("button", "", "All files"),
    changesButton = el("button", "", "Changes");
  modes.append(allButton, changesButton);
  const baseControls = el("form", "fv-controls");
  const baseSelect = el("select");
  baseSelect.setAttribute("aria-label", "Comparison base");
  for (const [value, label] of [
    ["branch", "Branch"],
    ["uncommitted", "Uncommitted"],
    ["ref", "Any ref"],
  ]) {
    const option = el("option", "", label);
    option.value = value;
    baseSelect.append(option);
  }
  const ref = el("input");
  ref.placeholder = "Commit or ref";
  ref.setAttribute("aria-label", "Commit or ref");
  ref.setAttribute("list", "fv-refs");
  const refs = el("datalist");
  refs.id = "fv-refs";
  const compare = el("button", "", "Compare");
  compare.type = "submit";
  const splitButton = el("button", "", "Split");
  splitButton.type = "button";
  baseControls.append(baseSelect, ref, refs, compare, splitButton);
  const status = el("div", "fv-status");
  status.setAttribute("role", "status");
  const notices = el("details", "fv-notices");
  const noticeSummary = el("summary");
  const noticeBody = el("div", "fv-notice-list");
  const noticeGroups = new Map<string, string[]>();
  notices.append(noticeSummary, noticeBody);
  header.append(modes, baseControls, status, notices);
  function setNotices(group: string, messages: (string | null | undefined)[]) {
    noticeGroups.set(
      group,
      messages.filter((message): message is string => Boolean(message)),
    );
    const all = [...new Set([...noticeGroups.values()].flat())];
    notices.hidden = all.length === 0;
    noticeSummary.textContent = `Notes (${all.length})`;
    noticeBody.replaceChildren(...all.map((message) => el("div", "", message)));
  }
  setNotices("state", [state.notice, ...(state.notices ?? [])]);
  const body = el("div", "fv-body"),
    sidebar = el("aside", "fv-sidebar"),
    search = el("input", "fv-search");
  search.placeholder = "Find a file";
  search.setAttribute("aria-label", "Find a file");
  const treeMount = el("div", "fv-tree"),
    treeStatus = el("div", "fv-tree-status");
  const main = el("main", "fv-content");
  const diffTail = el("div", "fv-diff-tail");
  diffTail.setAttribute("aria-hidden", "true");
  const diffResize = new ResizeObserver(() => {
    if (mode !== "changes" || disposed) return;
    diffTail.style.height = main.clientHeight + "px";
    restoreAnchor(diffAnchor);
  });
  diffResize.observe(main);
  main.tabIndex = -1;
  sidebar.append(search, treeStatus, treeMount);
  body.append(sidebar, main);
  wrapper.append(header, body);
  element.replaceChildren(wrapper);

  async function api<T>(
    name: string,
    params: Record<string, string> = {},
    signal = scope.signal,
  ): Promise<T> {
    const response = await fetch(
      "/_api/" + name + "?" + new URLSearchParams(params),
      { signal, credentials: "same-origin", cache: "no-store" },
    );
    const value = await response.json();
    if (!response.ok) throw new Error(value.error || "Unable to load " + name);
    if (signal.aborted || disposed)
      throw new DOMException("Aborted", "AbortError");
    return value;
  }
  function report(error: unknown) {
    if (
      !disposed &&
      !(error instanceof DOMException && error.name === "AbortError")
    )
      status.textContent =
        error instanceof Error ? error.message : String(error);
  }
  function urlUpdate(
    values: Record<string, string | null>,
    hash?: string,
    replace = false,
  ) {
    const url = new URL(location.href);
    for (const [key, value] of Object.entries(values))
      value === null
        ? url.searchParams.delete(key)
        : url.searchParams.set(key, value);
    if (hash !== undefined) url.hash = hash;
    history[replace ? "replaceState" : "pushState"](null, "", url);
  }
  function revokeImages() {
    for (const url of blobs) URL.revokeObjectURL(url);
    blobs.clear();
  }
  function blob(content: Content, type: string) {
    const url = URL.createObjectURL(new Blob([bytes(content)], { type }));
    blobs.add(url);
    return url;
  }
  function placeholder(
    host: HTMLElement,
    content: Content,
    label = "Binary file",
  ) {
    host.replaceChildren(
      el(
        "div",
        "fv-placeholder",
        content.deleted
          ? "Deleted"
          : content.tooLarge
            ? "File exceeds the 2 MB preview limit"
            : label,
      ),
    );
    const details = [
      content.size === undefined
        ? ""
        : `${content.size.toLocaleString()} bytes`,
      content.notice,
    ]
      .filter(Boolean)
      .join(" - ");
    if (details) host.append(el("div", "fv-size", details));
  }
  function selectLines(path: string, range: SelectedLineRange | null) {
    if (!range) return;
    urlUpdate(
      { open: path },
      `L${range.start}${range.end !== range.start ? "-L" + range.end : ""}`,
    );
    currentPath = path;
  }
  function lineOptions(path: string) {
    return {
      theme: "pierre-dark" as const,
      preferredHighlighter: "shiki-js" as const,
      themeType: "dark" as const,
      enableLineSelection: true,
      onLineSelectionEnd: (range: SelectedLineRange | null) =>
        selectLines(path, range),
    };
  }
  function applyLines(
    view: CodeFile | FileDiff,
    host: HTMLElement,
    scroll: boolean,
  ) {
    const range = rangeFromURL();
    view.setSelectedLines(range, { notify: false });
    if (!range || !scroll) return;
    if (view instanceof FileDiff) {
      view.revealLine(range.start);
      view.revealLine(range.end);
    }
    requestAnimationFrame(() => {
      if (!host.isConnected) return;
      const shadow =
        host.shadowRoot ?? host.querySelector("diffs-container")?.shadowRoot;
      // Pierre uses data-line for file rows and paired unified/split indexes for diffs.
      const index =
        view instanceof FileDiff
          ? view.getLineIndex(range.start, "additions")
          : undefined;
      const selector = index
        ? `[data-line][data-line-index="${index[0]},${index[1]}"]`
        : `[data-line="${range.start}"]`;
      shadow?.querySelector(selector)?.scrollIntoView({ block: "center" });
    });
  }
  function relativeResource(href: string, path: string): string | null {
    if (!href || /^[a-z][a-z\d+.-]*:|^\/\//i.test(href) || href.startsWith("/"))
      return null;
    let decoded: string;
    try {
      decoded = decodeURIComponent(href.split(/[?#]/)[0]);
    } catch {
      return null;
    }
    const parts = path.split("/").slice(0, -1);
    for (const part of decoded.split("/")) {
      if (!part || part === ".") continue;
      if (part === "..") {
        if (!parts.length) return null;
        parts.pop();
      } else parts.push(part);
    }
    return parts.join("/");
  }
  function scrollHeading(host: HTMLElement, hash: string) {
    let id: string;
    try {
      id = decodeURIComponent(hash.slice(1));
    } catch {
      return;
    }
    const target = [...host.querySelectorAll<HTMLElement>("[id]")].find(
      (node) => node.id === id,
    );
    target?.scrollIntoView({ block: "start" });
  }
  async function markdown(
    host: HTMLElement,
    source: string,
    path: string,
    signal: AbortSignal,
  ) {
    // Sanitize before insertion; resource URLs are removed before any DOM is attached.
    const fragment = DOMPurify.sanitize(
      marked.parse(source, { async: false }),
      {
        RETURN_DOM_FRAGMENT: true,
        USE_PROFILES: { html: true },
        FORBID_TAGS: [
          "style",
          "form",
          "input",
          "button",
          "iframe",
          "object",
          "embed",
          "video",
          "audio",
          "source",
        ],
        FORBID_ATTR: ["style", "srcset", "ping", "autofocus"],
      },
    );
    const headingIds = new Set<string>();
    for (const heading of fragment.querySelectorAll("h1,h2,h3,h4,h5,h6")) {
      const slug =
        (heading.textContent ?? "")
          .toLowerCase()
          .trim()
          .replace(/[^\p{L}\p{N}_\s-]/gu, "")
          .replace(/\s/g, "-") || "heading";
      let id = heading.id || slug;
      let suffix = 1;
      while (headingIds.has(id)) id = `${slug}-${suffix++}`;
      heading.id = id;
      headingIds.add(id);
    }
    const images: { node: HTMLImageElement; path: string }[] = [];
    for (const node of fragment.querySelectorAll("img")) {
      const target = relativeResource(node.getAttribute("src") ?? "", path);
      node.removeAttribute("src");
      if (target && imageType(target)) {
        contentPaths.add(target);
        images.push({ node, path: target });
      } else
        node.replaceWith(
          el("span", "fv-muted", node.alt || "Image unavailable"),
        );
    }
    for (const link of fragment.querySelectorAll("a")) {
      const href = link.getAttribute("href") ?? "";
      if (/^https?:\/\//i.test(href)) {
        link.rel = "noreferrer noopener";
        link.referrerPolicy = "no-referrer";
        continue;
      }
      const target = relativeResource(href, path);
      if (href.startsWith("#")) {
        link.addEventListener("click", (event) => {
          event.preventDefault();
          urlUpdate({}, href);
          scrollHeading(host, href);
        });
        continue;
      }
      if (!target) {
        link.removeAttribute("href");
        continue;
      }
      const url = new URL(location.href);
      url.searchParams.set("open", target);
      url.hash = href.includes("#") ? href.slice(href.indexOf("#")) : "";
      link.href = url.href;
      link.addEventListener("click", (event) => {
        event.preventDefault();
        urlUpdate({ open: target }, url.hash);
        void openFile(target).catch(report);
      });
    }
    host.classList.add("fv-markdown");
    host.replaceChildren(fragment);
    // A document cannot fan out unbounded local reads or image decodes.
    let pixels = 0;
    for (const image of images.slice(24)) {
      image.node.replaceWith(
        el("span", "fv-muted", "Image preview limit reached (24 images)"),
      );
    }
    for (const image of images.slice(0, 24)) {
      if (signal.aborted) return;
      try {
        const data = await api<Content>("file", { path: image.path }, signal);
        if (!data.tooLarge && !data.deleted && data.base64 && safeImage(data)) {
          const nextPixels = data.image!.decodedPixels;
          if (pixels + nextPixels > MAX_MARKDOWN_PIXELS) {
            image.node.replaceWith(
              el(
                "span",
                "fv-muted",
                "Image preview limit reached (32 million pixels)",
              ),
            );
          } else {
            pixels += nextPixels;
            image.node.width = data.image!.width;
            image.node.height = data.image!.height;
            image.node.src = blob(data, data.image!.mime);
          }
        } else
          image.node.replaceWith(
            el(
              "span",
              "fv-muted",
              data.notice ||
                "Image preview unavailable - safe dimensions required",
            ),
          );
      } catch (error) {
        if (signal.aborted) return;
        image.node.alt = "Image unavailable";
      }
    }
  }
  async function openFile(path: string, preserveScroll = false) {
    currentPath = path;
    contentScope.abort();
    contentScope = new AbortController();
    const signal = contentScope.signal;
    contentPaths.clear();
    contentPaths.add(path);
    if (!preserveScroll) status.textContent = "Loading…";
    const data = await api<Content>("file", { path }, signal);
    const oldView = fileView;
    const oldBlobs = new Set(blobs);
    let nextView: CodeFile | undefined;
    let committed = false;
    let scroll = 0;
    let scrollVersion = contentScrollVersion;
    let restoreFrame = 0;
    const restoreScroll = () => {
      cancelAnimationFrame(restoreFrame);
      restoreFrame = requestAnimationFrame(() => {
        if (
          signal.aborted ||
          !committed ||
          contentScrollVersion !== scrollVersion
        )
          return;
        main.scrollTop = scroll;
        expectedContentScroll = main.scrollTop;
      });
    };
    const commit = () => {
      if (committed || signal.aborted) return;
      scroll = preserveScroll ? main.scrollTop : 0;
      scrollVersion = contentScrollVersion;
      committed = true;
      oldView?.cleanUp();
      fileView = nextView;
      for (const url of oldBlobs) {
        URL.revokeObjectURL(url);
        blobs.delete(url);
      }
      main.replaceChildren(title, content);
      main.scrollTop = scroll;
      expectedContentScroll = main.scrollTop;
      if (preserveScroll) restoreScroll();
    };
    const title = el("div", "fv-file-header"),
      name = el("span", "", path),
      content = el("div", "fv-file");
    title.append(name);
    if (/\.(md|markdown)$/i.test(path)) {
      const toggle = el("button", "", preview ? "Source" : "Preview");
      toggle.onclick = () => {
        preview = !preview;
        void openFile(path).catch(report);
      };
      title.append(toggle);
    }
    if (
      data.deleted ||
      data.tooLarge ||
      data.base64 == null ||
      data.base64 === ""
    )
      placeholder(
        content,
        data,
        data.base64 === "" && !data.deleted
          ? "Empty file"
          : "Binary or unavailable file",
      );
    else if (imageType(path) && safeImage(data)) {
      const img = el("img", "fv-image");
      img.alt = path;
      img.width = data.image!.width;
      img.height = data.image!.height;
      img.src = blob(data, data.image!.mime);
      content.append(img);
    } else if (imageType(path) && !/\.svg$/i.test(path))
      placeholder(
        content,
        data,
        "Image preview unavailable - safe dimensions required",
      );
    else {
      let source: string | undefined;
      try {
        source = text(data);
        if (source.includes("\0")) source = undefined;
      } catch {
        /* Non-UTF8 content is displayed as binary. */
      }
      if (source === undefined) placeholder(content, data);
      else if (preview && /\.(md|markdown)$/i.test(path))
        await markdown(content, source, path, signal);
      else {
        let located = false;
        nextView = new CodeFile({
          ...lineOptions(path),
          disableFileHeader: true,
          onPostRender: (_host, instance, phase) => {
            if (phase === "unmount" || signal.aborted || nextView !== instance)
              return;
            commit();
            if (preserveScroll) restoreScroll();
            if (!located) {
              located = true;
              applyLines(instance, content, !preserveScroll);
            }
          },
        });
        signal.addEventListener(
          "abort",
          () => {
            cancelAnimationFrame(restoreFrame);
            if (!committed) nextView?.cleanUp();
          },
          { once: true },
        );
        nextView.render({
          file: { name: path, contents: source },
          containerWrapper: content,
        });
      }
    }
    if (!signal.aborted) {
      if (!nextView) commit();
      status.textContent = "";
      setNotices("file", [data.notice]);
      if (preview && !preserveScroll && location.hash)
        scrollHeading(content, location.hash);
    }
  }
  function choose(path: string) {
    if (path === currentPath && mode !== "changes") return;
    urlUpdate({ open: path }, "");
    currentPath = path;
    if (mode === "changes") revealDiff(path);
    else void openFile(path).catch(report);
  }
  function rememberPath(path: string) {
    const additions = new Set<string>();
    if (!knownPaths.has(path)) additions.add(path);
    let separator = path.lastIndexOf("/", path.length - 2);
    while (separator >= 0) {
      const parent = path.slice(0, separator + 1);
      if (!knownPaths.has(parent)) additions.add(parent);
      separator = path.lastIndexOf("/", separator - 1);
    }
    if (knownPaths.size + additions.size > MAX_TREE_PATHS) {
      treeLimitReached = true;
      return false;
    }
    for (const entry of additions) {
      knownPaths.add(entry);
      if (entry.endsWith("/")) directories.add(entry.slice(0, -1));
    }
    return true;
  }
  function hasExpandedParents(path: string) {
    for (
      let slash = path.lastIndexOf("/", path.length - 2);
      slash > 0;
      slash = path.lastIndexOf("/", slash - 1)
    ) {
      if (!expandedTreePaths.has(path.slice(0, slash + 1))) return false;
    }
    return true;
  }
  function treeChanged() {
    if (updatingTree) return;
    for (const directory of directories) {
      const path = directory + "/";
      const item = tree?.getItem(path);
      if (!item || !("isExpanded" in item)) continue;
      if (item.isExpanded()) expandedTreePaths.add(path);
      else {
        if (expandedTreePaths.delete(path)) treeScrollPending = false;
      }
    }
    // Pierre's expand operation opens ancestors too; do not retain a request
    // that would reopen a folder the human just collapsed.
    for (const path of expandedTreePaths) {
      if (!hasExpandedParents(path)) expandedTreePaths.delete(path);
    }
    if (treeLimitReached || mode === "changes" || search.value.trim()) return;
    for (const path of directories) {
      const item = tree?.getItem(path + "/");
      if (
        item &&
        "isExpanded" in item &&
        item.isExpanded() &&
        !loaded.has(path)
      ) {
        loaded.add(path);
        pendingDirectories.add(path);
      }
    }
    pumpDirectories();
  }
  function restoreTreeState() {
    if (!tree) return;
    updatingTree = true;
    try {
      // Missing folders remain pending until a later tree page supplies them.
      for (const path of expandedTreePaths) {
        if (!hasExpandedParents(path)) continue;
        const item = tree.getItem(path);
        if (item && "expand" in item && !item.isExpanded()) item.expand();
      }
      const selected = tree.getItem(currentPath);
      if (treeSelectionPending && selected && !selected.isDirectory()) {
        for (const path of tree.getSelectedPaths()) {
          if (path !== currentPath) tree.getItem(path)?.deselect();
        }
        selected.select();
        treeSelectionPending = false;
        if (treeScrollPending) {
          cancelAnimationFrame(treeRevealFrame);
          const selectedTree = tree;
          const selectedPath = currentPath;
          // Let the status text and the tree's resize observer settle first.
          treeRevealFrame = requestAnimationFrame(() => {
            treeRevealFrame = requestAnimationFrame(() => {
              treeRevealFrame = 0;
              if (
                disposed ||
                !treeScrollPending ||
                tree !== selectedTree ||
                currentPath !== selectedPath
              )
                return;
              treeScrollPending = false;
              tree.scrollToPath(selectedPath, {
                focus: false,
                offset: "nearest",
              });
            });
          });
        }
      }
    } finally {
      updatingTree = false;
    }
    if (treeRefreshAnchor && tree.getItem(treeRefreshAnchor.path)) {
      const anchor = treeRefreshAnchor;
      const interaction = treeInteraction;
      treeRefreshAnchor = undefined;
      tree.scrollToPath(anchor.path, { focus: false, offset: "top" });
      requestAnimationFrame(() => {
        if (disposed || interaction !== treeInteraction) return;
        const scroller = treeScroller();
        if (scroller) scroller.scrollTop += anchor.offset;
      });
    }
    treeChanged();
  }
  function treeScroller() {
    return tree
      ?.getFileTreeContainer()
      ?.shadowRoot?.querySelector<HTMLElement>(
        "[data-file-tree-virtualized-scroll]",
      );
  }
  function createTree(
    paths: string[],
    gitStatus: GitStatusEntry[] = [],
    preserveScroll = false,
  ) {
    if (preserveScroll && tree && !treeRefreshAnchor) {
      const scroller = treeScroller();
      if (scroller) {
        const height = tree.getItemHeight();
        const index = Math.floor(scroller.scrollTop / height);
        const row = tree.getVisibleRows(index, index + 1)[0];
        if (row)
          treeRefreshAnchor = {
            path: row.path,
            offset: scroller.scrollTop % height,
          };
      }
    }
    treeSelectionPending = Boolean(currentPath);
    updatingTree = true;
    try {
      knownPaths.clear();
      directories.clear();
      treeLimitReached = false;
      paths = paths.filter(rememberPath);
      gitStatus = gitStatus.filter(
        (entry) =>
          knownPaths.has(entry.path) || knownPaths.has(entry.path + "/"),
      );
      if (tree) {
        for (const request of directoryRequests.values()) request.abort();
        directoryRequests.clear();
        pendingDirectories.clear();
        loaded.clear();
        tree.resetPaths(paths, {
          initialExpandedPaths: [...expandedTreePaths].filter(
            hasExpandedParents,
          ),
        });
        tree.setGitStatus(gitStatus);
      } else {
        tree = new FileTree({
          paths,
          initialExpansion: "closed",
          initialExpandedPaths: [...expandedTreePaths].filter(
            hasExpandedParents,
          ),
          gitStatus,
          onSearchChange: () => {
            if (updatingTree || !search.value.trim()) return;
            // Pierre closes its search after selecting a result.
            queueMicrotask(() => {
              if (!disposed && tree && search.value.trim()) {
                updatingTree = true;
                try {
                  tree.setSearch(search.value.trim());
                } finally {
                  updatingTree = false;
                }
                treeSelectionPending = Boolean(currentPath);
                restoreTreeState();
              }
            });
          },
          onSelectionChange: (selected) => {
            if (updatingTree) return;
            const path = selected[0];
            if (path && !tree?.getItem(path)?.isDirectory()) {
              treeSelectionPending = false;
              treeScrollPending = false;
              choose(path);
            }
          },
        });
        tree.render({ containerWrapper: treeMount });
        tree.subscribe(treeChanged);
      }
    } finally {
      updatingTree = false;
    }
    updatingTree = true;
    try {
      tree?.setSearch(search.value.trim() || null);
    } finally {
      updatingTree = false;
    }
    restoreTreeState();
  }
  function pumpDirectories() {
    if (
      disposed ||
      directoryBusy ||
      refreshing ||
      treeLimitReached ||
      mode === "changes" ||
      search.value.trim()
    )
      return;
    for (const path of pendingDirectories) {
      pendingDirectories.delete(path);
      const item = tree?.getItem(path + "/");
      if (!item || !("isExpanded" in item) || !item.isExpanded()) {
        loaded.delete(path);
        continue;
      }
      directoryBusy = true;
      void loadDirectory(path)
        .catch((error) => {
          loaded.delete(path);
          report(error);
        })
        .finally(() => {
          directoryBusy = false;
          pumpDirectories();
        });
      break;
    }
  }
  async function loadDirectory(path: string) {
    const request = new AbortController();
    const navigation = scope.signal;
    const abort = () => request.abort();
    navigation.addEventListener("abort", abort, { once: true });
    directoryRequests.set(path, request);
    let cursor = "";
    try {
      do {
        const data = await api<{
          entries: { name: string; kind: string; ignored?: boolean }[];
          truncated: boolean;
          cursor?: string;
        }>("list", { path, ...(cursor ? { cursor } : {}) }, request.signal);
        const additions: { type: "add"; path: string }[] = [];
        const ignored: GitStatusEntry[] = [];
        for (const entry of data.entries) {
          const child = (path ? path + "/" : "") + entry.name;
          const directory = entry.kind === "directory";
          const key = child + (directory ? "/" : "");
          const exists = knownPaths.has(key);
          if (!rememberPath(key)) break;
          if (!exists) additions.push({ type: "add", path: key });
          if (entry.ignored) ignored.push({ path: key, status: "ignored" });
        }
        updatingTree = true;
        try {
          tree?.batch(additions);
          tree?.applyGitStatusPatch({ set: ignored });
        } finally {
          updatingTree = false;
        }
        restoreTreeState();
        treeStatus.textContent = treeLimitReached
          ? TREE_LIMIT_NOTICE
          : data.cursor
            ? "Loading folder…"
            : data.truncated
              ? "Folder listing limited"
              : "";
        if (treeLimitReached || !data.cursor || data.cursor === cursor) break;
        cursor = data.cursor;
        await new Promise((resolve) => setTimeout(resolve, 0));
        const item = tree?.getItem(path + "/");
        if (!item || !("isExpanded" in item) || !item.isExpanded()) {
          loaded.delete(path);
          break;
        }
      } while (!request.signal.aborted);
    } finally {
      navigation.removeEventListener("abort", abort);
      if (directoryRequests.get(path) === request)
        directoryRequests.delete(path);
    }
  }
  async function refreshTree() {
    const query = search.value.trim();
    const signal = scope.signal;
    const request = ++treeRequest;
    for (const directoryRequest of directoryRequests.values())
      directoryRequest.abort();
    const paths = new Set<string>();
    const directories = new Set<string>();
    const ignored = new Set<string>();
    const messages = new Set<string>();
    let cursor = "";
    let partial = false;
    do {
      const data = await api<TreeData>(
        "tree",
        { query, ...(cursor ? { cursor } : {}) },
        signal,
      );
      if (request !== treeRequest || query !== search.value.trim()) return;
      if (data.git !== undefined) {
        state.git = data.git;
        modes.hidden = !data.git;
        if (data.git) setNotices("repository", []);
      }
      for (const path of data.paths) {
        if (paths.size + directories.size >= MAX_TREE_PATHS) break;
        paths.add(path);
      }
      for (const path of data.directories) {
        if (paths.size + directories.size >= MAX_TREE_PATHS) break;
        directories.add(path);
      }
      for (const path of data.ignored) {
        if (ignored.size >= MAX_TREE_PATHS) break;
        ignored.add(path);
      }
      for (const message of data.notices ?? []) messages.add(message);
      partial = Boolean(data.partial);
      if (!data.cursor || data.cursor === cursor) break;
      if (paths.size + directories.size >= MAX_TREE_PATHS) {
        messages.add(TREE_LIMIT_NOTICE);
        break;
      }
      cursor = data.cursor;
      await new Promise((resolve) => setTimeout(resolve, 0));
    } while (!signal.aborted);
    // The index intentionally skips ignored folders. Refresh their expanded contents
    // before swapping models, so unrelated changes do not briefly hide the selection.
    if (!query) {
      for (const expanded of expandedTreePaths) {
        if (paths.size + directories.size >= MAX_TREE_PATHS) break;
        const path = expanded.replace(/\/$/, "");
        let ignoredAncestor = false;
        for (
          let parent = path;
          parent;
          parent = parent.slice(0, parent.lastIndexOf("/"))
        ) {
          if (ignored.has(parent) || ignored.has(parent + "/")) {
            ignoredAncestor = true;
            break;
          }
          if (!parent.includes("/")) break;
        }
        if (!ignoredAncestor) continue;
        let folderCursor = "";
        try {
          do {
            const listing = await api<{
              entries: { name: string; kind: string; ignored?: boolean }[];
              cursor?: string;
              truncated?: boolean;
            }>(
              "list",
              { path, ...(folderCursor ? { cursor: folderCursor } : {}) },
              signal,
            );
            if (request !== treeRequest || query !== search.value.trim())
              return;
            directories.add(path);
            for (const entry of listing.entries) {
              if (paths.size + directories.size >= MAX_TREE_PATHS) break;
              const child = path + "/" + entry.name;
              if (entry.kind === "directory") directories.add(child);
              else paths.add(child);
              if (entry.ignored && ignored.size < MAX_TREE_PATHS)
                ignored.add(child);
            }
            partial ||= Boolean(listing.truncated && !listing.cursor);
            if (
              !listing.cursor ||
              listing.cursor === folderCursor ||
              paths.size + directories.size >= MAX_TREE_PATHS
            )
              break;
            folderCursor = listing.cursor;
          } while (!signal.aborted);
        } catch (error) {
          if (signal.aborted) throw error;
          messages.add(
            error instanceof Error
              ? error.message
              : "Folder refresh unavailable",
          );
        }
      }
    }
    signal.throwIfAborted();
    if (request !== treeRequest || query !== search.value.trim()) return;
    createTree(
      [...paths, ...[...directories].map((path) => path + "/")],
      [...ignored].map((path) => ({ path, status: "ignored" })),
      true,
    );
    if (partial) messages.add("Partial results - expand folders to load more");
    if (treeLimitReached) messages.add(TREE_LIMIT_NOTICE);
    treeStatus.textContent = [...messages].join(" - ");
  }

  async function loadTree(query = "", signal = scope.signal, cursor = "") {
    treeStatus.textContent = query ? "Searching…" : "Indexing…";
    const request = ++treeRequest;
    const data = await api<TreeData>(
      "tree",
      { query, ...(cursor ? { cursor } : {}) },
      signal,
    );
    if (request !== treeRequest) return;
    if (data.git !== undefined) {
      state.git = data.git;
      modes.hidden = !data.git;
      if (data.git) setNotices("repository", []);
    }
    const paths = [
      ...data.paths,
      ...data.directories.map((path) => path + "/"),
    ];
    const ignored = data.ignored.map((path) => ({
      path,
      status: "ignored" as const,
    }));
    if (cursor) {
      const additions: string[] = [];
      for (const path of paths) {
        const exists = knownPaths.has(path);
        if (!rememberPath(path)) break;
        if (!exists) additions.push(path);
      }
      updatingTree = true;
      try {
        tree?.batch(additions.map((path) => ({ type: "add", path })));
        tree?.applyGitStatusPatch({
          set: ignored.filter(
            (entry) =>
              knownPaths.has(entry.path) || knownPaths.has(entry.path + "/"),
          ),
        });
      } finally {
        updatingTree = false;
      }
      restoreTreeState();
    } else {
      createTree(paths, ignored);
    }
    updatingTree = true;
    try {
      tree?.setSearch(query || null);
    } finally {
      updatingTree = false;
    }
    restoreTreeState();
    treeStatus.textContent = [
      treeLimitReached ? TREE_LIMIT_NOTICE : "",
      data.partial ? "Partial results - expand folders to load more" : "",
      ...(data.notices ?? []),
    ]
      .filter(Boolean)
      .join(" - ");
    if (!treeLimitReached && data.cursor && data.cursor !== cursor) {
      await new Promise((resolve) => setTimeout(resolve, 0));
      if (!signal.aborted && request === treeRequest)
        await loadTree(query, signal, data.cursor);
    }
  }
  function releaseDiff(row: DiffRow) {
    row.request?.abort();
    row.request = undefined;
    if (!row.ready && !row.view) return;
    // Keep the measured space when offscreen content is discarded.
    row.content.style.height = `${row.content.getBoundingClientRect().height}px`;
    row.view?.cleanUp();
    row.view = undefined;
    row.content.replaceChildren();
    row.ready = false;
  }

  function visibleAnchor() {
    const top = main.getBoundingClientRect().top;
    let anchor: HTMLElement | undefined;
    let nearestTop = Infinity;
    for (const row of nearDiffs) {
      const bounds = row.section.getBoundingClientRect();
      if (bounds.bottom > top && bounds.top < nearestTop) {
        anchor = row.section;
        nearestTop = bounds.top;
      }
    }
    return anchor ? { element: anchor, top: nearestTop - top } : undefined;
  }

  function restoreAnchor(anchor: ReturnType<typeof visibleAnchor>) {
    if (anchor?.element.isConnected) {
      main.scrollTop +=
        anchor.element.getBoundingClientRect().top -
        main.getBoundingClientRect().top -
        anchor.top;
    }
  }

  function revealDiff(path: string) {
    const row = diffs.get(path);
    if (!row) return;
    pinnedDiff = row;
    diffAnchor = { element: row.section, top: 0 };
    nearDiffs.add(row);
    restoreAnchor(diffAnchor);
    scheduleDiffs();
  }

  function scheduleDiffs() {
    if (!disposed && !diffFrame) {
      diffFrame = requestAnimationFrame(() => {
        diffFrame = 0;
        updateDiffs();
      });
    }
  }

  function updateDiffs() {
    if (disposed || mode !== "changes" || scope.signal.aborted) return;
    const bounds = main.getBoundingClientRect();
    const distance = (row: DiffRow) => {
      const rect = row.section.getBoundingClientRect();
      return Math.max(bounds.top - rect.bottom, rect.top - bounds.bottom, 0);
    };
    for (const row of nearDiffs) {
      if (row !== pinnedDiff && distance(row) > 600) {
        nearDiffs.delete(row);
        releaseDiff(row);
      }
    }
    const candidates = [...nearDiffs].sort((a, b) =>
      a === pinnedDiff ? -1 : b === pinnedDiff ? 1 : distance(a) - distance(b),
    );
    const retained = new Set(candidates.slice(0, MAX_RENDERED_DIFFS));
    for (const row of nearDiffs) {
      if (!retained.has(row)) releaseDiff(row);
    }
    for (const row of retained) {
      if (activeDiffRequests.size >= MAX_DIFF_REQUESTS) break;
      if (!row.request && (!row.ready || row.dirty)) void renderDiff(row);
    }
  }

  async function renderDiff(row: DiffRow) {
    const request = new AbortController();
    const navigation = scope.signal;
    const abort = () => request.abort();
    navigation.addEventListener("abort", abort, { once: true });
    row.request = request;
    activeDiffRequests.add(request);
    const path = row.change.path;
    if (!row.ready) row.content.textContent = "Loading diff…";
    try {
      const data = await api<{ old: Content | null; new: Content | null }>(
        "diff",
        { path, base },
        request.signal,
      );
      if (row.request !== request || !nearDiffs.has(row)) return;
      const old = data.old ?? { size: 0, deleted: true };
      const next = data.new ?? { size: 0, deleted: true };
      row.note.textContent = [
        ...new Set(
          [row.change.notice, old.notice, next.notice].filter(Boolean),
        ),
      ].join(" - ");
      let before: string | undefined;
      let after: string | undefined;
      try {
        if (
          old.tooLarge ||
          next.tooLarge ||
          (old.base64 == null && !old.deleted) ||
          (next.base64 == null && !next.deleted)
        ) {
          throw new Error("Content unavailable");
        }
        before = text(old);
        after = text(next);
        if (before.includes("\0") || after.includes("\0"))
          throw new Error("Binary content");
      } catch {
        before = after = undefined;
      }
      const anchor = diffAnchor ?? visibleAnchor();
      diffAnchor = anchor;
      row.view?.cleanUp();
      row.view = undefined;
      row.content.replaceChildren();
      row.content.style.height = "";
      row.content.dataset.settled = "true";
      row.ready = true;
      row.dirty = false;
      if (before === undefined || after === undefined) {
        placeholder(
          row.content,
          next.tooLarge || old.tooLarge ? { ...next, tooLarge: true } : next,
          "Binary or unavailable diff",
        );
      } else if (before === after) {
        const label = old.deleted
          ? "Empty file added"
          : next.deleted
            ? "Empty file deleted"
            : "No text changes";
        row.content.append(el("div", "fv-empty-diff", label));
        if (
          !old.deleted &&
          !next.deleted &&
          row.change.kind === "modified" &&
          !row.note.textContent
        ) {
          row.note.textContent = "File mode or metadata changed";
        }
      } else {
        const view = new FileDiff({
          ...lineOptions(path),
          diffStyle: split ? "split" : "unified",
          disableFileHeader: true,
          onPostRender: (_host, instance, phase) => {
            if (
              phase === "unmount" ||
              row.view !== instance ||
              currentPath !== path
            )
              return;
            const scroll = lineScrollPending;
            lineScrollPending = false;
            applyLines(instance, row.content, scroll);
            if (scroll) {
              pinnedDiff = undefined;
              diffAnchor = visibleAnchor();
            }
          },
        });
        row.view = view;
        view.render({
          oldFile: { name: path, contents: before },
          newFile: { name: path, contents: after },
          containerWrapper: row.content,
        });
      }
      restoreAnchor(diffAnchor);
    } catch (error) {
      if (!request.signal.aborted && row.request === request) {
        row.content.style.height = "";
        row.content.dataset.settled = "true";
        row.content.textContent =
          error instanceof Error ? error.message : "Diff unavailable";
        row.ready = true;
        row.dirty = false;
      }
    } finally {
      navigation.removeEventListener("abort", abort);
      activeDiffRequests.delete(request);
      if (row.request === request) row.request = undefined;
      scheduleDiffs();
    }
  }

  async function loadChanges(preserveScroll = false) {
    if (!preserveScroll) status.textContent = "Comparing…";
    const signal = scope.signal;
    let data: {
      base: string;
      changes: Change[];
      partial?: boolean;
      notices?: string[];
    };
    try {
      data = await api("changes", { base }, signal);
    } catch (error) {
      if (signal.aborted) throw error;
      const current = await api<State>("state", {}, signal);
      if (current.git !== false) throw error;
      state.git = false;
      urlUpdate({ mode: null }, undefined, true);
      setNotices("repository", [
        "Git is unavailable for this folder - showing all files",
      ]);
      await navigate();
      return;
    }
    state.git = true;
    modes.hidden = false;
    setNotices("repository", []);
    const statuses: GitStatusEntry[] = [];
    for (const change of data.changes) {
      if (
        ["added", "deleted", "modified", "renamed", "untracked"].includes(
          change.kind,
        )
      ) {
        statuses.push({
          path: change.path,
          status: change.kind as GitStatusEntry["status"],
        });
      }
    }
    createTree(
      data.changes.map((change) => change.path),
      statuses,
      preserveScroll,
    );
    baseSelect.options[0].textContent =
      base === "branch" && data.base === "uncommitted"
        ? "Branch - Uncommitted"
        : "Branch";
    const wanted = new Map(data.changes.map((change) => [change.path, change]));
    const anchor = preserveScroll ? (diffAnchor ?? visibleAnchor()) : undefined;
    diffAnchor = anchor;
    for (const [path, row] of diffs) {
      if (!wanted.has(path)) {
        releaseDiff(row);
        nearDiffs.delete(row);
        diffResize.unobserve(row.section);
        if (pinnedDiff === row) pinnedDiff = undefined;
        row.section.remove();
        diffs.delete(path);
      }
    }
    observer?.disconnect();
    observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          const row = diffs.get(
            (entry.target as HTMLElement).dataset.path ?? "",
          );
          if (!row) continue;
          if (entry.isIntersecting) nearDiffs.add(row);
          else if (row !== pinnedDiff) {
            nearDiffs.delete(row);
            releaseDiff(row);
          }
        }
        scheduleDiffs();
      },
      { root: main, rootMargin: "600px" },
    );
    if (!data.changes.length)
      main.replaceChildren(el("div", "fv-placeholder", "No changes"));
    else {
      main.querySelector(":scope > .fv-placeholder")?.remove();
      diffTail.style.height = main.clientHeight + "px";
      main.append(diffTail);
      for (let index = 0; index < data.changes.length; index++) {
        const change = data.changes[index];
        let row = diffs.get(change.path);
        if (!row) {
          const section = el("section", "fv-diff");
          section.dataset.path = change.path;
          const title = el("div", "fv-file-header", change.path);
          title.append(el("span", "fv-muted", change.kind));
          const content = el("div", "fv-diff-body");
          const note = el("div", "fv-note", change.notice ?? "");
          section.append(title, note, content);
          row = { change, section, content, note, ready: false, dirty: false };
          diffs.set(change.path, row);
        } else {
          row.change = change;
          row.note.textContent = change.notice ?? "";
          row.section.querySelector(".fv-muted")!.textContent = change.kind;
        }
        const previous = data.changes[index - 1];
        const expected = previous
          ? diffs.get(previous.path)!.section.nextSibling
          : main.firstChild;
        if (expected !== row.section) main.insertBefore(row.section, expected);
        observer.observe(row.section);
        diffResize.observe(row.section);
      }
    }
    restoreAnchor(anchor);
    status.textContent = [
      data.partial ? "Partial comparison" : "",
      ...(data.notices ?? []),
    ]
      .filter(Boolean)
      .join(" - ");
    if (currentPath && !preserveScroll) {
      lineScrollPending = Boolean(rangeFromURL());
      revealDiff(currentPath);
    }
  }
  async function navigate() {
    clearTimeout(refreshTimer);
    refreshTimer = 0;
    pendingRefresh = undefined;
    treeRefreshAnchor = undefined;
    treeInteraction++;
    scope.abort();
    scope = new AbortController();
    cancelAnimationFrame(treeRevealFrame);
    treeRevealFrame = 0;
    contentScope.abort();
    searchScope.abort();
    observer?.disconnect();
    fileView?.cleanUp();
    fileView = undefined;
    pinnedDiff = undefined;
    diffAnchor = undefined;
    diffResize.disconnect();
    diffResize.observe(main);
    for (const row of diffs.values()) releaseDiff(row);
    diffs.clear();
    nearDiffs.clear();
    cancelAnimationFrame(diffFrame);
    diffFrame = 0;
    revokeImages();
    tree?.cleanUp();
    tree = undefined;
    treeMount.replaceChildren();
    directoryRequests.clear();
    pendingDirectories.clear();
    directories.clear();
    loaded.clear();
    knownPaths.clear();
    expandedTreePaths.clear();
    main.replaceChildren();
    search.value = "";
    setNotices("refs", []);
    setNotices("file", []);
    baseSelect.options[0].textContent = "Branch";
    const url = new URL(location.href);
    mode = url.searchParams.get("mode") === "changes" ? "changes" : "files";
    main.classList.toggle("fv-changes", mode === "changes");
    base = url.searchParams.get("base") || "branch";
    currentPath = url.searchParams.get("open") || "";
    treeSelectionPending = treeScrollPending = Boolean(currentPath);
    for (
      let slash = currentPath.lastIndexOf("/");
      slash > 0;
      slash = currentPath.lastIndexOf("/", slash - 1)
    ) {
      expandedTreePaths.add(currentPath.slice(0, slash + 1));
    }
    modes.hidden = state.git === false;
    baseControls.hidden = mode !== "changes";
    allButton.setAttribute("aria-pressed", String(mode === "files"));
    changesButton.setAttribute("aria-pressed", String(mode === "changes"));
    baseSelect.value = ["branch", "uncommitted"].includes(base) ? base : "ref";
    ref.value = baseSelect.value === "ref" ? base : "";
    ref.hidden = compare.hidden = baseSelect.value !== "ref";
    if (mode === "changes") {
      void api<{ names: string[]; partial?: boolean; notices?: string[] }>(
        "refs",
      )
        .then((data) => {
          setNotices("refs", [
            data.partial
              ? "Ref suggestions are incomplete - type any ref to compare"
              : "",
            ...(data.notices ?? []),
          ]);
          refs.replaceChildren(
            ...data.names.map((name) => {
              const option = el("option");
              option.value = name;
              return option;
            }),
          );
        })
        .catch(report);
      await loadChanges();
    } else {
      await Promise.all([
        loadTree(),
        currentPath
          ? openFile(currentPath)
          : Promise.resolve(
              main.append(el("div", "fv-placeholder", "Select a file")),
            ),
      ]);
    }
  }
  allButton.onclick = () => {
    urlUpdate({ mode: null });
    void navigate().catch(report);
  };
  changesButton.onclick = () => {
    urlUpdate({ mode: "changes" });
    void navigate().catch(report);
  };
  baseSelect.onchange = () => {
    ref.hidden = compare.hidden = baseSelect.value !== "ref";
    if (baseSelect.value !== "ref") {
      urlUpdate({ base: baseSelect.value });
      void navigate().catch(report);
    } else ref.focus();
  };
  baseControls.onsubmit = (event) => {
    event.preventDefault();
    if (ref.value.trim()) {
      urlUpdate({ base: ref.value.trim() });
      void navigate().catch(report);
    }
  };
  splitButton.onclick = () => {
    diffAnchor ??= visibleAnchor();
    split = !split;
    splitButton.textContent = split ? "Unified" : "Split";
    for (const row of diffs.values()) {
      if (row.view) {
        const previous = row.view;
        const fileDiff = previous.fileDiff;
        const options = {
          ...previous.options,
          diffStyle: split ? ("split" as const) : ("unified" as const),
        };
        previous.cleanUp();
        row.content.replaceChildren();
        row.view = new FileDiff(options);
        row.view.render({ fileDiff, containerWrapper: row.content });
      }
    }
    restoreAnchor(diffAnchor);
  };
  search.oninput = () => {
    clearTimeout(searchTimer);
    for (const request of directoryRequests.values()) request.abort();
    pendingDirectories.clear();
    searchScope.abort();
    searchScope = new AbortController();
    const signal = searchScope.signal;
    const query = search.value.trim();
    if (mode === "changes") {
      tree?.setSearch(query);
      return;
    }
    searchTimer = window.setTimeout(() => {
      void loadTree(query, signal).catch(report);
    }, 150);
  };
  function queueRefresh() {
    if (disposed || refreshing || refreshTimer || !pendingRefresh) return;
    // Do not reset this timer: continuous writes must still produce updates.
    refreshTimer = window.setTimeout(() => {
      refreshTimer = 0;
      const change = pendingRefresh;
      pendingRefresh = undefined;
      if (!change || disposed) return;
      refreshing = true;
      const full = change.reconcile || change.structure || change.rules;
      const affected = new Set(change.paths ?? []);
      let work: Promise<unknown>;
      if (mode === "changes") {
        for (const row of diffs.values()) {
          if (
            full ||
            change.git ||
            affected.has(row.change.path) ||
            (change.content && !affected.size)
          ) {
            row.dirty = true;
            row.request?.abort();
            row.request = undefined;
          }
        }
        work = loadChanges(true);
      } else {
        const tasks: Promise<unknown>[] = [];
        if (full || change.git) tasks.push(refreshTree());
        if (
          currentPath &&
          (full ||
            (change.content &&
              (!affected.size ||
                [...contentPaths].some((path) => affected.has(path)))))
        ) {
          tasks.push(openFile(currentPath, true));
        }
        work = Promise.all(tasks);
      }
      void work.catch(report).finally(() => {
        refreshing = false;
        pumpDirectories();
        queueRefresh();
      });
    }, 100);
  }
  const pop = () => {
    void navigate().catch(report);
  };
  const hash = () => {
    if (preview && main.querySelector(".fv-markdown")) {
      scrollHeading(main, location.hash);
      return;
    }
    if (fileView) applyLines(fileView, main, true);
    else {
      const row = diffs.get(currentPath);
      if (row?.view) applyLines(row.view, row.section, true);
      else if (row) {
        lineScrollPending = true;
        revealDiff(currentPath);
      }
    }
  };
  const releasePinned = () => {
    contentScrollVersion++;
    pinnedDiff = undefined;
    diffAnchor = visibleAnchor();
  };
  const onDiffScroll = () => {
    if (
      mode === "files" &&
      Math.abs(main.scrollTop - expectedContentScroll) > 1
    ) {
      contentScrollVersion++;
      expectedContentScroll = main.scrollTop;
    }
    if (!pinnedDiff) diffAnchor = visibleAnchor();
    scheduleDiffs();
  };
  const treeInteracted = () => {
    treeInteraction++;
    treeRefreshAnchor = undefined;
  };
  sidebar.addEventListener("wheel", treeInteracted, { passive: true });
  sidebar.addEventListener("pointerdown", treeInteracted);
  sidebar.addEventListener("keydown", treeInteracted);
  main.addEventListener("wheel", releasePinned, { passive: true });
  main.addEventListener("touchstart", releasePinned, { passive: true });
  main.addEventListener("pointerdown", releasePinned);
  main.addEventListener("keydown", releasePinned);
  main.addEventListener("scroll", onDiffScroll, { passive: true });
  window.addEventListener("popstate", pop);
  window.addEventListener("hashchange", hash);
  void navigate().catch(report);
  return {
    refresh(change: Invalidation = { reconcile: true }) {
      setNotices(
        "watch",
        change.limited
          ? [
              "Live updates have limited coverage - periodic refresh remains active",
            ]
          : [],
      );
      const previous = pendingRefresh;
      const wildcard =
        (previous?.content && !previous.paths?.length) ||
        (change.content && !change.paths?.length);
      const paths = [
        ...new Set([...(previous?.paths ?? []), ...(change.paths ?? [])]),
      ];
      pendingRefresh = {
        content: previous?.content || change.content,
        structure: previous?.structure || change.structure,
        git: previous?.git || change.git,
        rules: previous?.rules || change.rules,
        reconcile: previous?.reconcile || change.reconcile,
        paths: wildcard || paths.length > MAX_TREE_PATHS ? [] : paths,
      };
      queueRefresh();
    },
    dispose() {
      disposed = true;
      clearTimeout(searchTimer);
      clearTimeout(refreshTimer);
      scope.abort();
      contentScope.abort();
      searchScope.abort();
      pendingDirectories.clear();
      observer?.disconnect();
      tree?.cleanUp();
      fileView?.cleanUp();
      for (const row of diffs.values()) releaseDiff(row);
      nearDiffs.clear();
      cancelAnimationFrame(diffFrame);
      cancelAnimationFrame(treeRevealFrame);
      revokeImages();
      diffResize.disconnect();
      sidebar.removeEventListener("wheel", treeInteracted);
      sidebar.removeEventListener("pointerdown", treeInteracted);
      sidebar.removeEventListener("keydown", treeInteracted);
      main.removeEventListener("wheel", releasePinned);
      main.removeEventListener("touchstart", releasePinned);
      main.removeEventListener("pointerdown", releasePinned);
      main.removeEventListener("keydown", releasePinned);
      main.removeEventListener("scroll", onDiffScroll);
      window.removeEventListener("popstate", pop);
      window.removeEventListener("hashchange", hash);
    },
  };
}
(window as unknown as { tmaFilesViewer: typeof mount }).tmaFilesViewer = mount;
