package io.github.konyaco.tukuai.engine.mahjong.sichuan.bot

import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.mahjong.ClaimKind
import io.github.konyaco.tukuai.engine.mahjong.MjHands
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScAction
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScPhase
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScScoring
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScView
import io.github.konyaco.tukuai.engine.mahjong.sichuan.SichuanMahjong
import io.github.konyaco.tukuai.engine.mahjong.sichuan.WinContext
import kotlin.random.Random

/**
 * A computer at the Sichuan mahjong table. It always wins when it can and always gets rid of
 * its void suit first. The easy one discards loose tiles and claims on a whim; the normal one
 * keeps the hand closest to ready with the most tiles left that help it, and only claims what
 * does not set it back; the hard one also steers for a full flush or all pungs, and when
 * someone looks close to winning it throws tiles they cannot want.
 */
class ScBot(private val level: BotLevel, seed: Long) : Bot<ScView, ScAction> {
    private val random = Random(seed)

    override fun act(view: ScView): ScAction = when (view.phase) {
        ScPhase.SWAP -> ScAction.Swap(swapTiles(view.hand))
        ScPhase.VOID -> ScAction.ChooseVoid(voidSuit(view.hand))
        ScPhase.PLAY -> turn(view)
        ScPhase.CLAIM, ScPhase.ROB -> claim(view)
        ScPhase.OVER -> throw IllegalStateException("The hand is over")
    }

    // ---- Before play ---------------------------------------------------------------------------

    /** How much a tile is worth keeping: copies of it and neighbours within two in its suit. */
    private fun usefulness(hand: List<Tile>, tile: Tile): Int {
        val counts = Tiles.counts(hand)
        var score = (counts[tile.id] - 1) * 3
        for (d in -2..2) {
            if (d == 0) continue
            val rank = tile.rank + d
            if (rank in 1..tile.suit.ranks) score += counts[Tile.of(tile.suit, rank).id] * (if (d in -1..1) 2 else 1)
        }
        // Middle tiles join more runs than the ends.
        if (tile.rank in 3..7) score += 1
        return score
    }

    /** How good a suit is to keep: its tiles and how well they fit together. */
    private fun suitStrength(hand: List<Tile>, suit: TileSuit): Int {
        val tiles = hand.filter { it.suit == suit }
        return tiles.size * 4 + tiles.sumOf { usefulness(tiles, it) }
    }

    /** Three tiles from the weakest suit that has three, the least useful of it. */
    fun swapTiles(hand: List<Tile>): List<Tile> {
        val suits = SichuanMahjong.SUITS.filter { s -> hand.count { it.suit == s } >= 3 }
        val suit = if (level == BotLevel.EASY) {
            suits.minBy { s -> hand.count { it.suit == s } }
        } else {
            suits.minBy { suitStrength(hand, it) }
        }
        val tiles = hand.filter { it.suit == suit }
        if (level == BotLevel.EASY) return tiles.shuffled(random).take(3)
        val picked = ArrayList<Tile>()
        var left = tiles
        repeat(3) {
            val worst = left.minWith(compareBy<Tile> { usefulness(left, it) }.thenBy { it.id })
            picked += worst
            left = Tiles.minus(left, listOf(worst))
        }
        return picked
    }

    /** The suit with the fewest tiles, the weakest of those when several tie. */
    fun voidSuit(hand: List<Tile>): TileSuit =
        SichuanMahjong.SUITS.minWith(compareBy<TileSuit> { s -> hand.count { it.suit == s } }.thenBy { suitStrength(hand, it) })

    // ---- Reading the hand ----------------------------------------------------------------------

    private fun sets(view: ScView) = 4 - view.myMelds.size

    /** Shanten of [tiles], leaving the void suit out; it has to go anyway. */
    private fun shanten(tiles: List<Tile>, sets: Int, void: TileSuit?): Int =
        MjHands.shanten(Tiles.counts(tiles.filter { it.suit != void }), sets, sevenPairs = true, fourAsTwoPairs = true)

    /** How many unseen tiles would bring [tiles] (a hand after its discard) a step nearer. */
    private fun accepted(view: ScView, tiles: List<Tile>, sets: Int, void: TileSuit?, now: Int): Int {
        var total = 0
        for (suit in SichuanMahjong.SUITS) {
            if (suit == void) continue
            for (tile in suit.tiles) {
                val left = 4 - view.seen(tile)
                if (left <= 0) continue
                if (shanten(tiles + tile, sets, void) < now) total += left
            }
        }
        return total
    }

