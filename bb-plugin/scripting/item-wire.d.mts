export function createItemEncoder(registries: unknown): (value: unknown) => string;
export function createItemDecoder(registries: unknown): (wire: string) => unknown;
type ItemTransport = null | boolean | number | string | ItemTransport[] | { [key: string]: ItemTransport };
export function encodeItemTransport(value: unknown): ItemTransport;
export function decodeItemTransport(value: unknown): unknown;
