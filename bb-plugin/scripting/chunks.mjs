// PC 1.21.1, prismarine-chunk 1.41.0. See chunks.LICENSE.
// Bundle only this selected implementation. Upstream free Buffer identifiers
// require lexical browser Buffer injection; alias 'buffer' to 'buffer/'.
import upstreamFactory from 'prismarine-chunk/src/pc/1.18/ChunkColumn.js'
import BitArray from 'prismarine-chunk/src/pc/common/BitArrayNoSpan.js'
import BiomeSection from 'prismarine-chunk/src/pc/common/PaletteBiome.js'
import { Buffer } from 'buffer/'

const MAX_HEIGHT = 4096
const MAX_DATA_BYTES = 4 * 1024 * 1024
function requireValue (test, message) {
  if (!test) throw new Error(`Chunk: ${message}`)
}
function dimensions (minY, height) {
  requireValue(Number.isInteger(minY) && minY % 16 === 0 && Math.abs(minY) <= 1048576, 'invalid minY')
  requireValue(Number.isInteger(height) && height > 0 && height <= MAX_HEIGHT && height % 16 === 0, 'invalid worldHeight (16..4096, multiple of 16)')
}
function fromBase64 (value, maxBytes) {
  requireValue(typeof value === 'string' && value.length <= Math.ceil(maxBytes / 3) * 4, 'invalid or oversized base64 data')
  const bytes = Buffer.from(value, 'base64')
  requireValue(bytes.length <= maxBytes && bytes.toString('base64') === value, 'invalid base64 data')
  return bytes
}

