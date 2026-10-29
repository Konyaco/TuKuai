package io.github.konyaco.tukuai.engine.mahjong.sichuan

import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MjHands
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles

/** How a hand was won, for the fan that depend on it. */
data class WinContext(
    val selfDrawn: Boolean = false,
    val kongBloom: Boolean = false,
    val kongDiscard: Boolean = false,
    val robKong: Boolean = false,
    val lastTile: Boolean = false,
    val heavenly: Boolean = false,
    val earthly: Boolean = false,
) {
    companion object {
        /** A win on an ordinary discard, as the end of the hand counts a ready hand. */
        val PLAIN = WinContext()
    }
}

/** Sichuan hands: which ones win, what they are worth, and what is ready at the end. */
object ScScoring {
    /** Whether [concealed], with [melds] laid down, is a winning hand for a player void in [void]. */
    fun isWin(concealed: List<Tile>, melds: List<Meld>, void: TileSuit?): Boolean {
        if (concealed.any { it.suit == void }) return false
        return MjHands.isWin(Tiles.counts(concealed), 4 - melds.size, sevenPairs = true, fourAsTwoPairs = true)
    }

    /** The tiles a hand one short of complete waits on; none while it holds its void suit. */
    fun waits(concealed: List<Tile>, melds: List<Meld>, void: TileSuit?): List<Tile> {
        if (concealed.any { it.suit == void }) return emptyList()
        return MjHands.waits(Tiles.counts(concealed), 4 - melds.size, sevenPairs = true, fourAsTwoPairs = true) {
            it.suit in SichuanMahjong.SUITS && it.suit != void
        }
    }

    /** How many tile kinds the hand and its melds hold all four of: its gen (根). */
    fun gens(concealed: List<Tile>, melds: List<Meld>): Int {
        val all = Tiles.counts(concealed + melds.flatMap { it.tiles })
        return all.count { it == 4 }
    }

    /** The fan of a winning hand: [concealed] holds the winning tile. */
    fun fans(rules: ScRules, concealed: List<Tile>, melds: List<Meld>, context: WinContext): List<ScFan> {
        val counts = Tiles.counts(concealed)
        val fans = ArrayList<ScFan>()
        val gens = gens(concealed, melds)
        val sevenPairs = melds.isEmpty() && MjHands.isSevenPairs(counts, fourAsTwoPairs = true)
        if (sevenPairs) {
            // Seven pairs is always worth more than reading the same tiles as sets.
            if (gens > 0) {
                fans += ScFan.DRAGON_SEVEN_PAIRS
                repeat(gens - 1) { fans += ScFan.GEN }
            } else {
                fans += ScFan.SEVEN_PAIRS
            }
        } else {
            if (allPungs(counts, melds)) fans += ScFan.ALL_PUNGS
            repeat(gens) { fans += ScFan.GEN }
        }
        val suits = (concealed + melds.flatMap { it.tiles }).map { it.suit }.toSet()
        if (suits.size == 1) fans += ScFan.FULL_FLUSH
        if (context.kongBloom) fans += ScFan.KONG_BLOOM
        if (context.kongDiscard) fans += ScFan.KONG_DISCARD
        if (context.robKong) fans += ScFan.ROB_KONG
        if (context.lastTile) fans += ScFan.LAST_TILE
        if (rules.heavenly && context.heavenly) fans += ScFan.HEAVENLY
        if (rules.heavenly && context.earthly) fans += ScFan.EARTHLY
        return fans
    }

    /** Every set three or four of a kind, and the concealed tiles pungs and one pair. */
    private fun allPungs(counts: IntArray, melds: List<Meld>): Boolean =
        melds.all { it.isOfAKind } && counts.count { it == 2 } == 1 && counts.all { it == 0 || it == 2 || it == 3 }

    /** The fan [fans] add up to; a heavenly or earthly hand counts the most there is. */
    fun total(rules: ScRules, fans: List<ScFan>): Int =
        if (ScFan.HEAVENLY in fans || ScFan.EARTHLY in fans) rules.topFan else fans.sumOf { it.fan }

    /**
     * The most a hand ready on [waits] could win on a discard, in fan, for the end of the
     * hand; -1 when it is not ready.
     */
    fun bestReady(rules: ScRules, concealed: List<Tile>, melds: List<Meld>, void: TileSuit?): Int =
        waits(concealed, melds, void).maxOfOrNull { total(rules, fans(rules, concealed + it, melds, WinContext.PLAIN)) } ?: -1
}
