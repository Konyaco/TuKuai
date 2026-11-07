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
        if (showingResult(view)) {
            resultButtons(table, view)
            return
        }
        if (!myTurn) return
        row(buttonY(), buttons(table, view))
    }

    /** Buttons side by side, centred. */
    protected fun row(y: Int, buttons: List<Btn>, w: Int = 56) {
        if (buttons.isEmpty()) return
        val gap = 6
        val widths = buttons.map { maxOf(w, font.width(it.text) + 12) }
        var x = (width - (widths.sum() + gap * (buttons.size - 1))) / 2
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

    /** The row with your name, badges and waits on the left and the wall on the right. */
    protected fun labelY() = if (screen.inWorld) buttonY() - 34 else handY() - RAISE - 11

    protected fun buttonY() = if (screen.inWorld) height - BOTTOM_BUTTONS else handY() - 36

    override fun controlsY(): Int = buttonY()

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

    private enum class Place { LEFT, TOP, RIGHT }

    override fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int) {
        val view = view(table) ?: return
        val me = table.snapshot.you ?: view.seat ?: 0
        PanelText.title(g, table, screen.interactive, width)
        if (showingResult(view)) {
            renderResult(g, table, view)
            return
        }
        hints(view)
        renderInfo(g, table, view)
        renderBox(g, table, view, (me + 3) % 4, Place.LEFT)
        renderBox(g, table, view, (me + 2) % 4, Place.TOP)
        renderBox(g, table, view, (me + 1) % 4, Place.RIGHT)
        renderMyDiscards(g, view, me)
        renderPrompt(g, table, view)
        renderSelf(g, table, view, me, mouseX, mouseY)
        renderExtra(g, table, view)
    }

    /** Anything else the rule set shows during a hand, such as the dora indicators. */
    protected open fun renderExtra(g: GuiGraphicsExtractor, table: ClientTable, view: V) {}

    /** The wall left, the round and the dealer: at the right of your row, or in the immersive view in the bottom right corner. */
    private fun renderInfo(g: GuiGraphicsExtractor, table: ClientTable, view: V) {
        val lines = info(table, view)
        if (screen.inWorld) {
            lines.forEachIndexed { i, line -> PanelText.text(g, line, width - 8, height - CORNER_TOP + i * 11, TableScreen.GRAY, PanelText.Align.RIGHT) }
            return
        }
        val line = Component.empty()
        lines.forEachIndexed { i, l -> if (i > 0) line.append(" · "); line.append(l) }
        g.text(font, line, width - 8 - font.width(line), labelY(), TableScreen.GRAY, true)
    }

    private fun renderBox(g: GuiGraphicsExtractor, table: ClientTable, view: V, seat: Seat, place: Place) {
        val compact = ClientConfig.immersive && screen.interactive
        val boxW = if (compact) 116 else 126
        val margin = if (compact) 4 else 8
        val x = when (place) {
            Place.LEFT -> margin
            Place.TOP -> width / 2 - boxW / 2
            Place.RIGHT -> width - margin - boxW
        }
        val y = if (place == Place.TOP) TOP_BOX_Y else if (compact) SIDE_BOX_Y + 6 else SIDE_BOX_Y
        val acting = live(table) && view.toAct == seat && !view.handOver
        g.fill(x - 2, y - 2, x + boxW + 2, y + BOX_H, if (acting) 0xA0806020.toInt() else 0x80202830.toInt())
        val score = score(view, seat)
        val scoreW = score?.let { font.width(it) + 4 } ?: 0
        val name = PanelText.seatLabel(table, seat).string
        g.text(font, font.plainSubstrByWidth(name, boxW - scoreW), x, y + 2, TableScreen.WHITE, true)
        if (score != null) g.text(font, Component.literal(score), x + boxW - font.width(score), y + 2, TableScreen.GRAY, true)
        MjArt.drawBack(g, x, y + 12, 0.25f)
        g.text(font, Component.literal(if (compact) "${view.handCounts[seat]}" else "× ${view.handCounts[seat]}"), x + 10, y + 13, TableScreen.WHITE, true)
        badge(view, seat)?.let { g.text(font, it, x + boxW - font.width(it), y + 13, TableScreen.YELLOW, true) }
        if (acting) PanelText.timer(g, table, x + boxW, y + 24, alignRight = true)
        val end = renderMelds(g, view.melds[seat], x, y + 24, SMALL_SCALE)
        renderFlowers(g, view.flowers.getOrElse(seat) { emptyList() }, end + 2, y + 24)
        if (screen.inWorld) return
        val w = MjArt.width(SMALL_SCALE)
        when (place) {
            Place.LEFT -> renderDiscards(g, view, seat, x + boxW + 6, y, ROW)
            Place.RIGHT -> renderDiscards(g, view, seat, x - 6 - ROW * w, y, ROW)
            Place.TOP -> renderDiscards(g, view, seat, x + boxW / 2 - ROW * w / 2, y + BOX_H + 3, ROW)
        }
    }

    /** A seat's discards, [perRow] to a row from ([x], [y]), the one being claimed framed and the sideways one marked. */
    private fun renderDiscards(g: GuiGraphicsExtractor, view: V, seat: Seat, x: Int, y: Int, perRow: Int) {
        val tiles = view.discards[seat]
        val w = MjArt.width(SMALL_SCALE)
        val h = MjArt.height(SMALL_SCALE)
        val turned = view.sideways(seat)
        tiles.forEachIndexed { i, tile ->
            val tx = x + (i % perRow) * w
            val ty = y + (i / perRow) * (h + 1)
            MjArt.draw(g, tile, tx, ty, SMALL_SCALE)
            if (i == turned) frame(g, tx, ty, w, h, RIICHI_MARK)
            if (view.claimFrom == seat && i == tiles.size - 1 && view.claimingDiscard) frame(g, tx, ty, w, h, MjScene.CLAIM_COLOR)
        }
    }

    protected fun frame(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int) {
        g.fill(x - 1, y - 1, x + w + 1, y, color)
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, color)
        g.fill(x - 1, y, x, y + h, color)
        g.fill(x + w, y, x + w + 1, y + h, color)
    }

    /** Melds side by side from [x]; returns the x past the last. A concealed kong shows its ends face down. */
    protected fun renderMelds(g: GuiGraphicsExtractor, melds: List<Meld>, x: Int, y: Int, scale: Float): Int {
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

    /** Flowers, small, from [x]; returns the x past the last. */
    protected fun renderFlowers(g: GuiGraphicsExtractor, flowers: List<Tile>, x: Int, y: Int): Int {
        var at = x
        for (f in flowers) {
            MjArt.draw(g, f, at, y + 2, FLOWER_SCALE)
            at += MjArt.width(FLOWER_SCALE)
        }
        return at
    }

    /** Your discards, twelve to a row, over the buttons; in the immersive view they are on the table. */
    private fun renderMyDiscards(g: GuiGraphicsExtractor, view: V, me: Seat) {
        val tiles = view.discards[me]
        if (tiles.isEmpty() || screen.inWorld) return
        val w = MjArt.width(SMALL_SCALE)
        val h = MjArt.height(SMALL_SCALE) + 1
        val rows = (tiles.size + MY_ROW - 1) / MY_ROW
        renderDiscards(g, view, me, width / 2 - MY_ROW * w / 2, buttonY() - 24 - rows * h, MY_ROW)
    }

    /** What this turn asks of you, or whom everyone waits for: over the buttons, or in the immersive view under the top box. */
    private fun renderPrompt(g: GuiGraphicsExtractor, table: ClientTable, view: V) {
        val y = if (screen.inWorld) TOP_BOX_Y + BOX_H + 12 else buttonY() - 11
        val (line, ask) = prompt(table, view, asked(table, view))
        if (line != null) g.centeredText(font, line, width / 2, y - 10, TableScreen.WHITE)
        if (ask != null) g.centeredText(font, ask, width / 2, y, TableScreen.YELLOW)
    }

    /**
     * Your hand and melds along the bottom, and your row over it: name, badges, the clock and
     * what you wait on. In the immersive view the hand is on the table and the rest goes in
     * the bottom left corner, beside the hotbar.
     */
    private fun renderSelf(g: GuiGraphicsExtractor, table: ClientTable, view: V, me: Seat, mouseX: Int, mouseY: Int) {
        val seen = table.snapshot.you != null || view.shown.getOrElse(me) { emptyList() }.isNotEmpty()
        val label = if (!seen) Component.translatable("tukuai.table.watching") else PanelText.seatLabel(table, me)
        val corner = screen.inWorld
        val labelY = if (corner) height - CORNER_TOP else labelY()
        g.text(font, label, 8, labelY, TableScreen.WHITE, true)
        if (!seen) return
        var x = 8 + font.width(label) + 6
        score(view, me)?.let {
            g.text(font, Component.literal(it), x, labelY, TableScreen.GRAY, true)
            x += font.width(it) + 6
        }
        if (asked(table, view) && table.timerTicks() >= 0) {
            PanelText.timer(g, table, x, labelY, alignRight = false)
            x += font.width(Component.translatable("tukuai.table.seconds", (table.timerTicks() + 19) / 20)) + 6
        }
        val rowY = if (corner) labelY + 11 else labelY
        if (corner) x = 8
        badge(view, me)?.let {
            g.text(font, it, x, rowY, TableScreen.YELLOW, true)
            x += font.width(it) + 6
        }
        if (corner) {
            renderWaits(g, table, view, 8, rowY + 13, mouseX, mouseY, me, summary = false)
        } else {
            // The row ends where the wall and round start on the right.
            val info = Component.empty()
            info(table, view).forEachIndexed { i, l -> if (i > 0) info.append(" · "); info.append(l) }
            renderWaits(g, table, view, x, labelY, mouseX, mouseY, me, limit = width - 14 - font.width(info))
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

        /** How many of another player's discards go in a row, and of your own. */
        private const val ROW = 5
        private const val MY_ROW = 12
        private const val RAISE = 6
        private const val APART = 6
        private const val MELD_GAP = 6
        const val TOP_BOX_Y = 4
        private const val SIDE_BOX_Y = 66
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
