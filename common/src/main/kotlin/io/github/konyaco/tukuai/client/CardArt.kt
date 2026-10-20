package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.engine.card.Decks
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.uno.UnoCard
import io.github.konyaco.tukuai.engine.uno.UnoColor
import io.github.konyaco.tukuai.engine.uno.UnoDeck
import io.github.konyaco.tukuai.engine.uno.UnoFace
import io.github.konyaco.tukuai.engine.uno.UnoKind
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.resources.Identifier

/**
 * The card atlas `textures/gui/cards.png`, made by `tools/gen_textures.py`: 32×44 cards
 * in rows of 14. Faces 0..53 follow [Decks.STANDARD_54], then come the card backs,
 * and two 16×16 hats sit below them. The UNO cards start on row 6, see [uno]. Right of
 * the cards are a patch of plain paper for the edges of cards on the table and a 16×16
 * tile of pale cloth for the table top.
 */
object CardArt {
    val ATLAS: Identifier = TuKuai.id("textures/gui/cards.png")
    const val WIDTH = 32
    const val HEIGHT = 44
    const val TEXTURE_WIDTH = 512
    const val TEXTURE_HEIGHT = 512
    private const val COLUMNS = 14
    private const val FIRST_BACK = 54
    const val BACK_COUNT = 4

    /** The 54 UNO faces in [UnoDeck.FACES] order, then the two wild cards with each colour named. */
    private const val UNO_FIRST = 6 * COLUMNS

    private const val ICON_Y = 220
    const val ICON_SIZE = 16

    /** Middle of the paper patch, in atlas pixels. */
    const val EDGE_U = 452
    const val EDGE_V = 4

    /** Top-left corner and size of the cloth tile, in atlas pixels. */
    const val FELT_U = 464
    const val FELT_V = 0
    const val FELT_SIZE = 16

    private val faceIndex: Map<PlayingCard, Int> = Decks.STANDARD_54.withIndex().associate { it.value to it.index }

    fun face(card: PlayingCard): Int = faceIndex.getValue(card)

    fun back(): Int = FIRST_BACK + ClientConfig.cardBack.coerceIn(0, BACK_COUNT - 1)

    fun isBack(sprite: Int): Boolean = sprite in FIRST_BACK until FIRST_BACK + BACK_COUNT

    /** An UNO card; a wild card that was played with [named] shows that colour in its middle. */
    fun uno(card: UnoCard, named: UnoColor? = null): Int {
        val face = card.face
        if (named == null || !face.isWild) return UNO_FIRST + UnoDeck.faceIndexOf(card)
        val four = if (face.kind == UnoKind.WILD_DRAW_FOUR) 1 else 0
        return UNO_FIRST + UnoDeck.FACES.size + four * UnoColor.entries.size + named.ordinal
    }

    /** An UNO face as printed, for pictures that show a kind of card rather than one from a game. */
    fun unoFace(face: UnoFace): Int = UNO_FIRST + UnoDeck.indexOf(face)

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
