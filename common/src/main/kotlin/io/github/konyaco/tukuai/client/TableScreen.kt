package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameRegistry
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.Occupant
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.Component
import net.minecraft.world.item.DyeColor
import org.lwjgl.glfw.GLFW

/**
 * The screen opened by right-clicking a card table. The lobby part (seats, rules, the
 * time limit) is the same for every game; the game itself is drawn by that game's [GamePanel].
 *
 * In immersive mode nothing covers the world, so the table and the cards on it stay in view.
 */
class TableScreen(val pos: BlockPos) : Screen(Component.translatable("tukuai.table.title")), PanelHost {
    private var table: ClientTable? = ClientTables[pos]
    private var panel: GamePanel? = null
    private var panelGame: String? = null

    /** After a game ends, players may look at the lobby instead of the result. */
    private var showLobby = false

    override val panelWidth: Int get() = width
    override val panelHeight: Int get() = height
    override val interactive: Boolean get() = true

    override fun send(op: TableOp, a: Int, b: Int, text: String, data: ByteArray) {
        Services.client.sendToServer(TableActionPayload(pos, op, a, b, text, data))
    }

    fun onTableChanged(updated: ClientTable) {
        val before = table?.snapshot?.status
        table = updated
        if (updated.snapshot.status == TableStatus.PLAYING || before != updated.snapshot.status) showLobby = false
        rebuildWidgets()
    }

    override fun refresh() = rebuildWidgets()

    /** Back to the seats and rules once a game is over, to start the next one. */
    fun openLobby() {
        showLobby = true
        rebuildWidgets()
    }

    override fun button(text: Component, x: Int, y: Int, w: Int, active: Boolean, h: Int, onPress: () -> Unit): Button {
        val b = Button.builder(text) { onPress() }.bounds(x, y, w, h).build()
        b.active = active
        return addRenderableWidget(b)
    }

    private fun inLobby(t: ClientTable?): Boolean =
        t == null || t.snapshot.status == TableStatus.LOBBY || (t.snapshot.status == TableStatus.FINISHED && showLobby) || t.view == null

    override fun init() {
        val t = table
        // Top-right controls, shown in every state.
        button(Component.translatable(if (ClientConfig.immersive) "tukuai.table.mode.immersive" else "tukuai.table.mode.full"), width - 74, 4, 70) {
            ClientConfig.immersive = !ClientConfig.immersive
            ClientConfig.save()
            rebuildWidgets()
        }
        button(Component.translatable("tukuai.table.card_back", ClientConfig.cardBack + 1), width - 74, 26, 70) {
            ClientConfig.cardBack = (ClientConfig.cardBack + 1) % CardArt.BACK_COUNT
            ClientConfig.save()
            rebuildWidgets()
        }
        if (t == null) return
        if (inLobby(t)) {
            initLobby(t)
        } else {
            if (panel == null || panelGame != t.snapshot.gameId) {
                panel = t.ui?.createPanel(this)
                panelGame = t.snapshot.gameId
            }
            panel?.rebuild(t)
            if (t.snapshot.status == TableStatus.FINISHED) {
                button(Component.translatable("tukuai.table.lobby"), width - 74, 48, 70) { openLobby() }
            }
        }
    }

    // ---- Lobby ---------------------------------------------------------------------------------

    private val lobbyWidth = 300

    private fun lobbyLeft() = (width - lobbyWidth) / 2

    /** Centred, but below the buttons in the top right corner on a screen too narrow to keep clear of them. */
    private fun lobbyTop(t: ClientTable): Int {
        val centred = (height - (90 + t.snapshot.seats.size * 24)) / 2
        val underButtons = lobbyLeft() + lobbyWidth + 8 > width - 76
        return if (underButtons) maxOf(centred, 56) else centred
    }

    private fun initLobby(t: ClientTable) {
        val s = t.snapshot
        val left = lobbyLeft()
        val top = lobbyTop(t)
        val game = GameRegistry[s.gameId]
        val canEdit = s.status != TableStatus.PLAYING

        val presets = game?.presets?.map { it.id } ?: listOf(s.presetId)
        button(Component.translatable("tukuai.table.rules", presetName(s.gameId, s.presetId)), left, top + 16, 196, canEdit) {
            val next = presets[(presets.indexOf(s.presetId) + 1).mod(presets.size)]
            send(TableOp.PRESET, text = next)
        }
        val timers = listOf(0, 15, 30, 60)
        button(timerText(s.turnSeconds), left + 200, top + 16, 100, canEdit) {
            send(TableOp.TIMER, timers[(timers.indexOf(s.turnSeconds) + 1).mod(timers.size)])
        }

        s.seats.forEachIndexed { i, seat ->
            val y = top + 44 + i * 24
            val x = left + 150
            when (val o = seat.occupant) {
                null -> {
                    button(Component.translatable("tukuai.table.sit"), x, y, 70, canEdit) { send(TableOp.SIT, i) }
                    button(Component.translatable("tukuai.table.add_bot"), x + 74, y, 76, canEdit) {
                        send(TableOp.ADD_BOT, i, ClientConfig.botLevel.ordinal)
                    }
                }
                is Occupant.Computer -> button(Component.translatable("tukuai.table.remove_bot"), x, y, 70, canEdit) {
                    send(TableOp.REMOVE_BOT, i)
                }
                is Occupant.Human -> if (s.you == i) {
                    button(Component.translatable("tukuai.table.stand"), x, y, 70) { send(TableOp.STAND) }
                }
            }
        }

        val bottom = top + 44 + s.seats.size * 24 + 6
        button(botLevelText(ClientConfig.botLevel), left, bottom, 96) {
            ClientConfig.botLevel = BotLevel.entries[(ClientConfig.botLevel.ordinal + 1) % BotLevel.entries.size]
            ClientConfig.save()
            rebuildWidgets()
        }
        button(Component.translatable("tukuai.table.fill_bots"), left + 100, bottom, 96, canEdit && s.seats.any { it.occupant == null }) {
            send(TableOp.ADD_BOT, -1, ClientConfig.botLevel.ordinal)
        }
        val ready = canEdit && s.you != null && s.seats.all { it.occupant != null }
        button(Component.translatable("tukuai.table.start"), left + 200, bottom, 100, ready) { send(TableOp.START) }
        if (s.status == TableStatus.PLAYING) {
            // A player looking at the lobby mid-game (only when the view could not be read).
            button(Component.translatable("tukuai.table.sit_back"), left + 200, bottom + 24, 100, s.you != null) {
                send(TableOp.SIT, s.you ?: 0)
            }
        }
    }

