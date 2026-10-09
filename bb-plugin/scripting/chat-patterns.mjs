// Pattern and message-wait API adapted from Mineflayer 4.39.0 chat.js.
// Native chat transport is installed separately. See chat-patterns.LICENSE.
function match(pattern, message) {
  const previous = pattern.lastIndex;
  try {
    pattern.lastIndex = 0;
    return pattern.exec(message);
  } finally { pattern.lastIndex = previous; }
}

// Returns the dispatcher the caller invokes once per received message string.
export function installChatPatterns(bot, emit) {
  const patterns = new Map();
  const waiters = new Set();
  let nextId = 0;
  // Documented inspection view; registration stays with add/removeChatPattern.
  Object.defineProperty(bot, 'chatPatterns', { enumerable: true, get: () =>
    [...patterns.values()].flatMap(({ name, values }) => values.map(pattern => ({ pattern, type: name }))) });
  function register(name, values, { repeat = true, parse = false } = {}) {
    if (!Array.isArray(values) || !values.length || !values.every(value => value instanceof RegExp))
      throw new TypeError('Pattern parameter should be a nonempty array of RegExp');
    const id = nextId++;
    patterns.set(id, { name, values: values.slice(), repeat, parse, position: 0, matches: [], captures: [] });
    return id;
  }
  bot.addChatPatternSet = (name, values, options) => register(name, values, options);
  bot.addChatPattern = (name, pattern, options) => register(name, [pattern], options);
  bot.removeChatPattern = name => {
    if (typeof name === 'number') patterns.delete(name);
    else for (const [id, pattern] of patterns) if (pattern.name === name) patterns.delete(id);
  };
  bot.awaitMessage = (...args) => {
    const timeout = typeof args.at(-1) === 'number' ? args.pop() : 20000;
    const wanted = args.flatMap(value => value);
    return new Promise((resolve, reject) => {
      const waiter = message => {
        if (!wanted.some(value => value instanceof RegExp ? match(value, message) : value === message)) return;
        clearTimeout(timer);
        waiters.delete(waiter);
        resolve(message);
      };
      const timer = setTimeout(() => {
        waiters.delete(waiter);
        reject(new Error(`Timeout waiting for message after ${timeout}ms`));
      }, timeout);
      waiters.add(waiter);
    });
  };
  return function dispatch(message) {
    const emissions = [];
    for (const [id, pattern] of [...patterns]) {
      if (patterns.get(id) !== pattern) continue;
      const found = match(pattern.values[pattern.position], message);
      if (!found) continue;
      pattern.matches.push(message);
      pattern.captures.push(found.slice(1));
      if (++pattern.position < pattern.values.length) continue;
      const { matches, captures } = pattern;
      // Retire this match before calling user listeners, which may reenter.
      if (pattern.repeat) {
        pattern.position = 0;
        pattern.matches = []; pattern.captures = [];
      } else patterns.delete(id);
      emissions.push([`chat:${pattern.name}`, pattern.parse ? captures : matches]);
    }
    // Every pattern consumes this message before listeners can emit another one.
    for (const event of emissions) emit(...event);
    for (const waiter of [...waiters]) waiter(message);
  };
}
