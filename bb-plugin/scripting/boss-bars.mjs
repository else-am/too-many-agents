// Adapted from Mineflayer 4.39.0 bossbar.js and plugins/boss_bar.js.
// See mineflayer.LICENSE. Native snapshots replace packet arrival timing.
const colors = ['pink', 'blue', 'red', 'green', 'yellow', 'purple', 'white'];
const divisions = [0, 6, 10, 12, 20];

export function installBossBars(bot, ChatMessage) {
  class BossBar {
    #title; #dividers; #color;
    constructor(uuid, title, health, dividers, color, flags) {
      this.entityUUID = uuid;
      this.title = title;
      this.health = health;
      this.dividers = dividers;
      this.color = color;
      setFlags(this, flags);
    }
    set title(value) {
      this.#title = value && typeof value === 'object' && value.type === 'string' && 'value' in value
        ? value.value : ChatMessage.fromNotch(value) ?? (typeof value === 'string' ? value : '');
    }
    get title() { return this.#title; }
    set dividers(value) { this.#dividers = divisions[value]; }
    get dividers() { return this.#dividers; }
    set color(value) { this.#color = colors[value]; }
    get color() { return this.#color; }
  }
  function setFlags(bar, value) {
    bar.shouldDarkenSky = !!(value & 1);
    bar.isDragonBar = !!(value & 2);
    bar.createFog = !!(value & 4);
  }
  const bars = new Map(), previous = new Map();
  Object.defineProperty(bot, 'bossBars', { get: () => [...bars.values()] });
  function update(rows) {
    if (!rows) return;
    const present = new Set();
    for (const row of rows) {
      present.add(row.uuid);
      const key = JSON.stringify(row);
      if (previous.get(row.uuid) === key) continue;
      const old = bars.get(row.uuid);
      const bar = old ?? new BossBar(row.uuid, row.title, row.health, row.dividers, row.color, row.flags);
      if (old) {
        const last = JSON.parse(previous.get(row.uuid));
        if (JSON.stringify(last.title) !== JSON.stringify(row.title)) bar.title = row.title;
        bar.health = row.health; bar.dividers = row.dividers; bar.color = row.color; setFlags(bar, row.flags);
      }
      bars.set(row.uuid, bar); previous.set(row.uuid, key);
    }
    for (const uuid of bars.keys()) if (!present.has(uuid)) { bars.delete(uuid); previous.delete(uuid); }
  }
  return { update };
}
