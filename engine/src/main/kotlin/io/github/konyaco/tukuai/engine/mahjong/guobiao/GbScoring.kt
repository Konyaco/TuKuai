package io.github.konyaco.tukuai.engine.mahjong.guobiao

import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.MjHands
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles

/**
 * A hand that may win, and how: [concealed] holds the winning [tile]. Winds count from 0 for
 * east. [lastOfKind] is set when the other three of the tile's kind lie face up on the table.
 */
data class GbWin(
    val concealed: List<Tile>,
    val melds: List<Meld>,
    val tile: Tile,
    val selfDrawn: Boolean,
    val seatWind: Int = 0,
    val prevalentWind: Int = 0,
    val lastTile: Boolean = false,
    val kongReplacement: Boolean = false,
    val robKong: Boolean = false,
    val lastOfKind: Boolean = false,
    val flowers: Int = 0,
)

/** What a winning hand counts: its [fans], a fan counted twice listed twice, and their [total]. */
data class GbScore(val fans: List<GbFan>) {
    val total: Int get() = fans.sumOf { it.fan }

    /** The fan without flowers, which is what the least a hand needs is measured in. */
    val base: Int get() = fans.filter { it != GbFan.FLOWER_TILES }.sumOf { it.fan }
}

/**
 * Reading and scoring Guobiao hands. Besides four sets and a pair, a hand may win as seven
 * pairs, thirteen orphans, the honours and knitted tiles (全不靠) or a knitted straight with a
 * set and a pair (组合龙). Every way of reading the hand and of placing the winning tile in it
 * is scored, and the best counts, as the rules ask.
 */
object GbScoring {
    private val ORPHANS: List<Tile> = Tiles.parse("19m 19p 19s 1234w 123d")

