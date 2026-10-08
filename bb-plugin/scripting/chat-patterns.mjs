// Pattern and message-wait API adapted from Mineflayer 4.39.0 chat.js.
// Native chat transport is installed separately. See chat-patterns.LICENSE.
const USERNAME = '(?:\\(.{1,15}\\)|\\[.{1,15}\\]|.){0,5}?(\\w+)';

function match(pattern, message) {
  const previous = pattern.lastIndex;
  try {
    pattern.lastIndex = 0;
    return pattern.exec(message);
  } finally { pattern.lastIndex = previous; }
}

export function installChatPatterns(bot, { defaultChatPatterns = true } = {}) {
  const patterns = new Map();
  let nextId = 0;
  // Documented inspection view; registration stays with add/removeChatPattern.
  Object.defineProperty(bot, 'chatPatterns', { enumerable: true, get: () =>
    [...patterns.values()].flatMap(({ name, values, description }) =>
      values.map(pattern => ({ pattern, type: name, description }))) });
  function register(name, values, { repeat = true, parse = false, deprecated = false, description } = {}) {
    if (!Array.isArray(values) || !values.length || !values.every(value => value instanceof RegExp))
      throw new TypeError('Pattern parameter should be a nonempty array of RegExp');
    const id = nextId++;
    patterns.set(id, { name, values: values.slice(), repeat, parse, deprecated, description,
      position: 0, matches: [], captures: [], originals: [] });
    return id;
  }
  bot.addChatPatternSet = (name, values, options) => register(name, values, options);
  bot.addChatPattern = (name, pattern, options) => register(name, [pattern], options);
  bot.chatAddPattern = (pattern, type, description) => bot.addChatPattern(type, pattern, { deprecated: true, description });
  bot.removeChatPattern = name => {
    if (typeof name === 'number') patterns.delete(name);
    else for (const [id, pattern] of patterns) if (pattern.name === name) patterns.delete(id);
  };
  bot.on('messagestr', (message, _position, original) => {
    const emissions = [];
    for (const [id, pattern] of [...patterns]) {
      if (patterns.get(id) !== pattern) continue;
      const found = match(pattern.values[pattern.position], message);
      if (!found) continue;
      pattern.matches.push(message);
      pattern.captures.push(found.slice(1));
      pattern.originals.push(original);
      if (++pattern.position < pattern.values.length) continue;
      const { matches, captures, originals } = pattern;
      // Retire this match before calling user listeners, which may reenter.
      if (pattern.repeat) {
        pattern.position = 0;
        pattern.matches = []; pattern.captures = []; pattern.originals = [];
      } else patterns.delete(id);
      if (pattern.deprecated)
        emissions.push([pattern.name, ...captures[0], originals[0]?.translate, ...originals]);
      else emissions.push([`chat:${pattern.name}`, pattern.parse ? captures : matches]);
    }
    // Every pattern consumes this message before listeners can emit another one.
    for (const event of emissions) bot.emit(...event);
  });
  bot.awaitMessage = (...args) => {
    const timeout = typeof args.at(-1) === 'number' ? args.pop() : 20000;
    const wanted = args.flatMap(value => value);
    return new Promise((resolve, reject) => {
      const timer = setTimeout(() => {
        bot.off('messagestr', listener);
        reject(new Error(`Timeout waiting for message after ${timeout}ms`));
      }, timeout);
      function listener(message) {
        if (!wanted.some(value => value instanceof RegExp ? match(value, message) : value === message)) return;
        clearTimeout(timer);
        bot.off('messagestr', listener);
        resolve(message);
      }
      bot.on('messagestr', listener);
    });
  };
  if (defaultChatPatterns) {
    bot.chatAddPattern(new RegExp(`^${USERNAME} whispers(?: to you)?:? (.*)$`), 'whisper');
    bot.chatAddPattern(new RegExp(`^\\[${USERNAME} -> \\w+\\s?\\] (.*)$`), 'whisper');
    bot.chatAddPattern(new RegExp(`^${USERNAME}\\s?[>:\\-»\\]\\)~]+\\s(.*)$`), 'chat');
  }
}
