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
 * Where each seat of a table is.
 *
 * A table has a fixed set of places around it, numbered counterclockwise seen from above
 * starting on the [CardTableBlock.FACING] side, so the next place is on your right. A 3x3
 * table, and a 5x5 one for up to four players, has one place in the middle of each side. A
 * 5x5 table for five to eight has two on each side, a block either side of the middle, the
 * left one first; when only one of a side's two is used, that player sits in the middle.
 *
 * A game with fewer seats than places leaves some places empty, and the players pick which:
 * each seat has a place, and seats are always in the order of their places, so play goes
 * counterclockwise round the table whichever places are taken.
 */
object TableSeats {
    fun maxSeats(radius: Int): Int = if (radius >= 2) 8 else 4

    /** How many places a table of [radius] has for a game of [count] seats. */
    fun placeCount(radius: Int, count: Int): Int = if (radius >= 2 && count > 4) 8 else 4

    /**
     * The places [count] seats take until the players choose: one on each of the first sides,
     * or at a big table two on the first sides and one on the rest, as tables always were.
     */
    fun defaultPlaces(radius: Int, count: Int): List<Int> {
        if (placeCount(radius, count) == 4) return (0 until minOf(count, 4)).toList()
        val perSide = when (count) {
            5 -> listOf(2, 1, 1, 1)
            6 -> listOf(2, 1, 2, 1)
            7 -> listOf(2, 2, 2, 1)
            else -> listOf(2, 2, 2, 2)
        }
        return perSide.flatMapIndexed { side, n -> if (n == 2) listOf(side * 2, side * 2 + 1) else listOf(side * 2) }
    }

    /** Whether [places] fit a game of [count] seats at a table of [radius]: as many, all different, in order. */
    fun valid(radius: Int, count: Int, places: List<Int>): Boolean {
        val total = placeCount(radius, count)
        return places.size == count && places.all { it in 0 until total } && places.zipWithNext().all { (a, b) -> a < b }
    }

    /** [places] if they fit a game of [count] seats at a table of [radius], else the default ones. */
    fun orDefault(radius: Int, count: Int, places: List<Int>): List<Int> =
        if (valid(radius, count, places)) places else defaultPlaces(radius, count)

    /**
     * Where place [place] is when the places in [used] are taken: at a big table with two
     * places a side, a player alone on their side sits in its middle.
     */
    fun spotOf(facing: Direction, total: Int, place: Int, used: Collection<Int>): SeatSpot {
        if (total == 4) return SeatSpot(turn(facing, place), 0)
        val side = turn(facing, place / 2)
        val along = when {
            (place xor 1) !in used -> 0
            place % 2 == 0 -> -1
            else -> 1
        }
        return SeatSpot(side, along)
    }

    /** Where each of [count] seats is, in seat order, when they take [places] (the default ones if not given). */
    fun spots(facing: Direction, radius: Int, count: Int, places: List<Int> = defaultPlaces(radius, count)): List<SeatSpot> {
        val total = placeCount(radius, count)
        val used = orDefault(radius, count, places)
        return used.map { spotOf(facing, total, it, used) }
    }

    /** [facing] turned counterclockwise [times] quarter turns. */
    fun turn(facing: Direction, times: Int): Direction {
        var side = facing
        repeat(times.mod(4)) { side = side.counterClockWise }
        return side
    }
}
