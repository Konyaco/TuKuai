package io.github.konyaco.tukuai.client

import com.mojang.blaze3d.platform.InputConstants
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameRegistry
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.Occupant
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableSnapshot
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.Tooltip
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
 * There the player turns their head by dragging with the right mouse button, and picks
 * their cards by clicking them on the table; see [TablePointer].
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
    override val inWorld: Boolean get() = ClientConfig.immersive

    /** Set while the right mouse button turns the player's head: where the cursor was when it went down. */
    private var turnFrom: Pair<Double, Double>? = null

    /** How far the mouse has moved while turning, in screen pixels; a right click that barely moves is a click. */
    private var turned = 0.0

    override fun send(op: TableOp, a: Int, b: Int, text: String, data: ByteArray) {
        Services.client.sendToServer(TableActionPayload(pos, op, a, b, text, data))
    }

    fun onTableChanged(updated: ClientTable) {
        update(updated)
        rebuildWidgets()
    }

    private fun update(updated: ClientTable) {
        val before = table?.snapshot?.status
        table = updated
        if (updated.snapshot.status == TableStatus.PLAYING || before != updated.snapshot.status) showLobby = false
    }

    /** Opens the rule book on this table's game; closing it comes back here. */
    fun openRules() {
        RulesScreen.open(this, table, table?.snapshot?.gameId ?: DouDizhu.id)
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
        // Updates that came while another screen (the rule book) was open.
        ClientTables[pos]?.takeIf { it !== table }?.let(::update)
        val t = table
        // Top-right controls, shown in every state. The rule book's button sits beside the
        // first one: the players' boxes on the right leave no room for a fourth row.
        val help = Component.literal("?")
        button(if (ClientConfig.guideSeen) help else help.withColor(YELLOW and 0xFFFFFF), width - CORNER, 4, 20) { openRules() }
            .setTooltip(Tooltip.create(Component.translatable("tukuai.guide.open")))
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
        // The card faces' button sits beside the card back's, as the rule book's does beside the view's.
        button(faceGlyph(ClientConfig.cardFace), width - CORNER, 26, 20) {
            ClientConfig.cardFace = (ClientConfig.cardFace + 1) % CardArt.FACE_STYLES.size
            ClientConfig.save()
            rebuildWidgets()
        }.setTooltip(Tooltip.create(Component.translatable("tukuai.table.card_face", faceName(ClientConfig.cardFace))))
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

    /** Centred, but pushed left on narrow screens so the panel stays clear of the top-right buttons. */
    private fun lobbyLeft() = maxOf(8, minOf((width - lobbyWidth) / 2, width - CORNER - 2 - 8 - lobbyWidth))

    /** Centred, but below the buttons in the top right corner on a screen too narrow to keep clear of them. */
    private fun lobbyTop(t: ClientTable): Int {
        val centred = (height - (SEATS_Y + 46 + t.snapshot.seats.size * 24 + hintHeight(t))) / 2
        val underButtons = lobbyLeft() + lobbyWidth + 8 > width - CORNER - 2
        return if (underButtons) maxOf(centred, 56) else centred
    }

    /** Until the player has opened the rule book, a line at the bottom of the lobby points to it. */
    private fun hintHeight(t: ClientTable) = if (!ClientConfig.guideSeen && t.snapshot.status != TableStatus.PLAYING) 12 else 0

    private fun initLobby(t: ClientTable) {
        val s = t.snapshot
        val left = lobbyLeft()
        val top = lobbyTop(t)
        val game = GameRegistry[s.gameId]
        val canEdit = s.status != TableStatus.PLAYING

        val games = GameRegistry.all.map { it.id }.filter { GameUis[it] != null }
        button(Component.translatable("tukuai.table.game", gameName(s.gameId)), left, top + 16, 112, canEdit && games.size > 1) {
            send(TableOp.GAME, text = games[(games.indexOf(s.gameId) + 1).mod(games.size)])
        }
        spectateButton(s, left + 116, top + 16, 80, canEdit)
        val timers = listOf(0, 15, 30, 60)
        button(timerText(s.turnSeconds), left + 200, top + 16, 100, canEdit) {
            send(TableOp.TIMER, timers[(timers.indexOf(s.turnSeconds) + 1).mod(timers.size)])
        }
        val presets = game?.presets?.map { it.id } ?: listOf(s.presetId)
        button(Component.translatable("tukuai.table.rules", presetName(s.gameId, s.presetId)), left, top + 40, lobbyWidth - 24, canEdit) {
            val next = presets[(presets.indexOf(s.presetId) + 1).mod(presets.size)]
            send(TableOp.PRESET, text = next)
        }
        button(Component.literal("?"), left + lobbyWidth - 20, top + 40, 20) { openRules() }
            .setTooltip(Tooltip.create(Component.translatable("tukuai.guide.open")))

        s.seats.forEachIndexed { i, seat ->
            val y = top + SEATS_Y + i * 24
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

        val bottom = top + SEATS_Y + s.seats.size * 24 + 6
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

    /** Whether others may see every hand. Only the host changes it; the server checks too. */
    private fun spectateButton(s: TableSnapshot, x: Int, y: Int, w: Int, canEdit: Boolean) {
        val mayChange = s.host == null || s.host == s.you
        val text = Component.translatable(if (s.spectators) "tukuai.table.spectate.on" else "tukuai.table.spectate.off")
        val tip = Component.translatable("tukuai.table.spectate.tip")
        if (!mayChange) tip.append("\n").append(Component.translatable("tukuai.table.spectate.host_only", seatName(s.seats.getOrNull(s.host ?: -1)?.occupant)))
        button(text, x, y, w, canEdit && mayChange) { send(TableOp.SPECTATE, if (s.spectators) 0 else 1) }
            .setTooltip(Tooltip.create(tip))
    }

    private fun renderLobby(g: GuiGraphicsExtractor, t: ClientTable) {
        val s = t.snapshot
        val left = lobbyLeft()
        val top = lobbyTop(t)
        val panelBottom = top + SEATS_Y + s.seats.size * 24 + 32 + hintHeight(t)
        g.fill(left - 8, top - 8, left + lobbyWidth + 8, panelBottom, 0xB0000000.toInt())
        if (hintHeight(t) > 0) {
            g.text(font, Component.translatable("tukuai.guide.first_time"), left, panelBottom - 14, YELLOW, false)
        }
        g.text(font, Component.translatable("tukuai.table.title"), left, top, WHITE, true)
        if (s.rounds > 0) {
            val text = Component.translatable("tukuai.table.played", s.rounds)
            g.text(font, text, left + lobbyWidth - font.width(text), top, GRAY, false)
        }
        s.seats.forEachIndexed { i, seat ->
            val y = top + SEATS_Y + i * 24
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
            var tagX = left + 40 + font.width(name) + 4
            if (seat.occupant is Occupant.Human && !seat.present) {
                val away = Component.translatable("tukuai.table.away")
                g.text(font, away, tagX, nameY, GRAY, false)
                tagX += font.width(away) + 4
            }
            if (s.host == i) g.text(font, Component.translatable("tukuai.table.host"), tagX, nameY, YELLOW, false)
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
        TablePointer.hovered = if (t != null && !inLobby(t) && turnFrom == null && children().none { it.isMouseOver(mouseX.toDouble(), mouseY.toDouble()) }) {
            tableCardAt(mouseX.toDouble(), mouseY.toDouble())
        } else {
            null
        }
        when {
            t == null -> g.centeredText(font, Component.translatable("tukuai.table.loading"), width / 2, height / 2, WHITE)
            inLobby(t) -> renderLobby(g, t)
            else -> panel?.render(g, t, mouseX, mouseY)
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        if (inWorld && event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            startTurning()
            return true
        }
        val t = table ?: return false
        if (inLobby(t)) return false
        if (inWorld && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            val card = tableCardAt(event.x(), event.y())
            if (card != null && panel?.clickTableCard(t, card) == true) return true
        }
        return panel?.mouseClicked(t, event.x(), event.y(), event.button()) ?: false
    }

    // ---- The immersive view --------------------------------------------------------------------

    /** Whether one of the player's cards on the table stands raised, as the game panel says. */
    fun isRaised(t: ClientTable, card: TableCard): Boolean = !inLobby(t) && panel?.isRaised(t, card) == true

    private fun tableCardAt(x: Double, y: Double): TableCard? = if (inWorld) TablePointer.cardAt(pos, x, y, width, height) else null

    /**
     * Hides the cursor and holds it in place while the right button turns the head, so the
     * view can turn as far as the player likes, as in the world without a screen.
     */
    private fun startTurning() {
        val mouse = minecraft.mouseHandler
        turnFrom = mouse.xpos() to mouse.ypos()
        turned = 0.0
        InputConstants.grabOrReleaseMouse(minecraft.window, GLFW.GLFW_CURSOR_DISABLED, mouse.xpos(), mouse.ypos())
    }

    /** Shows the cursor again where it was when turning started. */
    private fun stopTurning() {
        val (x, y) = turnFrom ?: return
        turnFrom = null
        InputConstants.grabOrReleaseMouse(minecraft.window, GLFW.GLFW_CURSOR_NORMAL, x, y)
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (turnFrom != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            // Back to screen pixels, then turned as much as the same move would in the world.
            val window = minecraft.window
            val dx = dragX * window.screenWidth / window.guiScaledWidth
            val dy = dragY * window.screenHeight / window.guiScaledHeight
            turned += kotlin.math.abs(dx) + kotlin.math.abs(dy)
            val options = minecraft.options
            val s = options.sensitivity().get() * 0.6 + 0.2
            val k = s * s * s * 8.0
            minecraft.player?.turn(
                if (options.invertMouseX().get()) -dx * k else dx * k,
                if (options.invertMouseY().get()) -dy * k else dy * k,
            )
            return true
        }
        return super.mouseDragged(event, dragX, dragY)
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (turnFrom != null && event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            val (x, y) = turnFrom!!
            stopTurning()
            // A right click that did not turn the view still does what it does on the screen,
            // such as putting back the picked cards.
            val t = table
            if (turned < CLICK_SLOP && t != null && !inLobby(t)) {
                val window = minecraft.window
                panel?.mouseClicked(t, x * window.guiScaledWidth / window.screenWidth, y * window.guiScaledHeight / window.screenHeight, event.button())
            }
            return true
        }
        return super.mouseReleased(event)
    }

    override fun removed() {
        stopTurning()
        TablePointer.forget()
        super.removed()
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val t = table
        if (t != null && !inLobby(t) && panel?.keyPressed(t, event.key()) == true) return true
        if (event.key() == GLFW.GLFW_KEY_TAB) {
            stopTurning()
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

        /** Where the rows of seats start in the lobby, below the game and rules buttons. */
        private const val SEATS_Y = 68

        /** Left edge of the top-right buttons, the rule book's included. */
        private const val CORNER = 98

        /** How far, in screen pixels, the mouse may move during a right click before it turns the view instead. */
        private const val CLICK_SLOP = 6.0

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

        /** The name of a card face style, see [CardArt.FACE_STYLES]. */
        fun faceName(style: Int): Component = Component.translatable("tukuai.card_face.${CardArt.FACE_STYLES[style]}")

        /** A hint of each card face style on its button: a red diamond, a big A, a blue diamond. */
        private fun faceGlyph(style: Int): Component = when (CardArt.FACE_STYLES[style]) {
            "large" -> Component.literal("A").withStyle { it.withBold(true) }
            "four_color" -> Component.literal("♦").withColor(0x4A7CFF)
            else -> Component.literal("♦").withColor(0xFF5A50)
        }

        fun gameName(gameId: String): Component = Component.translatable("tukuai.game.$gameId")

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
