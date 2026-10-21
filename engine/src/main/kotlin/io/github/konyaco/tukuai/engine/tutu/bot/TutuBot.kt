package io.github.konyaco.tukuai.engine.tutu.bot

import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.tutu.TutuAction
import io.github.konyaco.tukuai.engine.tutu.TutuCard
import io.github.konyaco.tukuai.engine.tutu.TutuColor
import io.github.konyaco.tukuai.engine.tutu.TutuEventKind
import io.github.konyaco.tukuai.engine.tutu.TutuKind
import io.github.konyaco.tukuai.engine.tutu.TutuView
import kotlin.random.Random

/**
 * Computer player for Tutu cards.
 *
 * - EASY plays any card that fits and names a colour at random.
 * - NORMAL stays in the colour it holds most of, keeps its wild cards for when nothing
 *   else fits, and hits a player about to go out with snowballs, shovels and lightning.
 * - HARD also remembers which colours each player drew rather than play, steers the game
 *   into a colour the next player lacks, and plays a cactus or TNT while the others still
 *   have many cards to draw.
 *
 * It only reads the [TutuView] for its own seat, so it never sees hidden cards or fuses.
 */
class TutuBot(private val level: BotLevel, seed: Long) : Bot<TutuView, TutuAction> {
    private val random = Random(seed)

    override fun act(view: TutuView): TutuAction {
        val seat = requireNotNull(view.seat) { "A bot needs a seat" }
        val options = view.hand.filter(view::playable)
        val drawn = view.drawn
        if (drawn != null) return if (drawn in options) play(view, seat, drawn) else TutuAction.Pass
        if (options.isEmpty()) return TutuAction.Draw
        val card = when (level) {
            BotLevel.EASY -> options.random(random)
            else -> options.maxBy { score(view, seat, it) + random.nextDouble() }
        }
        return play(view, seat, card)
    }

    private fun play(view: TutuView, seat: Seat, card: TutuCard): TutuAction.Play {
        val color = if (card.face.kind.namesColor) chooseColor(view, seat, card) else null
        return TutuAction.Play(card, color)
    }

    /** Higher is better. */
    private fun score(view: TutuView, seat: Seat, card: TutuCard): Double {
        val face = card.face
        val rest = view.hand - card
        val next = view.next(seat)
        val threat = view.handCounts[next] <= 2
        var score = 0.0
        if (face.isWild) {
            // Wild cards fit anything, so they are worth more kept, unless they finish the hand.
            score -= when (face.kind) {
                TutuKind.EGG -> 12
                else -> 18
            }
            if (rest.size <= 1) score += 40
            if (level == BotLevel.HARD && face.kind != TutuKind.EGG) {
                // A trap or a bomb hurts someone else more often than not while the others hold a lot.
                val others = view.handCounts.withIndex().filter { it.index != seat }.map { it.value }
                if (others.min() >= 4 && view.drawCount > 20) score += 8
            }
        } else {
            score += 10 + rest.count { it.face.color == face.color } * 3
        }
        score += when (face.kind) {
            TutuKind.SNOWBALL, TutuKind.SHOVEL -> if (threat) 45 else 2
            TutuKind.LIGHTNING -> if (threat) 50 else 6
            TutuKind.TRIDENT -> {
                val previous = (seat - view.direction).mod(view.players)
                if (threat && (view.players == 2 || view.handCounts[previous] > 2)) 35 else 0
            }
            else -> 0
        }
        if (level == BotLevel.HARD && !face.isWild && face.color in lacking(view, next)) score += 12
        return score
    }

    private fun chooseColor(view: TutuView, seat: Seat, card: TutuCard): TutuColor {
        val rest = view.hand - card
        if (level == BotLevel.EASY) return TutuColor.entries.random(random)
        val counts = TutuColor.entries.associateWith { color -> rest.count { it.face.color == color } }
        val lacking = if (level == BotLevel.HARD) lacking(view, view.next(seat)) else emptySet()
        return TutuColor.entries.maxBy { color ->
            counts.getValue(color) * 10 + (if (color in lacking) 6 else 0) + random.nextInt(3)
        }
    }

    /**
     * Colours [seat] seems not to hold: it drew instead of playing while that colour was to
     * be followed, and has not played one since.
     */
    private fun lacking(view: TutuView, seat: Seat): Set<TutuColor> {
        val lacking = HashSet<TutuColor>()
        val plays = view.log.count { it.kind == TutuEventKind.PLAY }
        var color: TutuColor? = if (plays == 0) view.color else null
        for (event in view.log) {
            when (event.kind) {
                TutuEventKind.PLAY -> {
                    val face = event.face ?: event.card!!.face
                    color = event.color ?: face.color
                    if (event.seat == seat && !face.isWild) lacking -= face.color!!
                }
                TutuEventKind.DRAW -> if (event.seat == seat && !event.forced) color?.let { lacking += it }
                else -> {}
            }
        }
        return lacking
    }
}
