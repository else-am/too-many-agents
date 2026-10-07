import type { BbPluginApi } from "@get-bb/plugin-sdk";
import type { MinecraftWorlds } from "./minecraft.js";
import { ApiError, object, string, type Session, type SpawnOptions } from "./protocol.js";
import { isWorldWorkspace } from "./world-workspace.js";

/** BB project/workspace choices for a world. The save owns only its project ID and folder. */
export function minecraftProjects(bb: BbPluginApi, worlds: MinecraftWorlds) {
  const creating = new Map<string, Promise<string>>();

  function color(value: unknown): string | undefined {
    if (value === undefined) return undefined;
    if (typeof value !== "string" || !/^#[0-9a-fA-F]{6}$/.test(value))
      throw new ApiError("invalid_project_color", "Enter a color as #RRGGBB");
    return value.toUpperCase();
  }

  async function configure(live: Session, projectId: string, name: unknown, value: unknown) {
    const selectedColor = color(value);
    const id = projectId === "minecraft" ? await ensureWorldProject(live) : projectId;
    const project = name === undefined
      ? await bb.sdk.projects.get({ projectId: id })
      : await bb.sdk.projects.update({ projectId: id, name: string(name, "name") });
    if (selectedColor !== undefined) await bb.storage.kv.set(`project-color:${id}`, selectedColor);
    return project;
  }

  async function create(name: string, folder: string, value: unknown) {
    const selectedColor = color(value);
    const { primaryHostId } = await bb.sdk.system.config();
    if (!primaryHostId) throw new ApiError("host_unavailable", "BB local host daemon is unavailable");
    const project = await bb.sdk.projects.create({ name, source: { type: "local_path", hostId: primaryHostId, path: folder } });
    if (selectedColor !== undefined) await bb.storage.kv.set(`project-color:${project.id}`, selectedColor);
    return project;
  }

  async function metadata(live: Session) {
    return object(await worlds.callback(live, "world_metadata", {}));
  }

  async function ensureWorldProject(live: Session): Promise<string> {
    const world = await metadata(live);
    if (world.worldProjectId) return string(world.worldProjectId, "world project");
    const previous = creating.get(live.worldId);
    if (previous) return previous;
    const work = (async () => {
      const folder = object(await worlds.callback(live, "world.workspace", {}));
      const path = string(folder.path, "workspace path");
      const { primaryHostId } = await bb.sdk.system.config();
      if (!primaryHostId)
        throw new ApiError("host_unavailable", "BB local host daemon is unavailable");
      // A project created before a lost mapping response can be found by its owned folder.
      const existing = (await bb.sdk.projects.list()).find((project) =>
        project.id === world.legacyWorldProjectId ||
        project.sources.some((source) => source.path === path && source.hostId === primaryHostId),
      );
      const project =
        existing ??
        (await bb.sdk.projects.create({
          name: `Minecraft: ${world.name}`,
          source: { type: "local_path", hostId: primaryHostId, path },
        }));
      await worlds.callback(live, "world.project", { projectId: project.id });
      return project.id;
    })();
    creating.set(live.worldId, work);
    // Keep a failed creation until plugin reload; never silently repeat a project mutation.
    return work;
  }

  async function creationOptions(live: Session, projectId: string) {
    const world = await metadata(live);
    if (!projectId || projectId === "minecraft" || projectId === world.worldProjectId)
      return { worktreeAvailable: false };
    const project = await bb.sdk.projects.get({ projectId });
    const source = project.sources.find((row) => row.isDefault) ?? project.sources[0];
    if (!source) return { worktreeAvailable: false };
    const providers = await bb.sdk.environments.listProviders({ projectId, hostId: source.hostId });
    return {
      worktreeAvailable: providers.some((row) =>
        row.id === "git-worktree" && (!row.availability || row.availability.status === "available"),
      ),
    };
  }

  async function selection(live: Session, selection: Partial<SpawnOptions>, worktree?: boolean) {
    const world = await metadata(live);
    const own =
      !selection.projectId ||
      selection.projectId === "minecraft" ||
      selection.projectId === world.worldProjectId;
    const projectId = own ? await ensureWorldProject(live) : selection.projectId!;
    let environment = selection.environment ?? { type: "project-default" as const };
    if (worktree !== undefined) {
      const project = await bb.sdk.projects.get({ projectId });
      const source = project.sources.find((row) => row.isDefault) ?? project.sources[0];
      if (worktree && (!source || !(await creationOptions(live, projectId)).worktreeAvailable))
        throw new ApiError("worktree_unavailable", "This project cannot create a worktree");
      if (source)
        environment = {
          type: "provider",
          environmentProviderId: worktree ? "git-worktree" : "project-checkout",
          ...(worktree ? { machine: { type: "existing" as const, hostId: source.hostId } } : {}),
          inputs: worktree ? { branch: { kind: "default" } } : {},
        };
    }
    if (own && environment.type === "project-default")
      environment = { type: "provider", environmentProviderId: "project-checkout", inputs: {} };
    if (
      environment.type === "provider" &&
      environment.environmentProviderId === "project-checkout" &&
      !environment.machine
    ) {
      const project = await bb.sdk.projects.get({ projectId });
      const source = project.sources.find((row) => row.isDefault) ?? project.sources[0];
      if (!source) throw new ApiError("project_folder_missing", "This project has no folder");
      const [existing] = await bb.sdk.environments.list({
        projectId,
        environmentProviderId: "project-checkout",
        hostId: source.hostId,
        path: source.path,
      });
      environment = existing
        ? { type: "reuse", environmentId: existing.id }
        : { ...environment, machine: { type: "existing", hostId: source.hostId } };
    }
    return { ...selection, projectId, environment };
  }

  async function list(live: Session) {
    const world = await metadata(live);
    const projects = await bb.sdk.projects.list({ includePersonal: true });
    const folder = object(await worlds.callback(live, "world.workspace", {}));
    const path = string(folder.path, "workspace path");
    const own = projects.find((project) => project.id === world.worldProjectId)
      ?? projects.find((project) => project.id === world.legacyWorldProjectId)
      ?? projects.find((project) => project.sources.some((source) => source.path === path));
    if (own) {
      if (world.worldProjectId !== own.id) await worlds.callback(live, "world.project", { projectId: own.id });
      const source = own.sources.find((row) => row.isDefault);
      if (source && source.path !== path)
        await bb.sdk.projects.sources.update({
          projectId: own.id,
          sourceId: source.id,
          type: "local_path",
          path,
        });
    }
    return Promise.all([
      { ...(own ?? { id: "minecraft", sources: [] }), name: "This world", kind: "world" },
      ...projects.filter(
        (project) =>
          project !== own &&
          project.kind !== "personal" &&
          // World projects own this save-relative folder, even after a rename or move.
          !project.sources.some((source) =>
            isWorldWorkspace(source.path),
          ),
      ),
      ...projects
        .filter((project) => project.kind === "personal")
        .map((project) => ({ ...project, name: "No project" })),
    ].map(async (project) => ({
      id: project.id,
      name: project.name,
      kind: project.kind,
      color: await bb.storage.kv.get<string>(`project-color:${project.id}`) ?? "",
      folders: project.sources.map((source) => ({
        label: "Folder",
        path: source.path,
      })),
      folder: (project.sources.find((row) => row.isDefault) ?? project.sources[0])?.path ?? "",
    })));
  }

  bb.onDispose(() => creating.clear());
  return { selection, list, metadata, creationOptions, create, configure };
}
export type MinecraftProjects = ReturnType<typeof minecraftProjects>;
