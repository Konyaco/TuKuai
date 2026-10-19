package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.Seat

enum class DdzPhase { BIDDING, DOUBLING, PLAYING, FINISHED }

sealed interface DdzAction {
    /** Score bidding: 1 to 3 points, or 0 to pass. */
    data class Bid(val points: Int) : DdzAction

    /** Rob bidding: call the landlord (or rob it once someone has called), or decline. */
    data class Call(val yes: Boolean) : DdzAction

    data class Double(val yes: Boolean) : DdzAction

    data class PlayCards(val cards: List<PlayingCard>) : DdzAction

    data object Pass : DdzAction
}

/** A play lying on the table: the one the next seats must beat. */
data class TablePlay(val seat: Seat, val cards: List<PlayingCard>, val pattern: Pattern)

enum class DdzEventKind { BID, CALL, NO_CALL, ROB, NO_ROB, REDEAL, LANDLORD, DOUBLE, NO_DOUBLE, PLAY, PASS }

/**
 * Something everyone at the table saw happen. The list of these is what views,
 * the table display and the replay viewer read.
 *
 * @param value the points for [DdzEventKind.BID]; otherwise 0
 */
data class DdzEvent(
    val seat: Seat,
    val kind: DdzEventKind,
    val value: Int = 0,
    val cards: List<PlayingCard> = emptyList(),
    val pattern: Pattern? = null,
)

/** How the final points were reached, shown on the result screen. */
data class Settlement(
    val landlordWon: Boolean,
    val bidMultiplier: Int,
    val bombs: Int,
    val spring: Boolean,
    /** The multiplier before each seat's own doubling. */
    val multiplier: Int,
    val scores: List<Int>,
)

data class DdzState(
    val rules: DdzRules,
    val seed: Long,
    /** How many times the cards were redealt because nobody took the landlord. */
    val redeals: Int,
    val hands: List<List<PlayingCard>>,
    val kitty: List<PlayingCard>,
    val phase: DdzPhase,
    val turn: Seat,
    val firstBidder: Seat,
    /** Bidding actions taken in the current deal, in order. */
    val bids: List<DdzEvent>,
    val landlord: Seat?,
    /** Score mode: the winning bid. Rob mode: 2 to the number of robs. */
    val bidMultiplier: Int,
    val doubles: List<Boolean?>,
    val lead: TablePlay?,
    val bombs: Int,
    val playCounts: List<Int>,
    val log: List<DdzEvent>,
    val settlement: Settlement?,
) {
    val result: GameResult?
        get() = settlement?.let { s -> GameResult(s.scores, s.scores.indices.filter { s.scores[it] > 0 }.toSet()) }
}
