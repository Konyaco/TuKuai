package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.table.TableOp
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component

/**
 * The client half of one game: how to read its view and draw it, both in the table
 * screen and on the table top in the world. A new game adds one of these to [GameUis].
 */
interface GameUi {
    val gameId: String

    fun decodeView(data: ByteArray): Any

    fun createPanel(host: PanelHost): GamePanel

    /**
     * Cards to lay on the table top, seen by everyone near the table.
     * [sides] gives the side of the table each seat sits on.
     */
    fun scene(table: ClientTable, sides: List<Direction>): List<TableCard>

    /** One or two short lines for the display in the middle of the table while a game is on. */
    fun displayLines(table: ClientTable): List<Component> = emptyList()

    /** The seat whose turn it is, lit up on the display; null when nobody is to act. */
    fun turnSeat(table: ClientTable): Int? = null

    /** Called when an update for a table arrives, to play sounds for what just happened at it. */
    fun onUpdate(previous: ClientTable?, table: ClientTable) {}
}

/** The screen a [GamePanel] is drawn in: the table screen, or the replay viewer. */
interface PanelHost {
    /** Screen size in GUI pixels. */
    val panelWidth: Int
    val panelHeight: Int

    /** False when watching a replay: there are no turns to take and nothing to send. */
    val interactive: Boolean

    /**
     * True in the immersive view at a table: the world stays in sight and the player picks
     * their cards on the table itself, so panels leave out their own hand.
     */
    val inWorld: Boolean get() = false

    /** Adds a button for this layout; panels call it from [GamePanel.rebuild]. */
    fun button(text: Component, x: Int, y: Int, w: Int, active: Boolean = true, h: Int = 20, onPress: () -> Unit): Button

    fun send(op: TableOp, a: Int = 0, b: Int = 0, text: String = "", data: ByteArray = ByteArray(0))

    /** Lays the buttons out again, for example after the selection changed. */
    fun refresh()

    /**
     * The buttons under a finished game's result, centred on [centerX]: play again (once
     * everyone is ready) or, after the last game of a match, the final standings; and the replay.
     */
    fun finishButtons(table: ClientTable, centerX: Int, y: Int) {}
}

/** The game part of the table screen or the replay viewer. It lives as long as the screen. */
interface GamePanel {
    /** Called when the table changes; add buttons through [PanelHost.button]. */
    fun rebuild(table: ClientTable)

    /** A free row across the screen where the replay viewer can put its controls. */
    fun controlsY(): Int

    fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int)

    fun mouseClicked(table: ClientTable, x: Double, y: Double, button: Int): Boolean = false

    /** The mouse moved with the left button held after [mouseClicked], to pick several cards in one sweep. */
    fun mouseDragged(table: ClientTable, x: Double, y: Double): Boolean = false

    /** The left button went up, which ends a sweep. */
    fun mouseReleased(table: ClientTable) {}

    fun keyPressed(table: ClientTable, key: Int): Boolean = false

    /**
     * In the immersive view the player picks cards by clicking them on the table, rather
     * than in a hand along the bottom of the screen. [card] is one of theirs ([TableCard.mine]).
     */
    fun clickTableCard(table: ClientTable, card: TableCard): Boolean = false

    /** The pointer swept over [card] with the left button held after [clickTableCard]: it gets picked or dropped too. */
    fun dragTableCard(table: ClientTable, card: TableCard) {}

    /** Whether one of the player's own cards on the table should stand raised, as picked or playable. */
    fun isRaised(table: ClientTable, card: TableCard): Boolean = false
}

/**
 * One card on the table top. Positions are in blocks from the middle of the table and
 * give the card's centre seen from above; ([upX], [upZ]) is the unit direction the top
 * of the card points to. A card with a [tilt] stands on its bottom edge, leaning that
 * many degrees up from flat, so its face turns towards whoever sits behind it.
 *
 * A card that was not on the table before flies in from ([fromX], [fromZ]), for example
 * from the deck when cards are dealt. Cards that were already there slide to their new
 * place; see [io.github.konyaco.tukuai.client.render.CardMotion].
 */
data class TableCard(
    val x: Float,
    val z: Float,
    val upX: Float,
    val upZ: Float,
    /** Index into the card atlas, see [CardArt]. */
    val sprite: Int,
    val scale: Float = 1f,
    /** Lifts a flat card over the earlier, overlapping cards of its row. */
    val layer: Int = 0,
    val tilt: Float = 0f,
    /** Raises the card off the table, in blocks; used while it flies. */
    val lift: Float = 0f,
    val fromX: Float = x,
    val fromZ: Float = z,
    /**
     * Tells apart cards that look the same, such as the two red fives of an UNO deck, so
     * each one moves from where it was. -1 for a card nobody may tell apart, such as one
     * seen from the back.
     */
    val key: Int = -1,
    /**
     * What the back of the card shows: -1 for the back the player picked, or a sprite such
     * as the face again, for a standing hand a watcher should read from either side.
     */
    val backSprite: Int = -1,
    /**
     * One of the player's own cards, which they pick by clicking it on the table in the
     * immersive view, see [GamePanel.clickTableCard].
     */
    val mine: Boolean = false,
    /** Slides the card this far up along its face, in blocks, as when picked out of a hand. */
    val rise: Float = 0f,
    /** Colour the face is tinted with, as ARGB; -1 leaves it as it is. */
    val tint: Int = -1,
    /** Colour of a lit frame around the card, as ARGB; 0 for none. */
    val outline: Int = 0,
)

object GameUis {
    private val uis = HashMap<String, GameUi>()

    fun register(ui: GameUi) {
        uis[ui.gameId] = ui
    }

    operator fun get(id: String): GameUi? = uis[id]
}