// Same registry tables as Block, plus actual native-ID ordered biomesArray.
// No all-version minecraft-data import and no native operations in this class.
export function createChunkClass (registry, Block) {
  requireValue(Array.isArray(registry.blocksArray) && Array.isArray(registry.biomesArray), 'blocksArray and biomesArray are required')
  requireValue(typeof Block?.fromStateId === 'function', 'shared Block constructor is required')
  const blocks = Object.fromEntries(registry.blocksArray.map(b => [b.id, b]))
  const blocksByStateId = []
  for (const b of registry.blocksArray) for (let id = b.minStateId; id <= b.maxStateId; id++) blocksByStateId[id] = b
  const biomes = Object.fromEntries(registry.biomesArray.map(b => [b.id, b]))
  const biomesByName = Object.fromEntries(registry.biomesArray.map(b => [b.name.replace('minecraft:', ''), b]))
  requireValue(registry.biomesArray.length > 0 && registry.biomesArray.every((b, i) => b.id === i), 'biomesArray must use contiguous native IDs')
  const mcData = { ...registry, blocks, blocksByStateId, biomes, biomesByName,
    version: { type: 'pc', majorVersion: '1.21', minecraftVersion: '1.21.1', '>=': version => {
      // These are the only feature comparisons made by the pinned factory.
      requireValue(version === '1.21.5' || version === '26.1', `unexpected upstream version comparison ${version}`)
      return false
    } } }
  const Upstream = upstreamFactory(Block, mcData)
  const Section = Upstream.section
  // Upstream's direct palette JSON constructor discards its data option.
  function restoreDirect (container, json) {
    const parsed = JSON.parse(json)
    if (parsed.type === 'direct') container.data = BitArray.fromJson(parsed.data)
  }
  const sectionFromJson = Section.fromJson
  Section.fromJson = json => {
    const section = sectionFromJson(json)
    restoreDirect(section.data, JSON.parse(json).data)
    return section
  }
  function configureBiomes (column) {
    for (const section of column.biomes) {
      // Upstream defaults biome palette promotion to eight bits rather than
      // the actual native registry width (seven for vanilla 1.21.1).
      if ('maxBitsPerBlock' in section.data) section.data.maxBitsPerBlock = column.maxBitsPerBiome
    }
  }

  // Validate the native container envelope before upstream can allocate from
  // lengths. Native containers use padded 64-bit words, never spanning values.
  function inspectWire (bytes, column) {
    requireValue(bytes.length <= MAX_DATA_BYTES, 'column data exceeds 4 MiB')
    let offset = 0
    const take = n => { requireValue(offset + n <= bytes.length, 'truncated column data'); const start = offset; offset += n; return start }
    const byte = () => bytes[take(1)]
    const varint = () => {
      let value = 0
      for (let n = 0; n < 5; n++) {
        const b = byte()
        value += (b & 127) * 2 ** (7 * n)
        if (!(b & 128)) {
          requireValue(value <= 0x7fffffff && (n === 0 || value >= 2 ** (7 * n)), 'invalid or noncanonical VarInt')
          return value
        }
      }
      throw new Error('Chunk: oversized VarInt')
    }
    function container (capacity, indirectMax, globalBits, known) {
      const bits = byte()
      requireValue(bits === 0 || (bits >= (capacity === 4096 ? 4 : 1) && bits <= indirectMax) || bits === globalBits, 'unsupported palette bit width')
      if (bits === 0) {
        requireValue(known[varint()] !== undefined, 'unknown palette value')
        requireValue(varint() === 0, 'nonempty singleton data')
        return
      }
      if (bits <= indirectMax) {
        const count = varint()
        requireValue(count > 0 && count <= 2 ** bits, 'invalid palette length')
        for (let i = 0; i < count; i++) requireValue(known[varint()] !== undefined, 'unknown palette value')
      }
      const count = varint()
      requireValue(count === Math.ceil(capacity / Math.floor(64 / bits)), 'invalid packed word count')
      take(count * 8)
    }
    const counts = []
    for (let i = 0; i < column.numSections; i++) {
      const count = bytes.readInt16BE(take(2))
      requireValue(count >= 0 && count <= 4096, 'invalid non-air count')
      counts.push(count)
      container(4096, 8, column.maxBitsPerBlock, blocksByStateId)
      container(64, 3, column.maxBitsPerBiome, biomes)
    }
    requireValue(offset === bytes.length, 'trailing column data')
    return counts
  }
  function validateValues (data, capacity, known) {
    if ('value' in data) return // Singleton already validated from the wire.
    for (let i = 0; i < capacity; i++) requireValue(known[data.get(i)] !== undefined, 'unknown value or invalid palette index')
  }
  function lightLayer (value) {
    const result = new BitArray({ bitsPerValue: 4, capacity: 4096 })
    if (Number.isInteger(value) && value >= 0 && value <= 15) {
      result.data.fill((value * 0x11111111) >>> 0)
    } else {
      const bytes = typeof value === 'string' ? fromBase64(value, 2048) : Buffer.from(value)
      requireValue(bytes.length === 2048, 'light layer must contain 2048 bytes')
      // DataLayer stores low nibble first; upstream network light reader reads
      // big-endian long pairs. Read native bytes explicitly, without that swap.
      for (let i = 0; i < 512; i++) result.data[i] = bytes.readUInt32LE(i * 4)
    }
    return result
  }

  return class ChunkColumn extends Upstream {
    constructor (options) {
      dimensions(options?.minY ?? -64, options?.worldHeight ?? 384)
      super(options)
      configureBiomes(this)
    }

    load (data) {
      const bytes = Buffer.isBuffer(data) ? data : Buffer.from(data)
      const counts = inspectWire(bytes, this)
      const decoded = { numSections: this.numSections, maxBitsPerBlock: this.maxBitsPerBlock, maxBitsPerBiome: this.maxBitsPerBiome, sections: [], biomes: [] }
      Upstream.prototype.load.call(decoded, bytes)
      for (let i = 0; i < this.numSections; i++) {
        validateValues(decoded.sections[i].data, 4096, blocksByStateId)
        validateValues(decoded.biomes[i].data, 64, biomes)
        // Singleton cave_air/void_air are nonzero IDs but have native count zero.
        decoded.sections[i].solidBlockCount = counts[i]
      }
      this.sections = decoded.sections
      this.biomes = decoded.biomes
      configureBiomes(this)
    }

    static fromJson (json) {
      const parsed = JSON.parse(json)
      dimensions(parsed.minY, parsed.worldHeight)
      const result = Upstream.fromJson(json)
      Object.setPrototypeOf(result, this.prototype)
      result.emptyBlockLightMask = BitArray.fromLongArray(parsed.emptyBlockLightMask, 1)
      for (let i = 0; i < result.biomes.length; i++) restoreDirect(result.biomes[i].data, parsed.biomes[i])
      configureBiomes(result)
      return result
    }

    getBiomeData (pos) { return biomes[this.getBiome(pos)] }

    loadBlockEntities (entities) {
      for (const entity of entities) {
        const value = entity.type === 'compound' ? entity.value : entity
        this.setBlockEntity({ x: value.x.value & 15, y: value.y.value, z: value.z.value & 15 }, entity)
      }
    }

    _loadBlockLightNibbles (y, buffer) {
      const index = y - this.minY / 16 + 1
      requireValue(index >= 0 && index < this.numSections + 2, 'light section outside column')
      this.blockLightSections[index] = lightLayer(buffer)
      this.blockLightMask.set(index, 1)
      this.emptyBlockLightMask.set(index, 0)
    }

    _loadSkyLightNibbles (y, buffer) {
      const index = y - this.minY / 16 + 1
      requireValue(index >= 0 && index < this.numSections + 2, 'light section outside column')
      this.skyLightSections[index] = lightLayer(buffer)
      this.skyLightMask.set(index, 1)
      this.emptySkyLightMask.set(index, 0)
    }

    loadSection (y, blockStates, biomeStates, blockLight, skyLight) {
      const index = y - this.minY / 16
      requireValue(Number.isInteger(index) && index >= 0 && index < this.numSections, 'section outside column')
      const palette = blockStates.palette.map(entry => {
        const block = Block.fromProperties(entry.Name.replace('minecraft:', ''), entry.Properties || {})
        requireValue(block !== null && block !== undefined, `unknown block ${entry.Name}`)
        return block.stateId
      })
      const biomePalette = biomeStates.palette.map(name => {
        const biome = biomesByName[name.replace('minecraft:', '')]
        requireValue(biome !== undefined, `unknown biome ${name}`)
        return biome.id
      })
      this.sections[index] = Section.fromLocalPalette({ data: BitArray.fromLongArray(blockStates.data || [], blockStates.bitsPerBlock), palette })
      if ('maxBitsPerBlock' in this.sections[index].data) this.sections[index].data.maxBitsPerBlock = this.maxBitsPerBlock
      this.biomes[index] = BiomeSection.fromLocalPalette({ data: BitArray.fromLongArray(biomeStates.data || [], biomeStates.bitsPerBiome), palette: biomePalette })
      configureBiomes(this)
      if (blockLight) this._loadBlockLightNibbles(y, blockLight)
      if (skyLight) this._loadSkyLightNibbles(y, skyLight)
    }

    // Integration extension. All public upstream setters above remain local
    // library edits, never native world mutation. Publish only after this returns.
    static fromSnapshot (row) {
      const column = new this({ minY: row.minY, worldHeight: row.worldHeight })
      column.load(fromBase64(row.data, MAX_DATA_BYTES))
      for (const name of ['skyLight', 'blockLight']) {
        const layers = row[name]
        requireValue(Array.isArray(layers) && layers.length === column.numSections + 2, `invalid ${name} layers`)
        for (let i = 0; i < layers.length; i++) {
          requireValue(typeof layers[i] === 'string' || (Number.isInteger(layers[i]) && layers[i] >= 0 && layers[i] <= 15), `invalid ${name} layer`)
          const layer = lightLayer(layers[i])
          const empty = layer.data.every(value => value === 0)
          column[`${name}Sections`][i] = layer
          column[`${name}Mask`].set(i, empty ? 0 : 1)
          column[`empty${name[0].toUpperCase()}${name.slice(1)}Mask`].set(i, empty ? 1 : 0)
        }
      }
      requireValue(Array.isArray(row.blockEntities) && row.blockEntities.length <= column.worldHeight * 256, 'invalid block entities')
      for (const entry of row.blockEntities) {
        requireValue(Number.isInteger(entry.x) && entry.x >= 0 && entry.x < 16 && Number.isInteger(entry.z) && entry.z >= 0 && entry.z < 16 && Number.isInteger(entry.y) && entry.y >= column.minY && entry.y < column.minY + column.worldHeight, 'block entity outside column')
        requireValue(entry.nbt?.type === 'compound' && entry.nbt.value && typeof entry.nbt.value === 'object', 'block entity requires typed compound NBT')
        column.setBlockEntity(entry, entry.nbt)
      }
      return column
    }
  }
}
