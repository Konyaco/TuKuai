package io.github.konyaco.tukuai.engine.mahjong.riichi.bot

import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcAction
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcEventKind
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcPhase
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcScoring
import io.github.konyaco.tukuai.engine.mahjong.riichi.RcView
import io.github.konyaco.tukuai.engine.mahjong.riichi.Riichi
import kotlin.random.Random

/**
 * A computer at the riichi table. It keeps its hand closest to ready with the most tiles
 * left that help, keeps dora and its own value honours, and declares riichi once ready with
 * a closed hand. It calls only for a yaku it can count on: a pung of a value honour, all
 * simples, or (the hard one) a flush. Once ready it only counts tiles that would really win.
 * The normal and hard ones fold when someone has declared riichi and their own hand is far
 * off, throwing the tiles that player cannot win on; the easy one neither folds nor plans.
 */
class RcBot(private val level: BotLevel, seed: Long) : Bot<RcView, RcAction> {
    private val random = Random(seed)

    override fun act(view: RcView): RcAction = when (view.phase) {
        RcPhase.HAND_OVER -> RcAction.Next
        RcPhase.PLAY -> turn(view)
        RcPhase.CLAIM, RcPhase.ROB -> claim(view)
        RcPhase.OVER -> throw IllegalStateException("The game is over")
    }

    // ---- Reading the hand ----------------------------------------------------------------------

    private fun shanten(concealed: List<Tile>, melds: List<Meld>) = RcScoring.shanten(concealed, melds.size)

    private fun doras(view: RcView): Set<Int> = view.doraIndicators.map { RcScoring.doraOf(it).id }.toSet()

    /** Whether [tile] is an honour this seat scores a pung of: dragons, its own wind and the round's. */
    private fun valueHonour(view: RcView, tile: Tile): Boolean {
        val me = view.seat ?: return false
        return tile.suit == TileSuit.DRAGONS || (tile.suit == TileSuit.WINDS && (tile.rank - 1 == view.seatWind(me) || tile.rank - 1 == view.round))
    }

    /** How much keeping a tile is worth besides shape: dora, red fives and value honours in pairs. */
    private fun keepValue(view: RcView, hand: List<Tile>, tile: Tile): Int {
        var v = 0
        if (tile.id in doras(view)) v += 3
        if (tile.red) v += 3
        if (valueHonour(view, tile) && hand.count { it.id == tile.id } >= 2) v += 2
        if (tile.suit.numbered && tile.rank in 3..7) v += 1
        return v
    }

    /** The waits of a ready hand that would win: with a yaku, and not of tiles this seat discarded. */
    private fun winningWaits(view: RcView, concealed: List<Tile>, melds: List<Meld>, riichi: Boolean): List<Tile> {
        val me = view.seat ?: return emptyList()
        val waits = RcScoring.waits(concealed, melds)
        if (waits.any { w -> view.discarded[me].any { it.id == w.id } }) return emptyList()
        val withMelds = view.copy(melds = view.melds.toMutableList().also { it[me] = melds })
        return waits.filter { w ->
            val ron = RcScoring.score(withMelds.winOn(w, concealed, tsumo = false, declaringRiichi = riichi))
            val draw = RcScoring.score(withMelds.winOn(w, concealed, tsumo = true, declaringRiichi = riichi))
            (ron != null && ron.yaku.isNotEmpty()) || (draw != null && draw.yaku.isNotEmpty())
        }
    }

    private fun accepted(view: RcView, concealed: List<Tile>, melds: List<Meld>, now: Int): Int {
        var total = 0
        for (suit in TileSuit.HAND) for (tile in suit.tiles) {
            val left = view.unseen(tile)
            if (left <= 0) continue
            if (shanten(concealed + tile, melds) < now) total += left
        }
        return total
    }

