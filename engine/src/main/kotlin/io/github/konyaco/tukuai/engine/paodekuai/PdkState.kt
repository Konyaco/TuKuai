package io.github.konyaco.tukuai.engine.paodekuai

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.Seat

sealed interface PdkAction {
    data class PlayCards(val cards: List<PlayingCard>) : PdkAction

    data object Pass : PdkAction
}

/** A play lying on the table: the one the next seats must beat. */
data class PdkTablePlay(val seat: Seat, val cards: List<PlayingCard>, val pattern: PdkPattern)

enum class PdkEventKind {
    PLAY,
    PASS,

    /** Everyone else passed on a bomb, so it won the round and collects its points. */
    BOMB_WON,
}

data class PdkEvent(
    val seat: Seat,
    val kind: PdkEventKind,
    val cards: List<PlayingCard> = emptyList(),
    val pattern: PdkPattern? = null,
)

/** How the final points were reached, shown on the result screen. */
data class PdkSettlement(
    val winner: Seat,
    val cardsLeft: List<Int>,
    /** Seats that never got to play a card, who pay double. */
    val shutOut: List<Boolean>,
    /** Bombs each seat won a round with. */
    val bombs: List<Int>,
    /** Points from the cards left in hand, and from bombs; [scores] is their sum. */
    val cardScores: List<Int>,
    val bombScores: List<Int>,
    val scores: List<Int>,
)

data class PdkState(
    val rules: PdkRules,
    val seed: Long,
    val hands: List<List<PlayingCard>>,
    /** Who held the 3 of spades and so led first. */
    val starter: Seat,
    val turn: Seat,
    val lead: PdkTablePlay?,
    val playCounts: List<Int>,
    /** Bombs each seat won a round with so far. */
    val bombs: List<Int>,
    val log: List<PdkEvent>,
    val settlement: PdkSettlement?,
) {
    val finished: Boolean get() = settlement != null

    /** The winner is whoever went out first, even when bombs leave them behind on points. */
    val result: GameResult?
        get() = settlement?.let { s -> GameResult(s.scores, setOf(s.winner)) }
}
