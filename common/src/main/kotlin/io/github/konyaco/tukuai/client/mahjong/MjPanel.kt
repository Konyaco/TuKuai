package io.github.konyaco.tukuai.client.mahjong

import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PanelText
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.ClaimKind
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Tiles
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScAction
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScCodecs
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScEventKind
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScFan
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScPhase
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScScoring
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScView
import io.github.konyaco.tukuai.engine.mahjong.sichuan.ScWin
import io.github.konyaco.tukuai.engine.mahjong.sichuan.SichuanMahjong
import io.github.konyaco.tukuai.engine.mahjong.sichuan.bot.ScBot
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW

/**
 * The mahjong part of the table screen: your hand along the bottom with your melds to its
 * right, the other three players around with their discards, and the buttons for what you
 * may do now. Tiles are picked by clicking them, in the hand or, in the immersive view, on
 * the table; clicking a picked tile again discards it.
 */
class MjPanel(private val screen: PanelHost) : GamePanel {
    /** Indexes into the held hand ([MjUi.held]) of the picked tiles. */
    private val selected = ArrayList<Int>()
    private var lastStep = -1
    private var wasMyTurn = false

    private val width get() = screen.panelWidth
    private val height get() = screen.panelHeight
    private val font get() = Minecraft.getInstance().font

    private fun send(action: ScAction) = screen.send(TableOp.ACT, data = ScCodecs.ACTION.encode(action))

    private fun live(table: ClientTable) = screen.interactive && table.snapshot.status == TableStatus.PLAYING

    /** Whether this player has to act now. */
    private fun asked(table: ClientTable, view: ScView) = live(table) && view.isMyTurn

    /** The hand along the bottom: your own, or for a watcher who may see every hand, seat 0's. */
    private fun shownHand(view: ScView, me: Seat): List<Pair<Tile, Int>> = when {
        view.seat != null && !view.won(view.seat) && !view.finished -> MjUi.held(view.hand, view.drawn)
        view.shown[me].isNotEmpty() -> MjUi.withCopies(view.shown[me].sorted())
        else -> emptyList()
    }

    private fun handY() = height - MjArt.height(HAND_SCALE) - 6

    /** The row with your name, void suit and waits on the left and the wall on the right. */
    private fun labelY() = if (screen.inWorld) buttonY() - 34 else handY() - RAISE - 11

    private fun buttonY() = if (screen.inWorld) height - BOTTOM_BUTTONS else handY() - 36

    override fun controlsY(): Int = buttonY()

    private fun pickedTiles(view: ScView): List<Tile> {
        val held = MjUi.held(view.hand, view.drawn)
        return selected.mapNotNull { held.getOrNull(it)?.first }
    }

    // ---- Buttons -------------------------------------------------------------------------------

