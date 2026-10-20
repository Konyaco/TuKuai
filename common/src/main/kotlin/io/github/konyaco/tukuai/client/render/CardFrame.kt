package io.github.konyaco.tukuai.client.render

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.content.CardTableBlock
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Where a [TableCard] is, as a thin box: centred on [c], with [r], [u] and [n] its right,
 * up and outward (face) directions, and half its width, height and thickness. Positions
 * are in blocks from the north-west corner of the table's middle block, as the renderer
 * draws them, so drawing a card and finding the card under the mouse agree.
 */
class CardFrame(
    val c: FloatArray,
    val r: FloatArray,
    val u: FloatArray,
    val n: FloatArray,
    val halfW: Float,
    val halfH: Float,
    val halfT: Float,
) {
    /**
     * How far along the ray from [origin] in direction [dir] it meets the card's face, or
     * null if it misses. Both are in the same blocks as [c].
     */
    fun hit(origin: DoubleArray, dir: DoubleArray): Double? {
        val facing = dir[0] * n[0] + dir[1] * n[1] + dir[2] * n[2]
        if (abs(facing) < 1e-6) return null
        val t = ((c[0] - origin[0]) * n[0] + (c[1] - origin[1]) * n[1] + (c[2] - origin[2]) * n[2]) / facing
        if (t <= 0) return null
        val d = DoubleArray(3) { origin[it] + dir[it] * t - c[it] }
        val along = d[0] * r[0] + d[1] * r[1] + d[2] * r[2]
        val up = d[0] * u[0] + d[1] * u[1] + d[2] * u[2]
        return if (abs(along) <= halfW && abs(up) <= halfH) t else null
    }

    companion object {
        /** Width of a card on the table at scale 1, in blocks. */
        const val CARD_WIDTH = 0.3f

        /** Thickness of a card at scale 1, in blocks. */
        const val CARD_THICKNESS = 0.008f

        /** How much higher each flat card in a row sits than the one before it. */
        private const val LAYER_LIFT = 0.002f

        fun of(card: TableCard, rise: Float = card.rise): CardFrame {
            val halfW = CARD_WIDTH * card.scale / 2
            val halfH = CARD_WIDTH * card.scale * CardArt.HEIGHT / CardArt.WIDTH / 2
            val halfT = CARD_THICKNESS * card.scale / 2
            val tilt = Math.toRadians(card.tilt.toDouble())
            val sin = sin(tilt).toFloat()
            val cos = cos(tilt).toFloat()
            // Right of the card as seen by someone reading it: the "up" direction turned clockwise.
            val r = floatArrayOf(-card.upZ, 0f, card.upX)
            val u = floatArrayOf(card.upX * cos, sin, card.upZ * cos)
            val n = floatArrayOf(-card.upX * sin, cos, -card.upZ * sin)
            // Card positions are measured from the middle of the table. A standing card rests on
            // its bottom edge; flat ones lift a little over the cards before them in their row.
            // A card picked from a hand slides up along its own face.
            val c = floatArrayOf(
                card.x + 0.5f + u[0] * rise,
                CardTableBlock.TOP_HEIGHT + sin * halfH + cos * halfT + card.lift + (if (card.tilt == 0f) card.layer * LAYER_LIFT else 0f) + u[1] * rise,
                card.z + 0.5f + u[2] * rise,
            )
            return CardFrame(c, r, u, n, halfW, halfH, halfT)
        }
    }
}