    /** How a hand of [concealed] stands: its shanten, a little worse when ready without a way to win, and the tiles that help. */
    private fun assess(view: RcView, concealed: List<Tile>, melds: List<Meld>, closed: Boolean): Pair<Double, Int> {
        val sh = shanten(concealed, melds)
        if (sh == 0) {
            val wins = winningWaits(view, concealed, melds, riichi = closed)
            val left = wins.sumOf { view.unseen(it) }
            return if (left > 0) 0.0 to left else 1.0 to 0
        }
        return sh.toDouble() to -1
    }

    // ---- Danger ---------------------------------------------------------------------------------

    /** For each player who declared riichi: the tiles safe against them, those they or anyone threw after their riichi. */
    private fun safeAgainst(view: RcView): Map<Seat, Set<Int>> {
        val me = view.seat ?: return emptyMap()
        val out = HashMap<Seat, MutableSet<Int>>()
        val declared = HashSet<Seat>()
        var pending: Seat? = null
        for (e in view.log) {
            when (e.kind) {
                RcEventKind.RIICHI -> pending = e.seat
                RcEventKind.DISCARD -> {
                    val t = e.tile ?: continue
                    for (s in declared) out.getOrPut(s) { HashSet() } += t.id
                    if (pending == e.seat) {
                        declared += e.seat
                        pending = null
                    }
                }
                else -> {}
            }
        }
        for (s in declared) {
            if (s == me) continue
            out.getOrPut(s) { HashSet() } += view.discarded[s].map { it.id }
        }
        return out.filterKeys { it != me }
    }

    /** How safe a tile is against the riichi players, higher safer. */
    private fun safety(view: RcView, tile: Tile, safe: Map<Seat, Set<Int>>): Int = safe.values.sumOf { set ->
        when {
            tile.id in set -> 100
            tile.isHonour -> 40 + 15 * view.seen(tile)
            tile.suit.numbered && suji(tile, set) -> 50
            tile.isTerminal -> 30
            else -> 10 - minOf(tile.rank - 1, 9 - tile.rank)
        }
    }

    /** Whether a numbered tile is three away from a safe tile on each side it could be waited from. */
    private fun suji(tile: Tile, safe: Set<Int>): Boolean {
        val lower = tile.rank - 3
        val upper = tile.rank + 3
        val l = lower < 1 || Tile.of(tile.suit, lower).id in safe
        val u = upper > 9 || Tile.of(tile.suit, upper).id in safe
        return (lower >= 1 || upper <= 9) && l && u
    }

    // ---- A turn --------------------------------------------------------------------------------

    private fun turn(view: RcView): RcAction {
        if (view.tsumo != null) return RcAction.Win
        if (view.canAbort) {
            val kinds = view.hand.filter { it.isTerminalOrHonour }.map { it.id }.distinct().size
            if (kinds in 9..10 || level == BotLevel.EASY) return RcAction.Abort
        }
        val me = view.seat!!
        if (view.riichi[me]) {
            view.kongs.firstOrNull()?.let { if (level != BotLevel.EASY) return RcAction.Kong(it) }
            return RcAction.Discard(view.drawn!!)
        }
        for (tile in view.kongs) {
            val closed = view.hand.count { it.id == tile.id } == 4
            val before = shanten(view.hand, view.myMelds)
            val melds = if (closed) view.myMelds + Meld(MeldKind.CONCEALED_KONG, tile) else view.myMelds
            val after = shanten(view.hand.filter { it.id != tile.id }, melds)
            if (after <= before && (closed || level == BotLevel.EASY)) return RcAction.Kong(tile)
        }
        val tile = discard(view)
        if (tile in view.riichiTiles) {
            val rest = Tiles.minus(view.hand, listOf(tile))
            // Ready with a closed hand: declare riichi, unless nothing could come to win on.
            if (winningWaits(view, rest, view.myMelds, riichi = true).sumOf { view.unseen(it) } > 0) return RcAction.Riichi(tile)
        }
        return RcAction.Discard(tile)
    }

