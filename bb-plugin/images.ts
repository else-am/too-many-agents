import { open } from "node:fs/promises";
import { basename, extname, isAbsolute, posix, win32 } from "node:path";
import type { BbPluginApi, PluginAgentToolContext, PluginAgentToolResult } from "@get-bb/plugin-sdk";
import { ApiError, object, type Json, type ObjectValue } from "./protocol.js";

export async function savePovSnapshot(
  bb: BbPluginApi,
  ctx: PluginAgentToolContext,
  result: PluginAgentToolResult,
): Promise<PluginAgentToolResult> {
  if (typeof result === "string" || result.isError) return result;
  const images = result.content.filter(part => part.type === "image");
  const image = images[0];
  if (images.length !== 1 || !image || image.mimeType !== "image/png"
      || typeof image.data !== "string" || image.data.length > 7 * 1024 * 1024)
    throw new ApiError("invalid_snapshot", "Expected one PNG snapshot");
  const bytes = Buffer.from(image.data, "base64");
  if (bytes.length < 8 || bytes.length > 5 * 1024 * 1024
      || !bytes.subarray(0, 8).equals(Buffer.from([137, 80, 78, 71, 13, 10, 26, 10])))
    throw new ApiError("invalid_snapshot", "Snapshot must be a PNG under 5 MB");

  const metadata = result.content.find(part => part.type === "text");
  const capturedAtMs = metadata ? object(JSON.parse(metadata.text), "snapshot metadata").capturedAtMs : undefined;
  if (typeof capturedAtMs !== "number" || !Number.isSafeInteger(capturedAtMs) || capturedAtMs <= 0)
    throw new ApiError("invalid_snapshot", "Snapshot is missing its capture timestamp");

  ctx.signal.throwIfAborted();
  const storage = await bb.sdk.threads.storageLocation({ threadId: ctx.threadId });
  // The agent's host may use different path separators from the plugin server.
  const paths = storage.storageRootPath.startsWith("/") ? posix : win32;
  const path = paths.join(storage.storageRootPath, "Attachments", `minecraft-pov-${capturedAtMs}.png`);
  ctx.signal.throwIfAborted();
  const saved = await bb.sdk.files.write({
    hostId: storage.hostId,
    rootPath: storage.storageRootPath,
    path,
    content: image.data,
    contentEncoding: "base64",
    createParents: true,
    expectedSha256: null,
  });
  if (saved.outcome !== "written")
    throw new ApiError("snapshot_exists", "A snapshot already exists at this capture timestamp");
  return {
    content: [
      ...result.content.filter(part => part.type === "text"),
      { type: "text", text: JSON.stringify({
        path,
        mimeType: "image/png",
        message: "Snapshot saved. Open this path with your image-viewing tool (Codex: view_image; Claude Code: Read) to see it.",
      }) },
    ],
  };
}

export function imageUploads(bb: BbPluginApi) {
  return async function withImages<T>(
    projectId: string,
    request: ObjectValue,
    submit: (prepared: ObjectValue) => Promise<T>,
  ): Promise<T> {
    if (!Array.isArray(request.input)) return submit(request);
    const input: Json[] = [];
    for (const value of request.input) {
      const item = object(value, "input item");
      if (item.type !== "localImage" || typeof item.path !== "string" || !isAbsolute(item.path)) {
        input.push(item);
        continue;
      }
      const mimeType = (
        {
          ".png": "image/png",
          ".jpg": "image/jpeg",
          ".jpeg": "image/jpeg",
          ".gif": "image/gif",
          ".webp": "image/webp",
        } as Record<string, string>
      )[extname(item.path).toLowerCase()];
      if (!mimeType) throw new ApiError("invalid_image", "Use a PNG, JPEG, GIF, or WebP image");
      const file = await open(item.path, "r");
      try {
        const info = await file.stat();
        if (!info.isFile() || info.size < 1 || info.size > 5 * 1024 * 1024)
          throw new ApiError(
            "invalid_image",
            "Images must be regular files between 1 byte and 5 MB",
          );
        const uploaded = await bb.sdk.projects.attachments.upload({
          projectId,
          filename: basename(item.path),
          mimeType,
          clientFile: await file.readFile(),
        });
        input.push({ ...item, path: uploaded.path });
      } finally {
        await file.close();
      }
    }
    return submit({ ...request, input });
  };
}
