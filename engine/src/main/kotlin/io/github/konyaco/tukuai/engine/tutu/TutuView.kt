package io.github.konyaco.tukuai.engine.tutu

import io.github.konyaco.tukuai.engine.core.Seat

/**
 * What one seat can see: its own hand, how many cards everyone else holds, the discard
 * pile, where the cacti are planted in the draw pile and how many TNT are burning, but not
 * how long their fuses are. Clients draw the table from this and computers decide from it.
 */
data class TutuView(
    /** The seat this view is for, or null for a spectator. */
    val seat: Seat?,
    val rules: TutuRules,
    val turn: Seat,
    val direction: Int,
    val top: TutuFace,
    val color: TutuColor,
    val hand: List<TutuCard>,
    val handCounts: List<Int>,
    /** The top few cards of the discard pile, the top one last. */
    val discardTop: List<TutuCard>,
    /** All the cards in the discard pile, including [discardTop]. */
    val discardCount: Int,
    val drawCount: Int,
    /** Planted cacti, each as the number of cards below it in the draw pile; see [TutuState.cacti]. */
    val cacti: List<Int>,
    /** How many TNT are burning. */
    val bombs: Int,
    /** Turns left on each TNT; only in replays, empty while playing. */
    val fuses: List<Int>,
    /** The card this seat just drew and may still play; null for everyone else. */
    val drawn: TutuCard?,
    val log: List<TutuEvent>,
    val settlement: TutuSettlement?,
    /** Every hand, shown to all once the game is over; empty before that. */
    val revealedHands: List<List<TutuCard>>,
) {
    val finished: Boolean get() = settlement != null

    val isMyTurn: Boolean get() = seat != null && !finished && turn == seat

    val players: Int get() = rules.players

    /** Whether this seat may play [card] now. */
    fun playable(card: TutuCard): Boolean = isMyTurn && Tutu.playable(hand, top, color, drawn, card)

    /** The seat that plays after [seat]. */
    fun next(seat: Seat): Seat = (seat + direction).mod(players)

    /** Cards to draw before the next cactus springs, or -1 if none is planted. */
    val cactusIn: Int get() = cacti.maxOrNull()?.let { drawCount - it } ?: -1

    /** What each card on the discard pile counts as: an egg shows what it hatched into. */
    fun shownFace(card: TutuCard): TutuFace =
        if (card.face.kind != TutuKind.EGG) card.face else log.lastOrNull { it.card == card }?.face ?: card.face

    companion object {
        /** How many cards of the discard pile a view carries; the rest are buried. */
        const val DISCARD_SHOWN = 6

        fun of(state: TutuState, seat: Seat?): TutuView = TutuView(
            seat = seat,
            rules = state.rules,
            turn = state.turn,
            direction = state.direction,
            top = state.top,
            color = state.color,
            hand = if (seat != null) state.hands[seat] else emptyList(),
            handCounts = state.hands.map { it.size },
            discardTop = state.discard.takeLast(DISCARD_SHOWN),
            discardCount = state.discard.size,
            drawCount = state.drawPile.size,
            cacti = state.cacti.sortedDescending(),
            bombs = state.fuses.size,
            fuses = emptyList(),
            drawn = state.drawn?.takeIf { seat == state.turn },
            log = state.log,
            settlement = state.settlement,
            revealedHands = if (state.settlement != null) state.hands else emptyList(),
        )
    }
}
