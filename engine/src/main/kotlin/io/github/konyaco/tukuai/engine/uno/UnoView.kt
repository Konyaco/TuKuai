package io.github.konyaco.tukuai.engine.uno

import io.github.konyaco.tukuai.engine.core.Seat

/**
 * What one seat can see: its own hand, how many cards everyone else holds, and the
 * discard pile. Clients draw the table from this and computers decide from it.
 */
data class UnoView(
    /** The seat this view is for, or null for a spectator. */
    val seat: Seat?,
    val rules: UnoRules,
    val turn: Seat,
    val direction: Int,
    val color: UnoColor,
    val hand: List<UnoCard>,
    val handCounts: List<Int>,
    /** The top few cards of the discard pile, the top one last. */
    val discardTop: List<UnoCard>,
    /** All the cards in the discard pile, including [discardTop]. */
    val discardCount: Int,
    val drawCount: Int,
    val pendingDraw: Int,
    /** The card this seat just drew and may still play; null for everyone else. */
    val drawn: UnoCard?,
    val log: List<UnoEvent>,
    val settlement: UnoSettlement?,
    /** Every hand, shown to all once the game is over; empty before that. */
    val revealedHands: List<List<UnoCard>>,
) {
    val finished: Boolean get() = settlement != null

    val isMyTurn: Boolean get() = seat != null && !finished && turn == seat

    val top: UnoCard get() = discardTop.last()

    val players: Int get() = rules.players

    /** Whether this seat may play [card] now. */
    fun playable(card: UnoCard): Boolean =
        isMyTurn && Uno.playable(rules, hand, top, color, pendingDraw, drawn, card)

    /** The seat that plays after [seat]. */
    fun next(seat: Seat): Seat = (seat + direction).mod(players)

    /** Seats that called UNO and still hold their one card. */
    val unoCalled: Set<Seat>
        get() {
            val called = HashSet<Seat>()
            for (event in log) {
                when (event.kind) {
                    UnoEventKind.UNO -> called += event.seat
                    UnoEventKind.DRAW, UnoEventKind.PENALTY -> called -= event.seat
                    else -> {}
                }
            }
            return called.filterTo(HashSet()) { handCounts[it] == 1 }
        }

    companion object {
        /** How many cards of the discard pile a view carries; the rest are buried. */
        const val DISCARD_SHOWN = 6

        fun of(state: UnoState, seat: Seat?): UnoView = UnoView(
            seat = seat,
            rules = state.rules,
            turn = state.turn,
            direction = state.direction,
            color = state.color,
            hand = if (seat != null) state.hands[seat] else emptyList(),
            handCounts = state.hands.map { it.size },
            discardTop = state.discard.takeLast(DISCARD_SHOWN),
            discardCount = state.discard.size,
            drawCount = state.drawPile.size,
            pendingDraw = state.pendingDraw,
            drawn = state.drawn?.takeIf { seat == state.turn },
            log = state.log,
            settlement = state.settlement,
            revealedHands = if (state.settlement != null) state.hands else emptyList(),
        )
    }
}
