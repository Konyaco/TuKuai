package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.client.render.CardFrame
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import org.joml.Matrix4f
import org.joml.Vector3f
import kotlin.math.min

/**
 * The mouse on the table in the immersive view. It finds which of the player's own cards
 * ([TableCard.mine]) is under the cursor, by casting a ray from the camera through it, and
 * dresses those cards for drawing: raised and tinted while picked, framed under the cursor.
 */
object TablePointer {
    /** The table the cards were last dressed for, and the cards as they were drawn. */
    private var drawnAt: BlockPos? = null
    private var drawn: List<TableCard> = emptyList()

    /** How far each of the player's cards stands raised right now, easing towards where it should. */
    private val rises = HashMap<Int, Float>()
    private var lastFrame = 0L

    /** The card under the cursor, set by the table screen each frame. */
    var hovered: TableCard? = null

    /** The cards of the table at [pos] as they should be drawn this frame. */
    fun dress(pos: BlockPos, cards: List<TableCard>): List<TableCard> {
        val screen = Minecraft.getInstance().screen as? TableScreen
        val table = ClientTables[pos]
        if (screen == null || screen.pos != pos || !screen.inWorld || table == null) {
            if (drawnAt == pos) forget()
            return cards
        }
        val now = System.nanoTime()
        val seconds = if (lastFrame == 0L) 0f else ((now - lastFrame) / 1e9f).coerceAtMost(0.1f)
        lastFrame = now
        val over = hovered?.let(::id)
        val dressed = cards.map { card ->
            if (!card.mine) return@map card
            val raised = screen.isRaised(table, card)
            val under = id(card) == over
            val target = (if (raised) RAISE else 0f) + (if (under) HOVER else 0f)
            val current = rises[id(card)] ?: target
            val rise = current + (target - current) * min(1f, seconds * EASE)
            rises[id(card)] = rise
            card.copy(rise = rise, tint = if (raised) PICKED_TINT else -1, outline = if (under) OUTLINE_COLOR else 0)
        }
        drawnAt = pos
        drawn = dressed
        return dressed
    }

    /**
     * The player's own card on the table at [pos] under the mouse at ([mouseX], [mouseY]) on
     * a screen [width] by [height] GUI pixels, or null. A raised card also counts where it
     * stood before it rose, so moving onto a card never makes it slide out from under the cursor.
     */
    fun cardAt(pos: BlockPos, mouseX: Double, mouseY: Double, width: Int, height: Int): TableCard? {
        if (drawnAt != pos || drawn.none { it.mine } || width <= 0 || height <= 0) return null
        val camera = Minecraft.getInstance().gameRenderer.mainCamera
        val inverse = camera.getViewRotationProjectionMatrix(Matrix4f()).invert()
        val x = (mouseX / width * 2 - 1).toFloat()
        val y = (1 - mouseY / height * 2).toFloat()
        // The camera sits at the origin of this matrix, so the near and far points are on the ray from it.
        val near = inverse.transformProject(Vector3f(x, y, -1f))
        val far = inverse.transformProject(Vector3f(x, y, 1f))
        val eye = camera.position()
        val origin = doubleArrayOf(eye.x - pos.x + near.x, eye.y - pos.y + near.y, eye.z - pos.z + near.z)
        val dir = doubleArrayOf((far.x - near.x).toDouble(), (far.y - near.y).toDouble(), (far.z - near.z).toDouble())
        var best: TableCard? = null
        var bestT = Double.MAX_VALUE
        for (card in drawn) {
            if (!card.mine) continue
            val t = listOfNotNull(CardFrame.of(card).hit(origin, dir), CardFrame.of(card, 0f).hit(origin, dir)).minOrNull() ?: continue
            if (t < bestT) {
                best = card
                bestT = t
            }
        }
        return best
    }

    /** Tells the player's cards apart: by key where the game gives one, else by face. */
    private fun id(card: TableCard): Int = if (card.key >= 0) card.key else KEYLESS + card.sprite

    fun forget() {
        drawnAt = null
        drawn = emptyList()
        rises.clear()
        hovered = null
        lastFrame = 0L
    }

    /** How far a picked card rises out of the hand, and a card under the cursor, in blocks. */
    private const val RAISE = 0.07f
    private const val HOVER = 0.02f

    /** How quickly cards rise and settle, per second. */
    private const val EASE = 18f

    private const val PICKED_TINT = 0xFFFFF0B0.toInt()
    private const val OUTLINE_COLOR = 0xFFFFD84A.toInt()

    /** Keeps faces apart from keys, which are small numbers. */
    private const val KEYLESS = 1 shl 20
}
