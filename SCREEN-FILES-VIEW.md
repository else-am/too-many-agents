# Screen files view

In-world screens that show files and git changes, so a Minecraft screen covers what the Claude Code / Codex desktop apps show beside a conversation: files, changes, a terminal and a browser. Screens already handle the browser. Files and changes share one viewer. Terminals use the same isolated built-in page boundary.

## Principle: agents open, humans use

Files, changes and terminals are human views. Agents already reach the same things through their own CLI tools, in a form better suited to them, and inside their sandbox. So agents can **open, close, screenshot and read the URL of** these views, but never drive them:

- Driving them would add nothing an agent can't already do.
- The game process runs outside the agent's sandbox. Anything an agent could steer inside it (reading files, typing into a terminal, running git) would be a way around the sandbox.

Today's `screen_open kind:file` loads a raw `file://` page in the game's Chromium. That already lets an agent read any file the game can read (through a screenshot or `screen_cdp`), and `screen_cdp` on any web page can navigate to `file://`. This plan removes both.

Use supported CEF/JCEF APIs and ordinary application code. No CEF fork, custom native browser extensions, or browser patches to maintain. Security work must preserve the intended human experience: the picker, previews, links, back/forward, copy, live updates and ordinary web browsing. Do not add routine permission prompts or silently remove these features to simplify enforcement. Resolve any conflict with these constraints before building on the affected mechanism.

## `tma://` built-in pages

Built-in views are pages on our own `tma://` scheme. The mod serves them from inside Chromium (CEF), with no network port:

- `tma://files/…`: the files and changes viewer (this plan)
- `tma://terminal/<id>`: a BB terminal (next)

For every `tma://` page:

- Native code binds each built-in browser instance to its screen, authorized root and current navigation generation. A URL is display state, never proof of permission. The page server and message router check that native authorization and the requesting frame before serving data or accepting a query; requests without a recognized browser/frame are denied.
- A web page can't load `tma://` content through navigation, an iframe, `fetch` or an image. One screen cannot borrow another screen's authorization.
- Chromium blocks all `file:` navigation, and blocks any switch between web pages and `tma:`. Only `screen_open`, the address bar and the viewer's own controls move a screen between the two.
- Crossing between a web page and a built-in view replaces the browser instance and isolates the built-in request context from web content. This discards CDP-installed scripts, bindings and other page instrumentation. Keep the screen identity and user-visible back/forward history across that replacement; stale callbacks and pending commands cannot reach the replacement browser.
- `screen_cdp` on a `tma://` page returns `view_only`. Screenshots are allowed.
- Archiving a thread closes every `tma://` screen linked to that thread's station. Loose screens are left alone.

### Terminals

`tma://terminal/<bb terminal id>` runs xterm.js, connected to the BB terminal, with the bundled Pixel Code font. It shows bounded output from a given sequence number on and sends what the human types.

- The human types into it. The agent can open it and screenshot it, but can't send input.
- When the BB terminal ends on its own, the page shows "terminal closed" and keeps its last output. It isn't closed automatically.
- Agents can open only existing terminals in their own local environment, with `screen_open kind: terminal`. They cannot create a terminal through the screen, send input, or use CDP.
- The human opens a shell from the native picker, in the chosen directory. Closing the screen detaches it; BB keeps the terminal session. Saved screens reconnect to the same session after trust and fresh authorization.

## Behavior

### Opening

- A blank screen shows two centered, stacked buttons: **Open a file or folder** opens the picker at home (`/Users/<you>`, `/home/<you>`, `C:\Users\<you>`); **Open a URL or search** focuses the address bar.
- Typing an absolute or `~/…` path in the address bar opens the viewer.
- Agents use `screen_open`:
  - `kind: "file"`: a file or folder.
  - `kind: "changes"`: changes mode, with an optional `base`.
  - Relative targets resolve against the agent's environment.
  - File/changes targets also accept a shared `tma://files/…?open=…#L20-L40` location. Raw paths retain literal `?` and `#` characters. A location never grants file access.

