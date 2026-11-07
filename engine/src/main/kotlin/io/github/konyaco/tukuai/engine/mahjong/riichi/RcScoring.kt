package io.github.konyaco.tukuai.engine.mahjong.riichi

import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.MjHands
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles

/**
 * The yaku of riichi mahjong. [closed] is the han with no open melds, [open] with some, 0 when
 * the yaku needs a closed hand; a yakuman counts 13 han and no other yaku.
 */
enum class RcYaku(val closed: Int, val open: Int, val yakuman: Boolean = false) {
    RIICHI(1, 0),
    DOUBLE_RIICHI(2, 0),
    IPPATSU(1, 0),
    MENZEN_TSUMO(1, 0),
    PINFU(1, 0),
    TANYAO(1, 1),
    IIPEIKOU(1, 0),
    HAKU(1, 1),
    HATSU(1, 1),
    CHUN(1, 1),
    SEAT_WIND(1, 1),
    ROUND_WIND(1, 1),
    HAITEI(1, 1),
    HOUTEI(1, 1),
    RINSHAN(1, 1),
    CHANKAN(1, 1),
    CHIITOITSU(2, 0),
    CHANTA(2, 1),
    ITTSU(2, 1),
    SANSHOKU(2, 1),
    SANSHOKU_DOUKOU(2, 2),
    SANKANTSU(2, 2),
    TOITOI(2, 2),
    SANANKOU(2, 2),
    SHOUSANGEN(2, 2),
    HONROUTOU(2, 2),
    RYANPEIKOU(3, 0),
    JUNCHAN(3, 2),
    HONITSU(3, 2),
    CHINITSU(6, 5),
    NAGASHI_MANGAN(5, 5),

    KOKUSHI(13, 0, true),
    SUUANKOU(13, 0, true),
    DAISANGEN(13, 13, true),
    SHOUSUUSHII(13, 13, true),
    DAISUUSHII(13, 13, true),
    TSUUIISOU(13, 13, true),
    RYUUIISOU(13, 13, true),
    CHINROUTOU(13, 13, true),
    SUUKANTSU(13, 13, true),
    CHUUREN(13, 0, true),
    TENHOU(13, 0, true),
    CHIIHOU(13, 0, true);

    fun han(menzen: Boolean): Int = if (menzen) closed else open

    val key: String get() = "tukuai.riichi.yaku.${name.lowercase()}"
}

/**
 * A hand that may win, and how: [concealed] holds the winning [tile]. Winds count from 0 for
 * east. [doraIndicators] and [uraIndicators] are the tiles turned up, and the ones under
 * them, which only count for a riichi hand.
 */
data class RcWin(
    val concealed: List<Tile>,
    val melds: List<Meld>,
    val tile: Tile,
    val tsumo: Boolean,
    val seatWind: Int,
    val roundWind: Int,
    val riichi: Boolean = false,
    val doubleRiichi: Boolean = false,
    val ippatsu: Boolean = false,
    val haitei: Boolean = false,
    val houtei: Boolean = false,
    val rinshan: Boolean = false,
    val chankan: Boolean = false,
    val tenhou: Boolean = false,
    val chiihou: Boolean = false,
    val doraIndicators: List<Tile> = emptyList(),
    val uraIndicators: List<Tile> = emptyList(),
    val kuitan: Boolean = true,
) {
    val dealer: Boolean get() = seatWind == 0
}

/**
 * What a winning hand scores: its [yaku] with the han each counts, dora, red fives and ura
 * dora, its fu, and [base], the basic points that payments are made from.
 */
