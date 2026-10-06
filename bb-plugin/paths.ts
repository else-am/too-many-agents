import { posix, win32 } from "node:path";

// Paths belong to BB hosts, which need not use the server's operating system.
export function pathStyle(path: string) {
  return /^[a-z]:[\\/]|^\\\\/i.test(path) ? win32 : posix;
}

export function isAbsolutePath(path: string) {
  return pathStyle(path).isAbsolute(path);
}

export function relativeInside(root: string, path: string): string | null {
  const paths = pathStyle(root);
  if (!isAbsolutePath(root) || !isAbsolutePath(path) || pathStyle(path) !== paths) return null;
  const relative = paths.relative(root, path);
  if (relative === ".." || relative.startsWith(`..${paths.sep}`) || paths.isAbsolute(relative)) return null;
  // BB's file routes require forward slashes, even for a Windows host.
  return relative.split(paths.sep).join("/");
}
