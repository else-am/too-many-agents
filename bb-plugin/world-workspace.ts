/** The save owns this folder; project names can be changed freely. */
export function isWorldWorkspace(path: string) {
  return /[\\/]too-many-agents[\\/]workspace[\\/]?$/.test(path);
}
