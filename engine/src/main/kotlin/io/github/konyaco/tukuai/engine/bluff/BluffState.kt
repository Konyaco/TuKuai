package io.github.konyaco.tukuai.engine.bluff

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.Seat

sealed interface BluffAction {
    /** Lays [cards] face down, saying they are all of the rank called for. */
    data class Play(val cards: List<PlayingCard>) : BluffAction

    /** Calls the last play a lie and turns it over. */
    data object Call : BluffAction

    /** Takes the penalty after losing a call: lights the creeper's fuse, or gives up a heart. */
    data object Light : BluffAction
}

enum class BluffPhase {
    /** Players lay cards down or call the last play a lie. */
    PLAY,

    /** A call was settled and [BluffState.reveal]'s loser has to take the penalty. */
    PENALTY,
    OVER,
}

/** The last play of the round so far, face down on the pile. */
data class BluffClaim(val seat: Seat, val cards: List<PlayingCard>)

/** A play turned over when it was called. */
data class BluffReveal(
    val caller: Seat,
    val claimer: Seat,
    val cards: List<PlayingCard>,
    /** Some card was not what was called for, so the claimer pays; otherwise the caller does. */
    val lied: Boolean,
) {
    val loser: Seat get() = if (lied) claimer else caller
}

enum class BluffEventKind {
    /** A round was dealt: [BluffEvent.rank] is called for and [BluffEvent.seat] plays first. */
    DEAL,
    PLAY,

    /** [BluffEvent.seat] called the play of [BluffEvent.other] a lie; [BluffEvent.flag] if it was one. */
    CALL,

    /** [BluffEvent.seat] took the penalty for the [BluffEvent.count]th time; [BluffEvent.flag] if it put them out. */
    LIGHT,
}

data class BluffEvent(
    val seat: Seat,
    val kind: BluffEventKind,
    /** For a play, the cards; in a view they are left out unless the viewer played them. */
    val cards: List<PlayingCard> = emptyList(),
    val count: Int = cards.size,
    val other: Seat = -1,
    val flag: Boolean = false,
    val rank: Rank? = null,
)

data class BluffState(
    val rules: BluffRules,
    val seed: Long,
    val round: Int,
    /** The rank every play this round claims to be. */
    val target: Rank,
    val hands: List<List<PlayingCard>>,
    /**
     * With [BluffPenalty.FUSE], on which lighting each seat's creeper blows; it is the same
     * as [BluffRules.chances] for everyone with hearts. Nobody may see it while play goes on.
     */
    val fuses: List<Int>,
    /** How many times each seat took the penalty. */
    val lit: List<Int>,
    val phase: BluffPhase,
    /** Who plays or calls next while [phase] is [BluffPhase.PLAY]. */
    val turn: Seat,
    val claim: BluffClaim?,
    /** Every card laid down this round, face down. */
    val pile: List<PlayingCard>,
    /** The last call, kept until the next one. */
    val reveal: BluffReveal?,
    val log: List<BluffEvent>,
) {
    fun isOut(seat: Seat): Boolean = lit[seat] >= fuses[seat]

    val alive: List<Seat> get() = hands.indices.filter { !isOut(it) }

    val finished: Boolean get() = phase == BluffPhase.OVER

    /** The last one standing wins one point from each of the others. */
    val result: GameResult?
        get() {
            if (!finished) return null
            val winner = alive.single()
            val scores = hands.indices.map { if (it == winner) hands.size - 1 else -1 }
            return GameResult(scores, setOf(winner))
        }
}
