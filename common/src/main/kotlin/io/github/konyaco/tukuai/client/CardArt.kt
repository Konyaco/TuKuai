package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.engine.card.Decks
import io.github.konyaco.tukuai.engine.card.PlayingCard
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * The card atlas `textures/gui/cards.png`, made by `tools/gen_textures.py`: 32×44 cards
 * in rows of 14. Faces 0..53 follow [Decks.STANDARD_54], then come the card backs,
 * and two 16×16 hats sit at the bottom. A patch of plain paper right of the cards
 * colours the edges of cards on the table.
 */
object CardArt {
    val ATLAS: Identifier = TuKuai.id("textures/gui/cards.png")
    const val WIDTH = 32
    const val HEIGHT = 44
    const val TEXTURE_WIDTH = 512
    const val TEXTURE_HEIGHT = 256
    private const val COLUMNS = 14
    private const val FIRST_BACK = 54
    const val BACK_COUNT = 4

    private const val ICON_Y = 220
    const val ICON_SIZE = 16

    /** Middle of the paper patch, in atlas pixels. */
    const val EDGE_U = 452
    const val EDGE_V = 4

    private val faceIndex: Map<PlayingCard, Int> = Decks.STANDARD_54.withIndex().associate { it.value to it.index }

    fun face(card: PlayingCard): Int = faceIndex.getValue(card)

    fun back(): Int = FIRST_BACK + ClientConfig.cardBack.coerceIn(0, BACK_COUNT - 1)

    fun u(sprite: Int): Int = (sprite % COLUMNS) * WIDTH

    fun v(sprite: Int): Int = (sprite / COLUMNS) * HEIGHT

    /** Draws a card with its top-left corner at ([x], [y]); [scale] 1 is 32×44 GUI pixels. */
    fun draw(g: GuiGraphicsExtractor, sprite: Int, x: Int, y: Int, scale: Float = 1f) {
        val w = (WIDTH * scale).toInt()
        val h = (HEIGHT * scale).toInt()
        g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, u(sprite).toFloat(), v(sprite).toFloat(), w, h, WIDTH, HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT)
    }

    fun drawCard(g: GuiGraphicsExtractor, card: PlayingCard, x: Int, y: Int, scale: Float = 1f) = draw(g, face(card), x, y, scale)

    /** The landlord's hat, or the farmer's straw hat when [landlord] is false. */
    fun drawHat(g: GuiGraphicsExtractor, landlord: Boolean, x: Int, y: Int) {
        g.blit(RenderPipelines.GUI_TEXTURED, ATLAS, x, y, if (landlord) 0f else 16f, ICON_Y.toFloat(), ICON_SIZE, ICON_SIZE, TEXTURE_WIDTH, TEXTURE_HEIGHT)
    }
}