data class RcScore(
    val yaku: List<Pair<RcYaku, Int>>,
    val dora: Int,
    val aka: Int,
    val ura: Int,
    val fu: Int,
    val yakuman: Int,
) {
    val han: Int get() = if (yakuman > 0) 13 * yakuman else yaku.sumOf { it.second } + dora + aka + ura

    /** The limit the hand reached, if any. */
    val limit: RcLimit
        get() = when {
            yakuman > 0 -> RcLimit.YAKUMAN
            han >= 13 -> RcLimit.KAZOE_YAKUMAN
            han >= 11 -> RcLimit.SANBAIMAN
            han >= 8 -> RcLimit.BAIMAN
            han >= 6 -> RcLimit.HANEMAN
            han >= 5 || fu * (1 shl (han + 2)) >= 2000 -> RcLimit.MANGAN
            else -> RcLimit.NONE
        }

    val base: Int
        get() = when (limit) {
            RcLimit.YAKUMAN -> 8000 * yakuman
            RcLimit.KAZOE_YAKUMAN -> 8000
            RcLimit.SANBAIMAN -> 6000
            RcLimit.BAIMAN -> 4000
            RcLimit.HANEMAN -> 3000
            RcLimit.MANGAN -> 2000
            RcLimit.NONE -> fu * (1 shl (han + 2))
        }

    /** Points a ron is worth, before honba. */
    fun ron(dealer: Boolean): Int = up(base * if (dealer) 6 else 4)

    /** What each player pays on a tsumo, before honba: the dealer's share, then everyone else's. */
    fun tsumo(dealer: Boolean): Pair<Int, Int> = if (dealer) up(base * 2) to up(base * 2) else up(base * 2) to up(base)

    private fun up(points: Int): Int = (points + 99) / 100 * 100
}

enum class RcLimit { NONE, MANGAN, HANEMAN, BAIMAN, SANBAIMAN, KAZOE_YAKUMAN, YAKUMAN }

/**
 * Reading and scoring riichi hands: four sets and a pair, seven pairs, or thirteen orphans.
 * Every way of reading the hand and of placing the winning tile in it is scored, and the
 * highest counts.
 */
object RcScoring {
    private val ORPHANS: List<Tile> = Tiles.parse("19m 19p 19s 1234w 123d")
    private val GREEN: Set<Tile> = Tiles.parse("23468s 2d").toSet()

    /** The tile an indicator makes dora: the next in its suit, round from 9 to 1, north to east and white, green, red. */
    fun doraOf(indicator: Tile): Tile = when (indicator.suit) {
        TileSuit.WINDS -> Tile.of(TileSuit.WINDS, indicator.rank % 4 + 1)
        TileSuit.DRAGONS -> Tile.of(TileSuit.DRAGONS, if (indicator.rank == 1) 3 else indicator.rank - 1)
        else -> Tile.of(indicator.suit, indicator.rank % indicator.suit.ranks + 1)
    }

    fun isThirteenOrphans(c: IntArray): Boolean = c.sum() == 14 && ORPHANS.all { c[it.id] >= 1 } && c.indices.all { c[it] == 0 || Tile.of(it) in ORPHANS }

    fun isWinShape(counts: IntArray, melds: Int): Boolean =
        MjHands.isStandard(counts, 4 - melds) || (melds == 0 && (MjHands.isSevenPairs(counts, fourAsTwoPairs = false) || isThirteenOrphans(counts)))

    /** The tiles a hand one short of complete waits on, in any shape. */
    fun waits(concealed: List<Tile>, melds: List<Meld>): List<Tile> {
        if (concealed.size % 3 != 1) return emptyList()
        val c = Tiles.counts(concealed)
        val held = Tiles.counts(concealed + melds.flatMap { it.tiles })
        val out = ArrayList<Tile>()
        for (suit in TileSuit.HAND) for (tile in suit.tiles) {
            if (held[tile.id] >= 4) continue
            c[tile.id]++
            if (isWinShape(c, melds.size)) out += tile
            c[tile.id]--
        }
        return out
    }

    fun shanten(concealed: List<Tile>, melds: Int): Int {
        val c = Tiles.counts(concealed)
        var best = MjHands.standardShanten(c, 4 - melds)
        if (melds == 0) {
            best = minOf(best, MjHands.sevenPairsShanten(c, fourAsTwoPairs = false))
            val kinds = ORPHANS.count { c[it.id] > 0 }
            best = minOf(best, 13 - kinds - if (ORPHANS.any { c[it.id] >= 2 }) 1 else 0)
        }
        return best
    }

