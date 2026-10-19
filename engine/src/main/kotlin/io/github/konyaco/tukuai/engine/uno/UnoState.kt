package io.github.konyaco.tukuai.engine.uno

import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.Seat

sealed interface UnoAction {
    /**
     * Plays [card]. A wild card needs the [color] to follow. Set [uno] when this leaves
     * one card in hand; forgetting it costs [UnoRules.unoPenalty] cards.
     */
    data class Play(val card: UnoCard, val color: UnoColor? = null, val uno: Boolean = false) : UnoAction

    /** Draws a card, or every card piled up by draw twos and wild draw fours. */
    data object Draw : UnoAction

    /** Keeps the card just drawn instead of playing it. */
    data object Pass : UnoAction
}

enum class UnoEventKind {
    /** [UnoEvent.card] was played; for a wild card [UnoEvent.color] is the colour named. */
    PLAY,

    /** [UnoEvent.count] cards were drawn: by choice, or forced by a draw card when [UnoEvent.forced]. */
    DRAW,

    /** Kept the card just drawn. */
    PASS,

    /** Lost the turn to a skip, a draw card, or a reverse between two players. */
    SKIPPED,

    /** Called UNO while playing the second last card. */
    UNO,

    /** Forgot to call UNO and drew [UnoEvent.count] cards for it. */
    PENALTY,

    /** The discard pile, all but its top card, was shuffled into a new draw pile. */
    RESHUFFLE,
}

/** Something everyone at the table saw happen. Drawn cards stay hidden: only how many. */
data class UnoEvent(
    val seat: Seat,
    val kind: UnoEventKind,
    val card: UnoCard? = null,
    val color: UnoColor? = null,
    val count: Int = 0,
    val forced: Boolean = false,
)

/** How the hand ended, shown on the result screen. */
data class UnoSettlement(
    val winner: Seat,
    /** What the cards left in each hand are worth; the winner's is 0. */
    val handPoints: List<Int>,
    val scores: List<Int>,
)

data class UnoState(
    val rules: UnoRules,
    val seed: Long,
    /** The top card is the last one. */
    val drawPile: List<UnoCard>,
    /** The top card is the last one. */
    val discard: List<UnoCard>,
    val hands: List<List<UnoCard>>,
    /** The colour to follow: the top card's, or the one named with a wild card. */
    val color: UnoColor,
    val turn: Seat,
    /** +1 goes to the next seat number, -1 back after a reverse. */
    val direction: Int,
    /** With [UnoRules.stacking]: cards piled up for the seat to act to draw or pass on. */
    val pendingDraw: Int,
    /** The card the seat to act just drew and may still play. */
    val drawn: UnoCard?,
    val reshuffles: Int,
    /** Turns in a row in which nobody played or drew anything, see [Uno.STALL_TURNS]. */
    val idleTurns: Int,
    val log: List<UnoEvent>,
    val settlement: UnoSettlement?,
) {
    val top: UnoCard get() = discard.last()

    val result: GameResult?
        get() = settlement?.let { GameResult(it.scores, setOf(it.winner)) }
}
