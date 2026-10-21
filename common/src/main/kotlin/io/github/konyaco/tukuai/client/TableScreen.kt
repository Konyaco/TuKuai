package io.github.konyaco.tukuai.client

import com.mojang.blaze3d.platform.InputConstants
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.card.Suit
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameRegistry
import io.github.konyaco.tukuai.engine.core.seatCount
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
 * time limit, the length of a match) is the same for every game; the game itself is
 * drawn by that game's [GamePanel], with the [Scoreboard] in the top left corner.
 *
 * The top right corner holds the rule book, the view, the menu with the looks of the
 * cards and stopping the match, and one button for the moment: auto play during a game,
 * the lobby after one. Popups (the menu, the leaderboard, the final standings, and a
 * proposal to stop) are drawn over everything else, and while one is open the buttons
 * under it are left out.
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

    private enum class Popup { MENU, LEADERBOARD, STANDINGS }

    /** A match that was stopped before anyone opened the screen ends on its standings. */
    private var popup: Popup? = table?.snapshot?.takeIf { it.matchOver && it.status == TableStatus.LOBBY }?.let { Popup.STANDINGS }

    /** True while [init] lays out a popup: the game panel still keeps up, but its buttons are not shown. */
    private var hidePanelButtons = false

    override val panelWidth: Int get() = width
    override val panelHeight: Int get() = height
    override val interactive: Boolean get() = true
    override val inWorld: Boolean get() = ClientConfig.immersive

    /** Set while the right mouse button turns the player's head: where the cursor was when it went down. */
    private var turnFrom: Pair<Double, Double>? = null

    /** How far the mouse has moved while turning, in screen pixels; a right click that barely moves is a click. */
    private var turned = 0.0

    /** Set while the left button sweeps over the player's cards on the table in the immersive view. */
    private var sweeping = false

    override fun send(op: TableOp, a: Int, b: Int, text: String, data: ByteArray) {
        Services.client.sendToServer(TableActionPayload(pos, op, a, b, text, data))
    }

    fun onTableChanged(updated: ClientTable) {
        update(updated)
        rebuildWidgets()
    }

    private fun update(updated: ClientTable) {
        val before = table?.snapshot
        table = updated
        val now = updated.snapshot
        if (now.status == TableStatus.PLAYING || before?.status != now.status) showLobby = false
        // A new game closes what was about the last one; a match everyone stopped ends on its standings.
        if (now.status == TableStatus.PLAYING && popup != Popup.MENU) popup = null
        if (now.matchOver && before?.matchOver == false && now.status == TableStatus.LOBBY) popup = Popup.STANDINGS
        if (popup == Popup.STANDINGS && !now.matchOver) popup = null
    }

    /** Opens the rule book on this table's game; closing it comes back here. */
    fun openRules() {
        RulesScreen.open(this, table, table?.snapshot?.gameId ?: DouDizhu.id)
    }

    override fun refresh() = rebuildWidgets()

    /** Back to the seats and rules once a game is over, to start the next one. */
    fun openLobby() {
        showLobby = true
        popup = null
        rebuildWidgets()
    }

    private fun openPopup(next: Popup?) {
        popup = next
        rebuildWidgets()
    }

    override fun button(text: Component, x: Int, y: Int, w: Int, active: Boolean, h: Int, onPress: () -> Unit): Button {
        val b = Button.builder(text) { onPress() }.bounds(x, y, w, h).build()
        b.active = active
        return if (hidePanelButtons) b else addRenderableWidget(b)
    }

    private fun inLobby(t: ClientTable?): Boolean =
        t == null || t.snapshot.status == TableStatus.LOBBY || (t.snapshot.status == TableStatus.FINISHED && showLobby) || t.view == null

    override fun init() {
        // Updates that came while another screen (the rule book) was open.
        ClientTables[pos]?.takeIf { it !== table }?.let(::update)
        val t = table
        initCorner(t)
        if (t == null) return
        val covered = endPrompt(t) || popup != null
        if (inLobby(t)) {
            if (!covered) initLobby(t)
        } else {
            if (panel == null || panelGame != t.snapshot.gameId) {
                panel = t.ui?.createPanel(this)
                panelGame = t.snapshot.gameId
            }
            // The panel keeps up with the table even under a popup, so it still notices the player's turn.
            hidePanelButtons = covered
            panel?.rebuild(t)
            hidePanelButtons = false
        }
        when {
            endPrompt(t) -> initEndPrompt(t)
            popup == Popup.MENU -> initMenu(t)
            popup == Popup.LEADERBOARD -> initLeaderboard()
            popup == Popup.STANDINGS -> initStandings(t)
        }
    }

    // ---- The top right corner ------------------------------------------------------------------

    private fun initCorner(t: ClientTable?) {
        val help = Component.literal("?")
        button(if (ClientConfig.guideSeen) help else help.withColor(YELLOW and 0xFFFFFF), width - CORNER, 4, 20) { openRules() }
            .setTooltip(Tooltip.create(Component.translatable("tukuai.guide.open")))
        button(Component.translatable(if (ClientConfig.immersive) "tukuai.table.mode.immersive" else "tukuai.table.mode.full"), width - 74, 4, 70) {
            ClientConfig.immersive = !ClientConfig.immersive
            ClientConfig.save()
            rebuildWidgets()
        }.setTooltip(Tooltip.create(Component.translatable("tukuai.table.mode.tip")))
        button(Component.literal("☰"), width - CORNER, 26, 20) {
            openPopup(if (popup == Popup.MENU) null else Popup.MENU)
        }.setTooltip(Tooltip.create(Component.translatable("tukuai.table.menu")))
        if (t == null || endPrompt(t)) return
        val s = t.snapshot
        if (s.status == TableStatus.PLAYING && s.you != null && !inLobby(t)) {
            val auto = s.seats.getOrNull(s.you)?.auto == true
            button(Component.translatable(if (auto) "tukuai.table.auto.off" else "tukuai.table.auto.on"), width - 74, 26, 70) {
                send(TableOp.AUTO, if (auto) 0 else 1)
            }
        } else if (s.status == TableStatus.FINISHED && !inLobby(t)) {
            button(Component.translatable("tukuai.table.lobby"), width - 74, 26, 70) { openLobby() }
        }
    }

    // ---- After a game --------------------------------------------------------------------------

    override fun finishButtons(table: ClientTable, centerX: Int, y: Int) {
        val s = table.snapshot
        if (s.matchOver) {
            button(Component.translatable("tukuai.table.standings").withColor(YELLOW and 0xFFFFFF), centerX - 100, y, 98) {
                openPopup(Popup.STANDINGS)
            }
        } else {
            readyButton(s, centerX - 100, y, 98, again = true)
        }
        button(Component.translatable("tukuai.table.replay"), centerX + 2, y, 98) { send(TableOp.REPLAY) }
    }

    /**
     * Start, play again or start a new match. With other players at the table it says how
     * many are ready, and pressing it again takes it back; the game starts when all are.
     */
    private fun readyButton(s: TableSnapshot, x: Int, y: Int, w: Int, again: Boolean) {
        val me = s.you
        val full = s.seats.all { it.occupant != null }
        val voters = s.voters
        val readyCount = voters.count { s.seats[it].ready }
        val mine = me != null && s.seats.getOrNull(me)?.ready == true
        val base = Component.translatable(
            when {
                s.matchOver -> "tukuai.table.new_match"
                again -> "tukuai.table.again"
                else -> "tukuai.table.start"
            },
        )
        val shared = voters.size > 1 && me in voters
        val text = when {
            !shared -> base
            mine -> Component.translatable("tukuai.table.unready", readyCount, voters.size)
            else -> Component.translatable("tukuai.table.ready_count", base, readyCount, voters.size)
        }
        val b = button(text, x, y, w, me != null && full && s.status != TableStatus.PLAYING) {
            send(if (mine) TableOp.UNREADY else TableOp.START)
        }
        if (shared) {
            val waiting = voters.filter { !s.seats[it].ready }.joinToString(", ") { seatName(s.seats[it].occupant).string }
            val tip = Component.translatable("tukuai.table.ready.tip")
            if (waiting.isNotEmpty()) tip.append("\n").append(Component.translatable("tukuai.table.ready.waiting", waiting))
            b.setTooltip(Tooltip.create(tip))
        }
    }

    // ---- Lobby ---------------------------------------------------------------------------------

    private val lobbyWidth = 300

    /** Centred, but pushed left on narrow screens so the panel stays clear of the top-right buttons. */
    private fun lobbyLeft() = maxOf(8, minOf((width - lobbyWidth) / 2, width - CORNER - 2 - 8 - lobbyWidth))

    /** Centred, but below the buttons in the top right corner on a screen too narrow to keep clear of them. */
    private fun lobbyTop(t: ClientTable): Int {
        val centred = (height - (SEATS_Y + 46 + seatRows(t) * 24 + hintHeight(t))) / 2
        val underButtons = lobbyLeft() + lobbyWidth + 8 > width - CORNER - 2
        return if (underButtons) maxOf(centred, 56) else centred
    }

    /** More than four seats go in two columns, each seat in a narrower cell. */
    private fun twoColumns(t: ClientTable) = t.snapshot.seats.size > 4

    private fun seatRows(t: ClientTable) = if (twoColumns(t)) (t.snapshot.seats.size + 1) / 2 else t.snapshot.seats.size

    /** Top left corner of seat [i]'s row in the lobby. */
    private fun seatCell(t: ClientTable, i: Int, left: Int, top: Int): Pair<Int, Int> {
        if (!twoColumns(t)) return left to top + SEATS_Y + i * 24
        val rows = seatRows(t)
        return left + (i / rows) * (lobbyWidth / 2 + 4) to top + SEATS_Y + (i % rows) * 24
    }

    /** Until the player has opened the rule book, a line at the bottom of the lobby points to it. */
    private fun hintHeight(t: ClientTable) = if (!ClientConfig.guideSeen && t.snapshot.status != TableStatus.PLAYING) 12 else 0

    private fun initLobby(t: ClientTable) {
        val s = t.snapshot
        val left = lobbyLeft()
        val top = lobbyTop(t)
        val game = GameRegistry[s.gameId]
        val canEdit = s.status != TableStatus.PLAYING

        button(Component.translatable("tukuai.table.leaderboard"), left + lobbyWidth - 64, top - 3, 64, h = 14) {
            openPopup(Popup.LEADERBOARD)
        }
        val games = GameRegistry.all.map { it.id }.filter { GameUis[it] != null }
        button(Component.translatable("tukuai.table.game", gameName(s.gameId)), left, top + 16, 148, canEdit && games.size > 1) {
            send(TableOp.GAME, text = games[(games.indexOf(s.gameId) + 1).mod(games.size)])
        }
        // Only rules this table has room for; eight players need a big table.
        val presets = game?.presets?.map { it.id }?.filter { game.seatCount(it) <= t.maxSeats }?.ifEmpty { null } ?: listOf(s.presetId)
        button(Component.translatable("tukuai.table.rules", presetName(s.gameId, s.presetId)), left + 152, top + 16, 148, canEdit) {
            send(TableOp.PRESET, text = presets[(presets.indexOf(s.presetId) + 1).mod(presets.size)])
        }
        val timers = listOf(0, 15, 30, 60)
        button(timerText(s.turnSeconds), left, top + 40, 97, canEdit) {
            send(TableOp.TIMER, timers[(timers.indexOf(s.turnSeconds) + 1).mod(timers.size)])
        }
        button(matchText(s.matchRounds), left + 101, top + 40, 98, canEdit) {
            send(TableOp.ROUNDS, ROUND_CHOICES[(ROUND_CHOICES.indexOf(s.matchRounds) + 1).mod(ROUND_CHOICES.size)])
        }.setTooltip(Tooltip.create(Component.translatable("tukuai.table.match.tip")))
        spectateButton(s, left + 203, top + 40, 97, canEdit)

        val narrow = twoColumns(t)
        s.seats.forEachIndexed { i, seat ->
            val (cellX, y) = seatCell(t, i, left, top)
            // In two columns the buttons share the right half of a cell half as wide.
            val x = if (narrow) cellX + 74 else left + 150
            val w = if (narrow) 36 else 70
            val botW = if (narrow) 36 else 76
            when (seat.occupant) {
                null -> {
                    button(Component.translatable("tukuai.table.sit"), x, y, w, canEdit) { send(TableOp.SIT, i) }
                    button(Component.translatable("tukuai.table.add_bot"), x + w + 4, y, botW, canEdit) {
                        send(TableOp.ADD_BOT, i, ClientConfig.botLevel.ordinal)
                    }
                }
                is Occupant.Computer -> button(Component.translatable("tukuai.table.remove_bot"), x, y, if (narrow) 76 else 70, canEdit) {
                    send(TableOp.REMOVE_BOT, i)
                }
                is Occupant.Human -> if (s.you == i) {
                    button(Component.translatable("tukuai.table.stand"), x, y, if (narrow) 76 else 70) { send(TableOp.STAND) }
                }
            }
        }

        val bottom = top + SEATS_Y + seatRows(t) * 24 + 6
        button(botLevelText(ClientConfig.botLevel), left, bottom, 96) {
            ClientConfig.botLevel = BotLevel.entries[(ClientConfig.botLevel.ordinal + 1) % BotLevel.entries.size]
            ClientConfig.save()
            rebuildWidgets()
        }
        button(Component.translatable("tukuai.table.fill_bots"), left + 100, bottom, 96, canEdit && s.seats.any { it.occupant == null }) {
            send(TableOp.ADD_BOT, -1, ClientConfig.botLevel.ordinal)
        }
        if (s.status == TableStatus.PLAYING) {
            // A player looking at the lobby mid-game (only when the view could not be read).
            button(Component.translatable("tukuai.table.sit_back"), left + 200, bottom, 100, s.you != null) {
                send(TableOp.SIT, s.you ?: 0)
            }
        } else {
            readyButton(s, left + 200, bottom, 100, again = false)
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
        val panelBottom = top + SEATS_Y + seatRows(t) * 24 + 32 + hintHeight(t)
        panelBox(g, left - 8, top - 8, left + lobbyWidth + 8, panelBottom)
        if (hintHeight(t) > 0) {
            g.text(font, Component.translatable("tukuai.guide.first_time"), left, panelBottom - 14, YELLOW, false)
        }
        val title = Component.translatable("tukuai.table.title")
        g.text(font, title, left, top, WHITE, true)
        if (s.rounds > 0) {
            g.text(font, Scoreboard.roundLine(t), left + font.width(title) + 8, top, if (s.matchOver) YELLOW else GRAY, false)
        }
        // A thin line between the rules and the seats.
        g.fill(left, top + SEATS_Y - 7, left + lobbyWidth, top + SEATS_Y - 6, DIVIDER)
        val voters = s.voters
        val narrow = twoColumns(t)
        s.seats.forEachIndexed { i, seat ->
            val (cellX, y) = seatCell(t, i, left, top)
            val color = when {
                seat.occupant == null -> GRAY
                s.you == i -> YELLOW
                else -> WHITE
            }
            // In two columns the seat is just its number, and the name gets what room is left before the buttons.
            val label = if (narrow) Component.literal("${i + 1}") else Component.translatable("tukuai.table.seat", i + 1)
            g.text(font, label, cellX, y + 6, GRAY, false)
            // A player who has played before gets their record under the name.
            val nameY = if (seat.games > 0) y + 1 else y + 6
            val nameX = if (narrow) cellX + 10 else left + 40
            val pointsText = if (s.rounds > 0) Scoreboard.signed(s.tally.getOrNull(i) ?: 0) else ""
            val nameEnd = if (narrow) cellX + 72 - (if (pointsText.isEmpty()) 0 else font.width(pointsText) + 3) else Int.MAX_VALUE
            val name = if (narrow) Component.literal(font.plainSubstrByWidth(seatName(seat.occupant).string, nameEnd - nameX)) else seatName(seat.occupant)
            g.text(font, name, nameX, nameY, color, false)
            if (seat.games > 0) {
                g.text(font, Component.translatable("tukuai.table.record", seat.games, seat.wins), nameX, y + 12, GRAY, false)
            }
            if (s.rounds > 0) {
                val points = s.tally.getOrNull(i) ?: 0
                val right = if (narrow) cellX + 72 else left + 146
                g.text(font, pointsText, right - font.width(pointsText), y + 6, Scoreboard.pointsColor(points), false)
            }
            if (narrow) return@forEachIndexed
            var tagX = nameX + font.width(name) + 4
            if (seat.occupant is Occupant.Human && !seat.present) {
                val away = Component.translatable("tukuai.table.away")
                g.text(font, away, tagX, nameY, GRAY, false)
                tagX += font.width(away) + 4
            }
            if (s.host == i) {
                val host = Component.translatable("tukuai.table.host")
                g.text(font, host, tagX, nameY, YELLOW, false)
                tagX += font.width(host) + 4
            }
            if (seat.ready && i in voters && voters.size > 1) g.text(font, "✔", tagX, nameY, GREEN, false)
        }
    }

    // ---- Popups --------------------------------------------------------------------------------

    /** Someone proposed to stop the match, and this player, who is in it, has not said yes yet. */
    private fun endPrompt(t: ClientTable): Boolean {
        val s = t.snapshot
        val me = s.you ?: return false
        return !s.matchOver && s.seats.any { it.endVote } && me in s.voters && s.seats.getOrNull(me)?.endVote != true
    }

    /** Whether this player may propose to stop the match now: a game is on or at least one was played. */
    private fun mayEnd(s: TableSnapshot): Boolean = s.you != null && !s.matchOver && (s.status == TableStatus.PLAYING || s.rounds > 0)

    // The menu: under its button in the top right corner.
    private fun menuRight() = width - 4
    private fun menuLeft() = menuRight() - MENU_WIDTH
    private val menuTop = 48
    private fun backX(i: Int) = menuLeft() + 8 + i * 22
    private val backY get() = menuTop + 18
    private fun faceX(i: Int) = menuLeft() + 8 + i * 30
    private val faceY get() = menuTop + 56
    private val menuButtonY get() = menuTop + 104
    private val menuBottom get() = menuButtonY + 26

    private fun initMenu(t: ClientTable) {
        val s = t.snapshot
        val me = s.you
        val proposed = me != null && s.seats.getOrNull(me)?.endVote == true
        val b = if (proposed) {
            button(Component.translatable("tukuai.table.end.withdraw"), menuLeft() + 8, menuButtonY, MENU_WIDTH - 16) {
                send(TableOp.KEEP)
                openPopup(null)
            }
        } else {
            button(Component.translatable("tukuai.table.end"), menuLeft() + 8, menuButtonY, MENU_WIDTH - 16, mayEnd(s)) {
                send(TableOp.END)
                openPopup(null)
            }
        }
        b.setTooltip(Tooltip.create(Component.translatable(if (s.voters.size > 1) "tukuai.table.end.tip_shared" else "tukuai.table.end.tip_alone")))
    }

    private fun renderMenu(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        panelBox(g, menuLeft(), menuTop, menuRight(), menuBottom)
        g.text(font, Component.translatable("tukuai.table.card_back"), menuLeft() + 8, menuTop + 6, GRAY, false)
        for (i in 0 until CardArt.BACK_COUNT) {
            val x = backX(i)
            CardArt.draw(g, CardArt.back(i), x, backY, 0.5f)
            frame(g, x, backY, CardArt.WIDTH / 2, CardArt.HEIGHT / 2, i == ClientConfig.cardBack, inBox(mouseX, mouseY, x, backY, CardArt.WIDTH / 2, CardArt.HEIGHT / 2))
        }
        val faceLabel = Component.translatable("tukuai.table.card_face", faceName(ClientConfig.cardFace))
        g.text(font, faceLabel, menuLeft() + 8, menuTop + 44, GRAY, false)
        val w = (CardArt.WIDTH * FACE_SCALE).toInt()
        val h = (CardArt.HEIGHT * FACE_SCALE).toInt()
        for (i in CardArt.FACE_STYLES.indices) {
            val x = faceX(i)
            CardArt.draw(g, CardArt.face(SAMPLE_CARD, i), x, faceY, FACE_SCALE)
            frame(g, x, faceY, w, h, i == ClientConfig.cardFace, inBox(mouseX, mouseY, x, faceY, w, h))
        }
        g.fill(menuLeft() + 8, menuButtonY - 6, menuRight() - 8, menuButtonY - 5, DIVIDER)
    }

    /** A click inside the menu picks a card back or face; one outside closes it. */
    private fun clickMenu(x: Double, y: Double): Boolean {
        val mx = x.toInt()
        val my = y.toInt()
        for (i in 0 until CardArt.BACK_COUNT) {
            if (inBox(mx, my, backX(i), backY, CardArt.WIDTH / 2, CardArt.HEIGHT / 2)) {
                ClientConfig.cardBack = i
                ClientConfig.save()
                rebuildWidgets()
                return true
            }
        }
        for (i in CardArt.FACE_STYLES.indices) {
            if (inBox(mx, my, faceX(i), faceY, (CardArt.WIDTH * FACE_SCALE).toInt(), (CardArt.HEIGHT * FACE_SCALE).toInt())) {
                ClientConfig.cardFace = i
                ClientConfig.save()
                rebuildWidgets()
                return true
            }
        }
        if (mx in menuLeft()..menuRight() && my in menuTop..menuBottom) return true
        openPopup(null)
        return true
    }

    // The leaderboard: the best players at this game in this world.
    private val boardWidth = 250
    private fun boardTop() = maxOf(50, (height - boardHeight()) / 2)
    private fun boardHeight() = 40 + maxOf(1, table?.snapshot?.leaderboard?.size ?: 0) * 11 + 30

    private fun initLeaderboard() {
        val left = (width - boardWidth) / 2
        button(Component.translatable("tukuai.table.close"), left + boardWidth / 2 - 40, boardTop() + boardHeight() - 26, 80) { openPopup(null) }
    }

    private fun renderLeaderboard(g: GuiGraphicsExtractor, t: ClientTable) {
        val s = t.snapshot
        val left = (width - boardWidth) / 2
        val top = boardTop()
        panelBox(g, left, top, left + boardWidth, top + boardHeight())
        g.centeredText(font, Component.translatable("tukuai.table.leaderboard.title", gameName(s.gameId)), width / 2, top + 7, GOLD)
        val cols = intArrayOf(left + 10, left + 30, left + 140, left + 178, left + boardWidth - 10)
        val headY = top + 23
        g.text(font, Component.translatable("tukuai.table.leaderboard.player"), cols[1], headY, GRAY, false)
        rightText(g, Component.translatable("tukuai.table.leaderboard.points"), cols[2], headY, GRAY)
        rightText(g, Component.translatable("tukuai.table.leaderboard.games"), cols[3], headY, GRAY)
        rightText(g, Component.translatable("tukuai.table.leaderboard.wins"), cols[4], headY, GRAY)
        if (s.leaderboard.isEmpty()) {
            g.centeredText(font, Component.translatable("tukuai.table.leaderboard.empty"), width / 2, top + 40, GRAY)
            return
        }
        val names = s.seats.mapNotNull { (it.occupant as? Occupant.Human)?.name }.toSet()
        s.leaderboard.forEachIndexed { i, entry ->
            val y = top + 37 + i * 11
            val place = 1 + s.leaderboard.count { it.points > entry.points }
            g.text(font, place.toString(), cols[0], y, Scoreboard.placeColor(place), false)
            g.text(font, font.plainSubstrByWidth(entry.name, 110), cols[1], y, if (entry.name in names) YELLOW else WHITE, false)
            rightText(g, Component.literal(Scoreboard.signed(entry.points)), cols[2], y, Scoreboard.pointsColor(entry.points))
            rightText(g, Component.literal(entry.games.toString()), cols[3], y, WHITE)
            val rate = if (entry.games > 0) entry.wins * 100 / entry.games else 0
            rightText(g, Component.literal("${entry.wins} ($rate%)"), cols[4], y, WHITE)
        }
    }

    // The final standings of a match.
    private val standingsWidth = 220
    private fun standingsTop(t: ClientTable) = maxOf(50, (height - standingsHeight(t)) / 2)
    private fun standingsHeight(t: ClientTable) = 36 + t.snapshot.seats.size * 16 + 32

    private fun initStandings(t: ClientTable) {
        val left = (width - standingsWidth) / 2
        val y = standingsTop(t) + standingsHeight(t) - 26
        readyButton(t.snapshot, left + 8, y, 100, again = true)
        val back = if (t.snapshot.status == TableStatus.LOBBY) "tukuai.table.lobby" else "tukuai.table.close"
        button(Component.translatable(back), left + standingsWidth - 108, y, 100) { openPopup(null) }
    }

    private fun renderStandings(g: GuiGraphicsExtractor, t: ClientTable) {
        val s = t.snapshot
        val left = (width - standingsWidth) / 2
        val top = standingsTop(t)
        panelBox(g, left, top, left + standingsWidth, top + standingsHeight(t))
        g.centeredText(font, Component.translatable("tukuai.table.standings.title"), width / 2, top + 7, GOLD)
        g.centeredText(font, Component.translatable("tukuai.table.standings.games", s.rounds), width / 2, top + 19, GRAY)
        for ((i, entry) in Scoreboard.standings(t).withIndex()) {
            val (place, seat) = entry
            val y = top + 36 + i * 16
            val color = Scoreboard.placeColor(place)
            if (place <= 3) g.fill(left + 8, y - 3, left + standingsWidth - 8, y + 11, (color and 0xFFFFFF) or 0x30000000)
            g.text(font, Component.translatable("tukuai.table.place", place), left + 14, y, color, true)
            val name = seatName(s.seats[seat].occupant)
            g.text(font, font.plainSubstrByWidth(name.string, 110), left + 60, y, if (seat == s.you) YELLOW else WHITE, true)
            val points = s.tally.getOrElse(seat) { 0 }
            rightText(g, Component.literal(Scoreboard.signed(points)), left + standingsWidth - 14, y, Scoreboard.pointsColor(points))
        }
    }

    // Someone proposed to stop: agree or keep playing.
    private val promptWidth = 240
    private fun promptTop() = (height - PROMPT_HEIGHT) / 2 - 20

    private fun initEndPrompt(t: ClientTable) {
        val left = (width - promptWidth) / 2
        val y = promptTop() + PROMPT_HEIGHT - 26
        button(Component.translatable("tukuai.table.end.agree"), left + 10, y, 106) { send(TableOp.END) }
        button(Component.translatable("tukuai.table.end.keep"), left + promptWidth - 116, y, 106) { send(TableOp.KEEP) }
    }

    private fun renderEndPrompt(g: GuiGraphicsExtractor, t: ClientTable) {
        val s = t.snapshot
        val left = (width - promptWidth) / 2
        val top = promptTop()
        panelBox(g, left, top, left + promptWidth, top + PROMPT_HEIGHT)
        val who = s.seats.filter { it.endVote }.joinToString(", ") { seatName(it.occupant).string }
        g.centeredText(font, Component.translatable("tukuai.table.end.proposed", who), width / 2, top + 8, YELLOW)
        val detail = when {
            s.status == TableStatus.PLAYING && s.rounds == 0 -> Component.translatable("tukuai.table.end.detail_none")
            s.status == TableStatus.PLAYING -> Component.translatable("tukuai.table.end.detail_drop", s.rounds)
            else -> Component.translatable("tukuai.table.end.detail", s.rounds)
        }
        font.split(detail, promptWidth - 20).forEachIndexed { i, line ->
            g.centeredText(font, line, width / 2, top + 22 + i * 10, GRAY)
        }
        val agreed = s.voters.count { s.seats[it].endVote }
        g.centeredText(font, Component.translatable("tukuai.table.end.count", agreed, s.voters.size), width / 2, top + 44, WHITE)
    }

    // ---- Drawing -------------------------------------------------------------------------------

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (ClientConfig.immersive) return
        val felt = DyeColor.byId(table?.snapshot?.felt ?: DyeColor.GREEN.id).textureDiffuseColor
        g.fill(0, 0, width, height, 0xFF000000.toInt() or darken(felt))
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val t = table
        val covered = t != null && (endPrompt(t) || (popup != null && popup != Popup.MENU))
        TablePointer.hovered = if (t != null && !inLobby(t) && !covered && popup == null && turnFrom == null &&
            children().none { it.isMouseOver(mouseX.toDouble(), mouseY.toDouble()) }
        ) {
            tableCardAt(mouseX.toDouble(), mouseY.toDouble())
        } else {
            null
        }
        when {
            t == null -> g.centeredText(font, Component.translatable("tukuai.table.loading"), width / 2, height / 2, WHITE)
            inLobby(t) -> if (popup == null || popup == Popup.MENU) renderLobby(g, t)
            else -> panel?.render(g, t, mouseX, mouseY)
        }
        if (t != null) {
            // Popups dim what is under them, except the menu, which only drops down.
            if (covered) g.fill(0, 0, width, height, DIM)
            when {
                endPrompt(t) -> renderEndPrompt(g, t)
                popup == Popup.MENU -> renderMenu(g, mouseX, mouseY)
                popup == Popup.LEADERBOARD -> renderLeaderboard(g, t)
                popup == Popup.STANDINGS -> renderStandings(g, t)
            }
        }
        super.extractRenderState(g, mouseX, mouseY, partialTick)
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (super.mouseClicked(event, doubleClick)) return true
        val t = table
        if (t != null && endPrompt(t)) return true
        if (popup == Popup.MENU) return clickMenu(event.x(), event.y())
        if (popup != null) return true
        if (inWorld && event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            startTurning()
            return true
        }
        if (t == null || inLobby(t)) return false
        if (inWorld && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            val card = tableCardAt(event.x(), event.y())
            if (card != null && panel?.clickTableCard(t, card) == true) {
                sweeping = true
                return true
            }
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
        val t = table
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && t != null && !inLobby(t) && popup == null && !endPrompt(t)) {
            if (sweeping) {
                // Sampled along the way, so a quick sweep does not skip the cards it passes.
                val steps = maxOf(1, (kotlin.math.hypot(dragX, dragY) / 3).toInt())
                for (i in 1..steps) {
                    val card = tableCardAt(event.x() - dragX * (steps - i) / steps, event.y() - dragY * (steps - i) / steps) ?: continue
                    if (card.mine) panel?.dragTableCard(t, card)
                }
                return true
            }
            if (panel?.mouseDragged(t, event.x(), event.y()) == true) return true
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
        if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            sweeping = false
            table?.let { panel?.mouseReleased(it) }
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
        if (event.key() == GLFW.GLFW_KEY_ESCAPE && popup != null) {
            openPopup(null)
            return true
        }
        val free = t != null && !inLobby(t) && popup == null && !endPrompt(t)
        if (free && panel?.keyPressed(t!!, event.key()) == true) return true
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

    // ---- Drawing bits --------------------------------------------------------------------------

    /** A dark panel with a thin gold line along its top, as the popups and the lobby have. */
    private fun panelBox(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int) {
        g.fill(x0, y0, x1, y1, PANEL)
        g.fill(x0, y0, x1, y0 + 1, PANEL_EDGE)
        g.fill(x0, y1 - 1, x1, y1, PANEL_SHADE)
    }

    /** A frame around a picture to choose: gold for the one chosen, white under the mouse. */
    private fun frame(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, chosen: Boolean, hovered: Boolean) {
        val color = when {
            chosen -> GOLD
            hovered -> WHITE
            else -> return
        }
        g.fill(x - 2, y - 2, x + w + 2, y - 1, color)
        g.fill(x - 2, y + h + 1, x + w + 2, y + h + 2, color)
        g.fill(x - 2, y - 1, x - 1, y + h + 1, color)
        g.fill(x + w + 1, y - 1, x + w + 2, y + h + 1, color)
    }

    private fun inBox(mx: Int, my: Int, x: Int, y: Int, w: Int, h: Int) = mx >= x && mx < x + w && my >= y && my < y + h

    private fun rightText(g: GuiGraphicsExtractor, text: Component, right: Int, y: Int, color: Int) {
        g.text(font, text, right - font.width(text), y, color, false)
    }

    companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val GRAY = 0xFFA0A0A0.toInt()
        const val YELLOW = 0xFFFFDD55.toInt()
        const val GREEN = 0xFF66DD66.toInt()
        const val RED = 0xFFFF6666.toInt()
        const val GOLD = 0xFFFFD24A.toInt()

        private const val PANEL = 0xF0101418.toInt()
        private const val PANEL_EDGE = 0xFFC9A44A.toInt()
        private const val PANEL_SHADE = 0xFF05070A.toInt()
        private const val DIVIDER = 0x40FFFFFF
        private const val DIM = 0x80000000.toInt()

        /** Where the rows of seats start in the lobby, below the game and rules buttons. */
        private const val SEATS_Y = 72

        /** Left edge of the top-right buttons. */
        private const val CORNER = 98

        private const val MENU_WIDTH = 104
        private const val FACE_SCALE = 0.75f
        private const val PROMPT_HEIGHT = 84

        /** The card the menu shows each face style with: a diamond, which the four-colour faces paint blue. */
        private val SAMPLE_CARD: PlayingCard = PlayingCard.Regular(Rank.TEN, Suit.DIAMONDS)

        /** Games in a match to choose from in the lobby, as the server allows. */
        private val ROUND_CHOICES = io.github.konyaco.tukuai.table.CardTableBlockEntity.ROUND_CHOICES

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

        fun gameName(gameId: String): Component = Component.translatable("tukuai.game.$gameId")

        fun presetName(gameId: String, presetId: String): Component = Component.translatable("tukuai.preset.$gameId.$presetId")

        fun timerText(seconds: Int): Component =
            if (seconds == 0) Component.translatable("tukuai.table.timer.off") else Component.translatable("tukuai.table.timer", seconds)

        fun matchText(rounds: Int): Component =
            if (rounds == 0) Component.translatable("tukuai.table.match.off") else Component.translatable("tukuai.table.match", rounds)

        /** A dark version of a dye colour for the screen background, so white text stays readable. */
        fun darken(rgb: Int): Int {
            val r = (rgb shr 16 and 0xFF) * 35 / 100
            val gr = (rgb shr 8 and 0xFF) * 35 / 100
            val b = (rgb and 0xFF) * 35 / 100
            return (r shl 16) or (gr shl 8) or b
        }
    }
}
