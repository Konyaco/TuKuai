package io.github.konyaco.tukuai.engine.uno.bot

import io.github.konyaco.tukuai.engine.core.Bot
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.uno.UnoAction
import io.github.konyaco.tukuai.engine.uno.UnoCard
import io.github.konyaco.tukuai.engine.uno.UnoColor
import io.github.konyaco.tukuai.engine.uno.UnoEventKind
import io.github.konyaco.tukuai.engine.uno.UnoKind
import io.github.konyaco.tukuai.engine.uno.UnoView
import kotlin.random.Random

/**
 * Computer player for UNO.
 *
 * - EASY plays any card that fits, names a colour at random and sometimes forgets to call UNO.
 * - NORMAL stays in the colour it holds most of, gets rid of costly cards first, keeps its
 *   wild cards for when nothing else fits, and stops a player about to go out.
 * - HARD also remembers which colours each player drew rather than play, and steers the
 *   game into a colour the next player lacks.
 *
 * It only reads the [UnoView] for its own seat, so it never sees hidden cards.
 */
class UnoBot(private val level: BotLevel, seed: Long) : Bot<UnoView, UnoAction> {
    private val random = Random(seed)

    override fun act(view: UnoView): UnoAction {
        val seat = requireNotNull(view.seat) { "A bot needs a seat" }
        val options = view.hand.filter(view::playable)
        val drawn = view.drawn
        if (drawn != null) return if (drawn in options) play(view, seat, drawn) else UnoAction.Pass
        if (options.isEmpty()) return UnoAction.Draw
        val card = when (level) {
            BotLevel.EASY -> options.random(random)
            else -> options.maxBy { score(view, seat, it) + random.nextDouble() }
        }
        return play(view, seat, card)
    }

    private fun play(view: UnoView, seat: Seat, card: UnoCard): UnoAction.Play {
        val color = if (card.face.isWild) chooseColor(view, seat, card) else null
        val uno = view.hand.size == 2 && !(level == BotLevel.EASY && random.nextInt(4) == 0)
        return UnoAction.Play(card, color, uno)
    }

    /** Higher is better. */
    private fun score(view: UnoView, seat: Seat, card: UnoCard): Double {
        val face = card.face
        val rest = view.hand - card
        val next = view.next(seat)
        val threat = view.handCounts[next] <= 2
        var score = 0.0
        if (face.isWild) {
            // Wild cards fit anything, so they are worth more kept, unless they finish the hand.
            score -= if (face.kind == UnoKind.WILD_DRAW_FOUR) 25 else 18
            if (rest.size <= 1) score += 40
        } else {
            score += 10 + rest.count { it.face.color == face.color } * 3
            score += face.points / 4.0
        }
        if (threat) {
            score += when (face.kind) {
                UnoKind.SKIP, UnoKind.DRAW_TWO, UnoKind.WILD_DRAW_FOUR -> 45
                UnoKind.REVERSE -> if (view.players > 2 && view.handCounts[(seat - view.direction).mod(view.players)] > 2) 35 else 0
                else -> 0
            }
        }
        if (level == BotLevel.HARD && !face.isWild && face.color in lacking(view, next)) score += 12
        return score
    }

    private fun chooseColor(view: UnoView, seat: Seat, card: UnoCard): UnoColor {
        val rest = view.hand - card
        if (level == BotLevel.EASY) return UnoColor.entries.random(random)
        val counts = UnoColor.entries.associateWith { color -> rest.count { it.face.color == color } }
        val lacking = if (level == BotLevel.HARD) lacking(view, view.next(seat)) else emptySet()
        return UnoColor.entries.maxBy { color ->
            counts.getValue(color) * 10 + (if (color in lacking) 6 else 0) + random.nextInt(3)
        }
    }

    /**
     * Colours [seat] seems not to hold: it drew instead of playing while that colour was to
     * be followed, and has not played one since.
     */
    private fun lacking(view: UnoView, seat: Seat): Set<UnoColor> {
        val lacking = HashSet<UnoColor>()
        val plays = view.log.count { it.kind == UnoEventKind.PLAY }
        var color: UnoColor? = if (plays == 0) view.color else null
        var pending = false
        for (event in view.log) {
            when (event.kind) {
                UnoEventKind.PLAY -> {
                    val face = event.card!!.face
                    color = event.color ?: face.color
                    pending = face.kind == UnoKind.DRAW_TWO || face.kind == UnoKind.WILD_DRAW_FOUR
                    if (event.seat == seat && !face.isWild) lacking -= face.color!!
                }
                UnoEventKind.DRAW -> {
                    if (event.seat == seat && !event.forced && !pending) color?.let { lacking += it }
                    pending = false
                }
                else -> {}
            }
        }
        return lacking
    }
}
