package io.github.konyaco.tukuai.engine.core

/**
 * Who sees what of a match at a table. A player dealt into the match sees only their own
 * seat until it is over, wherever they go in the meantime, so standing up cannot show them
 * the other hands. Everyone else watches, and sees every hand when the table lets them.
 */
object Watching {
    /**
     * The seat whose view a player gets, or null when they watch. [dealtIn] is the seat
     * they were dealt into when the match started, [seated] the seat they sit in now.
     */
    fun seatFor(dealtIn: Seat?, seated: Seat?, over: Boolean): Seat? = if (dealtIn != null && !over) dealtIn else seated

    /** The encoded view of [match] for one player, see [seatFor]; watchers see every hand when [open]. */
    fun encodedView(match: MatchHandle, dealtIn: Seat?, seated: Seat?, open: Boolean): ByteArray {
        val seat = seatFor(dealtIn, seated, match.result != null)
        return when {
            seat != null -> match.encodedView(seat)
            open -> match.encodedOpenView(null)
            else -> match.encodedView(null)
        }
    }
}