    private enum class Wait { RYANMEN, KANCHAN, PENCHAN, TANKI, SHANPON }

    private data class RSet(val run: Boolean, val tile: Tile, val concealed: Boolean, val kong: Boolean) {
        val tiles: List<Tile> get() = if (run) listOf(tile, tile.next!!, tile.next!!.next!!) else List(3) { tile }
        val terminalOrHonour: Boolean get() = tiles.any { it.isTerminalOrHonour }
    }

    private fun valuePair(win: RcWin, pair: Tile): Int {
        var fu = 0
        if (pair.suit == TileSuit.DRAGONS) fu += 2
        if (pair.suit == TileSuit.WINDS && pair.rank - 1 == win.seatWind) fu += 2
        if (pair.suit == TileSuit.WINDS && pair.rank - 1 == win.roundWind) fu += 2
        return fu
    }

    /** The best score of [win], or null when it is not a winning hand. A score with no yaku has an empty [RcScore.yaku] and cannot win. */
    fun score(win: RcWin): RcScore? {
        val c = Tiles.counts(win.concealed)
        val sets = 4 - win.melds.size
        if (c.sum() != sets * 3 + 2) return null
        val menzen = win.melds.all { it.kind == MeldKind.CONCEALED_KONG }
        val candidates = ArrayList<RcScore>()
        for (shape in MjHands.shapes(c, sets)) {
            for ((rsets, wait) in placements(win, shape)) candidates += standard(win, rsets, shape.pair, wait, menzen)
        }
        if (win.melds.isEmpty() && MjHands.isSevenPairs(c, fourAsTwoPairs = false)) candidates += sevenPairs(win)
        if (win.melds.isEmpty() && isThirteenOrphans(c)) candidates += finish(win, listOf(RcYaku.KOKUSHI), 0, true)
        return candidates.maxWithOrNull(compareBy<RcScore> { it.yaku.isNotEmpty() }.thenBy { it.base }.thenBy { it.han }.thenBy { it.fu })
    }

    private fun placements(win: RcWin, shape: MjHands.Shape): List<Pair<List<RSet>, Wait>> {
        val tile = win.tile.plain
        val meldSets = win.melds.map { m -> RSet(m.kind == MeldKind.CHOW, m.tile.plain, m.kind == MeldKind.CONCEALED_KONG, m.kind.isKong) }
        val out = ArrayList<Pair<List<RSet>, Wait>>()
        fun sets(openPung: Int): List<RSet> = meldSets + shape.groups.mapIndexed { i, g -> RSet(g.run, g.tile, !(i == openPung && !win.tsumo), false) }
        if (shape.pair == tile) out += sets(-1) to Wait.TANKI
        for ((i, g) in shape.groups.withIndex()) {
            if (g.run) {
                if (tile.suit != g.tile.suit) continue
                val offset = tile.rank - g.tile.rank
                val wait = when {
                    offset == 1 -> Wait.KANCHAN
                    offset == 0 && g.tile.rank == 7 -> Wait.PENCHAN
                    offset == 2 && g.tile.rank == 1 -> Wait.PENCHAN
                    offset == 0 || offset == 2 -> Wait.RYANMEN
                    else -> continue
                }
                out += sets(-1) to wait
            } else if (g.tile == tile) {
                out += sets(i) to Wait.SHANPON
            }
        }
        return out.distinct()
    }

    private fun sevenPairs(win: RcWin): RcScore {
        val all = win.concealed
        val yaku = ArrayList<RcYaku>()
        if (all.all { it.isHonour }) return finish(win, listOf(RcYaku.TSUUIISOU), 25, true)
        yaku += RcYaku.CHIITOITSU
        situational(win, yaku, true)
        if (all.none { it.isTerminalOrHonour }) yaku += RcYaku.TANYAO
        if (all.all { it.isTerminalOrHonour }) yaku += RcYaku.HONROUTOU
        flush(all, yaku)
        return finish(win, yaku, 25, true)
    }

