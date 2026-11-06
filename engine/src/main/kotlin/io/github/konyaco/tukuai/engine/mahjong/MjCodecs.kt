package io.github.konyaco.tukuai.engine.mahjong

import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

/** Writing tiles and melds, for every mahjong rule set's codecs. */
object MjCodecs {
    /** Tiles go as their [Tile.code], which is their id unless they are a red five. */
    fun tile(reader: WireReader): Tile {
        val code = reader.int()
        return Tile.fromCode(code) ?: throw IllegalArgumentException("Bad tile $code")
    }

    fun tileOrNull(reader: WireReader): Tile? = reader.int().takeIf { it >= 0 }?.let { Tile.fromCode(it) ?: throw IllegalArgumentException("Bad tile $it") }

    fun writeTile(writer: WireWriter, tile: Tile?) {
        writer.int(tile?.code ?: -1)
    }

    fun writeTiles(writer: WireWriter, tiles: List<Tile>) {
        writer.ints(tiles.map { it.code })
    }

    fun tiles(reader: WireReader): List<Tile> = reader.ints().map { Tile.fromCode(it) ?: throw IllegalArgumentException("Bad tile $it") }

    fun writeMeld(writer: WireWriter, meld: Meld) {
        writer.int(meld.kind.ordinal).int(meld.tile.id).int(meld.from ?: -1).int(meld.reds).int(meld.claimed?.code ?: -1)
    }

    fun meld(reader: WireReader): Meld =
        Meld(MeldKind.entries[reader.int()], tile(reader), reader.int().takeIf { it >= 0 }, reader.int(), tileOrNull(reader))

    fun suitOrNull(reader: WireReader): TileSuit? = reader.int().takeIf { it >= 0 }?.let { TileSuit.entries[it] }
}
