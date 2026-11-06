package io.github.konyaco.tukuai.engine.mahjong.guobiao.bot

import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.MjHands
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbAction
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbPhase
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbScoring
import io.github.konyaco.tukuai.engine.mahjong.guobiao.GbView
import io.github.konyaco.tukuai.engine.mahjong.guobiao.Guobiao
import kotlin.random.Random

/**
 * A computer at the Guobiao table. A hand needs 8 fan to win, so tile efficiency alone is
 * not enough: the computer measures its hand against a set of plans that each reach the 8
 * fan, such as a flush, all pungs, seven pairs or a straight, and keeps it close to the
 * nearest. Once ready it only counts tiles it could really win on. It always wins when it
 * can. The easy one sometimes discards carelessly and claims less wisely; the hard one also
 * plays safe when someone else looks close and it is far away.
 */
class GbBot(private val level: BotLevel, seed: Long) : Bot<GbView, GbAction> {
    private val random = Random(seed)

    override fun act(view: GbView): GbAction = when (view.phase) {
        GbPhase.HAND_OVER -> GbAction.Next
        GbPhase.PLAY -> turn(view)
        GbPhase.CLAIM, GbPhase.ROB -> claim(view)
        GbPhase.OVER -> throw IllegalStateException("The game is over")
    }

    // ---- Plans ---------------------------------------------------------------------------------

    /** How far a hand is from winning: about how many tiles it still needs, with a little more for plans less sure to reach 8 fan. */
    private class Plan(val distance: Double, val shanten: Int)

    private val numbered = TileSuit.NUMBERED

    /** Fixed sets a straight or the like needs: runs given by where they start. */
    private val fixedPlans: List<Pair<List<Tile>, Double>> by lazy {
        val out = ArrayList<Pair<List<Tile>, Double>>()
        for (s in numbered) out += listOf(1, 4, 7).map { Tile.of(s, it) } to 0.0
        for (a in numbered) for (b in numbered) for (c in numbered) {
            if (a == b || b == c || a == c) continue
            out += listOf(Tile.of(a, 1), Tile.of(b, 4), Tile.of(c, 7)) to 0.1
            for (k in 1..5) out += listOf(Tile.of(a, k), Tile.of(b, k + 1), Tile.of(c, k + 2)) to 0.6
        }
        for (k in 1..7) out += numbered.map { Tile.of(it, k) } to 0.1
        out.distinctBy { p -> p.first.map { it.id }.sorted() }
    }

    /** Shanten for a hand that must hold the runs starting at [starts], with [melds] laid down. */
    private fun fixedShanten(counts: IntArray, melds: List<Meld>, starts: List<Tile>): Int? {
        val needed = starts.toMutableList()
        var free = 0
        for (m in melds) {
            val match = needed.firstOrNull { m.kind == MeldKind.CHOW && it.id == m.tile.id }
            if (match != null) needed.remove(match) else free++
        }
        val sets = 4 - starts.size - free
        if (sets < 0) return null
        val rest = counts.copyOf()
        var missing = 0
        for (start in needed) for (d in 0..2) {
            val id = start.id + d
            if (rest[id] > 0) rest[id]-- else missing++
        }
        return missing + MjHands.standardShanten(rest, sets)
    }

    private fun filtered(counts: IntArray, keep: (Tile) -> Boolean): IntArray =
        IntArray(counts.size) { if (keep(Tile.of(it))) counts[it] else 0 }

    /** Shanten for all pungs and a pair, the melds all pungs or kongs. */
    private fun pungsShanten(counts: IntArray, melds: List<Meld>): Int? {
        if (melds.any { it.kind == MeldKind.CHOW }) return null
        val n = 4 - melds.size
        val pungs = minOf(n, counts.count { it >= 3 })
        val pairs = counts.count { it == 2 }
        var best = Int.MAX_VALUE
        for (eye in 0..minOf(1, pairs)) {
            val partials = minOf(pairs - eye, n - pungs)
            best = minOf(best, 2 * (n - pungs) - partials - eye)
        }
        return best
    }

    private fun windOrDragonValue(view: GbView, counts: IntArray): Double {
        val me = view.seat ?: 0
        val good = TileSuit.DRAGONS.tiles + Tile.of(TileSuit.WINDS, view.seatWind(me) + 1) + Tile.of(TileSuit.WINDS, view.prevalentWind + 1)
        val melded = view.myMelds.any { it.tile in good }
        return if (melded || good.any { counts[it.id] >= 2 }) 0.2 else 0.6
    }