    private fun situational(win: RcWin, yaku: MutableList<RcYaku>, menzen: Boolean) {
        if (win.doubleRiichi) yaku += RcYaku.DOUBLE_RIICHI else if (win.riichi) yaku += RcYaku.RIICHI
        if (win.ippatsu) yaku += RcYaku.IPPATSU
        if (win.tsumo && menzen) yaku += RcYaku.MENZEN_TSUMO
        if (win.haitei && win.tsumo && !win.rinshan) yaku += RcYaku.HAITEI
        if (win.houtei && !win.tsumo) yaku += RcYaku.HOUTEI
        if (win.rinshan) yaku += RcYaku.RINSHAN
        if (win.chankan) yaku += RcYaku.CHANKAN
    }

    private fun flush(all: List<Tile>, yaku: MutableList<RcYaku>) {
        val suits = all.filter { it.suit.numbered }.map { it.suit }.toSet()
        val honours = all.any { it.isHonour }
        if (suits.size == 1) yaku += if (honours) RcYaku.HONITSU else RcYaku.CHINITSU
    }

    private fun standard(win: RcWin, sets: List<RSet>, pair: Tile, wait: Wait, menzen: Boolean): RcScore {
        val all = win.concealed + win.melds.flatMap { it.tiles }
        val pungs = sets.filter { !it.run }
        val runs = sets.filter { it.run }
        val yakuman = ArrayList<RcYaku>()
        val concealedPungs = pungs.count { it.concealed }
        val dragons = pungs.count { it.tile.suit == TileSuit.DRAGONS }
        val winds = pungs.count { it.tile.suit == TileSuit.WINDS }
        if (concealedPungs == 4) yakuman += RcYaku.SUUANKOU
        if (dragons == 3) yakuman += RcYaku.DAISANGEN
        if (winds == 4) yakuman += RcYaku.DAISUUSHII else if (winds == 3 && pair.suit == TileSuit.WINDS) yakuman += RcYaku.SHOUSUUSHII
        if (all.all { it.isHonour }) yakuman += RcYaku.TSUUIISOU
        if (all.all { it.plain in GREEN }) yakuman += RcYaku.RYUUIISOU
        if (all.all { it.isTerminal }) yakuman += RcYaku.CHINROUTOU
        if (pungs.count { it.kong } == 4) yakuman += RcYaku.SUUKANTSU
        if (win.melds.isEmpty()) {
            val suit = win.tile.suit
            val c = Tiles.counts(win.concealed)
            if (suit.numbered && all.all { it.suit == suit } && (1..9).all { r -> c[Tile.of(suit, r).id] >= if (r == 1 || r == 9) 3 else 1 }) yakuman += RcYaku.CHUUREN
        }
        if (win.tenhou) yakuman += RcYaku.TENHOU
        if (win.chiihou) yakuman += RcYaku.CHIIHOU
        if (yakuman.isNotEmpty()) return finish(win, yakuman, 0, menzen)

        val yaku = ArrayList<RcYaku>()
        situational(win, yaku, menzen)
        val pinfuShape = menzen && runs.size == 4 && valuePair(win, pair) == 0 && wait == Wait.RYANMEN
        if (pinfuShape) yaku += RcYaku.PINFU
        if (all.none { it.isTerminalOrHonour } && (menzen || win.kuitan)) yaku += RcYaku.TANYAO
        if (menzen) {
            val same = runs.groupingBy { it.tile }.eachCount()
            val pairs = same.values.sumOf { it / 2 }
            if (pairs == 2) yaku += RcYaku.RYANPEIKOU else if (pairs == 1) yaku += RcYaku.IIPEIKOU
        }
        for (p in pungs) {
            when {
                p.tile.suit == TileSuit.DRAGONS -> yaku += when (p.tile.rank) { 1 -> RcYaku.CHUN; 2 -> RcYaku.HATSU; else -> RcYaku.HAKU }
                p.tile.suit == TileSuit.WINDS -> {
                    if (p.tile.rank - 1 == win.seatWind) yaku += RcYaku.SEAT_WIND
                    if (p.tile.rank - 1 == win.roundWind) yaku += RcYaku.ROUND_WIND
                }
            }
        }
        val outside = sets.all { it.terminalOrHonour } && pair.isTerminalOrHonour
        if (outside && runs.isNotEmpty()) yaku += if (all.any { it.isHonour }) RcYaku.CHANTA else RcYaku.JUNCHAN
        for (suit in TileSuit.NUMBERED) {
            if (listOf(1, 4, 7).all { r -> runs.any { it.tile.suit == suit && it.tile.rank == r } }) yaku += RcYaku.ITTSU
        }
        if (runs.groupBy { it.tile.rank }.values.any { g -> g.map { it.tile.suit }.toSet().size == 3 }) yaku += RcYaku.SANSHOKU
        if (pungs.filter { it.tile.suit.numbered }.groupBy { it.tile.rank }.values.any { g -> g.map { it.tile.suit }.toSet().size == 3 }) yaku += RcYaku.SANSHOKU_DOUKOU
        if (pungs.count { it.kong } == 3) yaku += RcYaku.SANKANTSU
        if (pungs.size == 4) yaku += RcYaku.TOITOI
        if (concealedPungs == 3) yaku += RcYaku.SANANKOU
        if (dragons == 2 && pair.suit == TileSuit.DRAGONS) yaku += RcYaku.SHOUSANGEN
        if (all.all { it.isTerminalOrHonour }) yaku += RcYaku.HONROUTOU
        flush(all, yaku)

        // Fu: 20, 10 for a closed ron, 2 for a tsumo but not with pinfu, and the sets, pair and wait.
        var fu = 20
        if (menzen && !win.tsumo) fu += 10
        if (win.tsumo && !pinfuShape) fu += 2
        for (p in pungs) {
            var f = if (p.tile.isTerminalOrHonour) 4 else 2
            if (p.concealed) f *= 2
            if (p.kong) f *= 4
            fu += f
        }
        fu += valuePair(win, pair)
        if (wait == Wait.KANCHAN || wait == Wait.PENCHAN || wait == Wait.TANKI) fu += 2
        fu = (fu + 9) / 10 * 10
        if (!menzen && fu == 20) fu = 30
        if (pinfuShape && win.tsumo) fu = 20
        return finish(win, yaku, fu, menzen)
    }