### Picker

A native Minecraft folder browser on the in-world screen, independent of the viewer's HTML page and header. Use the Minecraft font, minimal controls, and a width-capped container centered on the screen with left-aligned, truncated filenames.

- The picker's **Parent** button opens the parent directory and is disabled at the filesystem root. Backspace in the list does the same. The screen toolbar's Back/Forward follows visited locations.
- **Path field:**
  - Relative paths, `~`, `/…`, and drive letters on Windows (`D:/`) work.
  - Tab completes the next segment.
  - Enter enters a folder or opens a file.
- **Flat list:**
  - Folders first; no expandable tree.
  - Click a folder to enter it. Click a file to open it in the viewer.
  - Hovered or selected folder rows show icon-and-text **Files** and **Terminal** actions. Tab reaches these actions from the selected folder.
  - Keyboard navigation and scrolling work in pointing and keyboard/mouse capture modes (Option-click on macOS or Alt-click on Windows/Linux toggles capture at the current aim; Esc reaches the captured page).
- **Show hidden files** defaults to off.
- **Open this folder** opens the viewer rooted at the current folder. **Open terminal** starts a BB shell there. Both use the supplied icons with text.
- The picker is controlled by the player. Agent opens cannot enter it or inherit its authority.

### Viewer

A Pierre file tree on the left, content on the right, in Pierre's default dark theme. The header has:

- a path button (back to the picker at this root)
- the **All files / Changes** toggle (only in a git repo)
- the base picker and unified/split toggle (in changes mode)

**Root:** opening a file roots the viewer at its git top level, or at its folder outside a repo, and selects the file. Opening a folder roots it there. For an agent, clamp a repository root above its environment to the environment root; file listings and changes stay scoped to that root.

**All files:**

- Everything is shown, ignored paths included.
- Tracked and untracked paths load up front up to a bounded budget. Large trees load progressively, with a visible indexing state. Search covers tracked and untracked paths through a bounded background search even when the tree is not fully loaded; incomplete results are marked as such.
- Ignored folders (`node_modules/`, `build/`) show dimmed and collapsed, and load when expanded.
- Outside a repo, the tree loads up front to a cap. Folders past the cap load when expanded.

**Changes:**

- All changed files stacked, Codex-style. The tree lists only changed paths, and clicking one scrolls to it. Load and render diffs near the viewport, keeping stable scroll anchors while other diffs load.
- Untracked files count as added.
- Unified by default, with a split toggle.
- Base options:
  - **Branch:** the merge base of `HEAD` and the default branch, compared with the current working tree. Includes committed branch changes and the net staged/unstaged changes. This is the default.
  - **Uncommitted:** `HEAD` compared with the current working tree, including the net staged/unstaged changes.
  - Explicit comparison links may name a commit or ref; the viewer shows that comparison without offering a ref editor.
- Use the configured upstream remote's default branch, then `origin`'s default branch when available, without fetching automatically. If no default branch or merge base is available, fall back to Uncommitted with a short explanation. An unborn `HEAD` compares against an empty tree. An invalid explicit ref shows an error rather than silently changing the comparison.

**Content:**

- Text is highlighted with Shiki.
- Markdown has a Source/Preview toggle.
- HTML files display as source. Markdown previews are sanitized; repository content never executes as viewer code.
- Images render as images. SVG uses a non-scriptable image rendering path, never an inline document in the viewer.
- Other binary files show a placeholder with size and type.
- Files over 2 MB show a placeholder with a notice.

**Lines:** `#L20-L40` highlights and scrolls to those lines. Clicking or shift-clicking line numbers writes `#L…` into the URL.

**Live:**

- Updates are debounced by about 100 ms; this is a scheduling target, not a guarantee that every scan or diff completes in that time. Scroll position and selection are kept. Expensive updates show a quiet loading state and replace old content when ready.
- Deleted files show "deleted".
- Changes mode updates in place without jumping.

**Input:** mouse for the tree, list and line numbers. The keyboard for path fields, picker navigation and copy.