    /** The nearest plan for the concealed tiles [concealed] with [melds] laid down. */
    private fun plan(view: GbView, concealed: List<Tile>, melds: List<Meld>): Plan {
        val counts = Tiles.counts(concealed)
        val sets = 4 - melds.size
        var best = Double.MAX_VALUE
        var bestShanten = 99
        fun consider(shanten: Int?, penalty: Double) {
            if (shanten == null) return
            val d = shanten + penalty
            if (d < best) {
                best = d
                bestShanten = shanten
            }
        }
        // Anything, counting on the fan of how it is won; the least sure.
        consider(MjHands.standardShanten(counts, sets), if (melds.none { it.kind != MeldKind.CONCEALED_KONG }) 0.9 else 1.3)
        if (melds.isEmpty()) {
            consider(MjHands.sevenPairsShanten(counts, fourAsTwoPairs = true), 0.0)
            if (level != BotLevel.EASY) {
                consider(GbScoring.honoursKnittedShanten(counts), 0.2)
                consider(GbScoring.knittedStraightShanten(counts, 4), 0.2)
                consider(GbScoring.thirteenShanten(counts), 0.0)
            }
        } else if (sets >= 3 && level != BotLevel.EASY) {
            consider(GbScoring.knittedStraightShanten(counts, sets), 0.2)
        }
        for (suit in numbered) {
            val meldSuits = melds.map { it.tile.suit }
            if (meldSuits.all { it == suit }) {
                val only = filtered(counts) { it.suit == suit }
                consider(MjHands.standardShanten(only, sets), 0.0)
                if (melds.isEmpty()) consider(MjHands.sevenPairsShanten(only, fourAsTwoPairs = true), 0.0)
            }
            if (meldSuits.all { it == suit || !it.numbered }) {
                val half = filtered(counts) { it.suit == suit || it.isHonour }
                consider(MjHands.standardShanten(half, sets), windOrDragonValue(view, counts))
            }
        }
        consider(pungsShanten(counts, melds), 0.3)
        if (level != BotLevel.EASY) for ((starts, penalty) in fixedPlans) consider(fixedShanten(counts, melds, starts), penalty)
        return Plan(best, bestShanten)
    }

    /**
     * How good the hand of [concealed] (13 tiles less three for each meld) is: its plan's
     * distance, and once ready, whether any wait really wins and how many such tiles are left.
     */
    private fun assess(view: GbView, concealed: List<Tile>, melds: List<Meld>): Pair<Double, Int> {
        val waits = GbScoring.waits(concealed, melds)
        if (waits.isNotEmpty()) {
            val winners = waits.filter { tile ->
                val discardWin = Guobiao.winning(view.rules, view.copy(melds = view.melds.withMine(view, melds)).winOn(tile, concealed, false))
                val drawWin = Guobiao.winning(view.rules, view.copy(melds = view.melds.withMine(view, melds)).winOn(tile, concealed, true))
                discardWin != null || drawWin != null
            }
            val left = winners.sumOf { view.unseen(it) }
            if (winners.isNotEmpty() && left > 0) return 0.0 to left
            return 1.4 to 0
        }
        val p = plan(view, concealed, melds)
        return p.distance to 0
    }

    private fun List<List<Meld>>.withMine(view: GbView, melds: List<Meld>): List<List<Meld>> {
        val me = view.seat ?: return this
        return toMutableList().also { it[me] = melds }
    }

    /** How many unseen tiles would bring [concealed] nearer its plan. */
    private fun acceptance(view: GbView, concealed: List<Tile>, melds: List<Meld>, now: Double): Int {
        var total = 0
        for (suit in TileSuit.HAND) for (tile in suit.tiles) {
            val left = view.unseen(tile)
            if (left <= 0) continue
            if (plan(view, concealed + tile, melds).distance < now) total += left
        }
        return total
    }

    // ---- A turn --------------------------------------------------------------------------------

    private fun turn(view: GbView): GbAction {
        if (view.selfDraw != null) return GbAction.Win
        val now = plan(view, view.hand, view.myMelds)
        for (tile in view.kongs) {
            if (level == BotLevel.EASY) return GbAction.Kong(tile)
            val concealed = view.hand.count { it.id == tile.id } == 4
            val melds = if (concealed) view.myMelds + Meld(MeldKind.CONCEALED_KONG, tile) else view.myMelds.map { if (it.kind == MeldKind.PUNG && it.tile.id == tile.id) it.copy(kind = MeldKind.ADDED_KONG) else it }
            val after = plan(view, view.hand.filter { it.id != tile.id }, melds)
            if (after.distance <= now.distance) return GbAction.Kong(tile)
        }
        return GbAction.Discard(discard(view))
    }

