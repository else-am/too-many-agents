/*!
 * PC 1.21.1 adaptation of prismarine-block 1.23.0 (MIT).
 * Includes portions of prismarine-biome 1.4.0, prismarine-nbt 2.8.0,
 * and prismarine-chat 1.13.0 (MIT). See blocks.LICENSE for attribution.
 * prismarine-block / prismarine-biome author: Romain Beaumont.
 * prismarine-nbt author: roblabla.
 *
 * MIT License
 *
 * Copyright (c) 2020 PrismarineJS
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

// Registry data is bundled at build time. Required fields: blocksArray,
// blockCollisionShapes, materials, effectsByName,
// enchantmentsByName, language (minecraft-data 3.117.0, PC 1.21.1).
// Constructors preserve upstream defaults, not observed world values. The
// caller supplies position (the bundled Vec3), light, skyLight and typed entity
// NBT from snapshots, and passes the observed biome ID to the constructor.
// setSignText only mutates this object's NBT; it does not edit the world.
export function createBlockClass (registry) {
  for (const field of ['blocksArray', 'blockCollisionShapes', 'materials', 'effectsByName', 'enchantmentsByName', 'language']) {
    if (registry[field] == null) throw new Error(`Block registry is missing ${field}`)
  }
  const blocks = Object.fromEntries(registry.blocksArray.map(block => [block.id, block]))
  const blocksByStateId = []
  for (const block of registry.blocksArray) {
    for (let id = block.minStateId; id <= block.maxStateId; id++) blocksByStateId[id] = block
  }
  function Biome (id) {
    // Correct the pinned Block loader's version-object/registry mix-up.
    // Production supplies metadata indexed by the actual native biome IDs.
    return registry.biomes?.[id] ?? { color: 0, height: null, name: '', rainfall: 0, temperature: 0, id }
  }
  const sign = signMethods(registry.language)
  const shapes = registry.blockCollisionShapes
  for (const block of registry.blocksArray) {
    const shapesId = shapes.blocks[block.name]
    block.shapes = shapes.shapes[Array.isArray(shapesId) ? shapesId[0] : shapesId]
    if (block.states && Array.isArray(shapesId)) block.stateShapes = shapesId.map(id => shapes.shapes[id])
  }

  function getEffectLevel (effectName, effects) {
    const effectDescriptor = registry.effectsByName[effectName]
    if (!effectDescriptor) {
      return 0
    }
    const effectInfo = effects[effectDescriptor.id]
    if (!effectInfo) {
      return 0
    }
    return effectInfo.amplifier + 1
  }

  function getEnchantmentLevel (enchantmentName, enchantments) {
    const enchantmentDescriptor = registry.enchantmentsByName[enchantmentName]
    if (!enchantmentDescriptor) {
      return 0
    }

    for (const enchInfo of enchantments) {
      if (typeof enchInfo.name === 'string') {
        if (enchInfo.name.includes(enchantmentName)) {
          return enchInfo.lvl
        }
      } else if (enchInfo.name === enchantmentDescriptor.name) {
        return enchInfo.lvl
      }
    }
    return 0
  }

  function getMiningFatigueMultiplier (effectLevel) {
    switch (effectLevel) {
      case 0: return 1.0
      case 1: return 0.3
      case 2: return 0.09
      case 3: return 0.0027
      default: return 8.1E-4
    }
  }

  class Block {
    #metadata; #properties; #harvestTools;
    constructor (type, biomeId, metadata, stateId) {
      this.type = type
      this.#metadata = metadata ?? 0
      this.light = 0
      this.skyLight = 0
      this.biome = new Biome(biomeId)
      this.position = null
      this.stateId = stateId

      if (stateId === undefined && type !== undefined) {
        const b = blocks[type]
        // Make sure the block is actually valid and metadata is within valid bounds
        this.stateId = b === undefined ? null : Math.min(b.minStateId + metadata, b.maxStateId)
      }

      const blockEnum = blocksByStateId[this.stateId]
      if (blockEnum) {
        this.#metadata = this.stateId - blockEnum.minStateId
        this.type = blockEnum.id
        this.name = blockEnum.name
        this.hardness = blockEnum.hardness
        this.displayName = blockEnum.displayName
        this.shapes = blockEnum.shapes
        if (blockEnum.stateShapes) {
          if (blockEnum.stateShapes[this.#metadata] !== undefined) {
            this.shapes = blockEnum.stateShapes[this.#metadata]
          } else {
            // Default to shape 0
            this.shapes = blockEnum.stateShapes[0]
          }
        } else if (blockEnum.variations) {
          const variations = blockEnum.variations
          for (const i in variations) {
            if (variations[i].metadata === metadata) {
              this.displayName = variations[i].displayName
              this.shapes = variations[i].shapes
            }
          }
        }
        this.boundingBox = blockEnum.boundingBox
        this.transparent = blockEnum.transparent
        this.diggable = blockEnum.diggable
        this.material = blockEnum.material
        this.#harvestTools = blockEnum.harvestTools
        this.drops = blockEnum.drops
      } else {
        this.name = ''
        this.displayName = ''
        this.shapes = []
        this.hardness = 0
        this.boundingBox = 'empty'
        this.transparent = true
        this.diggable = false
      }

      this.#properties = {}
      if (blockEnum && blockEnum.states) {
        let data = this.#metadata
        for (let i = blockEnum.states.length - 1; i >= 0; i--) {
          const prop = blockEnum.states[i]
          this.#properties[prop.name] = propValue(prop, data % prop.num_values)
          data = Math.floor(data / prop.num_values)
        }
      }
      this.isWaterlogged = this.#properties.waterlogged

      // Extras - Inject helper methods based on the specific block type.
      if (this.name.includes('sign')) {
        Object.defineProperties(this, Object.getOwnPropertyDescriptors(sign))
      }
    }

    static fromStateId (stateId, biomeId) {
      return new Block(undefined, biomeId, 0, stateId)
    }

    get blockEntity () {
      return this.entity ? simplifyNbt(this.entity) : undefined
    }

    getProperties () {
      return { ...this.#properties }
    }

    canHarvest (heldItemType) {
      if (!this.#harvestTools) { return true }; // for blocks harvestable by hand
      return heldItemType && this.#harvestTools && this.#harvestTools[heldItemType]
    }
  }

  function propValue (state, value) {
    if (state.type === 'enum' || state.values) return state.values[value]
    if (state.type === 'bool') return !value
    return value
  }
  function digTime (block, heldItemType, creative, inWater, notOnGround, enchantments = [], effects = {}) {
      if (creative) return 0

      const materialToolMultipliers = registry.materials[block.material]
      const isBestTool = heldItemType && materialToolMultipliers && materialToolMultipliers[heldItemType]

      // Compute breaking speed multiplier
      let blockBreakingSpeed = 1

      if (isBestTool) {
        blockBreakingSpeed = materialToolMultipliers[heldItemType]
      }

      // Efficiency is applied if tools speed multiplier is more than 1.0
      const efficiencyLevel = getEnchantmentLevel('efficiency', enchantments)
      if (efficiencyLevel > 0 && blockBreakingSpeed > 1.0) {
        blockBreakingSpeed += efficiencyLevel * efficiencyLevel + 1
      }

      // Haste is always considered when effect is present, and when both
      // Conduit Power and Haste are present, highest level is considered
      const hasteLevel = Math.max(
        getEffectLevel('Haste', effects),
        getEffectLevel('ConduitPower', effects))

      if (hasteLevel > 0) {
        blockBreakingSpeed *= 1 + (0.2 * hasteLevel)
      }

      // Mining fatigue is applied afterwards, but multiplier only decreases up to level 4
      const miningFatigueLevel = getEffectLevel('MiningFatigue', effects)

      if (miningFatigueLevel > 0) {
        blockBreakingSpeed *= getMiningFatigueMultiplier(miningFatigueLevel)
      }

      // Apply 5x breaking speed de-buff if we are submerged in water and do not have aqua affinity
      const aquaAffinityLevel = getEnchantmentLevel('aqua_affinity', enchantments)

      if (inWater && aquaAffinityLevel === 0) {
        blockBreakingSpeed /= 5.0
      }

      // We always get 5x breaking speed de-buff if we are not on the ground
      if (notOnGround) {
        blockBreakingSpeed /= 5.0
      }

      // Compute block breaking delta (breaking progress applied in a single tick)
      const blockHardness = block.hardness
      const matchingToolMultiplier = block.canHarvest(heldItemType) ? 30.0 : 100.0

      let blockBreakingDelta = blockBreakingSpeed / blockHardness / matchingToolMultiplier

      // Delta will always be zero if block has -1.0 durability
      if (blockHardness === -1.0) {
        blockBreakingDelta = 0.0
      }

      // We will never be capable of breaking block if delta is zero, so abort now and return infinity
      if (blockBreakingDelta === 0.0) {
        return Infinity
      }

      // If breaking delta is more than 1.0 per tick, the block is broken instantly, so return 0
      if (blockBreakingDelta >= 1.0) {
        return 0
      }

      // Determine how many ticks breaking will take, then convert to millis and return result
      // We round ticks up because if progress is below 1.0, it will be finished next tick

      const ticksToBreakBlock = Math.ceil(1.0 / blockBreakingDelta)
      return ticksToBreakBlock * 50
    }
  return { Block, digTime };
}

// The typed object representation is identical to prismarine-nbt's builders.
const nbt = {
  comp: (value, name = '') => ({ type: 'compound', name, value }),
  string: value => ({ type: 'string', value }),
  byte: value => ({ type: 'byte', value }),
  list: value => ({ type: 'list', value: { type: value?.type ?? 'end', value: value?.value ?? [] } })
}

function simplifyNbt (data) {
  function transform (value, type) {
    if (type === 'compound') {
      return Object.keys(value).reduce((acc, key) => {
        acc[key] = simplifyNbt(value[key])
        return acc
      }, {})
    }
    if (type === 'list') return value.value.map(v => transform(v, value.type))
    return value
  }
  return transform(data.value, data.type)
}

function signMethods (language) {
  function toJsonArray (text) {
    if (typeof text === 'string') return text.split('\n').map(line => JSON.stringify({ text: line }))
    if (Array.isArray(text)) {
      return text.map(t => t?.toJSON
        ? JSON.stringify(t.toJSON())
        : typeof t === 'object' ? JSON.stringify(t) : JSON.stringify({ text: t }))
    }
    return []
  }

  function deepMerge (target, source) {
    for (const [key, value] of Object.entries(source)) {
      if (!(key in target)) target[key] = value
      else if (typeof value === 'object' && !Array.isArray(value)) deepMerge(target[key], value)
    }
  }

  function setText (block, side, text) {
    const messages = toJsonArray(text)
    if (!block.entity) block.entity = nbt.comp({ id: nbt.string('minecraft:sign') })
    // Keep the upstream spelling and one-message default, including isWaxed.
    deepMerge(block.entity, nbt.comp({
      isWaxed: nbt.byte(0),
      back_text: nbt.comp({
        has_glowing_text: nbt.byte(0), color: nbt.string('black'),
        messages: nbt.list(nbt.string(['{"text":""}']))
      }),
      front_text: nbt.comp({
        has_glowing_text: nbt.byte(0), color: nbt.string('black'),
        messages: nbt.list(nbt.string(['{"text":""}']))
      })
    }))
    block.entity.value[side].value.messages.value.value = messages
  }

  function getText (block, side) {
    if (!block.entity) return ''
    const messages = block.entity?.value?.[side]?.value?.messages?.value?.value
    if (!messages) return ''
    return messages.map(text => {
      const parsed = JSON.parse(text)
      return typeof parsed === 'string' ? parsed : new SignChatMessage(parsed).toString(language)
    }).join('\n')
  }

  return {
    setSignText (front, back) {
      if (front !== undefined) setText(this, 'front_text', front)
      if (back !== undefined) setText(this, 'back_text', back)
    },
    getSignText () { return [getText(this, 'front_text'), getText(this, 'back_text')] },
    get signText () { return this.getSignText()[0] },
    set signText (text) { this.setSignText(text) }
  }
}

// Only ChatMessage parsing and plain-text rendering are needed by Block.
// Preserve upstream component precedence, validation, translation formatting,
// and depth/length limits; no ChatMessage object is exposed by this module.
class SignChatMessage {
  constructor (message) {
    let json
    if (typeof message === 'string') json = message === '' ? { text: '' } : formattedString(message)
    else if (typeof message === 'number') json = { text: message }
    else if (Array.isArray(message)) json = { extra: message }
    else if (typeof message === 'object') json = sanitizeChat(message)
    else throw new Error('Expected String or Object for Message argument')

    if (typeof json.text === 'string' || typeof json.text === 'number') this.text = json.text
    else if (typeof json[''] === 'string' || typeof json[''] === 'number') this.text = json['']
    else if (typeof json.translate === 'string') {
      this.translate = json.translate
      if (typeof json.fallback === 'string') this.fallback = json.fallback
      if (typeof json.with === 'object') {
        if (!Array.isArray(json.with)) throw new Error('Expected with property to be an Array in ChatMessage')
        this.with = json.with.map(entry => new SignChatMessage(entry))
      }
    } else if (typeof json.selector === 'string') this.selector = json.selector
    else if (typeof json.keybind === 'string') this.keybind = json.keybind
    else if (typeof json.score === 'object' && json.score !== null) this.score = json.score

    if (typeof json.extra === 'object') {
      if (!Array.isArray(json.extra)) throw new Error('Expected extra property to be an Array in ChatMessage')
      this.extra = json.extra.map(entry => new SignChatMessage(entry))
    }
    // Upstream validates events even though plain text does not display them.
    if (json.color && !chatColors.includes(json.color)) json.color.match(/#[a-fA-F\d]{6}/)
    if (typeof json.clickEvent === 'object' && typeof json.clickEvent.action !== 'string') {
      throw new Error('ClickEvent action missing in ChatMessage')
    }
    if (typeof json.hoverEvent === 'object' && typeof json.hoverEvent.action !== 'string') {
      throw new Error('HoverEvent action missing in ChatMessage')
    }
  }

  toString (lang, depth = 0) {
    if (depth > 8) return ''
    let message = ''
    if (typeof this.text === 'string' || typeof this.text === 'number') message += this.text
    else if (this.translate !== undefined) {
      const args = (this.with ?? []).map(entry => entry.toString(lang, depth + 1))
      let format = Object.hasOwn(lang, this.translate) ? lang[this.translate] : null
      if (format === null && this.fallback !== undefined) format = this.fallback
      if (format === null) format = this.translate
      let i = 0
      message += format.replace(/%(?:(\d+)\$)?(s|%)/g, (match, index) => {
        if (match === '%%') return '%'
        const argument = index ? parseInt(index) - 1 : i++
        return args[argument] !== undefined ? args[argument] : ''
      })
    } else if (this.selector !== undefined) message += this.selector
    else if (this.keybind !== undefined) message += this.keybind
    else if (this.score !== undefined && this.score.name && this.score.objective) {
      message += `<score:${this.score.name}:${this.score.objective}>`
    }
    if (this.extra) message += this.extra.map(entry => entry.toString(lang, depth + 1)).join('')
    return message.replace(/§[0-9a-flnmokr]/g, '').replace(/\0/g, '').slice(0, 4096)
  }
}

const chatColors = [
  'black', 'dark_blue', 'dark_green', 'dark_aqua', 'dark_red', 'dark_purple',
  'gold', 'gray', 'dark_gray', 'blue', 'green', 'aqua', 'red', 'light_purple',
  'yellow', 'white', 'obfuscated', 'bold', 'strikethrough', 'underlined', 'italic', 'reset'
]

function sanitizeChat (obj) {
  if (Array.isArray(obj)) return obj.map(sanitizeChat)
  if (obj && typeof obj === 'object') {
    const result = {}
    for (const [key, value] of Object.entries(obj)) {
      if (key === '' && typeof value !== 'string' && typeof value !== 'number') continue
      if (typeof value === 'number' && key === '') {
        result.text = Math.round(value * 1000) / 1000
        continue
      }
      result[key] = sanitizeChat(value)
    }
    return result
  }
  return obj
}

// MessageBuilder.fromString's component nesting matters for the depth limit.
// Styles themselves are discarded because this path only returns plain text.
function formattedString (str) {
  let last = null
  let current = ''
  for (let i = str.length - 1; i > -1; i--) {
    const char = str.substring(i, i + 1)
    if (char !== '§') current += char
    else {
      const text = current.split('').reverse()
      text.shift()
      const next = { text: text.join('') }
      if (last !== null) next.extra = [last]
      last = next
      current = ''
    }
  }
  if (current !== '') {
    const next = { text: current.split('').reverse().join('') }
    if (last !== null) next.extra = [last]
    last = next
  }
  return last
}
