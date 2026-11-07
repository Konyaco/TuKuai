package io.github.konyaco.tukuai.client.mahjong

import com.mojang.blaze3d.platform.InputConstants
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PanelText
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.MjTableView
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.Tiles
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * A tile the hand waits on: how many of it are still unseen, and whether a win on it would
 * count at this table ([ok]), with [value] saying what it would be worth, such as "24 番".
 */
data class WaitHint(val tile: Tile, val left: Int, val ok: Boolean, val value: Component?)

/**
 * The parts of a mahjong panel every rule set shares: your hand along the bottom with your
 * melds and flowers to its right, the other three players around with their discards, and
 * your row with what you wait on. It also helps the way players expect of a mahjong client:
 * the tiles you wait on with how many are left of each, which tiles you could discard and
 * still be ready, and what each would leave you waiting on.
 *
 * Tiles are picked by clicking them, in the hand or, in the immersive view, on the table;
 * clicking a picked tile again discards it. A rule set fills in its buttons, prompt, badges
 * and the result.
 */
abstract class MjPanelBase<V : MjTableView>(protected val screen: PanelHost) : GamePanel {
    /** Indexes into the held hand ([MjScene.held]) of the picked tiles. */
    protected val selected = ArrayList<Int>()
    private var lastStep = -1
    private var wasMyTurn = false

    protected val width get() = screen.panelWidth
    protected val height get() = screen.panelHeight
    protected val font get() = Minecraft.getInstance().font

    /** The view of [table], if it is this rule set's. */
    protected abstract fun view(table: ClientTable): V?

    /** Whether it is this player's move. */
    protected abstract fun isMyTurn(view: V): Boolean

    /** On this player's turn with a full hand: may they discard now, and which tiles. */
    protected abstract fun discarding(view: V): Boolean

    protected open fun canDiscard(view: V, tile: Tile): Boolean = true

    protected abstract fun discard(view: V, tile: Tile)

    /** The buttons for what the player may do now, left to right; called only when it is their move. */
    protected abstract fun buttons(table: ClientTable, view: V): List<Btn>

    /** What the player is asked, in yellow over the buttons, and a line above it in white. */
    protected abstract fun prompt(table: ClientTable, view: V, mine: Boolean): Pair<Component?, Component?>

    /** A short line about a seat under its name: its wind, dealer, riichi and so on. */
    protected abstract fun badge(view: V, seat: Seat): Component?

    /** The points a seat has, shown by its name. */
    protected abstract fun score(view: V, seat: Seat): String?

    /** Lines in the corner: the wall, the round, the dealer. */
    protected abstract fun info(table: ClientTable, view: V): List<Component>

    /** What the hand of [concealed] tiles (one short of complete) waits on, or empty when it is not ready. */
    protected abstract fun waitHints(view: V, concealed: List<Tile>): List<WaitHint>

    /** Whether the hand or game is over, so the result takes the middle of the screen. */
    protected abstract fun showingResult(view: V): Boolean