**Read-only.** Humans can edit in a terminal.

### The URL is the shared state

The shared location lives in the URL: root, `mode`, `base`, `open` and `#L`. The page updates it as you click. An agent sees that location through `screen_list`. Reloads and saved worlds restore the location against the current files, not an exact visual snapshot. Split/unified, Markdown source/preview, expanded folders and arbitrary scroll position are local presentation state, outside this URL restoration promise. A human pointing at lines, or an agent opening a file at `#L…`, is "look at this" in either direction, with no new tool.

```
tma://files/Users/scott/?pick
tma://files/Users/scott/Else/too-many-agents/
tma://files/Users/scott/Else/too-many-agents/?open=bb-plugin/screens.ts#L12-L22
tma://files/Users/scott/Else/too-many-agents/?mode=changes&base=origin/main
tma://files/C:/Users/scott/Else/
tma://files/_app/viewer.js            (bundled page, served from the JAR)
```

### Who can see what

| Opened by | Paths | Picker |
| --- | --- | --- |
| Human (`openedBy` empty) | Anything | Yes |
| Agent | Only inside the agent's own environment | No |

- **Agent check at open time:** Java asks the trusted plugin for the calling world agent's current local environment, pins that folder, resolves the requested path and clamps repository-root promotion to it. The root comes from the plugin's own agent/thread/environment records, never agent-supplied fields or URL parameters. The environment root is the viewer's authorized read boundary, as explicitly confirmed by the user. The viewer does not reproduce narrower provider-specific filesystem restrictions; BB does not expose those restrictions to this plugin.
- **Check when serving:** Java pins the authorized root independently of the URL and enforces it for listings, files, previews, searches, diffs and watcher notifications. Do not re-resolve a moved root into a broader grant. Canonicalization followed by a separate file open is insufficient against concurrent symlink replacement: choose and validate a race-safe reading strategy for each supported platform before enabling file serving. Keep normal in-root symlinks usable; out-of-root targets stay inaccessible in that view.
- **Restore:** persisted URLs and `openedBy` values do not grant access. After the existing world-trust gate, re-establish native authorization; agent-owned views must revalidate their environment before loading.
- **Taking over an agent's viewer:** a human who leaves it through the path button makes it their own page (`openedBy` cleared). Browsing inside it doesn't change ownership.
- **Allowed to show the agent more:** human-opened views may show things outside an agent's environment, and the agent may screenshot them. That's the human choosing to show them.

## Security

- **Repository content is untrusted, even in a human-opened viewer.** Escape text and filenames, sanitize rendered Markdown with a maintained sanitizer, and allow only bundled viewer code to execute under a restrictive Content Security Policy. Disallow executable embeds and script URLs. Relative images go through the same authorized file reader. External links remain usable through explicit human navigation; remote preview resources must not become a channel for leaking local content. Never serve repository HTML or SVG as an executable document on the viewer origin.
- **Git metadata is untrusted, even in a human-opened viewer.** Use JGit's object, ref, ignore and text-conversion parsers with application-owned confined readers; do not launch Git or invoke repository-defined helpers. This replaces the proposed subprocess approach, whose working-tree walks and attribute processing could escape the intended boundary.
  - Working-tree bytes, directory entries, ignore files and metadata come only from verified handles. JGit's pack reader receives a confined random-access channel, never an unchecked repository pathname.
  - Keep the pinned JGit DFS adapter small. Do not construct a normal filesystem repository or transport that could read configuration/includes, follow alternates, execute filters or fetch objects independently.
  - Recognize repository and linked-worktree metadata narrowly, including reciprocal worktree links. A metadata dependency never authorizes file content outside the viewer's root. Scope base-tree paths before returning content.
  - Parse configuration as data without following includes; use only explicitly supported settings. Resolve and validate refs before comparisons. Invalid explicit refs fail visibly.
  - Bound pack caches, inflation, enumeration and comparison work; cancel stale requests. Use a separate cache identity for each authorization grant.
  - Preserve net staged/unstaged comparisons, normal line-ending handling, LFS where content is already available, and binary placeholders. Custom executable filters remain disabled and affected files explain that their raw content is shown.
