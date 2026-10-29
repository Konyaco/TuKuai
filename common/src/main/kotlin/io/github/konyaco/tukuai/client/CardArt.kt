package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.card.Decks
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.tutu.TutuCard
import io.github.konyaco.tukuai.engine.tutu.TutuColor
import io.github.konyaco.tukuai.engine.tutu.TutuDeck
import io.github.konyaco.tukuai.engine.tutu.TutuFace
import io.github.konyaco.tukuai.engine.tutu.TutuKind
import io.github.konyaco.tukuai.engine.uno.UnoCard
import io.github.konyaco.tukuai.engine.uno.UnoColor
import io.github.konyaco.tukuai.engine.uno.UnoDeck
import io.github.konyaco.tukuai.engine.uno.UnoFace
import io.github.konyaco.tukuai.engine.uno.UnoKind

/**
 * The card atlas `textures/gui/cards.png`, made by `tools/gen_textures.py`: 32×44 cards
 * in rows of 14. Faces 0..53 follow [Decks.STANDARD_54], then come the card backs,
 * and two 16×16 hats sit below them. The UNO cards start on row 6, see [uno], the
 * other face styles on row 11, see [face], the Tutu cards on row 19, see [tutu], and the creeper head of creeper
 * bluff on row 24, see [CREEPER_SIDE]. Right of the cards are a patch of plain paper
 * for the edges of cards on the table and a 16×16 tile of pale cloth for the table top.
 */
object CardArt {
    val ATLAS: Identifier = TuKuai.id("textures/gui/cards.png")
    const val WIDTH = 32
    const val HEIGHT = 44
    const val TEXTURE_WIDTH = 512
    const val TEXTURE_HEIGHT = 2048
    private const val COLUMNS = 14
    private const val FIRST_BACK = 54
    const val BACK_COUNT = 4

    /**
     * The looks a player can pick for playing card faces, see [ClientConfig.cardFace]: the
     * classic faces, large corners that stay readable in a fanned hand, and four colours.
     */
    val FACE_STYLES: List<String> = listOf("classic", "large", "four_color")

    /** Where the faces in the styles after the classic one start, 54 each. */
    private const val STYLE_FIRST = 11 * COLUMNS

    /** The 54 UNO faces in [UnoDeck.FACES] order, then the two wild cards with each colour named. */
    private const val UNO_FIRST = 6 * COLUMNS

    /**
     * The 55 Tutu faces in [TutuDeck.FACES] order, then TNT and the cactus with each colour
     * named, then the side and top of the little TNT block that burns on the table.
     */
    private const val TUTU_FIRST = 19 * COLUMNS

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

    /** A playing card in the face style this player picked. */
    fun face(card: PlayingCard): Int = face(card, ClientConfig.cardFace)

    /** A playing card in one of the [FACE_STYLES], to show what each looks like. */
    fun face(card: PlayingCard, faceStyle: Int): Int {
        val style = faceStyle.coerceIn(0, FACE_STYLES.size - 1)
        val index = faceIndex.getValue(card)
        return if (style == 0) index else STYLE_FIRST + (style - 1) * Decks.STANDARD_54.size + index
    }

    fun back(): Int = back(ClientConfig.cardBack)

    /** One of the [BACK_COUNT] card backs. */
    fun back(index: Int): Int = FIRST_BACK + index.coerceIn(0, BACK_COUNT - 1)

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

    /** A Tutu card showing [face]; TNT or a cactus that was played with [named] shows that colour around its picture. */
    fun tutu(face: TutuFace, named: TutuColor? = null): Int {
        if (named == null || !face.kind.namesColor) return TUTU_FIRST + TutuDeck.indexOf(face)
        val cactus = if (face.kind == TutuKind.CACTUS) 1 else 0
        return TUTU_FIRST + TutuDeck.FACES.size + cactus * TutuColor.entries.size + named.ordinal
    }

    fun tutu(card: TutuCard): Int = tutu(card.face)

    /** The side of the TNT block on the table; the top is the next sprite. The block uses the top 32×32 of each. */
    const val TNT_SIDE = TUTU_FIRST + 55 + 8
    const val TNT_TOP = TNT_SIDE + 1

    /** The face of the little creeper head before each player at creeper bluff; its top is the next sprite. */
    const val CREEPER_SIDE = 24 * COLUMNS
    const val CREEPER_TOP = CREEPER_SIDE + 1

    fun u(sprite: Int): Int = (sprite % COLUMNS) * WIDTH

    fun v(sprite: Int): Int = (sprite / COLUMNS) * HEIGHT

    /** Draws a card with its top-left corner at ([x], [y]); [scale] 1 is 32×44 GUI pixels. */
    fun draw(g: GuiGraphicsExtractor, sprite: Int, x: Int, y: Int, scale: Float = 1f) {
        val w = (WIDTH * scale).toInt()
        val h = (HEIGHT * scale).toInt()
        g.blitTexture(ATLAS, x, y, u(sprite).toFloat(), v(sprite).toFloat(), w, h, WIDTH, HEIGHT, TEXTURE_WIDTH, TEXTURE_HEIGHT)
    }

    fun drawCard(g: GuiGraphicsExtractor, card: PlayingCard, x: Int, y: Int, scale: Float = 1f) = draw(g, face(card), x, y, scale)

    /** The side of a TNT block, [size] GUI pixels square. */
    fun drawTnt(g: GuiGraphicsExtractor, x: Int, y: Int, size: Int) {
        g.blitTexture(ATLAS, x, y, u(TNT_SIDE).toFloat(), v(TNT_SIDE).toFloat(), size, size, WIDTH, WIDTH, TEXTURE_WIDTH, TEXTURE_HEIGHT)
    }

    /** A creeper's face, [size] GUI pixels square. */
    fun drawCreeper(g: GuiGraphicsExtractor, x: Int, y: Int, size: Int) {
        g.blitTexture(ATLAS, x, y, u(CREEPER_SIDE).toFloat(), v(CREEPER_SIDE).toFloat(), size, size, WIDTH, WIDTH, TEXTURE_WIDTH, TEXTURE_HEIGHT)
    }

    /** The landlord's hat, or the farmer's straw hat when [landlord] is false. */
    fun drawHat(g: GuiGraphicsExtractor, landlord: Boolean, x: Int, y: Int) {
        g.blitTexture(ATLAS, x, y, if (landlord) 0f else 16f, ICON_Y.toFloat(), ICON_SIZE, ICON_SIZE, ICON_SIZE, ICON_SIZE, TEXTURE_WIDTH, TEXTURE_HEIGHT)
    }
}
