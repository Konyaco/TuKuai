package io.github.konyaco.tukuai.engine.tutu

import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.Seat

sealed interface TutuAction {
    /** Plays [card]. TNT and cacti need the [color] to follow. */
    data class Play(val card: TutuCard, val color: TutuColor? = null) : TutuAction

    /** Draws a card. */
    data object Draw : TutuAction

    /** Keeps the card just drawn instead of playing it. */
    data object Pass : TutuAction
}

enum class TutuEventKind {
    /**
     * [TutuEvent.card] was played. [TutuEvent.face] is what it counts as: an egg's is what
     * hatched. For TNT and cacti [TutuEvent.color] is the colour named.
     */
    PLAY,

    /** [TutuEvent.count] cards were drawn: by choice, or forced by a card when [TutuEvent.forced]. */
    DRAW,

    /** Kept the card just drawn. */
    PASS,

    /** Lost the turn to a snowball, a shovel, lightning, or a trident between two players. */
    SKIPPED,

    /** Drew a planted cactus and has to take [TutuEvent.count] more cards. */
    CACTUS,

    /** [TutuEvent.count] lit TNT blew up as this seat's turn began; four cards each. */
    BOOM,
}

/** Something everyone at the table saw happen. Drawn cards stay hidden: only how many. */
data class TutuEvent(
    val seat: Seat,
    val kind: TutuEventKind,
    val card: TutuCard? = null,
    val face: TutuFace? = null,
    val color: TutuColor? = null,
    val count: Int = 0,
    val forced: Boolean = false,
)

/** How the hand ended, shown on the result screen. */
data class TutuSettlement(
    /** One seat that went out, or everyone tied on fewest cards when the draw pile ran out. */
    val winners: Set<Seat>,
    /** The draw pile ran out before anyone went out. */
    val pileEmpty: Boolean,
    /** What the cards left in each hand are worth, [Tutu.CARD_POINTS] each; winners' count as 0. */
    val handPoints: List<Int>,
    val scores: List<Int>,
)

data class TutuState(
    val rules: TutuRules,
    val seed: Long,
    /** The top card is the last one. */
    val drawPile: List<TutuCard>,
    /** The top card is the last one. */
    val discard: List<TutuCard>,
    val hands: List<List<TutuCard>>,
    /** What the top of the discard pile counts as: its own face, or what an egg hatched into. */
    val top: TutuFace,
    /** The colour to follow: the top card's, or the one named with TNT or a cactus. */
    val color: TutuColor,
    val turn: Seat,
    /** +1 goes to the next seat number, -1 back after a trident. */
    val direction: Int,
    /** The card the seat to act just drew and may still play. */
    val drawn: TutuCard?,
    /**
     * Planted cacti, each as the number of cards below it in the draw pile. A cactus springs
     * on whoever draws while the pile is down to that many cards. Everyone can see them.
     */
    val cacti: List<Int>,
    /** Lit TNT, each as the number of turns left before it blows. Only the server knows these. */
    val fuses: List<Int>,
    /** How many random numbers the game has used since the deal, so replays roll the same ones. */
    val rolls: Int,
    val log: List<TutuEvent>,
    val settlement: TutuSettlement?,
) {
    val result: GameResult?
        get() = settlement?.let { GameResult(it.scores, it.winners) }
}