- **CDP isolation is a prerequisite.** Test ordinary-page CDP for cross-browser access (`Target.*`), filesystem access (including uploads/downloads), persistent scripts/bindings, and commands racing navigation. Deny unsafe capabilities while retaining ordinary page interaction. Combine this with fresh built-in browser instances and separate request contexts; a check of the current URL alone is insufficient.
- Raw `file://` pages are removed.

## Implementation

- **Java (`screens/`):**
  - Register `tma` as a standard scheme, with a handler serving:
    - `_app/*`: the page bundle
    - `_api/list`, `tree`, `file`, `changes`, `diff`, `refs`
  - Navigation rules, the CDP refusal, path mapping in the address bar, the blank-screen buttons, and closing on archive.
- **Git:** a pinned JGit dependency, off the game thread, using confined object channels and working-tree reads. JGit supplies pack/delta parsing, revision walking, ignore rules and supported line-ending conversion. The application owns metadata discovery and authorization. The index is an optional bounded optimization; comparisons must remain correct when it cannot be used. No subprocess, private Git directory or hard-link cache is required.
- **Budgets:** bound tree enumeration, searches, object decoding, image decoding and total diff work, not just individual file size. Cancel work on navigation/close and discard obsolete results. Large repositories stay navigable while work progresses; expose partial results and limits rather than freezing or silently omitting paths.
- **Watching:**
  - One recursive watcher per root while a screen showing it is loaded, shared by screens with the same root, and stopped when they sleep or close.
  - Resolve each repository's actual Git directory and common Git directory, including linked worktrees. Watch the relevant `HEAD`, index, refs, `packed-refs` and configuration/ignore inputs there as well as working-tree changes. Refresh watches if the metadata location changes; keep their access scoped to the authorized repository metadata.
  - Changes are debounced to about 100 ms, with bounded background work and recovery from watcher overflow. Do not recursively traverse huge ignored trees just to maintain the initial view; watch loaded content and refresh lazy folders as they are opened.
  - Updates reach the page through a waiting `CefMessageRouter` query, immediately re-armed after each response; the page re-fetches affected content. The bundled JCEF consumes its Java callback after one response even for a persistent query, so this uses supported one-response queries with bounded pending notifications.
  - macOS uses a small FSEvents adapter through Minecraft's bundled JNA; Linux and Windows use `WatchService`. The proposed directory-watcher library retained unbounded event paths even with its traversal disabled, so it is not bundled. Native callbacks perform no file reads or directory walks; refreshed data still uses verified handles. Broad external ancestor/config inputs and missed notifications are reconciled every 30 seconds; working-tree updates use the normal 100 ms debounce.
- **Page:**
  - esbuild in `bb-plugin`.
  - Vanilla `@pierre/trees` and `@pierre/diffs` (no React), bundled Shiki grammars and a small markdown renderer.
  - The viewer's header and URL handling. The picker is native Java UI.
  - Built by `tools/build` into a JAR resource.
- **Tool schema (`surface/tools.json`):**
  - `screen_open` kinds become `url | file | changes`, with optional `base`.
  - The `screen_cdp` description notes `view_only`.
  - The environment check lives in the plugin (`bb-plugin/screens.ts`).

### Resolve before depending on it

- ~~Prove browser-instance authorization, context isolation, fresh-instance transitions and CDP restrictions with stock CEF/JCEF, including seamless back/forward navigation.~~ Live packaged-client isolation and native input checks pass. Root-scoped serving and agent environment grants pass packaged-client checks; no browser fork or custom native extension.
- ~~Prove confined file access under concurrent root/path/symlink replacement, and establish the agent read boundary.~~ Verified-handle races pass on macOS and Linux x86_64. The user confirmed the environment root as the viewer boundary; fresh trusted BB lookups authorize opens and saved-view restoration. Packaged checks cover nested-repository clamping, inside/outside links, forged scope arguments, human takeover, environment-root replacement and native Reload recovery after a refused restore. Linux arm64 and Windows runtime validation remain.
- ~~Exercise the confined JGit object/metadata path against malicious configuration, redirects and concurrent swaps; verify comparisons against independent Git fixture results.~~ Ordinary, linked, unborn and nested repositories match the fixture results, including staged cancellation, modes, line endings and attribute fallback. External-object swap probes returned no outside bytes and no helpers ran. Unsupported index formats and submodules report partial results; Windows runtime remains unverified.