    protected abstract fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: V)

    /** Where the result's buttons go, once it is over. */
    protected abstract fun resultButtons(table: ClientTable, view: V)

    /** The tile the computer would discard, for the hint. */
    protected abstract fun hintTile(view: V): Tile?

    /** A button: its text, whether it may be pressed, and what it does. */
    data class Btn(val text: Component, val active: Boolean = true, val press: () -> Unit)

    protected fun live(table: ClientTable) = screen.interactive && table.snapshot.status == TableStatus.PLAYING

    protected fun asked(table: ClientTable, view: V) = live(table) && isMyTurn(view)

    protected fun held(view: V): List<Pair<Tile, Int>> = MjScene.held(view.hand, view.drawn)

    protected fun pickedTiles(view: V): List<Tile> {
        val held = held(view)
        return selected.mapNotNull { held.getOrNull(it)?.first }
    }

    // ---- Help with the hand --------------------------------------------------------------------

    private var hintsFor: Any? = null
    private var discardHints: Map<Tile, List<WaitHint>> = emptyMap()
    private var waitingHints: List<WaitHint> = emptyList()

    /** Works out, once for each new view, what the hand waits on and what each discard would leave. */
    private fun hints(view: V) {
        if (hintsFor === view) return
        hintsFor = view
        val seat = view.seat
        if (seat == null || view.handOver) {
            discardHints = emptyMap()
            waitingHints = emptyList()
            return
        }
        waitingHints = if (view.hand.size % 3 == 1) waitHints(view, view.hand) else emptyList()
        discardHints = if (view.hand.size % 3 == 2) {
            view.hand.distinct().associateWith { waitHints(view, Tiles.minus(view.hand, listOf(it))) }.filterValues { it.isNotEmpty() }
        } else emptyMap()
    }

    /** The tiles whose discard leaves the hand ready: green when some wait would win, yellow when none would. */
    fun readyAfter(view: V): Map<Tile, Boolean> {
        hints(view)
        return discardHints.mapValues { (_, w) -> w.any { it.ok && it.left > 0 } }
    }

    // ---- Buttons -------------------------------------------------------------------------------

    override fun rebuild(table: ClientTable) {
        val view = view(table) ?: return
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
        buttonsLeft = width
        if (showingResult(view)) {
            resultButtons(table, view)
            return
        }
        if (!myTurn) return
        row(buttonY(), buttons(table, view))
    }

    /** Buttons side by side: on the right over the hand, or centred in the immersive view. */
    protected fun row(y: Int, buttons: List<Btn>, w: Int = 52) {
        if (buttons.isEmpty()) return
        val gap = 4
        val widths = buttons.map { maxOf(w, font.width(it.text) + 12) }
        val total = widths.sum() + gap * (buttons.size - 1)
        var x = if (screen.inWorld) (width - total) / 2 else width - MARGIN - total
        buttonsLeft = x
        buttons.forEachIndexed { i, b ->
            screen.button(b.text, x, y, widths[i], b.active) { b.press() }
            x += widths[i] + gap
        }
    }

    protected fun hint(view: V) {
        val tile = hintTile(view) ?: return
        selected.clear()
        val held = held(view)
        held.indices.lastOrNull { held[it].first == tile }?.let { selected += it }
        screen.refresh()
    }

    // ---- Layout --------------------------------------------------------------------------------

    protected fun handY() = height - MjArt.height(HAND_SCALE) - 6

    /** The row with your name, badges and waits, left of the buttons. */
    protected fun labelY() = if (screen.inWorld) buttonY() - 34 else handY() - RAISE - 12

    /** The buttons stand right over the hand, on the right; in the immersive view above the hotbar. */
    protected fun buttonY() = if (screen.inWorld) height - BOTTOM_BUTTONS else handY() - RAISE - 22

    /** Where the row of buttons starts on the left, as last laid out. */
    private var buttonsLeft = Int.MAX_VALUE

    /** The replay viewer puts its controls over your row, where the buttons would be. */
    override fun controlsY(): Int = if (screen.interactive) buttonY() else labelY() - 26

    private fun handLeft(view: V, count: Int, apart: Boolean, me: Seat): Int {
        val w = MjArt.width(HAND_SCALE)
        val melds = view.melds[me]
        val flowers = view.flowers.getOrElse(me) { emptyList() }.size
        val total = count * w + (if (apart) APART else 0) + melds.sumOf { it.tiles.size } * w + melds.size * MELD_GAP +
            (if (flowers > 0) MELD_GAP + flowers * MjArt.width(SMALL_SCALE) else 0)
        return (width - total) / 2
    }

    /** The hand along the bottom: your own, or for a watcher who may see every hand, that seat's. */
    private fun shownHand(view: V, me: Seat): List<Pair<Tile, Int>> = when {
        view.seat != null && !view.handOver && view.winningTile(view.seat!!) == null -> held(view)
        view.shown.getOrElse(me) { emptyList() }.isNotEmpty() -> MjScene.withCopies(view.shown[me].sorted())
        else -> emptyList()
    }

    /** The held hand's tiles on screen, as (index, x). */
    private fun handSlots(view: V, me: Seat): List<Pair<Int, Int>> {
        val held = shownHand(view, me)
        val apart = view.drawn != null && view.seat != null && held.size == view.hand.size
        var x = handLeft(view, held.size, apart, me)
        val w = MjArt.width(HAND_SCALE)
        return held.indices.map { i ->
            if (apart && i == held.size - 1) x += APART
            (i to x).also { x += w }
        }
    }

    // ---- Input ---------------------------------------------------------------------------------

    /** Picks or drops the tile at [index] of the held hand, or discards it when it was picked already. */
    protected open fun pick(table: ClientTable, view: V, index: Int): Boolean {
        if (!asked(table, view) || !discarding(view)) return false
        val tile = held(view).getOrNull(index)?.first ?: return false
        if (index in selected && canDiscard(view, tile)) {
            discard(view, tile)
            selected.clear()
        } else {
            selected.clear()
            selected += index
        }
        screen.refresh()
        return true
    }

    override fun mouseClicked(table: ClientTable, x: Double, y: Double, button: Int): Boolean {
        if (!screen.interactive || screen.inWorld || button != InputConstants.MOUSE_BUTTON_LEFT) return false
        val view = view(table) ?: return false
        val me = view.seat ?: return false
        if (showingResult(view)) return false
        val top = handY() - RAISE
        if (y < top || y > height) return false
        val w = MjArt.width(HAND_SCALE)
        val hit = handSlots(view, me).firstOrNull { (_, left) -> x >= left && x < left + w } ?: return false
        return pick(table, view, hit.first)
    }

    override fun clickTableCard(table: ClientTable, card: TableCard): Boolean {
        if (!screen.interactive) return false
        val view = view(table) ?: return false
        if (view.seat == null) return false
        val held = held(view)
        val index = held.indices.firstOrNull { MjScene.ownKey(held[it].first, held[it].second) == card.key } ?: return false
        return pick(table, view, index)
    }

    override fun isRaised(table: ClientTable, card: TableCard): Boolean {
        val view = view(table) ?: return false
        val held = held(view)
        return selected.any { i -> held.getOrNull(i)?.let { MjScene.ownKey(it.first, it.second) == card.key } == true }
    }

    override fun keyPressed(table: ClientTable, key: Int): Boolean {
        val view = view(table) ?: return false
        if (!asked(table, view)) return false
        return when (key) {
            InputConstants.KEY_SPACE, InputConstants.KEY_RETURN -> {
                if (discarding(view)) pickedTiles(view).singleOrNull()?.takeIf { canDiscard(view, it) }?.let { discard(view, it) } else onEnter(view)
                true
            }
            InputConstants.KEY_H -> {
                if (discarding(view)) hint(view)
                true
            }
            else -> false
        }
    }

    /** Enter when not discarding: pass, or go on to the next hand. */
    protected open fun onEnter(view: V) {}

    // ---- Drawing -------------------------------------------------------------------------------

    /**
     * The table, laid out as one sits at it: a box in the middle with the round and the wall,
     * the four ponds round it, each in front of its player, the other three players' names,
     * points and melds along the edges, and your hand along the bottom with your row over it.
     * In the immersive view the ponds lie on the table itself, so only the names and the box
     * are drawn, at the top.
     */
    override fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int) {
        val view = view(table) ?: return
        val me = table.snapshot.you ?: view.seat ?: 0
        PanelText.title(g, table, screen.interactive, width)
        if (showingResult(view)) {
            renderResult(g, table, view)
            return
        }
        hints(view)
        val box = renderCentre(g, table, view)
        if (!screen.inWorld) {
            renderPond(g, view, me, box, Side.BOTTOM)
            renderPond(g, view, (me + 1) % 4, box, Side.RIGHT)
            renderPond(g, view, (me + 2) % 4, box, Side.TOP)
            renderPond(g, view, (me + 3) % 4, box, Side.LEFT)
        }
        renderTag(g, table, view, (me + 3) % 4, Side.LEFT, box)
        renderTag(g, table, view, (me + 2) % 4, Side.TOP, box)
        renderTag(g, table, view, (me + 1) % 4, Side.RIGHT, box)
        renderPrompt(g, table, view)
        renderSelf(g, table, view, me, mouseX, mouseY)
        renderExtra(g, table, view)
    }

    /** A colour to mark a tile in your hand with along its bottom, such as the void suit in Sichuan mahjong; 0 for none. */
    protected open fun markColor(view: V, tile: Tile): Int = 0

    /** Anything else the rule set shows during a hand. */
    protected open fun renderExtra(g: GuiGraphicsExtractor, table: ClientTable, view: V) {}

    /** Tiles the box in the middle shows under its lines, such as the dora indicators. */
    protected open fun centreTiles(view: V): List<Tile> = emptyList()

    /** A line under the box's tiles, such as their name. */
    protected open fun centreTilesLabel(view: V): Component? = null

    private enum class Side { BOTTOM, RIGHT, TOP, LEFT }

    /** The box in the middle: left, top, right, bottom. */
    private data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val cx: Int get() = (left + right) / 2
        val cy: Int get() = (top + bottom) / 2
    }

    private val pondW get() = MjArt.width(POND_SCALE)
    private val pondH get() = MjArt.height(POND_SCALE) + 1

    /** How far down the table area reaches: just over the prompt. */
    private fun tableBottom() = buttonY() - 24

    private fun renderCentre(g: GuiGraphicsExtractor, table: ClientTable, view: V): Box {
        val lines = info(table, view)
        val tiles = centreTiles(view)
        val label = if (tiles.isNotEmpty()) centreTilesLabel(view) else null
        val w = maxOf(lines.maxOfOrNull { font.width(it) } ?: 0, tiles.size * MjArt.width(SMALL_SCALE), label?.let { font.width(it) } ?: 0, 6 * pondW) + 10
        val h = lines.size * 10 + (if (tiles.isNotEmpty()) MjArt.height(SMALL_SCALE) + 3 else 0) + (if (label != null) 10 else 0) + 6
        // In the immersive view the box sits a little left of the middle, leaving the player across room beside it.
        val cx = if (screen.inWorld) width / 2 - 24 else width / 2
        val cy = if (screen.inWorld) TOP_BOX_Y + h / 2 else (TOP_LIMIT + tableBottom()) / 2
        val box = Box(cx - w / 2, cy - h / 2, cx + (w + 1) / 2, cy + (h + 1) / 2)
        g.fill(box.left, box.top, box.right, box.bottom, 0xC0101418.toInt())
        frame(g, box.left, box.top, box.right - box.left, box.bottom - box.top, 0x60FFD24A)
        var y = box.top + 4
        for ((i, line) in lines.withIndex()) {
            g.centeredText(font, line, cx, y, if (i == 0) TableScreen.YELLOW else TableScreen.WHITE)
            y += 10
        }
        if (label != null) {
            g.centeredText(font, label, cx, y, TableScreen.GRAY)
            y += 10
        }
        if (tiles.isNotEmpty()) MjArt.row(g, tiles, cx - tiles.size * MjArt.width(SMALL_SCALE) / 2, y + 1, SMALL_SCALE)
        return box
    }

    /**
     * A seat's pond, six to a row, on its side of the box: yours below it, the player across
     * above it with the rows growing upwards, the others beside it. Rows squeeze together when
     * there are more than the room allows.
     */
    private fun renderPond(g: GuiGraphicsExtractor, view: V, seat: Seat, box: Box, side: Side) {
        val tiles = view.discards[seat]
        if (tiles.isEmpty()) return
        val rows = (tiles.size + POND_ROW - 1) / POND_ROW
        val room = when (side) {
            Side.BOTTOM -> tableBottom() - box.bottom - 3
            Side.TOP -> box.top - 3 - TOP_LIMIT
            else -> tableBottom() - box.top
        }
        val pitch = if (rows <= 1) pondH else minOf(pondH, (room - pondH) / (rows - 1)).coerceAtLeast(4)
        val rowW = POND_ROW * pondW
        val turned = view.sideways(seat)
        tiles.forEachIndexed { i, tile ->
            val row = i / POND_ROW
            val col = i % POND_ROW
            val (x, y) = when (side) {
                Side.BOTTOM -> box.cx - rowW / 2 + col * pondW to box.bottom + 3 + row * pitch
                Side.TOP -> box.cx - rowW / 2 + col * pondW to box.top - 3 - pondH - row * pitch
                Side.RIGHT -> box.right + 4 + col * pondW to box.top + row * pitch
                Side.LEFT -> box.left - 4 - rowW + col * pondW to box.top + row * pitch
            }
            MjArt.draw(g, tile, x, y, POND_SCALE)
            if (i == turned) frame(g, x, y, pondW, pondH - 1, RIICHI_MARK)
            if (view.claimFrom == seat && i == tiles.size - 1 && view.claimingDiscard) frame(g, x, y, pondW, pondH - 1, MjScene.CLAIM_COLOR)
        }
    }

    protected fun frame(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int) {
        g.fill(x - 1, y - 1, x + w + 1, y, color)
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, color)
        g.fill(x - 1, y, x, y + h, color)
        g.fill(x + w, y, x + w + 1, y + h, color)
    }

    /**
     * Another player: name and points, their wind and how many tiles they hold, then their melds
     * and flowers, in a panel at the edge on their side; lit while it is their move.
     */
    private fun renderTag(g: GuiGraphicsExtractor, table: ClientTable, view: V, seat: Seat, side: Side, box: Box) {
        val w = TAG_W
        // Melds, then flowers, wrapped to the panel's width: (x offset, row) for each.
        val tw = MjArt.width(MELD_SCALE)
        val pieces = view.melds[seat].map { it.tiles.size * tw + 2 } + view.flowers.getOrElse(seat) { emptyList() }.map { MjArt.width(FLOWER_SCALE) }
        val places = ArrayList<Pair<Int, Int>>()
        var px = 0
        var row = 0
        for (piece in pieces) {
            if (px > 0 && px + piece > w) {
                px = 0
                row++
            }
            places += px to row
            px += piece
        }
        val rowH = MjArt.height(MELD_SCALE) + 1
        val h = if (pieces.isNotEmpty()) 22 + (row + 1) * rowH + 1 else 22
        val (x, y) = when (side) {
            Side.LEFT -> MARGIN to SIDE_TAG_Y
            Side.RIGHT -> width - MARGIN - w to SIDE_TAG_Y
            // Across the table: beside the pond above the box, clear of the corners.
            else -> if (screen.inWorld) box.right + 6 to TOP_BOX_Y else box.cx + POND_ROW * pondW / 2 + 5 to TOP_BOX_Y
        }
        val acting = live(table) && view.toAct == seat && !view.handOver
        g.fill(x - 2, y - 2, x + w + 2, y + h, if (acting) 0xB0806020.toInt() else 0x90202830.toInt())
        if (acting) g.fill(x - 2, y - 2, x - 1, y + h, 0xFFFFD24A.toInt())
        // The name on top; under it the tiles held, the points and the wind.
        g.text(font, font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, w), x, y + 1, TableScreen.WHITE, true)
        MjArt.drawBack(g, x, y + 11, 0.22f)
        val count = "${view.handCounts[seat]}"
        g.text(font, Component.literal(count), x + 9, y + 12, TableScreen.WHITE, true)
        val badge = badge(view, seat)?.let { font.plainSubstrByWidth(it.string, w / 2) }
        if (badge != null) g.text(font, badge, x + w - font.width(badge), y + 12, TableScreen.YELLOW, true)
        if (acting) {
            PanelText.timer(g, table, x + 13 + font.width(count), y + 12, alignRight = false)
        } else {
            score(view, seat)?.let { g.text(font, Component.literal(it), x + 13 + font.width(count), y + 12, TableScreen.GRAY, true) }
        }
        view.melds[seat].forEachIndexed { i, meld -> renderMelds(g, listOf(meld), x + places[i].first, y + 22 + places[i].second * rowH, MELD_SCALE, gap = 2) }
        view.flowers.getOrElse(seat) { emptyList() }.forEachIndexed { i, f ->
            val (fx, fr) = places[view.melds[seat].size + i]
            MjArt.draw(g, f, x + fx, y + 22 + fr * rowH, FLOWER_SCALE)
        }
    }

    /** Melds side by side from [x]; returns the x past the last. A concealed kong shows its ends face down. */
    protected fun renderMelds(g: GuiGraphicsExtractor, melds: List<Meld>, x: Int, y: Int, scale: Float, gap: Int = 3): Int {
        var at = x
        val w = MjArt.width(scale)
        for (meld in melds) {
            meld.tiles.forEachIndexed { i, tile ->
                if (meld.kind == MeldKind.CONCEALED_KONG && (i == 0 || i == 3)) MjArt.drawBack(g, at, y, scale) else MjArt.draw(g, tile, at, y, scale)
                at += w
            }
            at += gap
        }
        return at
    }

    /** Flowers, small, from [x]; returns the x past the last. */
    protected fun renderFlowers(g: GuiGraphicsExtractor, flowers: List<Tile>, x: Int, y: Int): Int {
        var at = x
        for (f in flowers) {
            MjArt.draw(g, f, at, y, FLOWER_SCALE)
            at += MjArt.width(FLOWER_SCALE)
        }
        return at
    }

    /**
     * What this turn asks of you, in yellow, and what was just played, in white: right above
     * the buttons, aligned with them on the right; in the immersive view under the box at the top.
     */
    private fun renderPrompt(g: GuiGraphicsExtractor, table: ClientTable, view: V) {
        val (line, ask) = prompt(table, view, asked(table, view))
        if (screen.inWorld) {
            val y = TOP_BOX_Y + 50
            if (line != null) g.centeredText(font, line, width / 2, y, TableScreen.WHITE)
            if (ask != null) g.centeredText(font, ask, width / 2, y + 10, TableScreen.YELLOW)
            return
        }
        val y = buttonY() - 11
        if (ask != null) PanelText.text(g, ask, width - MARGIN, y, TableScreen.YELLOW, PanelText.Align.RIGHT)
        if (line != null) PanelText.text(g, line, width - MARGIN, if (ask != null) y - 10 else y, TableScreen.WHITE, PanelText.Align.RIGHT)
    }

    /**
     * Your hand and melds along the bottom, and your row over it: name, points, wind, the clock
     * and what you wait on. In the immersive view the hand is on the table and the rest goes in
     * the bottom left corner, beside the hotbar.
     */
    private fun renderSelf(g: GuiGraphicsExtractor, table: ClientTable, view: V, me: Seat, mouseX: Int, mouseY: Int) {
        val seen = table.snapshot.you != null || view.shown.getOrElse(me) { emptyList() }.isNotEmpty()
        val label = if (!seen) Component.translatable("tukuai.table.watching") else PanelText.seatLabel(table, me)
        val corner = screen.inWorld
        val labelY = if (corner) height - CORNER_TOP else labelY()
        val acting = live(table) && view.toAct == me && !view.handOver
        g.text(font, label, MARGIN, labelY, if (acting) TableScreen.YELLOW else TableScreen.WHITE, true)
        if (!seen) return
        var x = MARGIN + font.width(label) + 6
        score(view, me)?.let {
            g.text(font, Component.literal(it), x, labelY, TableScreen.GRAY, true)
            x += font.width(it) + 6
        }
        badge(view, me)?.let {
            g.text(font, it, x, labelY, TableScreen.YELLOW, true)
            x += font.width(it) + 6
        }
        if (asked(table, view) && table.timerTicks() >= 0) {
            PanelText.timer(g, table, x, labelY, alignRight = false)
            x += font.width(Component.translatable("tukuai.table.seconds", (table.timerTicks() + 19) / 20)) + 6
        }
        if (corner) {
            renderWaits(g, table, view, MARGIN, labelY + 13, mouseX, mouseY, me, summary = false)
        } else {
            renderWaits(g, table, view, x, labelY, mouseX, mouseY, me, limit = buttonsLeft - 6)
        }
        if (screen.inWorld || view.handOver) return
        val held = shownHand(view, me)
        val y = handY()
        val w = MjArt.width(HAND_SCALE)
        val playing = view.seat == me && asked(table, view) && discarding(view)
        val ready = if (playing) readyAfter(view) else emptyMap()
        var end = 0
        for ((i, tx) in handSlots(view, me)) {
            val tile = held[i].first
            val picked = i in selected
            val hover = mouseX >= tx && mouseX < tx + w && mouseY >= y - RAISE && view.seat == me
            val ty = if (picked) y - RAISE else y
            MjArt.draw(g, tile, tx, ty, HAND_SCALE, dim = playing && !canDiscard(view, tile), tint = if (hover && !picked) 0x30FFFFFF else 0)
            // A tile whose discard leaves the hand ready gets a mark over it: green when it could win, yellow when not.
            ready[tile]?.let { ok -> g.fill(tx + 3, ty - 5, tx + w - 3, ty - 2, if (ok) READY_MARK else NOT_READY_MARK) }
            markColor(view, tile).takeIf { it != 0 }?.let { g.fill(tx + 1, ty + MjArt.height(HAND_SCALE) - 3, tx + w - 1, ty + MjArt.height(HAND_SCALE) - 1, it) }
            if (picked) frame(g, tx, ty, w, MjArt.height(HAND_SCALE), 0xFFFFD84A.toInt())
            end = tx + w
        }
        if (end == 0) end = handLeft(view, 0, false, me)
        val meldEnd = renderMelds(g, view.melds[me], end + MELD_GAP, y, HAND_SCALE)
        renderFlowers(g, view.flowers.getOrElse(me) { emptyList() }, meldEnd + 2, y + 8)
    }

    /** The tile picked, or the one under the mouse, for showing what discarding it would leave. */
    private fun focused(view: V, mouseX: Int, mouseY: Int, me: Seat): Tile? {
        pickedTiles(view).singleOrNull()?.let { return it }
        if (screen.inWorld) return null
        val y = handY()
        if (mouseY < y - RAISE || mouseY > height) return null
        val w = MjArt.width(HAND_SCALE)
        val held = shownHand(view, me)
        return handSlots(view, me).firstOrNull { (_, tx) -> mouseX >= tx && mouseX < tx + w }?.let { held[it.first].first }
    }

    /**
     * The tiles you wait on once ready, each with how many are still unseen; on your turn with
     * a tile picked or under the mouse, those you would wait on after discarding it. A wait
     * that would not count at this table is greyed.
     */
    private fun renderWaits(g: GuiGraphicsExtractor, table: ClientTable, view: V, x: Int, y: Int, mouseX: Int, mouseY: Int, me: Seat, summary: Boolean = true, limit: Int = Int.MAX_VALUE) {
        if (view.seat == null || view.handOver) return
        val focus = if (asked(table, view) && discarding(view)) focused(view, mouseX, mouseY, me) else null
        val (key, waits) = when {
            focus != null -> "tukuai.mahjong.waits_after" to discardHints[focus].orEmpty()
            view.hand.size % 3 == 1 -> "tukuai.mahjong.waits" to waitingHints
            else -> return
        }
        if (waits.isEmpty()) {
            if (focus != null) g.text(font, Component.translatable("tukuai.mahjong.not_ready_after"), x, y, TableScreen.GRAY, true)
            return
        }
        val label = Component.translatable(key)
        g.text(font, label, x, y, TableScreen.GREEN, true)
        var at = x + font.width(label) + 4
        val w = MjArt.width(SMALL_SCALE)
        for (hint in waits) {
            if (at + w + font.width("${hint.left}") > limit) return
            MjArt.draw(g, hint.tile, at, y - 4, SMALL_SCALE, dim = !hint.ok || hint.left == 0)
            val left = "${hint.left}"
            g.text(font, Component.literal(left), at + w + 1, y, if (hint.left == 0) TableScreen.RED else if (hint.ok) TableScreen.WHITE else TableScreen.GRAY, true)
            at += w + font.width(left) + 4
        }
        if (!summary) return
        val winning = waits.filter { it.ok }
        val total = (winning.ifEmpty { waits }).sumOf { it.left }
        val line = Component.translatable("tukuai.mahjong.left_total", total)
        if (winning.isEmpty()) line.append(" · ").append(cannotWin()) else winning.mapNotNull { it.value }.firstOrNull()?.let { line.append(" · ").append(it) }
        if (at + 2 + font.width(line) <= limit) g.text(font, line, at + 2, y, TableScreen.GRAY, true)
    }

    /** Said when every tile the hand waits on would not count as a win at this table. */
    protected open fun cannotWin(): Component = Component.translatable("tukuai.mahjong.cannot_win")

    companion object {
        const val HAND_SCALE = 0.6f
        const val SMALL_SCALE = 0.28f
        private const val FLOWER_SCALE = 0.22f

        private const val POND_SCALE = 0.25f
        private const val MELD_SCALE = 0.22f
        private const val POND_ROW = 6
        private const val MARGIN = 8
        private const val TAG_W = 80

        /** The table area starts below the scoreboard row at the top. */
        private const val TOP_LIMIT = 14
        private const val SIDE_TAG_Y = 62
        private const val RAISE = 6
        private const val APART = 6
        private const val MELD_GAP = 6
        const val TOP_BOX_Y = 4
        const val BOX_H = 38
        const val RESULT_TOP = 30
        private const val BOTTOM_BUTTONS = 64
        private const val READY_MARK = 0xFF55DD55.toInt()
        private const val NOT_READY_MARK = 0xFFDDAA33.toInt()
        private const val RIICHI_MARK = 0xFF66AAFF.toInt()

        /** In the immersive view, how far up from the bottom the corners beside the hotbar start. */
        private const val CORNER_TOP = 42
    }
}
