package io.github.konyaco.tukuai.client

import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.Direction

/**
 * The client half of one game: how to read its view and draw it, both in the table
 * screen and on the table top in the world. A new game adds one of these to [GameUis].
 */
interface GameUi {
    val gameId: String

    fun decodeView(data: ByteArray): Any

    fun createPanel(screen: TableScreen): GamePanel

    /**
     * Cards to lay on the table top, seen by everyone near the table.
     * [sides] gives the side of the table each seat sits on.
     */
    fun scene(table: ClientTable, sides: List<Direction>): List<TableCard>
}

/** The game part of the table screen. It lives as long as the screen. */
interface GamePanel {
    /** Called when the table changes; add buttons through [TableScreen.button]. */
    fun rebuild(table: ClientTable)

    fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int)

    fun mouseClicked(table: ClientTable, x: Double, y: Double, button: Int): Boolean = false

    fun keyPressed(table: ClientTable, key: Int): Boolean = false
}

/**
 * One card lying flat on the table top. Positions are in blocks from the table's
 * north-west corner; ([upX], [upZ]) is the unit direction the top of the card points to.
 */
data class TableCard(
    val x: Float,
    val z: Float,
    val upX: Float,
    val upZ: Float,
    /** Index into the card atlas, see [CardArt]. */
    val sprite: Int,
    val scale: Float = 1f,
    /** Small lift so later cards draw over earlier ones. */
    val layer: Int = 0,
)

object GameUis {
    private val uis = HashMap<String, GameUi>()

    fun register(ui: GameUi) {
        uis[ui.gameId] = ui
    }

    operator fun get(id: String): GameUi? = uis[id]
}
