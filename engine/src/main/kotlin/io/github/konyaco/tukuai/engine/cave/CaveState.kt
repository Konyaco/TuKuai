package io.github.konyaco.tukuai.engine.cave

import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.Seat

/** What a player inside the cave chooses once a card has turned up; everyone inside chooses at once. */
sealed interface CaveAction {
    /** Stays in for the next card. */
    data object Deeper : CaveAction

    /** Goes back out with what they carry, and their share of the emeralds left on the path. */
    data object Back : CaveAction
}

/** A card turned up this round, with the emeralds still lying on it after it was shared out. */
data class PathCard(val card: CaveCard, val left: Int = 0)

enum class CaveEventKind {
    /** Round [CaveEvent.round] began, with everyone at the mouth of the cave. */
    ROUND,

    /**
     * [CaveEvent.card] turned up. For a vein, the [CaveEvent.count] players inside each got
     * [CaveEvent.amount] emeralds of it, and the rest stayed on the card.
     */
    REVEAL,

    /**
     * [CaveEvent.seat] went back out and put [CaveEvent.amount] emeralds in their chest,
     * [CaveEvent.count] of them picked up from the path on the way.
     */
    BACK,

    /** [CaveEvent.card] was the second of its hazard: the round ends for the [CaveEvent.count] players inside. */
    COLLAPSE,

    /** [CaveEvent.seat] was inside when it ended, and dropped the [CaveEvent.amount] emeralds they carried. */
    LOST,

    /** The deck ran out, and everyone inside came back out safely; [CaveEvent.count] of them. */
    EMPTY,
}

data class CaveEvent(
    val kind: CaveEventKind,
    val round: Int,
    val seat: Seat = -1,
    val card: CaveCard? = null,
    val amount: Int = 0,
    val count: Int = 0,
)

data class CaveState(
    val rules: CaveRules,
    val seed: Long,
    val round: Int,
    /** The cards of this round still face down, the next one first. Nobody sees their order. */
    val deck: List<CaveCard>,
    /** One hazard card taken out of the game for each round it ended, see [CaveDeck.deck]. */
    val removed: List<Hazard>,
    val path: List<PathCard>,
    val inside: List<Boolean>,
    /** What each player inside chose for the card on top of the path; null until they choose. */
    val choices: List<CaveAction?>,
    /** The emeralds each player carries this round, lost if the round ends with them inside. */
    val pack: List<Int>,
    /** The emeralds each player brought back out, theirs for the rest of the game. */
    val chest: List<Int>,
    val finished: Boolean,
    val log: List<CaveEvent>,
) {
    val players: Int get() = inside.size

    /** The players inside who still have to choose, in seat order. */
    val deciding: List<Seat> get() = if (finished) emptyList() else inside.indices.filter { inside[it] && choices[it] == null }

    /** The players with the most emeralds win, and each of them a point from each of the others. */
    val result: GameResult?
        get() {
            if (!finished) return null
            return CaveScoring.result(chest)
        }
}

object CaveScoring {
    /**
     * The richest win; ties share the win. Each winner gets a point from each player who did
     * not win, so the points add up to nothing.
     */
    fun result(chest: List<Int>): GameResult {
        val best = chest.max()
        val winners = chest.indices.filter { chest[it] == best }.toSet()
        val losers = chest.size - winners.size
        val scores = chest.indices.map { if (it in winners) losers else -winners.size }
        return GameResult(scores, winners)
    }
}
