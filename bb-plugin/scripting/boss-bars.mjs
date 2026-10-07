// Adapted from Mineflayer 4.39.0 bossbar.js and plugins/boss_bar.js.
// See mineflayer.LICENSE. Native snapshots replace packet arrival timing.
const colors = ['pink', 'blue', 'red', 'green', 'yellow', 'purple', 'white'];
const divisions = [0, 6, 10, 12, 20];

export function installBossBars(bot, ChatMessage) {
  class BossBar {
    constructor(uuid, title, health, dividers, color, flags) {
      this.entityUUID = uuid;
      this.title = title;
      this.health = health;
      this.dividers = dividers;
      this.color = color;
      this.flags = flags;
    }
    set title(value) {
      this._title = value && typeof value === 'object' && value.type === 'string' && 'value' in value
        ? value.value : ChatMessage.fromNotch(value) ?? (typeof value === 'string' ? value : '');
    }
    get title() { return this._title; }
    set dividers(value) { this._dividers = divisions[value]; }
    get dividers() { return this._dividers; }
    set color(value) { this._color = colors[value]; }
    get color() { return this._color; }
    set flags(value) {
      this.shouldDarkenSky = !!(value & 1);
      this.isDragonBar = !!(value & 2);
      this.createFog = !!(value & 4);
    }
    get flags() { return (this.shouldDarkenSky ? 1 : 0) | (this.isDragonBar ? 2 : 0) | (this.createFog ? 4 : 0); }
    get shouldCreateFog() { return this.createFog; }
  }
  const bars = new Map(), previous = new Map();
  Object.defineProperty(bot, 'bossBars', { get: () => [...bars.values()] });
  function update(rows, emit) {
    if (!rows) return [];
    const events = [], present = new Set();
    for (const row of rows) {
      present.add(row.uuid);
      const key = JSON.stringify(row);
      if (previous.get(row.uuid) === key) continue;
      const old = bars.get(row.uuid);
      const bar = old ?? new BossBar(row.uuid, row.title, row.health, row.dividers, row.color, row.flags);
      if (old) {
        const last = JSON.parse(previous.get(row.uuid));
        if (JSON.stringify(last.title) !== JSON.stringify(row.title)) bar.title = row.title;
        bar.health = row.health; bar.dividers = row.dividers; bar.color = row.color; bar.flags = row.flags;
      }
      bars.set(row.uuid, bar); previous.set(row.uuid, key);
      if (emit) events.push([old ? 'bossBarUpdated' : 'bossBarCreated', bar]);
    }
    for (const [uuid, bar] of bars) if (!present.has(uuid)) {
      bars.delete(uuid); previous.delete(uuid);
      if (emit) events.push(['bossBarDeleted', bar]);
    }
    return events;
  }
  return { BossBar, update };
}
