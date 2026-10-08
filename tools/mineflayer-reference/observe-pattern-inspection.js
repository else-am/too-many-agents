// Run in the packaged guest after reopening the saved test world. Matching here
// is script-local; this does not repeat or establish native chat transport.
const before = JSON.stringify(bot.inventory.slots);
const baseline = bot.chatPatterns.length;
if (!baseline || !bot.chatPatterns.every(p => p.pattern instanceof RegExp && typeof p.type === 'string'))
  throw new Error('Default pattern inspection missing');
const legacy = /^__legacy (.+)$/;
const legacyId = bot.chatAddPattern(legacy, '__inspection', 'Inspection description');
const sequence = [/^__first (.+)$/, /^__second (.+)$/];
bot.addChatPatternSet('__sequence', sequence, { parse: true, repeat: false });
const rows = bot.chatPatterns;
if (!rows.some(p => p.pattern === legacy && p.type === '__inspection' && p.description === 'Inspection description')
  || sequence.some(pattern => !rows.some(p => p.pattern === pattern && p.type === '__sequence')))
  throw new Error('Registered pattern inspection differs');
const legacyMatches = [], setMatches = [];
bot.on('__inspection', value => legacyMatches.push(value));
bot.on('chat:__sequence', values => setMatches.push(values));
for (const text of ['__legacy retained', '__first one', '__second two'])
  bot.emit('messagestr', text, 'system', new ChatMessage(text));
if (JSON.stringify(legacyMatches) !== '["retained"]' || JSON.stringify(setMatches) !== '[[["one"],["two"]]]'
  || bot.chatPatterns.some(p => p.type === '__sequence')) throw new Error('Sequential matching/retirement differs');
bot.removeChatPattern(legacyId);
if (bot.chatPatterns.length !== baseline || JSON.stringify(bot.inventory.slots) !== before)
  throw new Error('Pattern removal or inventory differs');
return { username: bot.username, mainHand: bot.settings.mainHand,
  loadedColumns: bot.world.getColumns().length, defaultPatterns: baseline,
  descriptions: true, sequentialMatch: true, retired: true, inventoryUnchanged: true };
