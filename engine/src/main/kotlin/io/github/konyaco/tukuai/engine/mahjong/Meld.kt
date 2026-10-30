package io.github.konyaco.tukuai.engine.mahjong

import io.github.konyaco.tukuai.engine.core.Seat

enum class MeldKind {
    /** A run of three claimed from the player before; not every rule set allows it. */
    CHOW,
    PUNG,

    /** Three in hand and the fourth claimed from a discard. */
    EXPOSED_KONG,

    /** A pung made into a kong with the fourth tile, which others may rob to win. */
    ADDED_KONG,

    /** All four drawn by the player; nobody may rob it. */
    CONCEALED_KONG;

    val isKong: Boolean get() = this == EXPOSED_KONG || this == ADDED_KONG || this == CONCEALED_KONG
}

/**
 * A set laid down in front of a player: a run starting at [tile], or three or four of
 * [tile]. [from] is the seat whose discard was claimed for it, null for a concealed kong.
 */
data class Meld(val kind: MeldKind, val tile: Tile, val from: Seat? = null) {
    val tiles: List<Tile>
        get() = when (kind) {
            MeldKind.CHOW -> listOf(tile, tile.next!!, tile.next!!.next!!)
            MeldKind.PUNG -> List(3) { tile }
            else -> List(4) { tile }
        }

    /** Whether it is three or four of a kind, as every set but a run is. */
    val isOfAKind: Boolean get() = kind != MeldKind.CHOW
}

/**
 * The wall: every tile of the set, shuffled, of which [drawnFront] have been dealt or drawn
 * from the front and [drawnBack] taken from the back as replacements for kongs.
 */
data class Wall(val tiles: List<Tile>, val drawnFront: Int = 0, val drawnBack: Int = 0) {
    val remaining: Int get() = tiles.size - drawnFront - drawnBack

    val isEmpty: Boolean get() = remaining == 0

    /** The next tile from the front and the wall without it. */
    fun draw(): Pair<Tile, Wall> {
        check(!isEmpty) { "The wall is empty" }
        return tiles[drawnFront] to copy(drawnFront = drawnFront + 1)
    }

    /** The last tile at the back, drawn after a kong, and the wall without it. */
    fun drawBack(): Pair<Tile, Wall> {
        check(!isEmpty) { "The wall is empty" }
        return tiles[tiles.size - 1 - drawnBack] to copy(drawnBack = drawnBack + 1)
    }

    /** Deals [count] tiles from the front. */
    fun deal(count: Int): Pair<List<Tile>, Wall> {
        check(count <= remaining) { "Only $remaining tiles left" }
        return tiles.subList(drawnFront, drawnFront + count) to copy(drawnFront = drawnFront + count)
    }
}
