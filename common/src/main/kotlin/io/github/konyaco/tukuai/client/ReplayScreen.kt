package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameRegistry
import io.github.konyaco.tukuai.engine.core.MatchHandle
import io.github.konyaco.tukuai.engine.core.Replay
import io.github.konyaco.tukuai.engine.core.presetIdOf
import io.github.konyaco.tukuai.engine.core.rebuildAny
import io.github.konyaco.tukuai.table.Occupant
import io.github.konyaco.tukuai.table.SeatInfo
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableSnapshot
import io.github.konyaco.tukuai.table.TableStatus
import java.util.UUID
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.item.DyeColor
import org.lwjgl.glfw.GLFW

/**
 * Watches a saved game move by move, drawn by the same [GamePanel] as a live game but
 * with every hand shown. The viewer can step back and forth and switch whose seat to watch from.
 */
class ReplayScreen(private val name: String, private val replay: Replay, startSeat: Int = 0) :
    CompatScreen(Component.translatable("tukuai.replay.title")), PanelHost {

    private val ui = GameUis[replay.gameId]
    private val presetId = GameRegistry[replay.gameId]?.let { it.presetIdOf(replay.rules) ?: it.presets.first().id } ?: ""
    /** How many moves the game has. */
    val total = replay.steps.size

    private var match: MatchHandle? = null
    private var broken = false
    private var table: ClientTable? = null
    private var panel: GamePanel? = null

    /** How many moves have been played so far. */
    var step = 0
        private set
    var seat = startSeat.coerceIn(0, (replay.players.size - 1).coerceAtLeast(0))
        private set
    var playing = true
        private set
    private var wait = 0

    init {
        seek(0)
    }

    override val panelWidth: Int get() = width
    override val panelHeight: Int get() = height
    override val interactive: Boolean get() = false

    override fun send(op: TableOp, a: Int, b: Int, text: String, data: ByteArray) {}

    override fun refresh() = rebuildWidgets()

    override fun button(text: Component, x: Int, y: Int, w: Int, active: Boolean, h: Int, onPress: () -> Unit): Button {
        val b = Button.builder(text) { onPress() }.bounds(x, y, w, h).build()
        b.active = active
        return addRenderableWidget(b)
    }

    // ---- Moving through the game ---------------------------------------------------------------

    /** Shows the game after [target] moves. Going forward replays only the new moves. */
    fun seek(target: Int) {
        val to = target.coerceIn(0, total)
        try {
            var m = match
            if (m == null || to < step) {
                m = replay.rebuildAny(to)
            } else {
                for (i in step until to) {
                    val s = replay.steps[i]
                    m.submit(s.seat, s.action)?.let { error -> throw IllegalStateException("Move $i was refused: $error") }
                }
            }
            match = m
            step = to
        } catch (e: Exception) {
            TuKuai.LOGGER.warn("Could not play the replay {}", name, e)
            broken = true
            match = null
        }
        if (step >= total) playing = false
        wait = 0
        updateTable()
    }

    fun setSeat(target: Int) {
        seat = target.mod(replay.players.size.coerceAtLeast(1))
        updateTable()
    }

    fun pause() {
        playing = false
        if (minecraft != null) rebuildWidgets()
    }

    private fun togglePlaying() {
        if (step >= total) seek(0)
        playing = !playing
        wait = 0
        rebuildWidgets()
    }

    private fun updateTable() {
        val m = match
        table = if (m == null) {
            null
        } else {
            val snapshot = TableSnapshot(
                gameId = replay.gameId,
                presetId = presetId,
                felt = DyeColor.GREEN.id,
                turnSeconds = 0,
                seats = replay.players.map { SeatInfo(occupantOf(it), present = true, auto = false) },
                you = seat,
                tally = List(replay.players.size) { 0 },
                rounds = 0,
                status = if (m.result != null) TableStatus.FINISHED else TableStatus.PLAYING,
                step = step,
                timerTicks = -1,
                view = m.encodedOpenView(seat),
            )
            ClientTable(BlockPos.ZERO, snapshot, ClientTables.ticks)
        }
        if (minecraft != null) rebuildWidgets()
    }

    override fun tick() {
        if (!playing || broken) return
        if (++wait >= STEP_TICKS) seek(step + 1)
    }

    // ---- Layout --------------------------------------------------------------------------------

    override fun init() {
        val t = table ?: return
        if (panel == null) panel = ui?.createPanel(this)
        val p = panel ?: return
        p.rebuild(t)

        button(Component.translatable("tukuai.replay.seat", TableScreen.seatName(occupantOf(replay.players[seat]), seat)), width - 134, 4, 130) {
            setSeat(seat + 1)
        }

        val y = p.controlsY()
        val widths = intArrayOf(24, 24, 44, 24, 24)
        val gap = 4
        var x = (width - (widths.sum() + gap * (widths.size - 1))) / 2
        val controls = listOf<Triple<String, Boolean, () -> Unit>>(
            Triple("tukuai.replay.first", step > 0) { seek(0) },
            Triple("tukuai.replay.back", step > 0) { back() },
            Triple(if (playing) "tukuai.replay.pause" else "tukuai.replay.play", true) { togglePlaying() },
            Triple("tukuai.replay.forward", step < total) { forward() },
            Triple("tukuai.replay.last", step < total) { seek(total) },
        )
        controls.forEachIndexed { i, (key, active, action) ->
            button(Component.translatable(key), x, y, widths[i], active) { action() }
            x += widths[i] + gap
        }
        stepTextX = x + 2
        stepTextY = y + 6
    }

    private var stepTextX = 0
    private var stepTextY = 0

    private fun back() {
        playing = false
        seek(step - 1)
    }

    private fun forward() {
        playing = false
        seek(step + 1)
    }

    // ---- Drawing -------------------------------------------------------------------------------

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fill(0, 0, width, height, 0xFF000000.toInt() or TableScreen.darken(DyeColor.GREEN.textureDiffuseColor))
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val t = table
        if (t == null || panel == null) {
            val key = if (broken || ui == null) "tukuai.replay.broken" else "tukuai.table.loading"
            g.centeredText(font, Component.translatable(key), width / 2, height / 2, TableScreen.WHITE)
        } else {
            panel?.render(g, t, mouseX, mouseY)
            val time = SAVED_AT.find(name)?.destructured?.let { (_, mo, d, h, mi) -> "$mo-$d $h:$mi" }
            val label = if (time == null) Component.translatable("tukuai.replay.title") else Component.translatable("tukuai.replay.named", time)
            g.text(font, label, 6, 17, TableScreen.YELLOW, true)
            g.text(font, Component.translatable("tukuai.replay.step", step, total), stepTextX, stepTextY, TableScreen.WHITE, true)
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        when (event.key()) {
            GLFW.GLFW_KEY_SPACE -> togglePlaying()
            GLFW.GLFW_KEY_LEFT -> back()
            GLFW.GLFW_KEY_RIGHT -> forward()
            GLFW.GLFW_KEY_HOME -> seek(0)
            GLFW.GLFW_KEY_END -> seek(total)
            GLFW.GLFW_KEY_TAB -> setSeat(seat + 1)
            else -> return super.keyPressed(event)
        }
        return true
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        /** Replay files are named after when the game ended, `yyyyMMdd-HHmmss-<game>`. */
        private val SAVED_AT = Regex("^(\\d{4})(\\d{2})(\\d{2})-(\\d{2})(\\d{2})")

        /** One move a second while playing. */
        private const val STEP_TICKS = 20

        /** Opens a replay sent by the server, starting from the viewer's own seat if they played in it. */
        fun open(name: String, data: ByteArray) {
            val mc = net.minecraft.client.Minecraft.getInstance()
            val replay = try {
                Replay.decode(data)
            } catch (e: Exception) {
                TuKuai.LOGGER.warn("Could not read the replay {}", name, e)
                return
            }
            val own = mc.player?.let { replay.players.indexOf(it.name.string) } ?: -1
            mc.setScreen(ReplayScreen(name, replay, own.coerceAtLeast(0)))
        }

        /** Replays store each seat as a player name, or `bot:<level>` for a computer player. */
        fun occupantOf(player: String): Occupant? = when {
            player.isEmpty() -> null
            player.startsWith("bot:") ->
                Occupant.Computer(BotLevel.entries.firstOrNull { it.name.equals(player.removePrefix("bot:"), ignoreCase = true) } ?: BotLevel.NORMAL)
            else -> Occupant.Human(UUID.nameUUIDFromBytes(player.toByteArray()), player)
        }
    }
}
