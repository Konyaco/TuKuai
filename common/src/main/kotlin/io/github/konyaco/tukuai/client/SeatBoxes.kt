package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.engine.core.Seat

/** Where the boxes of the other players go in a table screen, for games of two to eight. */
object SeatBoxes {
    /**
     * The top-left corner of [seat]'s box, [box] pixels wide, in a screen [width] pixels wide,
     * as seen from [me]. Up to four at the table: on your right, across and on your left; with
     * more, from your right up the right side, across the top and down the left, clear of the
     * middle of the screen.
     */
    fun of(players: Int, me: Seat, seat: Seat, width: Int, box: Int): Pair<Int, Int> {
        val rel = (seat - me).mod(players)
        val right = width - 8 - box
        val across = width / 2 - box / 2
        if (players <= 4) {
            val place = when {
                players == 2 -> 1
                players == 3 -> if (rel == 1) 0 else 2
                else -> rel - 1
            }
            return listOf(right to 76, across to 4, 8 to 76)[place]
        }
        val slots = listOf(right to 124, right to 76, width / 2 + 6 to 4, width / 2 - 6 - box to 4, 8 to 76, 8 to 124, right to 28)
        val used = when (players - 1) {
            4 -> listOf(0, 1, 4, 5)
            5 -> listOf(0, 1, 2, 4, 5)
            6 -> listOf(0, 1, 2, 3, 4, 5)
            else -> listOf(0, 1, 6, 2, 3, 4, 5)
        }
        return slots[used[(rel - 1).coerceIn(0, used.size - 1)]]
    }
}