### To confirm while building

- ~~`@pierre/trees` provides an expand event to load folders lazily (otherwise wrap it).~~ Its public subscription and directory state support bounded lazy loading.
- ~~Large-file and large-repo performance of `@pierre/trees` and `@pierre/diffs`.~~ Packaged checks cover search/selection and picker completion beyond 12,000 files, plus a 201-file change set with bounded nearby rendering. Selecting the last diff, retaining its filter, switching split/unified, wheel scrolling and dragging the scrollbar keep a stable view. Larger inputs expose explicit limits.
- ~~JNA is available at runtime.~~ Minecraft ships JNA and jna-platform 5.14; the macOS and Linux x86_64 held-handle probes passed. Linux was exercised in a local container; Linux arm64 and Windows still need runtime checks.

### Not supported

- Editing.
- UNC network paths on Windows (`\\server\share`).

## Order

1. ~~Prove browser/CDP isolation using supported APIs; implement the scheme, browser-instance authorization, `file:` block, safe navigation and content policy.~~ Verified-handle file access is implemented and race-tested on macOS and Linux x86_64; root-scoped serving and environment grants pass packaged acceptance. Linux arm64/Windows runtime validation remain.
2. ~~Picker, address-bar paths and blank-screen buttons.~~ Packaged-client checks cover home/Tab completion, folder history, file-to-repository promotion, typed paths, ownership changes and saved-world restoration. Built-in-first startup and early stylesheet requests are handled using stock JCEF callbacks and per-browser request contexts. Agent opens and saved views obtain their own environment-confined grants after a trusted BB lookup.
3. ~~Validate Git hardening before Git-backed discovery. Viewer in files mode, safe previews and bounded loading.~~ Packaged-client checks cover syntax, line ranges, sanitized previews, binary/size/deleted states, and selection/search/Tab completion past 12,000 files. Shared-location tool calls preserve selection and line ranges within the authorized scope; real plugin turns verify relative paths, literal filename characters and environment confinement (Opus 5.5 for the enabled agent workflow). Line highlights survive live edits, and native gutter clicks/range drags update the URL. Native directory cursors passed macOS and Linux x86_64 replacement probes. Agent `kind:file` views now read within the environment grant; copied outside locations are refused without changing an existing human view.
4. ~~Changes mode and base picker using the validated Git path.~~ Packaged checks cover Branch, Uncommitted, explicit/invalid refs, unborn HEAD and unified/split rendering. The `kind:changes` and `base` tool contract is implemented, including environment-confined comparisons for agents nested inside a larger repository.
5. ~~Watcher and live updates.~~ Packaged macOS checks cover repeated edits with retained scroll, creation/rename/deletion, expanded ignored folders, ordinary and linked-worktree commits, packed refs and ignore changes, sleep/wake, shared root subscriptions, root replacement refusal, Git creation/removal, burst updates and final watcher cleanup. `tools/check files-live` retains repeatable lifecycle checks and frames for visual review. A Linux x86_64 container probe runs the unchanged watcher and confined-reader code against real filesystem events, including shared subscriptions, sleep/wake, a 5,000-file burst, replacement races and cleanup; its browser/cache boundaries are stubbed. Linux game/Chromium integration, Linux arm64 and Windows runtime checks remain.
6. ~~Closing on archive.~~ Packaged checks cover both BB and game archive paths while the screen chunk is unloaded: station-linked built-ins close, linked web and loose built-in locations survive, and the freed station can be removed after returning. Picker restoration, native capture, viewer and browser-isolation acceptance also pass after watcher integration.