    private fun renderLobby(g: GuiGraphicsExtractor, t: ClientTable) {
        val s = t.snapshot
        val left = lobbyLeft()
        val top = lobbyTop(t)
        val panelBottom = top + 44 + s.seats.size * 24 + 32
        g.fill(left - 8, top - 8, left + lobbyWidth + 8, panelBottom, 0xB0000000.toInt())
        g.text(font, Component.translatable("tukuai.game.${s.gameId}"), left, top, WHITE, true)
        if (s.rounds > 0) {
            val text = Component.translatable("tukuai.table.played", s.rounds)
            g.text(font, text, left + lobbyWidth - font.width(text), top, GRAY, false)
        }
        s.seats.forEachIndexed { i, seat ->
            val y = top + 44 + i * 24
            val name = seatName(seat.occupant)
            val color = when {
                seat.occupant == null -> GRAY
                s.you == i -> YELLOW
                else -> WHITE
            }
            g.text(font, Component.translatable("tukuai.table.seat", i + 1), left, y + 6, GRAY, false)
            // A player who has played before gets their record under the name.
            val nameY = if (seat.games > 0) y + 1 else y + 6
            g.text(font, name, left + 40, nameY, color, false)
            if (seat.games > 0) {
                g.text(font, Component.translatable("tukuai.table.record", seat.games, seat.wins), left + 40, y + 12, GRAY, false)
            }
            val points = s.tally.getOrNull(i) ?: 0
            if (s.rounds > 0) {
                val p = Component.literal(if (points > 0) "+$points" else points.toString())
                g.text(font, p, left + 146 - font.width(p), y + 6, if (points >= 0) GREEN else RED, false)
            }
            if (seat.occupant is Occupant.Human && !seat.present) {
                g.text(font, Component.translatable("tukuai.table.away"), left + 40 + font.width(name) + 4, nameY, GRAY, false)
            }
        }
    }

    // ---- Drawing -------------------------------------------------------------------------------

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (ClientConfig.immersive) return
        val felt = DyeColor.byId(table?.snapshot?.felt ?: DyeColor.GREEN.id).textureDiffuseColor
        g.fill(0, 0, width, height, 0xFF000000.toInt() or darken(felt))
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val t = table
        when {
            t == null -> g.centeredText(font, Component.translatable("tukuai.table.loading"), width / 2, height / 2, WHITE)
            inLobby(t) -> renderLobby(g, t)
            else -> panel?.render(g, t, mouseX, mouseY)
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        val t = table ?: return false
        if (inLobby(t)) return false
        return panel?.mouseClicked(t, event.x(), event.y(), event.button()) ?: false
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val t = table
        if (t != null && !inLobby(t) && panel?.keyPressed(t, event.key()) == true) return true
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            ClientConfig.immersive = !ClientConfig.immersive
            ClientConfig.save()
            rebuildWidgets()
            return true
        }
        return super.keyPressed(event)
    }

    override fun isPauseScreen(): Boolean = false

    override fun tick() {
        val player = minecraft.player
        if (player == null || player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos)) > 10.0 * 10.0) onClose()
    }

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GRAY = 0xFFA0A0A0.toInt()
        const val YELLOW = 0xFFFFDD55.toInt()
        const val GREEN = 0xFF66DD66.toInt()
        const val RED = 0xFFFF6666.toInt()

        fun open(pos: BlockPos) {
            val mc = net.minecraft.client.Minecraft.getInstance()
            if (mc.screen !is TableScreen) mc.setScreen(TableScreen(pos))
        }

        fun seatName(occupant: Occupant?): Component = when (occupant) {
            null -> Component.translatable("tukuai.table.empty")
            is Occupant.Human -> Component.literal(occupant.name)
            is Occupant.Computer -> Component.translatable("tukuai.table.bot", botLevelName(occupant.level))
        }

        fun botLevelName(level: BotLevel): Component = Component.translatable("tukuai.bot.${level.name.lowercase()}")

        fun botLevelText(level: BotLevel): Component = Component.translatable("tukuai.table.bot_level", botLevelName(level))

        fun presetName(gameId: String, presetId: String): Component = Component.translatable("tukuai.preset.$gameId.$presetId")

        fun timerText(seconds: Int): Component =
            if (seconds == 0) Component.translatable("tukuai.table.timer.off") else Component.translatable("tukuai.table.timer", seconds)

        /** A dark version of a dye colour for the screen background, so white text stays readable. */
        fun darken(rgb: Int): Int {
            val r = (rgb shr 16 and 0xFF) * 35 / 100
            val gr = (rgb shr 8 and 0xFF) * 35 / 100
            val b = (rgb and 0xFF) * 35 / 100
            return (r shl 16) or (gr shl 8) or b
        }
    }
}
