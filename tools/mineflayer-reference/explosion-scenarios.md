# Explosion damage estimate (before implementation)

Port synchronous `getExplosionDamages(entity, position, power, rawDamages=false)`
from Mineflayer 4.39.0. Keep its damage/armor/difficulty estimate and nullable
result; this is not authoritative native damage and retains upstream's missing
resistance/enchantment reductions. Power is the explosion strength, radius=2×power.

Prewritten `explosion.mjs` invokes the actual pinned plugin for clear/blocked/slab
exposure, four difficulties, armor/toughness/modifiers, raw mode, missing armor
and distance cutoff. Run `--reference-only` before adding the adapter.

Explicit corrections: accept native modern armor_toughness key as well as legacy
armorToughness; sample actual observed width/height instead of assuming every body
is a player; return null for unknown ray cells, missing body/attribute information
or unknown difficulty rather than treating unseen space as air or returning NaN.
Malformed numerical inputs and excessive sample counts reject with RangeError.
Exposure work is bounded. Existing raycast shape/traversal bounds still apply.

Focused later native fixture: compare clear and wall-shielded estimates using
observed cow/player-sized geometry and modern native armor attributes. Estimate
calls must not damage entities, remove blocks or alter items. Actual blast damage
can differ due to native immunity, effects, enchantments and other game rules;
do not claim physical conformance from this pure query.
