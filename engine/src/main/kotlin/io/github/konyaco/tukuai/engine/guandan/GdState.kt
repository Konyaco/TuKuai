package io.github.konyaco.tukuai.engine.guandan

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.Seat

enum class GdPhase {
    /** Whoever got a tribute gives a card back to whoever paid it. */
    RETURN,
    PLAYING,

    /** A round of a longer game is over; its winner deals the next one. */
    ROUND_OVER,
    FINISHED,
}

sealed interface GdAction {
    data class PlayCards(val cards: List<PlayingCard>) : GdAction

    data object Pass : GdAction

    /** Gives back a card for the tribute this seat received. */
    data class Return(val card: PlayingCard) : GdAction

    /** Deals the next round, once everyone has seen how the last one went. */
    data object Next : GdAction
}

/** A play lying on the table: the one the others must beat. */
data class GdTablePlay(val seat: Seat, val cards: List<PlayingCard>, val pattern: GdPattern)

enum class GdEventKind {
    PLAY,
    PASS,

    /** [GdEvent.seat] played their last card; [GdEvent.other] is their place, 0 for first. */
    OUT,

    /** Everyone passed on the last play of a player who is out, so their partner leads. */
    PARTNER_LEADS,

    /** [GdEvent.seat] paid [GdEvent.cards] to [GdEvent.other]. */
    TRIBUTE,

    /** [GdEvent.seat] gave [GdEvent.cards] back to [GdEvent.other]. */
    RETURN,

    /** The side that would pay holds both big jokers, so nobody pays. */
    NO_TRIBUTE,
}

data class GdEvent(
    val seat: Seat,
    val kind: GdEventKind,
    val cards: List<PlayingCard> = emptyList(),
    val pattern: GdPattern? = null,
    val other: Int = -1,
)

/** One tribute before a round: [from] paid [card] to [to], who gave [returned] back. */
data class GdTribute(val from: Seat, val to: Seat, val card: PlayingCard, val returned: PlayingCard? = null)

/** How a finished round went. */
data class GdRound(
    /** The level played, as a place on [GdValue.LADDER]. */
    val level: Int,
    /** The team whose level it was, or -1 for the first round, which both teams start at 2. */
    val levelTeam: Int,
    /** All four seats, first out first. */
    val order: List<Seat>,
    /** How many levels the winners went up: 3, 2 or 1 with the partner second, third or last. */
    val gain: Int,
    val levelsAfter: List<Int>,
) {
    val winnerTeam: Int get() = Guandan.team(order[0])
}

data class GdState(
    val rules: GdRules,
    val seed: Long,
    /** Which round this is, from 0. */
    val round: Int,
    /** Each team's level as a place on [GdValue.LADDER]; team 0 is seats 0 and 2. */
    val levels: List<Int>,
    /** The team whose level this round is played at, -1 in the first round. */
    val levelTeam: Int,
    /** The level being played, as a place on [GdValue.LADDER]. */
    val level: Int,
    val phase: GdPhase,
    val hands: List<List<PlayingCard>>,
    val turn: Seat,
    /** The play to beat, or null when [turn] leads. */
    val lead: GdTablePlay?,
    /** Passes since [lead] was played. */
    val passes: Int,
    /** Seats that have played out their hand this round, first first. */
    val order: List<Seat>,
    val tributes: List<GdTribute>,
    /** Who leads once the cards are given back. */
    val leader: Seat,
    /** What happened this round. */
    val log: List<GdEvent>,
    val rounds: List<GdRound>,
    /** Points so far, by seat. */
    val scores: List<Int>,
    /** The winning team's seats, once the game is over. */
    val winners: Set<Seat>?,
) {
    val levelRank: Rank get() = GdValue.LADDER[level]

    val finished: Boolean get() = phase == GdPhase.FINISHED

    val result: GameResult? get() = winners?.let { GameResult(scores, it) }
}
