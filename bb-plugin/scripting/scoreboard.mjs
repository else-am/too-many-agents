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
  const membersByTeam = new WeakMap(), itemsByBoard = new WeakMap();
  class Team {
    constructor(team, name, friendlyFire, nameTagVisibility, collisionRule, formatting, prefix, suffix) {
      this.team = team;
      membersByTeam.set(this, dictionary());
      updateTeam(this, name, friendlyFire, nameTagVisibility, collisionRule, formatting, prefix, suffix);
    }
    get members() { return Object.keys(membersByTeam.get(this)); }
  }
  class ScoreBoard {
    constructor(packet) { this.name = packet.name; setTitle(this, packet.displayText); itemsByBoard.set(this, dictionary()); }
    get items() { return Object.values(itemsByBoard.get(this)).sort((a, b) => b.value - a.value); }
  }
  bot.scoreboards = dictionary(); bot.teams = dictionary();
  let teamMap = dictionary();
  bot.scoreboard = Object.create(null, {
    list: { get() { return this[0]; }, enumerable: true },
    sidebar: { get() { return this[1]; }, enumerable: true },
    belowName: { get() { return this[2]; }, enumerable: true },
  });
  const teamKeys = new Map(), titleKeys = new Map();
  let revision;
  function update(value) {
    if (!value || revision === value.revision) return;
    revision = value.revision;
    const presentTeams = new Set(), presentBoards = new Set();
    for (const row of value.teams) {
      presentTeams.add(row.team);
      let team = bot.teams[row.team];
      const { members, team: name, ...properties } = row;
      const key = JSON.stringify(properties);
      if (!team) team = bot.teams[name] = new Team(name, row.name, row.friendlyFire, row.nameTagVisibility,
        row.collisionRule, row.formatting, row.prefix, row.suffix);
      else if (key !== teamKeys.get(name))
        updateTeam(team, row.name, row.friendlyFire, row.nameTagVisibility, row.collisionRule, row.formatting, row.prefix, row.suffix);
      teamKeys.set(name, key);
      const memberMap = dictionary();
      for (const member of members) memberMap[member] = '';
      membersByTeam.set(team, memberMap);
    }
    for (const name of Object.keys(bot.teams)) if (!presentTeams.has(name)) { delete bot.teams[name]; teamKeys.delete(name); }
    teamMap = dictionary();
    for (const team of Object.values(bot.teams)) for (const member of team.members) teamMap[member] = team;
    for (const row of value.boards) {
      presentBoards.add(row.name);
      let board = bot.scoreboards[row.name];
      const title = JSON.stringify(row.title);
      if (!board) board = bot.scoreboards[row.name] = new ScoreBoard({ name: row.name, displayText: row.title });
      else if (titleKeys.get(row.name) !== title) setTitle(board, row.title);
      titleKeys.set(row.name, title);
      const items = itemsByBoard.get(board), scores = new Set();
      for (const score of row.scores) {
        scores.add(score.name);
        if (items[score.name]?.value !== score.value) addScore(board, score.name, score.value);
      }
      for (const name of Object.keys(items)) if (!scores.has(name)) delete items[name];
    }
    for (const name of Object.keys(bot.scoreboards)) if (!presentBoards.has(name)) { delete bot.scoreboards[name]; titleKeys.delete(name); }
    for (let slot = 0; slot < 19; slot++) {
      const board = own(value.positions, slot) ? bot.scoreboards[value.positions[slot]] : undefined;
      if (board) bot.scoreboard[slot] = board; else delete bot.scoreboard[slot];
    }
  }
  return { update };

  function updateTeam(subject, name, friendlyFire, nameTagVisibility, collisionRule, formatting, prefix, suffix) {
    Object.assign(subject, { name: message(name), friendlyFire, nameTagVisibility, collisionRule,
      color: colors[formatting] ?? 'reset', prefix: message(prefix), suffix: message(suffix) });
  }

  function memberDisplayName(subject, member) {
    const result = subject.prefix.clone();
    result.append(new ChatMessage({ text: member, color: subject.color }), subject.suffix);
    return result;
  }

  function setTitle(subject, title) { subject.title = message(title).toString(); }

  function addScore(subject, name, value) {
    const item = { name, value, get displayName() { return own(teamMap, name)
      ? memberDisplayName(teamMap[name], name) : new ChatMessage(name); } };
    itemsByBoard.get(subject)[name] = item;
  }
}
