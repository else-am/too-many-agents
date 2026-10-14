export function namespace(pluginId: string, toolNames: string[]): {
  cli: string;
  tool(name: string): string;
  skill(name: string): string;
  rewrite(text: string): string;
};
