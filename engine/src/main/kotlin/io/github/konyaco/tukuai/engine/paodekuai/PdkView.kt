package io.github.konyaco.tukuai.engine.paodekuai

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.Seat

/**
 * What one seat can see. Clients draw the table from this and computers decide from it,
 * so it never carries another seat's cards while the game is on.
 */
data class PdkView(
    /** The seat this view is for, or null for a spectator. */
    val seat: Seat?,
    val rules: PdkRules,
    val starter: Seat,
    val turn: Seat,
    val hand: List<PlayingCard>,
    val handCounts: List<Int>,
    val lead: PdkTablePlay?,
    val bombs: List<Int>,
    val log: List<PdkEvent>,
    val settlement: PdkSettlement?,
    /** Every hand, shown to all once the game is over; empty before that. */
    val revealedHands: List<List<PlayingCard>>,
) {
    val finished: Boolean get() = settlement != null

    val isMyTurn: Boolean get() = seat != null && !finished && turn == seat

    /** The play the seat to act must beat, or null when it may lead anything. */
    val toBeat: PdkPattern? get() = lead?.takeIf { it.seat != turn }?.pattern

    /** Nobody has played yet, so the first play must hold the 3 of spades. */
    val opening: Boolean get() = log.none { it.kind == PdkEventKind.PLAY }

    /** What this seat may do, when it is its turn. */
    fun turnRules(): PdkTurn? = if (isMyTurn) PaoDeKuai.turnOf(rules, hand, toBeat, handCounts, turn, opening) else null

    /** Cards that have left hands so far, by everyone. */
    val playedCards: List<PlayingCard> get() = log.filter { it.kind == PdkEventKind.PLAY }.flatMap { it.cards }

    companion object {
        fun of(state: PdkState, seat: Seat?): PdkView = PdkView(
            seat = seat,
            rules = state.rules,
            starter = state.starter,
            turn = state.turn,
            hand = if (seat != null) state.hands[seat] else emptyList(),
            handCounts = state.hands.map { it.size },
            lead = state.lead,
            bombs = state.bombs,
            log = state.log,
            settlement = state.settlement,
            revealedHands = if (state.finished) state.hands else emptyList(),
        )
    }
}
