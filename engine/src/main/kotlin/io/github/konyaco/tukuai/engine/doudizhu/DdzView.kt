package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.Seat

/**
 * What one seat can see. Clients draw the table from this and bots decide from it,
 * so it must never carry another seat's hidden cards while the game is running.
 */
data class DdzView(
    /** The seat this view is for, or null for a spectator. */
    val seat: Seat?,
    val rules: DdzRules,
    val phase: DdzPhase,
    val turn: Seat,
    val firstBidder: Seat,
    val landlord: Seat?,
    val hand: List<PlayingCard>,
    val handCounts: List<Int>,
    /** The three kitty cards once the landlord has taken them; empty before that. */
    val kitty: List<PlayingCard>,
    val bids: List<DdzEvent>,
    val bidMultiplier: Int,
    val doubles: List<Boolean?>,
    val lead: TablePlay?,
    val bombs: Int,
    val log: List<DdzEvent>,
    val settlement: Settlement?,
    /** Every hand, shown to all once the game is over; empty before that. */
    val revealedHands: List<List<PlayingCard>>,
) {
    val isMyTurn: Boolean get() = seat != null && phase != DdzPhase.FINISHED && turn == seat

    /** The play the seat to act must beat, or null when it may lead anything. */
    val toBeat: Pattern? get() = lead?.takeIf { it.seat != turn }?.pattern

    fun isLandlord(s: Seat): Boolean = landlord == s

    fun isTeammate(a: Seat, b: Seat): Boolean = landlord != null && a != landlord && b != landlord

    /** Points that may be bid now in score mode; 0 means pass. */
    val allowedBids: List<Int> get() = listOf(0) + ((bids.maxOfOrNull { it.value } ?: 0) + 1..3)

    /** In rob mode, true when the seat to act would rob rather than call. */
    val robbing: Boolean get() = bids.any { it.kind == DdzEventKind.CALL }

    /** Cards that have left hands so far, by everyone. */
    val playedCards: List<PlayingCard> get() = log.filter { it.kind == DdzEventKind.PLAY }.flatMap { it.cards }

    companion object {
        fun of(state: DdzState, seat: Seat?): DdzView {
            val finished = state.phase == DdzPhase.FINISHED
            return DdzView(
                seat = seat,
                rules = state.rules,
                phase = state.phase,
                turn = state.turn,
                firstBidder = state.firstBidder,
                landlord = state.landlord,
                hand = if (seat != null) state.hands[seat] else emptyList(),
                handCounts = state.hands.map { it.size },
                kitty = if (state.landlord != null) state.kitty else emptyList(),
                bids = state.bids,
                bidMultiplier = state.bidMultiplier,
                doubles = state.doubles,
                lead = state.lead,
                bombs = state.bombs,
                log = state.log,
                settlement = state.settlement,
                revealedHands = if (finished) state.hands else emptyList(),
            )
        }
    }
}