    /** Drops yaku a bigger one includes, then counts dora; a yakuman counts nothing else. */
    private fun finish(win: RcWin, found: List<RcYaku>, fu: Int, menzen: Boolean): RcScore {
        val yakuman = found.filter { it.yakuman && it.han(menzen) > 0 }
        if (yakuman.isNotEmpty()) return RcScore(yakuman.map { it to 13 }, 0, 0, 0, fu, yakuman.size)
        val yaku = found.toMutableList()
        if (RcYaku.DOUBLE_RIICHI in yaku) yaku.remove(RcYaku.RIICHI)
        if (RcYaku.RYANPEIKOU in yaku) yaku.remove(RcYaku.IIPEIKOU)
        if (RcYaku.CHINITSU in yaku) yaku.remove(RcYaku.HONITSU)
        if (RcYaku.HONROUTOU in yaku) yaku.remove(RcYaku.CHANTA)
        val counted = yaku.distinct().filter { it.han(menzen) > 0 }.map { it to it.han(menzen) }
        if (counted.isEmpty()) return RcScore(emptyList(), 0, 0, 0, fu, 0)
        val all = win.concealed + win.melds.flatMap { it.tiles }
        val dora = win.doraIndicators.sumOf { ind -> val d = doraOf(ind); all.count { it.id == d.id } }
        val aka = all.count { it.red }
        val ura = if (win.riichi || win.doubleRiichi) win.uraIndicators.sumOf { ind -> val d = doraOf(ind); all.count { it.id == d.id } } else 0
        return RcScore(counted, dora, aka, ura, fu, 0)
    }
}