    /** The tile to discard. */
    fun discard(view: RcView): Tile {
        val options = view.discardable.ifEmpty { view.hand.distinct() }
        if (options.size == 1) return options.first()
        if (level == BotLevel.EASY && random.nextInt(5) == 0) return options.random(random)
        val closed = view.menzen
        val scored = options.map { tile ->
            val rest = Tiles.minus(view.hand, listOf(tile))
            val (d, left) = assess(view, rest, view.myMelds, closed)
            Triple(tile, d, left)
        }
        val best = scored.minOf { it.second }
        if (level != BotLevel.EASY) {
            val safe = safeAgainst(view)
            val folding = safe.isNotEmpty() && (best >= 2.0 || (level == BotLevel.HARD && best >= 1.0 && view.liveLeft < 40))
            if (folding) return scored.maxWith(compareBy<Triple<Tile, Double, Int>> { safety(view, it.first, safe) }.thenBy { -it.second }).first
        }
        val choices = scored.filter { it.second <= best + 1e-9 }
        if (best == 0.0) return choices.maxWith(compareBy<Triple<Tile, Double, Int>> { it.third }.thenBy { -keepValue(view, view.hand, it.first) }).first
        return choices.maxWith(
            compareBy<Triple<Tile, Double, Int>> { accepted(view, Tiles.minus(view.hand, listOf(it.first)), view.myMelds, best.toInt()) * 4 - keepValue(view, view.hand, it.first) * 3 },
        ).first
    }

    // ---- Answering a discard -------------------------------------------------------------------

    /** A yaku an open hand could count on: a value honour among its pungs, all simples, or a flush of one suit. */
    private fun openYaku(view: RcView, concealed: List<Tile>, melds: List<Meld>): Boolean {
        val all = concealed + melds.flatMap { it.tiles }
        if (melds.any { it.kind != MeldKind.CHOW && valueHonour(view, it.tile) }) return true
        val counts = Tiles.counts(concealed)
        if ((TileSuit.WINDS.tiles + TileSuit.DRAGONS.tiles).any { valueHonour(view, it) && counts[it.id] >= 2 }) return true
        if (view.rules.kuitan && all.count { it.isTerminalOrHonour } <= 1 && melds.flatMap { it.tiles }.none { it.isTerminalOrHonour }) return true
        if (level == BotLevel.HARD) {
            val suits = all.filter { it.suit.numbered }.map { it.suit }.toSet()
            if (suits.size == 1 && melds.flatMap { it.tiles }.all { it.suit in suits || it.isHonour }) return true
        }
        return false
    }

    private fun claim(view: RcView): RcAction {
        val tile = view.claimTile ?: return RcAction.Pass
        val o = view.options
        if (o.win) return RcAction.Win
        if (view.phase == RcPhase.ROB) return RcAction.Pass
        val me = view.seat ?: return RcAction.Pass
        if (view.riichi.withIndex().any { it.value && it.index != me } && level != BotLevel.EASY) return RcAction.Pass
        val from = view.claimFrom ?: return RcAction.Pass
        val now = shanten(view.hand, view.myMelds)
        if (o.pung) {
            val rest = Tiles.minusKind(view.hand, tile, 2)
            val melds = view.myMelds + Meld(MeldKind.PUNG, tile.plain, from)
            val after = rest.distinct().minOf { shanten(Tiles.minus(rest, listOf(it)), melds) }
            val worth = if (level == BotLevel.EASY) random.nextBoolean() else (valueHonour(view, tile) || (after < now && openYaku(view, rest, melds)))
            if (worth && after <= now) return RcAction.Pung
        }
        if (level != BotLevel.EASY) {
            for (start in o.chows) {
                var rest = view.hand
                for (d in 0..2) {
                    val t = Tile.of(start.suit, start.rank + d)
                    if (t.id != tile.id) rest = Tiles.minusKind(rest, t, 1)
                }
                val melds = view.myMelds + Meld(MeldKind.CHOW, start, from)
                val banned = Riichi.swapBan(start, tile)
                val after = rest.distinct().filter { r -> banned.none { it.id == r.id } }.minOfOrNull { shanten(Tiles.minus(rest, listOf(it)), melds) } ?: continue
                if (after < now && now <= 2 && openYaku(view, rest, melds)) return RcAction.Chow(start)
            }
        }
        return RcAction.Pass
    }
}
