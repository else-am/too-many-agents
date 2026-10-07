// Adapted from Mineflayer 4.39.0 scoreboard/team objects and plugins.
// See mineflayer.LICENSE. Native snapshots replace packet arrival timing.
export function installScoreboards(bot, ChatMessage) {
  const own = (object, key) => Object.hasOwn(object, key);
  const dictionary = () => Object.create(null);
  const colors = ['black','dark_blue','dark_green','dark_aqua','dark_red','dark_purple','gold','gray',
    'dark_gray','blue','green','aqua','red','light_purple','yellow','white','obfuscated','bold',
    'strikethrough','underlined','italic','reset'];
  function message(value) {
    if (value && typeof value === 'object' && own(value, 'type') && own(value, 'value'))
      return ChatMessage.fromNotch(value);
    if (typeof value === 'string') {
      try { return new ChatMessage(JSON.parse(value)); } catch { return new ChatMessage(value); }
    }
    return new ChatMessage(value ?? '');
  }
  class Team {
    constructor(team, name, friendlyFire, nameTagVisibility, collisionRule, formatting, prefix, suffix) {
      this.team = team;
      this.membersMap = dictionary();
      this.update(name, friendlyFire, nameTagVisibility, collisionRule, formatting, prefix, suffix);
    }
    parseMessage(value) { return message(value); }
    add(name) { this.membersMap[name] = ''; return ''; }
    remove(name) { const old = this.membersMap[name]; delete this.membersMap[name]; return old; }
    get members() { return Object.keys(this.membersMap); }
    get memberMap() { return this.membersMap; } // Declaration alias; source calls it membersMap.
    update(name, friendlyFire, nameTagVisibility, collisionRule, formatting, prefix, suffix) {
      Object.assign(this, { name: message(name), friendlyFire, nameTagVisibility, collisionRule,
        color: colors[formatting] ?? 'reset', prefix: message(prefix), suffix: message(suffix) });
    }
    displayName(member) {
      const result = this.prefix.clone();
      result.append(new ChatMessage({ text: member, color: this.color }), this.suffix);
      return result;
    }
  }
  class ScoreBoard {
    constructor(packet) { this.name = packet.name; this.setTitle(packet.displayText); this.itemsMap = dictionary(); }
    setTitle(title) { this.title = message(title).toString(); }
    add(name, value) {
      const item = { name, value, get displayName() { return own(bot.teamMap, name)
        ? bot.teamMap[name].displayName(name) : new ChatMessage(name); } };
      this.itemsMap[name] = item; return item;
    }
    remove(name) { const old = this.itemsMap[name]; delete this.itemsMap[name]; return old; }
    get items() { return Object.values(this.itemsMap).sort((a, b) => b.value - a.value); }
  }
  bot.scoreboards = dictionary(); bot.teams = dictionary(); bot.teamMap = dictionary();
  bot.scoreboard = Object.create(null, {
    list: { get() { return this[0]; }, enumerable: true },
    sidebar: { get() { return this[1]; }, enumerable: true },
    belowName: { get() { return this[2]; }, enumerable: true },
  });
  ScoreBoard.positions = bot.scoreboard;
  const teamKeys = new Map(), titleKeys = new Map();
  let revision;
  function update(value, emit) {
    if (!value || revision === value.revision) return [];
    revision = value.revision;
    const events = [], presentTeams = new Set(), presentBoards = new Set();
    for (const row of value.teams) {
      presentTeams.add(row.team);
      let team = bot.teams[row.team];
      const fresh = !team;
      const { members, team: name, ...properties } = row;
      const key = JSON.stringify(properties);
      if (fresh) team = bot.teams[name] = new Team(name, row.name, row.friendlyFire, row.nameTagVisibility,
        row.collisionRule, row.formatting, row.prefix, row.suffix);
      else if (key !== teamKeys.get(name)) {
        team.update(row.name, row.friendlyFire, row.nameTagVisibility, row.collisionRule, row.formatting, row.prefix, row.suffix);
        events.push(['teamUpdated', team]);
      }
      teamKeys.set(name, key);
      const wanted = new Set(members);
      let added = false, removed = false;
      for (const member of team.members) if (!wanted.has(member)) { team.remove(member); removed = true; }
      for (const member of members) if (!own(team.membersMap, member)) { team.add(member); added = true; }
      if (fresh) events.push(['teamCreated', team]);
      else {
        if (removed) events.push(['teamMemberRemoved', team]);
        if (added) events.push(['teamMemberAdded', team]);
      }
    }
    for (const [name, team] of Object.entries(bot.teams)) if (!presentTeams.has(name)) {
      delete bot.teams[name]; teamKeys.delete(name); events.push(['teamRemoved', team]);
    }
    for (const name of Object.keys(bot.teamMap)) delete bot.teamMap[name];
    for (const team of Object.values(bot.teams)) for (const member of team.members) bot.teamMap[member] = team;
    for (const row of value.boards) {
      presentBoards.add(row.name);
      let board = bot.scoreboards[row.name];
      const fresh = !board, title = JSON.stringify(row.title);
      if (fresh) {
        board = bot.scoreboards[row.name] = new ScoreBoard({ name: row.name, displayText: row.title });
        events.push(['scoreboardCreated', board]);
      } else if (titleKeys.get(row.name) !== title) { board.setTitle(row.title); events.push(['scoreboardTitleChanged', board]); }
      titleKeys.set(row.name, title);
      const scores = new Set();
      for (const score of row.scores) {
        scores.add(score.name);
        const previous = board.itemsMap[score.name];
        if (!previous || previous.value !== score.value) events.push(['scoreUpdated', board, board.add(score.name, score.value)]);
      }
      for (const name of Object.keys(board.itemsMap)) if (!scores.has(name)) events.push(['scoreRemoved', board, board.remove(name)]);
    }
    for (const [name, board] of Object.entries(bot.scoreboards)) if (!presentBoards.has(name)) {
      delete bot.scoreboards[name]; titleKeys.delete(name); events.push(['scoreboardDeleted', board]);
    }
    for (let slot = 0; slot < 19; slot++) {
      const previous = bot.scoreboard[slot], next = own(value.positions, slot) ? bot.scoreboards[value.positions[slot]] : undefined;
      if (next === previous) continue;
      if (next) bot.scoreboard[slot] = next; else delete bot.scoreboard[slot];
      events.push(['scoreboardPosition', slot, next, previous]);
    }
    return emit ? events : [];
  }
  return { update, ScoreBoard, Team };
}