    override fun rebuild(table: ClientTable) {
        val view = table.view as? ScView ?: return
        val s = table.snapshot
        if (s.step != lastStep) {
            lastStep = s.step
            selected.clear()
        }
        if (!screen.interactive) return
        val myTurn = asked(table, view)
        if (myTurn && !wasMyTurn) {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f))
        }
        wasMyTurn = myTurn
        if (view.finished) {
            if (s.status == TableStatus.FINISHED) screen.finishButtons(table, width / 2, resultBottom(view) - 24)
            return
        }
        if (!myTurn) return
        val y = buttonY()
        when (view.phase) {
            ScPhase.SWAP -> {
                val picked = pickedTiles(view)
                val ok = SichuanMahjong.swapProblem(view.hand, picked) == null
                row(y, listOf(
                    Triple(Component.translatable("tukuai.ddz.hint"), true) { hintSwap(view) },
                    Triple(Component.translatable("tukuai.mahjong.pass_on"), ok) { send(ScAction.Swap(picked)) },
                ))
            }
            ScPhase.VOID -> {
                val suggestion = ScBot(BotLevel.NORMAL, 0).voidSuit(view.hand)
                row(y, SichuanMahjong.SUITS.map { suit ->
                    val count = view.hand.count { it.suit == suit }
                    val label = Component.translatable(MjArt.suitKey(suit)).copy().append(" ($count)")
                    if (suit == suggestion) label.withColor(TableScreen.YELLOW and 0xFFFFFF)
                    Triple(label, true) { send(ScAction.ChooseVoid(suit)) }
                }, w = 74)
            }
            ScPhase.PLAY -> {
                val buttons = ArrayList<Triple<Component, Boolean, () -> Unit>>()
                if (view.canWin) buttons += Triple(Component.translatable("tukuai.mahjong.win"), true) { send(ScAction.Win) }
                for (tile in view.kongs) {
                    buttons += Triple(Component.translatable("tukuai.mahjong.kong_tile", tileName(tile)), true) { send(ScAction.Kong(tile)) }
                }
                buttons += Triple(Component.translatable("tukuai.ddz.hint"), true) { hintDiscard(view) }
                val pick = pickedTiles(view).singleOrNull()
                buttons += Triple(Component.translatable("tukuai.mahjong.discard"), pick != null && pick in view.discardable) {
                    pick?.let { send(ScAction.Discard(it)) }
                }
                row(y, buttons)
            }
            ScPhase.CLAIM, ScPhase.ROB -> {
                val tile = view.claimTile ?: return
                val buttons = ArrayList<Triple<Component, Boolean, () -> Unit>>()
                if (ClaimKind.WIN in view.options) buttons += Triple(Component.translatable("tukuai.mahjong.win"), true) { send(ScAction.Win) }
                if (ClaimKind.KONG in view.options) buttons += Triple(Component.translatable("tukuai.mahjong.kong"), true) { send(ScAction.Kong(tile)) }
                if (ClaimKind.PUNG in view.options) buttons += Triple(Component.translatable("tukuai.mahjong.pung"), true) { send(ScAction.Pung) }
                buttons += Triple(Component.translatable("tukuai.ddz.pass"), true) { send(ScAction.Pass) }
                row(y, buttons)
            }
            ScPhase.OVER -> {}
        }
    }

    /** Buttons side by side, centred. */
    private fun row(y: Int, buttons: List<Triple<Component, Boolean, () -> Unit>>, w: Int = 56) {
        val gap = 6
        val widths = buttons.map { maxOf(w, font.width(it.first) + 12) }
        var x = (width - (widths.sum() + gap * (buttons.size - 1))) / 2
        buttons.forEachIndexed { i, (text, active, press) ->
            screen.button(text, x, y, widths[i], active) { press() }
            x += widths[i] + gap
        }
    }

    private fun indexOf(view: ScView, tile: Tile, last: Boolean = true): Int? {
        val held = MjUi.held(view.hand, view.drawn)
        val indexes = held.indices.filter { held[it].first == tile }
        return if (last) indexes.lastOrNull() else indexes.firstOrNull()
    }

    private fun hintSwap(view: ScView) {
        selected.clear()
        val held = MjUi.held(view.hand, view.drawn)
        for (tile in ScBot(BotLevel.NORMAL, 0).swapTiles(view.hand)) {
            held.indices.firstOrNull { held[it].first == tile && it !in selected }?.let { selected += it }
        }
        screen.refresh()
    }

    /** Picks the tile the computer would discard. */
    private fun hintDiscard(view: ScView) {
        val tile = ScBot(BotLevel.NORMAL, 0).discard(view)
        selected.clear()
        indexOf(view, tile)?.let { selected += it }
        screen.refresh()
    }

    // ---- Input ---------------------------------------------------------------------------------

    /** Picks or drops the tile at [index] of the held hand, or discards it when it was picked already. */
    private fun pick(table: ClientTable, view: ScView, index: Int): Boolean {
        if (!asked(table, view)) return false
        val held = MjUi.held(view.hand, view.drawn)
        val tile = held.getOrNull(index)?.first ?: return false
        when (view.phase) {
            ScPhase.SWAP -> {
                if (index in selected) {
                    selected.remove(index)
                } else {
                    // Three of one suit: picking another suit starts again.
                    if (selected.any { held[it].first.suit != tile.suit }) selected.clear()
                    if (selected.size >= 3) selected.removeAt(0)
                    selected += index
                }
            }
            ScPhase.PLAY -> {
                if (index in selected && tile in view.discardable) {
                    send(ScAction.Discard(tile))
                    selected.clear()
                } else {
                    selected.clear()
                    selected += index
                }
            }
            else -> return false
        }
        screen.refresh()
        return true
    }

    private fun handLeft(count: Int, apart: Boolean, meldTiles: Int, melds: Int): Int {
        val w = MjArt.width(HAND_SCALE)
        val total = count * w + (if (apart) APART else 0) + meldTiles * w + melds * MELD_GAP
        return (width - total) / 2
    }

    /** The held hand's tiles on screen, as (index, x). */
    private fun handSlots(view: ScView, me: Seat): List<Pair<Int, Int>> {
        val held = shownHand(view, me)
        val apart = view.drawn != null && view.seat != null && held.size == view.hand.size
        val melds = view.melds[me]
        var x = handLeft(held.size, apart, melds.sumOf { it.tiles.size }, melds.size)
        val w = MjArt.width(HAND_SCALE)
        return held.indices.map { i ->
            if (apart && i == held.size - 1) x += APART
            (i to x).also { x += w }
        }
    }

    override fun mouseClicked(table: ClientTable, x: Double, y: Double, button: Int): Boolean {
        if (!screen.interactive || screen.inWorld || button != GLFW.GLFW_MOUSE_BUTTON_LEFT) return false
        val view = table.view as? ScView ?: return false
        val me = view.seat ?: return false
        val top = handY() - RAISE
        if (y < top || y > height) return false
        val w = MjArt.width(HAND_SCALE)
        val hit = handSlots(view, me).firstOrNull { (_, left) -> x >= left && x < left + w } ?: return false
        return pick(table, view, hit.first)
    }

    override fun clickTableCard(table: ClientTable, card: TableCard): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? ScView ?: return false
        if (view.seat == null) return false
        val held = MjUi.held(view.hand, view.drawn)
        val index = held.indices.firstOrNull { MjUi.ownKey(held[it].first, held[it].second) == card.key } ?: return false
        return pick(table, view, index)
    }

    override fun isRaised(table: ClientTable, card: TableCard): Boolean {
        val view = table.view as? ScView ?: return false
        val held = MjUi.held(view.hand, view.drawn)
        return selected.any { i -> held.getOrNull(i)?.let { MjUi.ownKey(it.first, it.second) == card.key } == true }
    }

    override fun keyPressed(table: ClientTable, key: Int): Boolean {
        val view = table.view as? ScView ?: return false
        if (!asked(table, view)) return false
        return when (key) {
            GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_ENTER -> {
                val picked = pickedTiles(view)
                when (view.phase) {
                    ScPhase.SWAP -> if (SichuanMahjong.swapProblem(view.hand, picked) == null) send(ScAction.Swap(picked))
                    ScPhase.PLAY -> picked.singleOrNull()?.takeIf { it in view.discardable }?.let { send(ScAction.Discard(it)) }
                    ScPhase.CLAIM, ScPhase.ROB -> send(ScAction.Pass)
                    else -> {}
                }
                true
            }
            GLFW.GLFW_KEY_H -> {
                when (view.phase) {
                    ScPhase.SWAP -> hintSwap(view)
                    ScPhase.PLAY -> hintDiscard(view)
                    else -> {}
                }
                true
            }
            else -> false
        }
    }

    // ---- Drawing -------------------------------------------------------------------------------

    private enum class Place { LEFT, TOP, RIGHT }

    override fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int) {
        val view = table.view as? ScView ?: return
        val me = table.snapshot.you ?: view.seat ?: 0
        PanelText.title(g, table, screen.interactive, width)
        if (view.finished) {
            renderResult(g, table, view)
            return
        }
        renderInfo(g, table, view)
        renderBox(g, table, view, (me + 3) % 4, Place.LEFT)
        renderBox(g, table, view, (me + 2) % 4, Place.TOP)
        renderBox(g, table, view, (me + 1) % 4, Place.RIGHT)
        renderMyDiscards(g, view, me)
        renderPrompt(g, table, view)
        renderSelf(g, table, view, me, mouseX, mouseY)
    }

    /**
     * The wall left and who deals: at the right of your row, or in the immersive view in the
     * bottom right corner, beside the hotbar.
     */
    private fun renderInfo(g: GuiGraphicsExtractor, table: ClientTable, view: ScView) {
        val wall = Component.translatable("tukuai.mahjong.wall", view.wallLeft)
        val dealer = Component.translatable("tukuai.mahjong.dealer", MjUi.name(table, view.dealer))
        if (screen.inWorld) {
            PanelText.text(g, wall, width - 8, height - CORNER_TOP, TableScreen.GRAY, PanelText.Align.RIGHT)
            PanelText.text(g, dealer, width - 8, height - CORNER_TOP + 11, TableScreen.GRAY, PanelText.Align.RIGHT)
            return
        }
        val line = wall.copy().append(" · ").append(dealer)
        g.text(font, line, width - 8 - font.width(line), labelY(), TableScreen.GRAY, true)
    }

    private fun tileName(tile: Tile): Component =
        Component.translatable("tukuai.mahjong.tile", tile.rank, Component.translatable(MjArt.suitKey(tile.suit)))

    /** A small badge with a seat's void suit, coloured as the suit. */
    private fun voidBadge(g: GuiGraphicsExtractor, view: ScView, seat: Seat, x: Int, y: Int): Int {
        val suit = view.voids[seat] ?: return 0
        val text = Component.translatable("tukuai.mahjong.void_badge", Component.translatable(MjArt.suitKey(suit)))
        g.text(font, text, x, y, 0xFF000000.toInt() or MjArt.suitColor(suit), true)
        return font.width(text)
    }

    private fun renderBox(g: GuiGraphicsExtractor, table: ClientTable, view: ScView, seat: Seat, place: Place) {
        val compact = ClientConfig.immersive && screen.interactive
        val boxW = if (compact) 116 else 126
        val margin = if (compact) 4 else 8
        val x = when (place) {
            Place.LEFT -> margin
            Place.TOP -> width / 2 - boxW / 2
            Place.RIGHT -> width - margin - boxW
        }
        val y = if (place == Place.TOP) TOP_BOX_Y else SIDE_BOX_Y
        val acting = live(table) && view.toAct == seat && !view.finished && view.phase != ScPhase.SWAP && view.phase != ScPhase.VOID
        g.fill(x - 2, y - 2, x + boxW + 2, y + BOX_H, if (acting) 0xA0806020.toInt() else 0x80202830.toInt())
        val name = PanelText.seatLabel(table, seat).string
        g.text(font, font.plainSubstrByWidth(name, boxW), x, y + 2, TableScreen.WHITE, true)
        val win = view.winOf(seat)
        if (win != null) {
            g.text(font, Component.translatable("tukuai.mahjong.won_place", view.wins.indexOf(win) + 1), x, y + 13, TableScreen.YELLOW, true)
        } else {
            MjArt.drawBack(g, x, y + 12, 0.25f)
            g.text(font, Component.literal(if (compact) "${view.handCounts[seat]}" else "× ${view.handCounts[seat]}"), x + 10, y + 13, TableScreen.WHITE, true)
            if (view.phase == ScPhase.SWAP || view.phase == ScPhase.VOID) {
                if (view.picked[seat]) g.text(font, Component.literal("✔"), x + boxW - 8, y + 13, TableScreen.GREEN, true)
            }
        }
        view.voids[seat]?.let { suit ->
            val text = Component.translatable("tukuai.mahjong.void_badge", Component.translatable(MjArt.suitKey(suit)))
            g.text(font, text, x + boxW - font.width(text), y + 13, 0xFF000000.toInt() or MjArt.suitColor(suit), true)
        }
        val score = view.scores[seat]
        if (score != 0) {
            val text = Component.literal(if (score > 0) "+$score" else "$score")
            g.text(font, text, x + boxW - font.width(text), y + 2, if (score > 0) TableScreen.GREEN else TableScreen.RED, true)
        }
        if (acting) PanelText.timer(g, table, x + boxW, y + 24, alignRight = true)
        // Melds inside the box's last line, then a winner's hand below the box (beside it, across the table).
        renderMelds(g, view.melds[seat], x, y + 24, SMALL_SCALE)
        val w = MjArt.width(SMALL_SCALE)
        val h = MjArt.height(SMALL_SCALE) + 1
        if (win != null || (view.finished && view.shown[seat].isNotEmpty())) {
            val tiles = view.shown[seat]
            val ordered = if (win != null) tiles.toMutableList().also { it.remove(win.tile); it += win.tile } else tiles.sorted()
            val perRow = 7
            val left = if (place == Place.TOP) x + boxW + 6 else x
            val top = if (place == Place.TOP) y else y + BOX_H + 3
            ordered.forEachIndexed { i, tile ->
                val col = i % perRow
                val tx = if (place == Place.RIGHT) x + boxW - (perRow - col) * w else left + col * w
                MjArt.draw(g, tile, tx, top + (i / perRow) * h, SMALL_SCALE, tint = if (win != null && i == ordered.size - 1) 0x50FFC020 else 0)
            }
            if (view.finished) return
        }
        // The discards lie on the table in the immersive view; here they go beside the box, or below it across the table.
        if (screen.inWorld) return
        when (place) {
            Place.LEFT -> renderDiscards(g, view, seat, x + boxW + 6, y, ROW)
            Place.RIGHT -> renderDiscards(g, view, seat, x - 6 - ROW * w, y, ROW)
            Place.TOP -> renderDiscards(g, view, seat, x + boxW / 2 - ROW * w / 2, y + BOX_H + 3, ROW)
        }
    }

    /** A seat's discards, [perRow] to a row from ([x], [y]), the one being claimed framed. */
    private fun renderDiscards(g: GuiGraphicsExtractor, view: ScView, seat: Seat, x: Int, y: Int, perRow: Int) {
        val tiles = view.discards[seat]
        val w = MjArt.width(SMALL_SCALE)
        val h = MjArt.height(SMALL_SCALE)
        tiles.forEachIndexed { i, tile ->
            val tx = x + (i % perRow) * w
            val ty = y + (i / perRow) * (h + 1)
            MjArt.draw(g, tile, tx, ty, SMALL_SCALE)
            if (view.claimFrom == seat && i == tiles.size - 1 && view.phase == ScPhase.CLAIM) frame(g, tx, ty, w, h, MjUi.CLAIM_COLOR)
        }
    }

    private fun frame(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int) {
        g.fill(x - 1, y - 1, x + w + 1, y, color)
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, color)
        g.fill(x - 1, y, x, y + h, color)
        g.fill(x + w, y, x + w + 1, y + h, color)
    }

    /** Melds side by side from [x]; returns the x past the last. A concealed kong shows its ends face down. */
    private fun renderMelds(g: GuiGraphicsExtractor, melds: List<Meld>, x: Int, y: Int, scale: Float): Int {
        var at = x
        val w = MjArt.width(scale)
        for (meld in melds) {
            meld.tiles.forEachIndexed { i, tile ->
                if (meld.kind == MeldKind.CONCEALED_KONG && (i == 0 || i == 3)) MjArt.drawBack(g, at, y, scale) else MjArt.draw(g, tile, at, y, scale)
                at += w
            }
            at += 3
        }
        return at
    }

    /** Your discards, twelve to a row, over the buttons; in the immersive view they are on the table. */
    private fun renderMyDiscards(g: GuiGraphicsExtractor, view: ScView, me: Seat) {
        val tiles = view.discards[me]
        if (tiles.isEmpty() || screen.inWorld) return
        val w = MjArt.width(SMALL_SCALE)
        val h = MjArt.height(SMALL_SCALE) + 1
        val rows = (tiles.size + MY_ROW - 1) / MY_ROW
        renderDiscards(g, view, me, width / 2 - MY_ROW * w / 2, buttonY() - 24 - rows * h, MY_ROW)
    }

    /** What this turn asks of you, or whom everyone waits for, over the buttons. */
    private fun renderPrompt(g: GuiGraphicsExtractor, table: ClientTable, view: ScView) {
        val y = buttonY() - 11
        val mine = asked(table, view)
        val text: Component? = when (view.phase) {
            ScPhase.SWAP -> if (mine) Component.translatable("tukuai.mahjong.note.swap") else waitingFor(table, view)
            ScPhase.VOID -> {
                view.swapDirection?.let { g.centeredText(font, Component.translatable("tukuai.mahjong.swap.${it.name.lowercase()}"), width / 2, y - 10, TableScreen.WHITE) }
                if (mine) Component.translatable("tukuai.mahjong.note.void") else waitingFor(table, view)
            }
            ScPhase.PLAY -> when {
                !mine -> null
                view.canWin -> Component.translatable("tukuai.mahjong.note.can_win")
                view.discardable.any { it.suit == view.myVoid } -> Component.translatable("tukuai.mahjong.note.void_first", Component.translatable(MjArt.suitKey(view.myVoid!!)))
                else -> Component.translatable("tukuai.mahjong.note.discard")
            }
            ScPhase.CLAIM, ScPhase.ROB -> {
                val tile = view.claimTile
                val from = view.claimFrom
                if (tile != null && from != null) {
                    val key = if (view.phase == ScPhase.ROB) "tukuai.mahjong.note.robbing" else "tukuai.mahjong.note.discarded"
                    val line = Component.translatable(key, MjUi.name(table, from), tileName(tile))
                    g.centeredText(font, line, width / 2, y - 10, TableScreen.WHITE)
                    if (mine) Component.translatable("tukuai.mahjong.note.claim") else null
                } else {
                    null
                }
            }
            ScPhase.OVER -> null
        }
        if (text != null) g.centeredText(font, text, width / 2, y, TableScreen.YELLOW)
    }

    private fun waitingFor(table: ClientTable, view: ScView): Component? {
        val waiting = view.picked.withIndex().filter { !it.value }.map { it.index }
        if (waiting.isEmpty()) return null
        return Component.translatable("tukuai.mahjong.waiting_for", MjUi.name(table, waiting.first()))
    }

    /**
     * Your hand and melds along the bottom, and your row over it: name, void suit, the clock and
     * what you wait on. In the immersive view the hand is on the table and the rest goes in the
     * bottom left corner, beside the hotbar.
     */
    private fun renderSelf(g: GuiGraphicsExtractor, table: ClientTable, view: ScView, me: Seat, mouseX: Int, mouseY: Int) {
        val seen = table.snapshot.you != null || view.shown[me].isNotEmpty()
        val label = if (!seen) Component.translatable("tukuai.table.watching") else PanelText.seatLabel(table, me)
        val corner = screen.inWorld && !view.finished
        val labelY = if (corner) height - CORNER_TOP else labelY()
        g.text(font, label, 8, labelY, TableScreen.WHITE, true)
        if (!seen) return
        var x = 8 + font.width(label) + 6
        if (asked(table, view) && table.timerTicks() >= 0) {
            PanelText.timer(g, table, x, labelY, alignRight = false)
            x += font.width(Component.translatable("tukuai.table.seconds", (table.timerTicks() + 19) / 20)) + 6
        }
        // In the corner each part gets a line of its own.
        val rowY = if (corner) labelY + 11 else labelY
        if (corner) x = 8
        x += voidBadge(g, view, me, x, rowY)
        view.winOf(me)?.let {
            val won = Component.translatable("tukuai.mahjong.won_place", view.wins.indexOf(it) + 1)
            g.text(font, won, x + 6, rowY, TableScreen.YELLOW, true)
            x += 6 + font.width(won)
        }
        if (corner) renderWaits(g, table, view, 8, rowY + 13) else renderWaits(g, table, view, x + 6, labelY)
        if (screen.inWorld || view.finished) return
        val held = shownHand(view, me)
        val y = handY()
        val w = MjArt.width(HAND_SCALE)
        val playing = view.seat == me && asked(table, view) && view.phase == ScPhase.PLAY
        var end = 0
        for ((i, tx) in handSlots(view, me)) {
            val tile = held[i].first
            val picked = i in selected
            val hover = mouseX >= tx && mouseX < tx + w && mouseY >= y - RAISE && view.seat == me
            val ty = if (picked) y - RAISE else y
            MjArt.draw(g, tile, tx, ty, HAND_SCALE, dim = playing && tile !in view.discardable, tint = if (hover && !picked) 0x30FFFFFF else 0)
            // The void suit is marked with a red bar along the bottom.
            if (tile.suit == view.voids[me]) g.fill(tx + 1, ty + MjArt.height(HAND_SCALE) - 3, tx + w - 1, ty + MjArt.height(HAND_SCALE) - 1, VOID_MARK)
            if (picked) frame(g, tx, ty, w, MjArt.height(HAND_SCALE), 0xFFFFD84A.toInt())
            end = tx + w
        }
        if (end == 0) end = handLeft(0, false, view.melds[me].sumOf { it.tiles.size }, view.melds[me].size)
        renderMelds(g, view.melds[me], end + MELD_GAP, y, HAND_SCALE)
    }

    /**
     * The tiles you wait on, once ready; on your turn with a tile picked, those you would wait
     * on after discarding it. From ([x], [y]) on your row.
     */
    private fun renderWaits(g: GuiGraphicsExtractor, table: ClientTable, view: ScView, x: Int, y: Int) {
        val seat = view.seat ?: return
        if (view.won(seat) || view.finished) return
        val picked = pickedTiles(view).singleOrNull()
        val waits = when {
            view.phase == ScPhase.PLAY && asked(table, view) && picked != null ->
                ScScoring.waits(Tiles.minus(view.hand, listOf(picked)), view.myMelds, view.myVoid)
            view.hand.size % 3 == 1 -> view.waits
            else -> emptyList()
        }
        if (waits.isEmpty()) return
        val label = Component.translatable(if (view.hand.size % 3 == 1) "tukuai.mahjong.waits" else "tukuai.mahjong.waits_after")
        g.text(font, label, x, y, TableScreen.GREEN, true)
        MjArt.row(g, waits, x + font.width(label) + 4, y - 4, SMALL_SCALE, gap = 1)
    }

    // ---- The result ----------------------------------------------------------------------------

    private fun resultHeight(@Suppress("UNUSED_PARAMETER") view: ScView): Int = 24 + 4 * RESULT_ROW + 30

    private fun resultBottom(view: ScView): Int = RESULT_TOP + resultHeight(view)

    private fun fanText(win: ScWin): Component {
        val text = Component.empty()
        text.append(Component.translatable(if (win.selfDrawn) "tukuai.mahjong.self_drawn" else "tukuai.mahjong.on_discard"))
        val counts = win.fans.groupingBy { it }.eachCount()
        for ((fan, n) in counts) {
            text.append(" · ").append(Component.translatable("tukuai.mahjong.fan.${fan.name.lowercase()}"))
            if (n > 1) text.append(" ×$n")
        }
        if (win.fans.isEmpty()) text.append(" · ").append(Component.translatable("tukuai.mahjong.fan.plain"))
        text.append(" ").append(Component.translatable("tukuai.mahjong.fan_count", win.fan))
        return text
    }

    /** Each player: how the hand went for them, their tiles, and the points they won or lost. */
    private fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: ScView) {
        val w = minOf(360, width - 16)
        val x = width / 2 - w / 2
        val top = RESULT_TOP
        g.fill(x, top, x + w, top + resultHeight(view), 0xF0101010.toInt())
        val title = if (view.wins.isEmpty()) Component.translatable("tukuai.mahjong.nobody_won") else Component.translatable("tukuai.mahjong.over")
        g.centeredText(font, title, width / 2, top + 6, TableScreen.YELLOW)
        val me = table.snapshot.you
        for (seat in 0 until SichuanMahjong.SEATS) {
            val y = top + 22 + seat * RESULT_ROW
            g.text(font, font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, 68), x + 8, y, if (seat == me) TableScreen.YELLOW else TableScreen.WHITE, false)
            val win = view.winOf(seat)
            val how: Component = when {
                win != null -> fanText(win)
                view.log.any { it.kind == ScEventKind.PIG && it.seat == seat } -> Component.translatable("tukuai.mahjong.pig")
                view.log.any { it.kind == ScEventKind.NOT_READY && it.seat == seat } -> Component.translatable("tukuai.mahjong.not_ready")
                else -> Component.translatable("tukuai.mahjong.ready_or_out")
            }
            val line = font.split(how, w - 120).firstOrNull()
            if (line != null) g.text(font, line, x + 80, y, TableScreen.GRAY, false)
            val points = view.scores[seat]
            val text = Component.literal(if (points > 0) "+$points" else points.toString())
            g.text(font, text, x + w - 8 - font.width(text), y, if (points >= 0) TableScreen.GREEN else TableScreen.RED, false)
            // The hand as it ended, the winning tile last and lit, then the melds.
            val tiles = view.shown[seat].let { t -> if (win != null) t.toMutableList().also { it.remove(win.tile); it += win.tile } else t.sorted() }
            var tx = x + 80
            tiles.forEachIndexed { i, tile ->
                MjArt.draw(g, tile, tx, y + 10, SMALL_SCALE, tint = if (win != null && i == tiles.size - 1) 0x50FFC020 else 0)
                tx += MjArt.width(SMALL_SCALE)
            }
            renderMelds(g, view.melds[seat], tx + 4, y + 10, SMALL_SCALE)
        }
    }

    companion object {
        private const val HAND_SCALE = 0.6f
        private const val SMALL_SCALE = 0.28f

        /** How many of another player's discards go in a row, and of your own. */
        private const val ROW = 5
        private const val MY_ROW = 12
        private const val VOID_MARK = 0xFFE04040.toInt()
        private const val RAISE = 6
        private const val APART = 6
        private const val MELD_GAP = 6
        private const val TOP_BOX_Y = 4
        private const val SIDE_BOX_Y = 66
        private const val BOX_H = 38
        /** The result starts below the scoreboard and reaches nearly to the bottom. */
        private const val RESULT_TOP = 62
        private const val RESULT_ROW = 28
        private const val BOTTOM_BUTTONS = 64

        /** In the immersive view, how far up from the bottom the corners beside the hotbar start. */
        private const val CORNER_TOP = 42
    }
}
