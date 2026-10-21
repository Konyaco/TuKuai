package io.github.konyaco.tukuai.client

import net.minecraft.core.Direction

/**
 * Lays out playing cards on the table top for games like Dou Dizhu and Pao De Kuai: rows
 * of cards in front of a side of the table, either standing like a hand held up to its
 * owner or lying flat like a play.
 */
class PlayingCardScene {
    val cards = ArrayList<TableCard>()

    /**
     * A row of [sprites] in front of the seat at [place], [distance] blocks out from the
     * middle, running left to right as the player there sees them. New cards fly in from
     * ([fromX], [fromZ]). A [standing] hand shows its faces on both sides when [bothSides]
     * is set. The player's own hand is [mine], so they can pick its cards on the table.
     */
    fun row(
        place: SeatPlace,
        distance: Float,
        sprites: List<Int>,
        step: Float,
        scale: Float,
        fromX: Float,
        fromZ: Float,
        standing: Boolean = false,
        bothSides: Boolean = false,
        mine: Boolean = false,
    ) {
        if (sprites.isEmpty()) return
        val side = place.side
        // The player on this side faces the middle; cards run left to right as they see them.
        val upX = -side.stepX.toFloat()
        val upZ = -side.stepZ.toFloat()
        val rightX = -upZ
        val rightZ = upX
        val total = step * (sprites.size - 1)
        val cx = side.stepX * distance + place.shiftX
        val cz = side.stepZ * distance + place.shiftZ
        sprites.forEachIndexed { i, sprite ->
            val offset = -total / 2 + i * step
            if (standing) {
                // Like a fanned hand: each card stands a little nearer its owner than the one
                // on its left, covering all of it but the corner with the rank.
                val nearer = (i - (sprites.size - 1) / 2f) * HAND_DEPTH
                cards += TableCard(
                    cx + rightX * offset + side.stepX * nearer,
                    cz + rightZ * offset + side.stepZ * nearer,
                    upX, upZ, sprite, scale, tilt = HAND_TILT, fromX = fromX, fromZ = fromZ,
                    backSprite = if (bothSides) sprite else -1, mine = mine,
                )
            } else {
                cards += TableCard(cx + rightX * offset, cz + rightZ * offset, upX, upZ, sprite, scale, i, fromX = fromX, fromZ = fromZ, mine = mine)
            }
        }
    }

    companion object {
        /** How far each standing card is from the next, towards its owner. */
        private const val HAND_DEPTH = 0.008f

        /** Degrees up from flat: the face points at the eyes of someone sitting behind it. */
        private const val HAND_TILT = 60f

        /** The side of the table nobody sits at, for three players; cards are dealt from there. */
        fun emptySide(seats: List<SeatPlace>): Direction? {
            val sides = seats.map { it.side }
            return sides.firstOrNull()?.let { first -> Direction.entries.firstOrNull { it.axis.isHorizontal && it !in sides } ?: first }
        }
    }
}
