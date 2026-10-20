package io.github.konyaco.tukuai.engine.guandan

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.core.Seat

/**
 * What one seat can see. Clients draw the table from this and computers decide from it,
 * so it never carries another seat's cards while a round is on. Tributes and the cards
 * given back are seen by everyone, as they are at a real table.
 */
data class GdView(
    /** The seat this view is for, or null for a spectator. */
    val seat: Seat?,
    val rules: GdRules,
    val round: Int,
    val levels: List<Int>,
    val levelTeam: Int,
    val level: Int,
    val phase: GdPhase,
    val turn: Seat,
    val hand: List<PlayingCard>,
    val handCounts: List<Int>,
    val lead: GdTablePlay?,
    val order: List<Seat>,
    val tributes: List<GdTribute>,
    val log: List<GdEvent>,
    val rounds: List<GdRound>,
    val scores: List<Int>,
    val winners: Set<Seat>?,
    /** Every hand, shown to all once a round is over; empty before that. */
    val revealedHands: List<List<PlayingCard>>,
) {
    val levelRank: Rank get() = GdValue.LADDER[level]

    val finished: Boolean get() = phase == GdPhase.FINISHED

    val isMyTurn: Boolean get() = seat != null && !finished && turn == seat

    /** The play the seat to act must beat, or null when it leads. */
    val toBeat: GdPattern? get() = lead?.pattern

    /** What this seat may play, when it is its turn to play. */
    fun turnRules(): GdTurn? = if (isMyTurn && phase == GdPhase.PLAYING) GdTurn(hand, toBeat, levelRank) else null

    /** The tribute this seat has to give a card back for now, if any. */
    val owedReturn: GdTribute? get() = if (isMyTurn && phase == GdPhase.RETURN) tributes.firstOrNull { it.to == seat && it.returned == null } else null

    /** Cards that have been played so far this round, by everyone. */
    val playedCards: List<PlayingCard> get() = log.filter { it.kind == GdEventKind.PLAY }.flatMap { it.cards }

    companion object {
        fun of(state: GdState, seat: Seat?): GdView {
            val over = state.phase == GdPhase.ROUND_OVER || state.phase == GdPhase.FINISHED
            return GdView(
                seat = seat,
                rules = state.rules,
                round = state.round,
                levels = state.levels,
                levelTeam = state.levelTeam,
                level = state.level,
                phase = state.phase,
                turn = state.turn,
                hand = if (seat != null) state.hands[seat] else emptyList(),
                handCounts = state.hands.map { it.size },
                lead = state.lead,
                order = state.order,
                tributes = state.tributes,
                log = state.log,
                rounds = state.rounds,
                scores = state.scores,
                winners = state.winners,
                revealedHands = if (over) state.hands else emptyList(),
            )
        }
    }
}
