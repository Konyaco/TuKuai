package io.github.konyaco.tukuai.engine.mahjong

import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

/** Writing tiles and melds, for every mahjong rule set's codecs. */
object MjCodecs {
    fun tile(reader: WireReader): Tile {
        val id = reader.int()
        require(id in 0 until Tile.KINDS) { "Bad tile $id" }
        return Tile.of(id)
    }

    fun tileOrNull(reader: WireReader): Tile? = reader.int().takeIf { it >= 0 }?.let { require(it < Tile.KINDS) { "Bad tile $it" }; Tile.of(it) }

    fun writeTiles(writer: WireWriter, tiles: List<Tile>) {
        writer.ints(tiles.map { it.id })
    }

    fun tiles(reader: WireReader): List<Tile> = reader.ints().map { require(it in 0 until Tile.KINDS) { "Bad tile $it" }; Tile.of(it) }

    fun writeMeld(writer: WireWriter, meld: Meld) {
        writer.int(meld.kind.ordinal).int(meld.tile.id).int(meld.from ?: -1)
    }

    fun meld(reader: WireReader): Meld = Meld(MeldKind.entries[reader.int()], tile(reader), reader.int().takeIf { it >= 0 })

    fun suitOrNull(reader: WireReader): TileSuit? = reader.int().takeIf { it >= 0 }?.let { TileSuit.entries[it] }
}
