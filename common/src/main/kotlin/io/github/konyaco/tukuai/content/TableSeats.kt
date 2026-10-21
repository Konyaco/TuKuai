package io.github.konyaco.tukuai.content

import net.minecraft.core.Direction

/**
 * One seat at a table: the [side] of the table it is on, and how many blocks [along] that
 * side from its middle, to the right of whoever sits there.
 */
data class SeatSpot(val side: Direction, val along: Int) {
    /** To the right of whoever sits here, looking at the middle of the table. */
    val right: Direction get() = side.counterClockWise
}

/**
 * Where each seat of a table is. Seats go counterclockwise seen from above, so the next
 * seat is on your right, starting on the [CardTableBlock.FACING] side.
 *
 * A 3x3 table has one seat in the middle of each side, so up to four. A 5x5 table seats
 * up to four the same way, and five to eight with two on some sides: those two sit a block
 * either side of the middle, the left one first.
 */
object TableSeats {
    fun maxSeats(radius: Int): Int = if (radius >= 2) 8 else 4

    fun spots(facing: Direction, radius: Int, count: Int): List<SeatSpot> {
        val perSide = when {
            count <= 4 || radius < 2 -> List(minOf(count, 4)) { 1 }
            count == 5 -> listOf(2, 1, 1, 1)
            count == 6 -> listOf(2, 1, 2, 1)
            count == 7 -> listOf(2, 2, 2, 1)
            else -> listOf(2, 2, 2, 2)
        }
        val spots = ArrayList<SeatSpot>(count)
        var side = facing
        for (n in perSide) {
            if (n == 1) spots += SeatSpot(side, 0) else spots += listOf(SeatSpot(side, -1), SeatSpot(side, 1))
            side = side.counterClockWise
        }
        return spots
    }
}
