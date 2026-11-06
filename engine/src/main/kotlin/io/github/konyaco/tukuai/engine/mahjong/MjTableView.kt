package io.github.konyaco.tukuai.engine.mahjong

import io.github.konyaco.tukuai.engine.core.Seat

/**
 * What every mahjong rule set's view tells about the table, so one piece of drawing code can
 * lay out the wall, hands, melds and discards for all of them.
 */
interface MjTableView {
    /** The seat this view is for, or null for a spectator. */
    val seat: Seat?
    val dealer: Seat
    val toAct: Seat?

    /** This seat's concealed tiles in order, with [drawn] among them. */
    val hand: List<Tile>

    /** The tile this seat just drew, if it is their turn after a draw. */
    val drawn: Tile?
    val handCounts: List<Int>

    /** Each seat's concealed tiles where they may be seen face up. */
    val shown: List<List<Tile>>
    val melds: List<List<Meld>>
    val discards: List<List<Tile>>
    val wallSize: Int
    val drawnFront: Int
    val drawnBack: Int

    /** The hand is over: every hand lies face up. */
    val handOver: Boolean

    /** The discard, or kong tile, being answered, and who discarded it or made the kong. */
    val claimTile: Tile?
    val claimFrom: Seat?

    /** [claimTile] is a discard, lying last among [claimFrom]'s discards, rather than a kong's fourth tile. */
    val claimingDiscard: Boolean

    /** The tile [seat] won on, to frame it; null when they have not won. */
    fun winningTile(seat: Seat): Tile?

    /** The last set laid down, as its seat and index among their melds, to frame it; null when the last thing was not a set. */
    val latestMeld: Pair<Seat, Int>?

    /** The flowers each seat set aside, for rule sets that have them. */
    val flowers: List<List<Tile>> get() = emptyList()

    /** Which of [seat]'s discards lies sideways, as one does when a player declares riichi; -1 for none. */
    fun sideways(seat: Seat): Int = -1

    /** Tiles of the wall shown face up, such as dora indicators, by their place counted from the front. */
    val wallFaceUp: Map<Int, Tile> get() = emptyMap()

    val wallLeft: Int get() = wallSize - drawnFront - drawnBack

    /**
     * How many of [tile]'s kind this seat can see: in its hand, every discard, every meld,
     * hands lying face up and tiles turned up in the wall. The rest may still come.
     */
    fun seen(tile: Tile): Int =
        hand.count { it.id == tile.id } + discards.sumOf { d -> d.count { it.id == tile.id } } +
            melds.sumOf { m -> m.sumOf { meld -> meld.tiles.count { it.id == tile.id } } } +
            shown.withIndex().filter { it.index != seat }.sumOf { (_, h) -> h.count { it.id == tile.id } } +
            wallFaceUp.values.count { it.id == tile.id }

    /** How many of [tile]'s kind are still unseen by this seat. */
    fun unseen(tile: Tile): Int = (4 - seen(tile)).coerceAtLeast(0)
}