    /** The tile to discard. */
    fun discard(view: GbView): Tile {
        val options = view.hand.distinct()
        if (level == BotLevel.EASY && random.nextInt(4) == 0) {
            return options.minBy { usefulness(view.hand, it) * 10 + random.nextInt(10) }
        }
        val scored = options.map { tile ->
            val rest = Tiles.minus(view.hand, listOf(tile))
            val (distance, waiting) = assess(view, rest, view.myMelds)
            Triple(tile, distance, waiting)
        }
        val best = scored.minOf { it.second }
        if (level == BotLevel.HARD) defend(view, best, scored)?.let { return it }
        val choices = scored.filter { it.second <= best + 1e-9 }
        if (best == 0.0) return choices.maxWith(compareBy<Triple<Tile, Double, Int>> { it.third }.thenBy { -usefulness(view.hand, it.first) }).first
        return choices.maxWith(
            compareBy<Triple<Tile, Double, Int>> { acceptance(view, Tiles.minus(view.hand, listOf(it.first)), view.myMelds, it.second) }
                .thenBy { -usefulness(view.hand, it.first) },
        ).first
    }

    /** How much a tile is worth keeping: copies of it and neighbours within two in its suit. */
    private fun usefulness(hand: List<Tile>, tile: Tile): Int {
        val counts = Tiles.counts(hand)
        var score = (counts[tile.id] - 1) * 3
        if (tile.suit.numbered) {
            for (d in -2..2) {
                if (d == 0) continue
                val rank = tile.rank + d
                if (rank in 1..tile.suit.ranks) score += counts[Tile.of(tile.suit, rank).id] * (if (d in -1..1) 2 else 1)
            }
            if (tile.rank in 3..7) score += 1
        }
        return score
    }

    /**
     * When someone else looks close (three sets laid down, or the wall nearly out) and this
     * hand is far off, throws the safest tile that sets the hand back no more than a step:
     * one they or everyone threw already, or one mostly out.
     */
    private fun defend(view: GbView, distance: Double, scored: List<Triple<Tile, Double, Int>>): Tile? {
        val me = view.seat ?: return null
        val threats = view.handCounts.indices.filter { it != me && (view.melds[it].size >= 3 || view.wallLeft < 12) }
        if (threats.isEmpty() || distance < 2.0) return null
        fun safety(tile: Tile): Int = threats.sumOf { seat -> if (view.discards[seat].any { it.id == tile.id }) 10 else view.seen(tile) * 2 + if (tile.isHonour) 1 else 0 }
        return scored.filter { it.second <= distance + 1.0 }
            .maxWith(compareBy<Triple<Tile, Double, Int>> { safety(it.first) }.thenBy { -it.second }).first
    }

    // ---- Answering a discard -------------------------------------------------------------------

    private fun claim(view: GbView): GbAction {
        val tile = view.claimTile ?: return GbAction.Pass
        val o = view.options
        if (o.win) return GbAction.Win
        if (view.phase == GbPhase.ROB) return GbAction.Pass
        val now = assess(view, view.hand, view.myMelds).first
        val from = view.claimFrom ?: return GbAction.Pass
        if (o.kong) {
            val melds = view.myMelds + Meld(MeldKind.EXPOSED_KONG, tile, from)
            val after = assess(view, view.hand.filter { it.id != tile.id }, melds).first
            if (after <= now || level == BotLevel.EASY) return GbAction.Kong(tile)
        }
        var bestAction: GbAction = GbAction.Pass
        var bestAfter = now - (if (level == BotLevel.EASY) 0.0 else 0.25)
        if (o.pung) {
            val rest = Tiles.minusKind(view.hand, tile, 2)
            val after = afterDiscard(view, rest, view.myMelds + Meld(MeldKind.PUNG, tile, from))
            if (after < bestAfter) {
                bestAfter = after
                bestAction = GbAction.Pung
            }
        }
        for (start in o.chows) {
            var rest = view.hand
            for (d in 0..2) {
                val t = Tile.of(start.suit, start.rank + d)
                if (t.id != tile.id) rest = Tiles.minusKind(rest, t, 1)
            }
            val after = afterDiscard(view, rest, view.myMelds + Meld(MeldKind.CHOW, start, from))
            if (after < bestAfter) {
                bestAfter = after
                bestAction = GbAction.Chow(start)
            }
        }
        if (level == BotLevel.EASY && bestAction == GbAction.Pass && (o.pung || o.chows.isNotEmpty()) && random.nextInt(3) == 0) {
            return if (o.pung) GbAction.Pung else GbAction.Chow(o.chows.first())
        }
        return bestAction
    }

    /** The best the hand can be after a claim and the discard that follows it. */
    private fun afterDiscard(view: GbView, rest: List<Tile>, melds: List<Meld>): Double =
        rest.distinct().minOf { assess(view, Tiles.minus(rest, listOf(it)), melds).first }
}
