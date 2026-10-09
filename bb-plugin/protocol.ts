import type { BbPluginApi, JsonValue } from "@get-bb/plugin-sdk";

export type Json = JsonValue;
export type ObjectValue = { [key: string]: Json };
export type Sdk = BbPluginApi["sdk"];
export type Args<F extends (...args: never[]) => unknown> = Parameters<F>[0];
export type SpawnOptions = Args<Sdk["threads"]["spawn"]>;
export type Thread = Awaited<ReturnType<Sdk["threads"]["get"]>>;
export interface Session {
  connectionId: string;
  worldId: string;
  worldSessionId: string;
  callbackUrl: string;
  callbackToken: string;
}
export interface Agent {
  agentId: string;
  name: string;
  threadId: string;
  projectId: string;
  minecraftAccess: boolean;
  settings: ObjectValue;
  draft: ObjectValue;
  body: ObjectValue;
  archived: boolean;
  removed: boolean;
  startNonce: string;
}
export interface Identity {
  worldId: string;
  agentId: string;
}

export class ApiError extends Error {
  constructor(
    readonly code: string,
    message: string,
    readonly nativeCode?: string,
    readonly httpStatus?: number,
  ) {
    super(message);
  }
}
export const PROTOCOL = 3;
export const MAX_BODY_BYTES = 1024 * 1024;
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;
export function object(value: unknown, label = "arguments"): ObjectValue {
  if (!value || typeof value !== "object" || Array.isArray(value))
    throw new ApiError("invalid_request", `${label} must be an object`);
  return value as ObjectValue;
}
export function string(value: unknown, label: string): string {
  if (typeof value !== "string" || !value.trim())
    throw new ApiError("invalid_request", `${label} is required`);
  return value;
}
export function uuid(value: unknown, label: string): string {
  const id = string(value, label);
  if (!UUID.test(id)) throw new ApiError("invalid_request", `${label} must be a UUID`);
  return id;
}
export function optional(data: ObjectValue, ...keys: string[]): ObjectValue {
  return Object.fromEntries(
    keys.filter((key) => typeof data[key] === "string").map((key) => [key, data[key]!]),
  );
}
export function describe(error: unknown): string {
  return error instanceof Error ? error.message : String(error);
}
export function loopbackUrl(value: unknown): string {
  const url = new URL(string(value, "callbackUrl"));
  if (
    url.protocol !== "http:" ||
    !["127.0.0.1", "[::1]"].includes(url.hostname) ||
    url.username ||
    url.password ||
    url.search ||
    url.hash
  )
    throw new ApiError(
      "invalid_request",
      "callbackUrl must be literal loopback HTTP without credentials or query",
    );
  return url.href;
}