    // ---- A turn --------------------------------------------------------------------------------

    private fun turn(view: ScView): ScAction {
        if (view.canWin) return ScAction.Win
        val void = view.myVoid
        val sets = sets(view)
        for (tile in view.kongs) {
            if (level == BotLevel.EASY) return ScAction.Kong(tile)
            val held = view.hand.count { it == tile }
            val concealed = held == 4
            val before = shanten(view.hand, sets, void)
            val after = shanten(view.hand.filter { it != tile }, if (concealed) sets - 1 else sets, void)
            // A kong pays at once and draws a free tile; take it unless it costs a step.
            if (after <= before) return ScAction.Kong(tile)
        }
        return ScAction.Discard(discard(view))
    }

    /** The tile to discard: always of the void suit while it holds any. */
    fun discard(view: ScView): Tile {
        val void = view.myVoid
        val options = SichuanMahjong.discardable(view.hand, void)
        if (options.all { it.suit == void }) {
            // Void tiles first; the one others can least use, as more of it is out.
            return if (level == BotLevel.EASY) options.random(random) else options.maxBy { view.seen(it) * 10 - it.id % 9 }
        }
        if (level == BotLevel.EASY) return options.minWith(compareBy<Tile> { usefulness(view.hand, it) }.thenBy { random.nextInt(4) })
        val sets = sets(view)
        val scored = options.map { tile ->
            val rest = Tiles.minus(view.hand, listOf(tile))
            val sh = shanten(rest, sets, void)
            Triple(tile, sh, accepted(view, rest, sets, void, sh))
        }
        val best = scored.minOf { it.second }
        var choices = scored.filter { it.second == best }
        if (level == BotLevel.HARD) {
            defend(view, best, scored)?.let { return it }
            // Ready: wait on what wins the most, counting how many of each tile are left.
            if (best == 0) return choices.maxWith(compareBy<Triple<Tile, Int, Int>> { readyValue(view, Tiles.minus(view.hand, listOf(it.first))) }.thenBy { it.third }).first
            // One away: look a draw ahead, to the ready hands each tile that helps would bring.
            if (best == 1) return choices.maxWith(compareBy<Triple<Tile, Int, Int>> { nearReadyValue(view, Tiles.minus(view.hand, listOf(it.first))) }.thenBy { it.third }).first
            val aim = aim(view)
            return choices.maxWith(
                compareBy<Triple<Tile, Int, Int>> { it.third + aimBonus(aim, view, it.first) }.thenBy { -usefulness(view.hand, it.first) },
            ).first
        }
        choices = choices.sortedWith(compareByDescending<Triple<Tile, Int, Int>> { it.third }.thenBy { usefulness(view.hand, it.first) })
        return choices.first().first
    }

    /** What a ready hand is worth waiting on: each tile it wins on, times how many are left, times what it would score. */
    private fun readyValue(view: ScView, rest: List<Tile>): Int =
        ScScoring.waits(rest, view.myMelds, view.myVoid).sumOf { tile ->
            val left = (4 - view.seen(tile) - (rest.count { it == tile } - view.hand.count { it == tile })).coerceAtLeast(0)
            left * view.rules.points(ScScoring.total(view.rules, ScScoring.fans(view.rules, rest + tile, view.myMelds, WinContext.PLAIN)))
        }

    /**
     * What a hand one tile from ready is worth: for each tile that would make it ready, how
     * many are left times the best ready hand it allows after the next discard.
     */
    private fun nearReadyValue(view: ScView, rest: List<Tile>): Int {
        val void = view.myVoid
        var total = 0
        for (suit in SichuanMahjong.SUITS) {
            if (suit == void) continue
            for (tile in suit.tiles) {
                val left = 4 - view.seen(tile)
                if (left <= 0) continue
                val drawn = rest + tile
                if (shanten(drawn, sets(view), void) > 0) continue
                val best = SichuanMahjong.discardable(drawn, void).maxOf { readyValue(view, Tiles.minus(drawn, listOf(it))) }
                total += left * best
            }
        }
        return total
    }

    /** What the hard computer steers for: a full flush in one suit, all pungs, or nothing in particular. */
    private sealed interface Aim {
        data class Flush(val suit: TileSuit) : Aim
        data object Pungs : Aim
    }

