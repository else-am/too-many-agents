import { open, realpath, unlink } from "node:fs/promises";
import { tmpdir } from "node:os";
import { basename, dirname, extname, isAbsolute, join } from "node:path";
import type { BbPluginApi } from "@get-bb/plugin-sdk";
import { ApiError, object, type Json, type ObjectValue } from "./protocol.js";

export function imageUploads(bb: BbPluginApi) {
  return async function withImages<T>(
    projectId: string,
    request: ObjectValue,
    submit: (prepared: ObjectValue) => Promise<T>,
  ): Promise<T> {
    if (!Array.isArray(request.input)) return submit(request);
    const input: Json[] = [];
    const uploadedPaths = new Set<string>();
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
        uploadedPaths.add(item.path);
      } finally {
        await file.close();
      }
    }
    const result = await submit({ ...request, input });
    if (uploadedPaths.size === 0) return result;
    // Only remove our temporary copies after BB accepts the message; never delete the user's source file.
    const temporaryDirectory = await realpath(join(tmpdir(), "too-many-agents-images")).catch(
      () => null,
    );
    if (temporaryDirectory)
      for (const path of uploadedPaths) {
        if (!/^image-\d+\.(png|jpe?g|gif|webp)$/i.test(basename(path))) continue;
        try {
          if ((await realpath(dirname(path))) === temporaryDirectory) await unlink(path);
        } catch {
          bb.log.warn("Could not remove a Minecraft temporary image after successful delivery");
        }
      }
    return result;
  };
}
