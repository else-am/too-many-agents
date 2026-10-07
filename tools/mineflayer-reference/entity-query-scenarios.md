# Entity query checks

Source contract: Mineflayer 4.39.0 `entities.js` findPlayer/findPlayers and nearestEntity.
Compare null/string/case-insensitive/RegExp/function/omitted player filters; string results have null/single/array shapes. Include a closer nonplayer and the controlled body in nearestEntity, tied distances, and no match. Returned values must be the current observed Entity objects. These are loaded-view queries; they do not establish a complete player list.

Final live check can reuse the packaged fixture: nearestEntity excludes bot.entity and honors a villager predicate; findPlayers(null) selects only observed players; case-insensitive lookup returns the same observed player. No world mutation required.

Attribute/event slice: compare native base values and modifier IDs/amounts/operations to the 1.21.1 attribute packet schema. Change one native attribute and one equipment slot, then verify entityAttributes/entityEquip listeners see complete hydrated data on the same Entity object. Unchanged snapshots must not repeat events. Modifier ordering is canonicalized only for change detection. Live checks remain pending.