    private fun aim(view: ScView): Aim? {
        val void = view.myVoid
        val all = view.hand + view.myMelds.flatMap { it.tiles }
        val kept = SichuanMahjong.SUITS.filter { it != void }
        val top = kept.maxBy { s -> all.count { it.suit == s } }
        val inTop = all.count { it.suit == top }
        if (inTop >= 10 && view.myMelds.all { it.tile.suit == top }) return Aim.Flush(top)
        val counts = Tiles.counts(view.hand)
        val pairs = counts.count { it >= 2 }
        if (pairs + view.myMelds.size >= 4 && counts.count { it >= 3 } + view.myMelds.size >= 1) return Aim.Pungs
        return null
    }

    private fun aimBonus(aim: Aim?, view: ScView, tile: Tile): Int = when (aim) {
        is Aim.Flush -> if (tile.suit != aim.suit) 3 else 0
        Aim.Pungs -> if (view.hand.count { it == tile } == 1) 2 else 0
        null -> 0
    }

    /**
     * When another player looks close to winning (three melds, or two with the wall half
     * gone, or the wall nearly out) and this hand is still far off, throws the safest tile
     * that costs the hand no more than a step: one of a suit they are void in, one they threw
     * themselves, or one mostly out already. Null when there is no need.
     */
    private fun defend(view: ScView, shanten: Int, scored: List<Triple<Tile, Int, Int>>): Tile? {
        val me = view.seat ?: return null
        val threats = view.handCounts.indices.filter { seat ->
            val melds = view.melds[seat].size
            seat != me && !view.won(seat) && (melds >= 3 || (melds >= 2 && view.wallLeft < 30) || view.wallLeft < 10)
        }
        if (threats.isEmpty() || shanten < 2) return null
        fun safety(tile: Tile): Int = threats.sumOf { seat ->
            when {
                view.voids[seat] == tile.suit -> 10
                view.discards[seat].contains(tile) -> 6
                else -> view.seen(tile)
            }
        }
        val affordable = scored.filter { it.second <= shanten + (if (shanten >= 3) 1 else 0) }
        return affordable.maxWith(compareBy<Triple<Tile, Int, Int>> { safety(it.first) }.thenBy { -it.second }.thenBy { it.third }).first
    }

    // ---- Answering a discard -------------------------------------------------------------------

    private fun claim(view: ScView): ScAction {
        val tile = view.claimTile ?: return ScAction.Pass
        val options = view.options
        if (ClaimKind.WIN in options) return ScAction.Win
        if (level == BotLevel.EASY) {
            if (ClaimKind.KONG in options) return ScAction.Kong(tile)
            if (ClaimKind.PUNG in options && random.nextBoolean()) return ScAction.Pung
            return ScAction.Pass
        }
        val void = view.myVoid
        val sets = sets(view)
        val now = shanten(view.hand, sets, void)
        val sevenPairsPath = view.myMelds.isEmpty() &&
            MjHands.sevenPairsShanten(Tiles.counts(view.hand.filter { it.suit != void }), true) <=
            MjHands.standardShanten(Tiles.counts(view.hand.filter { it.suit != void }), sets)
        if (sevenPairsPath) return ScAction.Pass
        if (ClaimKind.KONG in options) {
            val after = shanten(view.hand.filter { it != tile }, sets - 1, void)
            if (after <= now) return ScAction.Kong(tile)
        }
        if (ClaimKind.PUNG in options) {
            val rest = Tiles.minus(view.hand, listOf(tile, tile))
            // The best hand left after the discard that follows the pung.
            val after = SichuanMahjong.discardable(rest, void).minOf { shanten(Tiles.minus(rest, listOf(it)), sets - 1, void) }
            if (after < now) return ScAction.Pung
            if (level == BotLevel.HARD && after == now) {
                // As near as before: worth it when the hand then waits on as many tiles, or steers where it aims.
                val aim = aim(view)
                if (aim == Aim.Pungs || (aim is Aim.Flush && tile.suit == aim.suit)) return ScAction.Pung
                val kept = accepted(view, view.hand, sets, void, now)
                val bestAfter = SichuanMahjong.discardable(rest, void).map { Tiles.minus(rest, listOf(it)) }
                    .filter { shanten(it, sets - 1, void) == now }.maxOfOrNull { accepted(view, it, sets - 1, void, now) } ?: 0
                if (bestAfter >= kept) return ScAction.Pung
            }
        }
        return ScAction.Pass
    }
}