    /** The tiles of the three knitted runs, 147, 258 and 369, each in a suit of its own: six ways. */
    val KNITTED: List<List<Tile>> = listOf(
        listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0),
    ).map { order ->
        order.flatMapIndexed { start, suit -> listOf(1, 4, 7).map { Tile.of(TileSuit.NUMBERED[suit], it + start) } }
    }

    private val GREEN: Set<Tile> = Tiles.parse("23468s 2d").toSet()
    private val REVERSIBLE: Set<Tile> = Tiles.parse("1234589p 245689s 3d").toSet()

    // ---- Shapes --------------------------------------------------------------------------------

    private fun isThirteenOrphans(c: IntArray): Boolean =
        c.sum() == 14 && ORPHANS.all { c[it.id] >= 1 } && c.indices.all { c[it] == 0 || Tile.of(it) in ORPHANS }

    /** The knitted way, if any, every numbered tile of [c] belongs to, for 全不靠. */
    private fun honoursAndKnitted(c: IntArray): List<Tile>? {
        if (c.sum() != 14 || c.any { it > 1 }) return null
        val numbered = c.indices.filter { c[it] > 0 && Tile.of(it).suit.numbered }.map { Tile.of(it) }
        return KNITTED.firstOrNull { it.containsAll(numbered) }
    }

    /** Whether [counts] (concealed, with [melds] laid down) wins in any of the shapes. */
    fun isWinShape(counts: IntArray, melds: Int): Boolean {
        val sets = 4 - melds
        if (MjHands.isStandard(counts, sets)) return true
        if (melds == 0 && (MjHands.isSevenPairs(counts, fourAsTwoPairs = true) || isThirteenOrphans(counts) || honoursAndKnitted(counts) != null)) return true
        if (sets >= 1) {
            for (knit in KNITTED) {
                if (knit.all { counts[it.id] > 0 }) {
                    val rest = counts.copyOf()
                    knit.forEach { rest[it.id]-- }
                    if (MjHands.isStandard(rest, sets - 3)) return true
                }
            }
        }
        return false
    }

    /** The tiles of a hand's kinds (not flowers) that [concealed] waits on, in any shape. */
    fun waits(concealed: List<Tile>, melds: List<Meld>): List<Tile> {
        if (concealed.size % 3 != 1) return emptyList()
        val c = Tiles.counts(concealed)
        val held = Tiles.counts(concealed + melds.flatMap { it.tiles })
        val out = ArrayList<Tile>()
        for (suit in TileSuit.HAND) {
            for (tile in suit.tiles) {
                if (held[tile.id] >= 4) continue
                c[tile.id]++
                if (isWinShape(c, melds.size)) out += tile
                c[tile.id]--
            }
        }
        return out
    }

    /**
     * How many tiles a hand is from ready in any of the shapes: -1 when complete. The knitted
     * shapes are left out unless [knitted], as they are slow to read and rarely worth it.
     */
    fun shanten(concealed: List<Tile>, melds: Int, knitted: Boolean = true): Int {
        val c = Tiles.counts(concealed)
        val sets = 4 - melds
        var best = MjHands.standardShanten(c, sets)
        if (melds == 0) {
            best = minOf(best, MjHands.sevenPairsShanten(c, fourAsTwoPairs = true))
            best = minOf(best, thirteenShanten(c))
            if (knitted) best = minOf(best, honoursKnittedShanten(c))
        }
        if (knitted && sets >= 3) best = minOf(best, knittedStraightShanten(c, sets))
        return best
    }

    fun thirteenShanten(c: IntArray): Int {
        val kinds = ORPHANS.count { c[it.id] > 0 }
        val pair = ORPHANS.any { c[it.id] >= 2 }
        return 13 - kinds - (if (pair) 1 else 0)
    }

    fun honoursKnittedShanten(c: IntArray): Int {
        val honours = TileSuit.WINDS.tiles.count { c[it.id] > 0 } + TileSuit.DRAGONS.tiles.count { c[it.id] > 0 }
        return KNITTED.minOf { knit -> 13 - honours - knit.count { c[it.id] > 0 } }
    }

    fun knittedStraightShanten(c: IntArray, sets: Int): Int = KNITTED.minOf { knit ->
        val rest = c.copyOf()
        var missing = 0
        for (t in knit) if (rest[t.id] > 0) rest[t.id]-- else missing++
        missing + MjHands.standardShanten(rest, sets - 3)
    }

    // ---- Scoring -------------------------------------------------------------------------------

    private enum class Wait { NONE, EDGE, CLOSED, SINGLE }

    /** One set of a reading: a run from [tile], or three or four of [tile]. */
    private data class GSet(val run: Boolean, val tile: Tile, val concealedPung: Boolean, val meld: MeldKind?) {
        val kong: Boolean get() = meld?.isKong == true
        val tiles: List<Tile> get() = if (run) listOf(tile, tile.next!!, tile.next!!.next!!) else List(if (kong) 4 else 3) { tile }
    }

    /** The best score of [win], or null when it is not a winning hand. */
    fun score(win: GbWin): GbScore? {
        val c = Tiles.counts(win.concealed)
        val sets = 4 - win.melds.size
        if (c.sum() != sets * 3 + 2) return null
        val candidates = ArrayList<List<GbFan>>()
        val single = singleWait(win)
        // Four sets and a pair, every way, with the winning tile in every place it may go.
        for (shape in MjHands.shapes(c, sets)) {
            for (placed in placements(win, shape)) candidates += standard(win, placed.first, shape.pair, placed.second.takeIf { single })
        }
        if (win.melds.isEmpty()) {
            if (MjHands.isSevenPairs(c, fourAsTwoPairs = true)) candidates += sevenPairs(win, c)
            if (isThirteenOrphans(c)) candidates += special(win, listOf(GbFan.THIRTEEN_ORPHANS))
            honoursAndKnitted(c)?.let { knit ->
                val honours = c.indices.count { c[it] > 0 && Tile.of(it).isHonour }
                val fans = ArrayList<GbFan>()
                fans += if (honours == 7) GbFan.GREATER_HONOURS_KNITTED else GbFan.LESSER_HONOURS_KNITTED
                if (knit.all { c[it.id] > 0 }) fans += GbFan.KNITTED_STRAIGHT
                candidates += special(win, fans)
            }
        }
        if (sets >= 3) {
            for (knit in KNITTED) {
                if (!knit.all { c[it.id] > 0 }) continue
                val rest = c.copyOf()
                knit.forEach { rest[it.id]-- }
                for (shape in MjHands.shapes(rest, sets - 3)) {
                    // The winning tile may be one of the knitted ones too.
                    for (placed in placements(win, shape, knitted = knit)) {
                        candidates += standard(win, placed.first, shape.pair, placed.second.takeIf { single }, knitted = true)
                    }
                }
            }
        }
        val best = candidates.maxByOrNull { fans -> fans.sumOf { it.fan } } ?: return null
        return GbScore(best.sortedBy { it.ordinal })
    }

    /** Whether the hand without the winning tile waited on that one tile alone; only then do the waiting fan count. */
    private fun singleWait(win: GbWin): Boolean {
        val before = Tiles.minusKind(win.concealed, win.tile, 1)
        val waits = waits(before, win.melds)
        return waits.size == 1 && waits[0].id == win.tile.id
    }

    /**
     * Every way to read [shape] with the winning tile placed in it: the sets (melds first) and
     * how the tile was waited on. A pung the tile completes from a discard is not concealed.
     */
    private fun placements(win: GbWin, shape: MjHands.Shape, knitted: List<Tile>? = null): List<Pair<List<GSet>, Wait>> {
        val tile = win.tile.plain
        val meldSets = win.melds.map { m ->
            GSet(m.kind == MeldKind.CHOW, m.tile.plain, m.kind == MeldKind.CONCEALED_KONG, m.kind)
        }
        val groups = shape.groups
        val out = ArrayList<Pair<List<GSet>, Wait>>()
        fun sets(discardPung: Int): List<GSet> = meldSets + groups.mapIndexed { i, g ->
            GSet(g.run, g.tile, !g.run && !(i == discardPung && !win.selfDrawn), null)
        }
        if (knitted != null && tile in knitted) out += sets(-1) to Wait.NONE
        if (shape.pair == tile) out += sets(-1) to Wait.SINGLE
        for ((i, g) in groups.withIndex()) {
            if (g.run) {
                val offset = tile.id - g.tile.id
                if (tile.suit != g.tile.suit || offset !in 0..2) continue
                val wait = when {
                    offset == 1 -> Wait.CLOSED
                    offset == 2 && g.tile.rank == 1 -> Wait.EDGE
                    offset == 0 && g.tile.rank == 7 -> Wait.EDGE
                    else -> Wait.NONE
                }
                out += sets(-1) to wait
            } else if (g.tile == tile) {
                out += sets(i) to Wait.NONE
            }
        }
        return out.distinct()
    }

    /** Fan that come from how the hand was won, whatever its shape. */
    private fun situational(win: GbWin, fans: MutableList<GbFan>, concealedHand: Boolean) {
        if (win.selfDrawn) fans += GbFan.SELF_DRAWN
        if (concealedHand) fans += if (win.selfDrawn) GbFan.FULLY_CONCEALED else GbFan.CONCEALED_HAND
        if (win.lastTile) fans += if (win.selfDrawn) GbFan.LAST_TILE_DRAW else GbFan.LAST_TILE_CLAIM
        if (win.kongReplacement && win.selfDrawn) fans += GbFan.OUT_WITH_REPLACEMENT
        if (win.robKong) fans += GbFan.ROBBING_THE_KONG
        if (win.lastOfKind) fans += GbFan.LAST_OF_KIND
    }

    /** Fan that depend only on which tiles the whole hand holds. */
    private fun suits(all: List<Tile>, fans: MutableList<GbFan>) {
        val numbered = all.filter { it.suit.numbered }
        val suits = numbered.map { it.suit }.toSet()
        val honours = all.any { it.isHonour }
        val winds = all.any { it.suit == TileSuit.WINDS }
        val dragons = all.any { it.suit == TileSuit.DRAGONS }
        when {
            numbered.isEmpty() -> fans += GbFan.ALL_HONOURS
            suits.size == 1 && !honours -> fans += GbFan.FULL_FLUSH
            suits.size == 1 -> fans += GbFan.HALF_FLUSH
            suits.size == 2 -> fans += GbFan.ONE_VOIDED_SUIT
        }
        if (suits.size == 3 && winds && dragons) fans += GbFan.ALL_TYPES
        if (!honours) {
            fans += GbFan.NO_HONOURS
            val ranks = numbered.map { it.rank }
            if (ranks.all { it in 2..8 }) fans += GbFan.ALL_SIMPLES
            when {
                ranks.all { it >= 7 } -> fans += GbFan.UPPER_TILES
                ranks.all { it in 4..6 } -> fans += GbFan.MIDDLE_TILES
                ranks.all { it <= 3 } -> fans += GbFan.LOWER_TILES
                ranks.all { it >= 6 } -> fans += GbFan.UPPER_FOUR
                ranks.all { it <= 4 } -> fans += GbFan.LOWER_FOUR
            }
        }
        if (all.all { it.plain in GREEN }) fans += GbFan.ALL_GREEN
        if (all.all { it.plain in REVERSIBLE }) fans += GbFan.REVERSIBLE_TILES
        if (all.all { it.isTerminalOrHonour }) {
            when {
                !honours -> fans += GbFan.ALL_TERMINALS
                numbered.isNotEmpty() -> fans += GbFan.ALL_TERMINALS_AND_HONOURS
            }
        }
    }

    private fun sevenPairs(win: GbWin, c: IntArray): List<GbFan> {
        val fans = ArrayList<GbFan>()
        val pairs = c.indices.filter { c[it] > 0 }.map { Tile.of(it) }
        val shifted = pairs.size == 7 && pairs.map { it.suit }.toSet().size == 1 && pairs.first().suit.numbered &&
            pairs.zipWithNext().all { (a, b) -> b.id == a.id + 1 }
        fans += if (shifted) GbFan.SEVEN_SHIFTED_PAIRS else GbFan.SEVEN_PAIRS
        suits(win.concealed, fans)
        repeat(c.count { it == 4 }) { fans += GbFan.TILE_HOG }
        situational(win, fans, concealedHand = true)
        return finish(win, fans)
    }

    private fun special(win: GbWin, shapeFans: List<GbFan>): List<GbFan> {
        val fans = ArrayList(shapeFans)
        suits(win.concealed, fans)
        situational(win, fans, concealedHand = true)
        return finish(win, fans)
    }

    /** The fan of four sets and a pair, or with [knitted] a knitted straight, a set and a pair. */
    private fun standard(win: GbWin, sets: List<GSet>, pair: Tile, wait: Wait?, knitted: Boolean = false): List<GbFan> {
        val fans = ArrayList<GbFan>()
        val all = win.concealed + win.melds.flatMap { it.tiles }
        if (knitted) fans += GbFan.KNITTED_STRAIGHT
        suits(all, fans)
        val pungs = sets.filter { !it.run }
        val runs = sets.filter { it.run }
        val exposed = win.melds.count { it.kind != MeldKind.CONCEALED_KONG }

        // Nine gates: 1112345678999 waiting on any tile of the suit, all concealed.
        if (win.melds.isEmpty() && !knitted) {
            val before = Tiles.counts(Tiles.minusKind(win.concealed, win.tile, 1))
            val suit = win.tile.suit
            if (suit.numbered && before.sum() == 13 && (1..9).all { r -> before[Tile.of(suit, r).id] == if (r == 1 || r == 9) 3 else 1 }) {
                fans += GbFan.NINE_GATES
            }
        }

        // Whole hand shapes.
        if (!knitted && pungs.size == 4) fans += GbFan.ALL_PUNGS
        if (runs.size == 4 && pair.suit.numbered) fans += GbFan.ALL_CHOWS
        if (knitted && runs.size == 1 && pair.suit.numbered) fans += GbFan.ALL_CHOWS
        if (!knitted) {
            if (sets.all { s -> s.tiles.any { it.isTerminalOrHonour } } && pair.isTerminalOrHonour) fans += GbFan.OUTSIDE_HAND
            if (sets.all { s -> s.tiles.any { it.suit.numbered && it.rank == 5 } } && pair.suit.numbered && pair.rank == 5) fans += GbFan.ALL_FIVES
            if (pungs.size == 4 && pungs.all { it.tile.suit.numbered && it.tile.rank % 2 == 0 } && pair.suit.numbered && pair.rank % 2 == 0) {
                fans += GbFan.ALL_EVEN_PUNGS
            }
        }

        // Dragons and winds.
        val dragonPungs = pungs.count { it.tile.suit == TileSuit.DRAGONS }
        when {
            dragonPungs == 3 -> fans += GbFan.BIG_THREE_DRAGONS
            dragonPungs == 2 && pair.suit == TileSuit.DRAGONS -> fans += GbFan.LITTLE_THREE_DRAGONS
            dragonPungs == 2 -> fans += GbFan.TWO_DRAGON_PUNGS
            dragonPungs == 1 -> fans += GbFan.DRAGON_PUNG
        }
        val windPungs = pungs.filter { it.tile.suit == TileSuit.WINDS }
        when {
            windPungs.size == 4 -> fans += GbFan.BIG_FOUR_WINDS
            windPungs.size == 3 && pair.suit == TileSuit.WINDS -> fans += GbFan.LITTLE_FOUR_WINDS
            windPungs.size == 3 -> fans += GbFan.BIG_THREE_WINDS
        }
        val prevalent = windPungs.any { it.tile.rank - 1 == win.prevalentWind }
        val seat = windPungs.any { it.tile.rank - 1 == win.seatWind }
        if (prevalent) fans += GbFan.PREVALENT_WIND
        if (seat) fans += GbFan.SEAT_WIND
        // A pung of terminals or honours, unless a fan above already counts it.
        var yaojiu = pungs.count { it.tile.isTerminal }
        if (windPungs.size < 3) {
            yaojiu += windPungs.count { it.tile.rank - 1 != win.prevalentWind && it.tile.rank - 1 != win.seatWind }
        }
        if (GbFan.NINE_GATES in fans) yaojiu--
        repeat(yaojiu.coerceAtLeast(0)) { fans += GbFan.PUNG_OF_TERMINALS_OR_HONOURS }

        // Kongs and concealed pungs.
        val melded = sets.count { it.meld == MeldKind.EXPOSED_KONG || it.meld == MeldKind.ADDED_KONG }
        val concealedKongs = sets.count { it.meld == MeldKind.CONCEALED_KONG }
        when (melded + concealedKongs) {
            4 -> fans += GbFan.FOUR_KONGS
            3 -> fans += GbFan.THREE_KONGS
            2 -> when (concealedKongs) {
                2 -> fans += GbFan.TWO_CONCEALED_KONGS
                0 -> fans += GbFan.TWO_MELDED_KONGS
                else -> {
                    fans += GbFan.CONCEALED_KONG
                    fans += GbFan.MELDED_KONG
                }
            }
            1 -> fans += if (concealedKongs == 1) GbFan.CONCEALED_KONG else GbFan.MELDED_KONG
        }
        when (pungs.count { it.concealedPung }) {
            4 -> fans += GbFan.FOUR_CONCEALED_PUNGS
            3 -> fans += GbFan.THREE_CONCEALED_PUNGS
            2 -> fans += GbFan.TWO_CONCEALED_PUNGS
        }

        // Tile hog: all four of a kind in the hand, not as a kong.
        val counts = Tiles.counts(all)
        repeat(counts.indices.count { id -> counts[id] == 4 && sets.none { it.kong && it.tile.id == id } }) { fans += GbFan.TILE_HOG }

        // Sets that go together.
        fans += runFans(runs.map { it.tile }, pair)
        fans += pungFans(pungs.filter { it.tile.suit.numbered }.map { it.tile })

        if (exposed == 4 && !win.selfDrawn) fans += GbFan.MELDED_HAND
        when (wait) {
            Wait.EDGE -> fans += GbFan.EDGE_WAIT
            Wait.CLOSED -> fans += GbFan.CLOSED_WAIT
            Wait.SINGLE -> fans += GbFan.SINGLE_WAIT
            else -> {}
        }
        situational(win, fans, concealedHand = exposed == 0)
        return finish(win, fans)
    }

    /** Drops what bigger fan include, counts a hand with nothing else as a chicken hand, and adds the flowers. */
    private fun finish(win: GbWin, found: List<GbFan>): List<GbFan> {
        val fans = found.toMutableList()
        for (fan in found.distinct().sortedBy { it.ordinal }) {
            if (fan !in fans) continue
            val excluded = fan.excludes
            fans.removeAll { it in excluded }
        }
        if (fans.isEmpty()) fans += GbFan.CHICKEN_HAND
        repeat(win.flowers) { fans += GbFan.FLOWER_TILES }
        return fans
    }

    // ---- Sets that go together -----------------------------------------------------------------

    /**
     * The fan runs make together. A pattern of three or four runs counts first; a run left
     * over may then go with one of them once, and runs that make no pattern go together in
     * pairs, each pair once, no run twice in the same fan, never round a circle (the rules'
     * 套算一次 and 不得相同). The best way counts.
     */
    private fun runFans(runs: List<Tile>, pair: Tile): List<GbFan> {
        if (runs.size == 4) {
            four(runs, pair)?.let { return listOf(it) }
        }
        return combine(runs, ::threeRuns, ::twoRuns)
    }

    private fun pungFans(pungs: List<Tile>): List<GbFan> {
        if (pungs.size == 4) {
            val s = pungs.sortedBy { it.id }
            if (s.map { it.suit }.toSet().size == 1 && s.zipWithNext().all { (a, b) -> b.rank == a.rank + 1 }) return listOf(GbFan.FOUR_PURE_SHIFTED_PUNGS)
        }
        return combine(pungs, ::threePungs, ::twoPungs)
    }

    private fun four(runs: List<Tile>, pair: Tile): GbFan? {
        val s = runs.sortedBy { it.id }
        val oneSuit = s.map { it.suit }.toSet().size == 1
        if (oneSuit && s.all { it == s[0] }) return GbFan.QUADRUPLE_CHOW
        if (oneSuit && pair.suit == s[0].suit && pair.rank == 5 && s.map { it.rank } == listOf(1, 1, 7, 7)) return GbFan.PURE_TERMINAL_CHOWS
        if (oneSuit) {
            val d = s[1].rank - s[0].rank
            if ((d == 1 || d == 2) && s.zipWithNext().all { (a, b) -> b.rank - a.rank == d }) return GbFan.FOUR_PURE_SHIFTED_CHOWS
        }
        // Three-suited terminal chows: 123 and 789 in two suits, the pair of fives in the third.
        val bySuit = s.groupBy { it.suit }
        if (bySuit.size == 2 && bySuit.values.all { r -> r.map { it.rank }.sorted() == listOf(1, 7) } &&
            pair.suit.numbered && pair.suit !in bySuit.keys && pair.rank == 5
        ) return GbFan.THREE_SUITED_TERMINAL_CHOWS
        return null
    }

    private fun threeRuns(a: Tile, b: Tile, c: Tile): GbFan? {
        val s = listOf(a, b, c).sortedBy { it.rank }
        val suits = s.map { it.suit }.toSet().size
        val ranks = s.map { it.rank }
        return when {
            suits == 1 && ranks[0] == ranks[1] && ranks[1] == ranks[2] -> GbFan.PURE_TRIPLE_CHOW
            suits == 1 && ranks == listOf(1, 4, 7) -> GbFan.PURE_STRAIGHT
            suits == 1 && ((ranks[1] - ranks[0] == 1 && ranks[2] - ranks[1] == 1) || (ranks[1] - ranks[0] == 2 && ranks[2] - ranks[1] == 2)) -> GbFan.PURE_SHIFTED_CHOWS
            suits == 3 && ranks == listOf(1, 4, 7) -> GbFan.MIXED_STRAIGHT
            suits == 3 && ranks[0] == ranks[1] && ranks[1] == ranks[2] -> GbFan.MIXED_TRIPLE_CHOW
            suits == 3 && ranks[1] - ranks[0] == 1 && ranks[2] - ranks[1] == 1 -> GbFan.MIXED_SHIFTED_CHOWS
            else -> null
        }
    }

    private fun twoRuns(a: Tile, b: Tile): GbFan? {
        val (lo, hi) = if (a.rank <= b.rank) a to b else b to a
        return when {
            a.suit == b.suit && a.rank == b.rank -> GbFan.PURE_DOUBLE_CHOW
            a.suit != b.suit && a.rank == b.rank -> GbFan.MIXED_DOUBLE_CHOW
            a.suit == b.suit && hi.rank - lo.rank == 3 -> GbFan.SHORT_STRAIGHT
            a.suit == b.suit && lo.rank == 1 && hi.rank == 7 -> GbFan.TWO_TERMINAL_CHOWS
            else -> null
        }
    }

    private fun threePungs(a: Tile, b: Tile, c: Tile): GbFan? {
        val s = listOf(a, b, c).sortedBy { it.rank }
        val suits = s.map { it.suit }.toSet().size
        val ranks = s.map { it.rank }
        val shifted = ranks[1] - ranks[0] == 1 && ranks[2] - ranks[1] == 1
        return when {
            suits == 3 && ranks[0] == ranks[2] -> GbFan.TRIPLE_PUNG
            suits == 1 && shifted -> GbFan.PURE_SHIFTED_PUNGS
            suits == 3 && shifted -> GbFan.MIXED_SHIFTED_PUNGS
            else -> null
        }
    }

    private fun twoPungs(a: Tile, b: Tile): GbFan? = if (a.suit != b.suit && a.rank == b.rank) GbFan.DOUBLE_PUNG else null

    private fun combine(items: List<Tile>, three: (Tile, Tile, Tile) -> GbFan?, two: (Tile, Tile) -> GbFan?): List<GbFan> {
        val n = items.size
        var best: List<GbFan> = pairsOnly(items.indices.toList(), items, two, emptySet())
        if (n >= 3) {
            for (i in 0 until n) for (j in i + 1 until n) for (k in j + 1 until n) {
                val fan = three(items[i], items[j], items[k]) ?: continue
                val fans = arrayListOf(fan)
                val rest = (0 until n) - setOf(i, j, k)
                for (r in rest) {
                    // The run left over goes with one of the three once, in a fan the pattern does not include.
                    val extra = listOf(i, j, k).mapNotNull { two(items[r], items[it]) }.filter { it !in fan.excludes }.maxByOrNull { it.fan }
                    if (extra != null) fans += extra
                }
                if (fans.sumOf { it.fan } > best.sumOf { it.fan }) best = fans
            }
        }
        return best
    }

    /** The best set of pairs among [indexes]: no circle, and no item in two pairs of the same fan. */
    private fun pairsOnly(indexes: List<Int>, items: List<Tile>, two: (Tile, Tile) -> GbFan?, excluded: Set<GbFan>): List<GbFan> {
        val edges = ArrayList<Triple<Int, Int, GbFan>>()
        for (a in indexes.indices) for (b in a + 1 until indexes.size) {
            val fan = two(items[indexes[a]], items[indexes[b]]) ?: continue
            if (fan !in excluded) edges += Triple(indexes[a], indexes[b], fan)
        }
        var best: List<GbFan> = emptyList()
        for (mask in 1 until (1 shl edges.size)) {
            val chosen = edges.filterIndexed { i, _ -> mask and (1 shl i) != 0 }
            if (chosen.size >= items.size) continue
            // No circle: a forest over the items.
            val parent = IntArray(items.size) { it }
            fun root(x: Int): Int = if (parent[x] == x) x else root(parent[x])
            var ok = true
            for ((a, b, _) in chosen) {
                val ra = root(a)
                val rb = root(b)
                if (ra == rb) { ok = false; break }
                parent[ra] = rb
            }
            if (!ok) continue
            // No item in two pairs of the same fan.
            val uses = chosen.flatMap { (a, b, f) -> listOf(a to f, b to f) }
            if (uses.size != uses.toSet().size) continue
            val fans = chosen.map { it.third }
            if (fans.sumOf { it.fan } > best.sumOf { it.fan }) best = fans
        }
        return best
    }
}