## Acceptance tests

On a dev client (`tools/dev start alpha`), through `tools/game` UI hooks, agent tools and screenshots.

**Opening and navigation**
- Blank screen → **Files** opens the picker at home. Type `Else/`, then Tab to complete, then Enter: it lands in the right folder.
- Back and forward step through the picker's folders.
- Picking a file opens the viewer at its repo root, with the file selected.
- Picking a non-repo folder shows no changes toggle.
- Typing `~/Else/too-many-agents` in the address bar opens the viewer.
- An agent opens `kind:file` with a relative path, and `kind:changes` with and without `base`.
- Opening a file in an environment nested inside a repository clamps the root and keeps both modes within that scope.
- Switching between web pages and built-in views, including back/forward, preserves normal navigation without extra prompts or leaked page instrumentation.

**Viewing**
- Text, markdown (both toggles), an image, a binary file and a file over 2 MB each render as expected.
- `node_modules/` shows dimmed and expands when clicked.
- Search finds tracked and untracked files.
- `#L20-L40` from an agent highlights and scrolls. A human shift-clicking lines updates the URL, and the agent reads it back from `screen_list`.
- Branch, Uncommitted and a typed ref each show the right file set. Untracked files show as added. The unified/split toggle works.
- Compare committed, staged and unstaged changes together, including changes that cancel each other. Exercise an unborn branch, missing default branch, unrelated histories and an invalid typed ref.
- A large repository and a large change set remain usable during loading and search. Partial results are identified, navigation cancels stale work, and loading diffs does not jump the scroll position.

**Live**
- An agent edits the open file: the page updates without losing scroll position.
- An agent creates, deletes and renames files: the tree and changes update. A deleted open file shows "deleted".
- A commit changes the Uncommitted set.
- Leaving and returning (screen asleep, then awake) shows current state.
- In a linked worktree, commits, staging, branch switches and shared/packed ref changes refresh the view. Ignore/configuration changes and watcher overflow recover without reopening the screen.

**Persistence and lifecycle**
- Save, quit and reload: every screen restores its shared location (root, mode, base, file, lines) against current files, after revalidating access. Presentation toggles and arbitrary scroll position need not be restored.
- Archiving a thread closes the `tma://` screens linked to its station, and leaves its loose screens and web pages open.
- A human leaving an agent's viewer through the path button clears `openedBy`.

**Escape attempts (all must fail)**
- An agent opens `../`, an absolute path outside its environment, or a symlink to `~/.ssh`.
- Swap a root or child symlink after opening and repeatedly during reads, listings, search and preview loading. No external content is returned. Ordinary in-root symlinks still work.
- `screen_cdp` on a viewer.
- Install CDP scripts/bindings on a web page, then enter a built-in view through human navigation and back/forward. They never run in the built-in view. Pending commands cannot act on the replacement browser.
- Use ordinary-page CDP to target another browser, upload local files or write downloads. It cannot bypass screen access or filesystem restrictions.
- `Page.navigate` to `file:///etc/hosts` or `tma://files/...` from a web page.
- An iframe, `fetch` or `<img>` pointing at `tma://` from a web page.
- Script-bearing Markdown, HTML, SVG, filenames and links cannot execute code, query privileged APIs or leak local content. Ordinary Markdown formatting, safe images, links and copy still work.
- Forge root/query parameters or router messages, including requests from a different screen or an obsolete frame. No request gains broader access.
- A repo whose configuration sets fsmonitor, filters and diff helpers, each touching a marker file. Cover included/global config and concurrent configuration/attribute changes. Open both modes, search, refresh and switch bases: no marker appears and no helper/network activity occurs.
- A base ref of `--output=/tmp/x`.
- A downloaded (untrusted) world containing a forged human-opened `tma://files/~/.ssh/…` screen waits for the trust click before loading.

**Cross-platform**
- Paths with a Windows drive letter, and home detection on macOS, Linux and Windows.
