package io.github.konyaco.tukuai.client.cave

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PanelText
import io.github.konyaco.tukuai.client.PlayingCardHand
import io.github.konyaco.tukuai.client.SeatBoxes
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.cave.CaveAction
import io.github.konyaco.tukuai.engine.cave.CaveCard
import io.github.konyaco.tukuai.engine.cave.CaveCodecs
import io.github.konyaco.tukuai.engine.cave.CaveEventKind
import io.github.konyaco.tukuai.engine.cave.CaveScoring
import io.github.konyaco.tukuai.engine.cave.CaveView
import io.github.konyaco.tukuai.engine.cave.PathCard
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

/**
 * The cave expedition part of the table screen: the other players around the edges in the
 * order they sit, with what each has in their chest and carries; the path in the middle with
 * the emeralds left on it and how likely the next card is to end the round; and under it, while
 * you are in the cave, the two choices.
 */
class CavePanel(private val screen: PanelHost) : GamePanel {
    private var wasMyTurn = false

    private val width get() = screen.panelWidth
    private val height get() = screen.panelHeight
    private val font get() = Minecraft.getInstance().font

    private fun send(action: CaveAction) = screen.send(TableOp.ACT, data = CaveCodecs.ACTION.encode(action))

    /** There is no hand, so the buttons sit at the bottom; in the immersive view, above the hotbar. */
    private fun buttonY() = if (screen.inWorld) height - PlayingCardHand.BOTTOM_BUTTONS else height - 30

    override fun controlsY(): Int = buttonY()

    private fun myTurn(table: ClientTable, view: CaveView) =
        screen.interactive && table.snapshot.status == TableStatus.PLAYING && view.isMyTurn

    // ---- Buttons -------------------------------------------------------------------------------

    override fun rebuild(table: ClientTable) {
        val view = table.view as? CaveView ?: return
        if (!screen.interactive) return
        val mine = myTurn(table, view)
        if (mine && !wasMyTurn) {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f))
        }
        wasMyTurn = mine
        if (table.snapshot.status == TableStatus.FINISHED) screen.finishButtons(table, width / 2, resultTop() + resultHeight(view) - 26)
        if (!mine) return
        val w = 96
        val gap = 8
        val x = (width - (2 * w + gap)) / 2
        screen.button(Component.translatable("tukuai.cave.deeper"), x, buttonY(), w) { send(CaveAction.Deeper) }
        screen.button(Component.translatable("tukuai.cave.back"), x + w + gap, buttonY(), w) { send(CaveAction.Back) }
    }

    // ---- Drawing -------------------------------------------------------------------------------

    override fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int) {
        val view = table.view as? CaveView ?: return
        val me = table.snapshot.you ?: 0
        PanelText.title(g, table, screen.interactive, width)
        for (seat in 0 until view.players) {
            if (seat == me) continue
            val (x, y) = SeatBoxes.of(view.players, me, seat, width, BOX)
            renderOpponent(g, table, view, seat, x, y)
        }
        if (view.finished) {
            renderResult(g, table, view)
            return
        }
        renderMiddle(g, table, view, mouseX, mouseY)
        renderSelf(g, table, view, me)
    }

    /** A player's name, their chest and what they carry, and whether they are in the cave and have chosen. */
    private fun renderOpponent(g: GuiGraphicsExtractor, table: ClientTable, view: CaveView, seat: Seat, x: Int, y: Int) {
        val playing = table.snapshot.status == TableStatus.PLAYING
        val choosing = playing && seat in view.deciding
        g.fill(x - 2, y - 2, x + BOX + 2, y + 36, if (choosing) 0x90806020.toInt() else 0x70000000)
        g.text(font, font.plainSubstrByWidth(PanelText.seatLabel(table, seat).string, BOX), x, y + 1, TableScreen.WHITE, true)
        renderCounts(g, view, seat, x, y + 12)
        if (view.finished) return
        val status = when {
            !view.inside[seat] -> Component.translatable("tukuai.cave.status.out")
            view.decided[seat] -> Component.translatable("tukuai.cave.status.chosen")
            else -> Component.translatable("tukuai.cave.status.choosing")
        }
        val color = when {
            !view.inside[seat] -> TableScreen.GREEN
            view.decided[seat] -> TableScreen.GRAY
            else -> TableScreen.YELLOW
        }
        g.text(font, font.plainSubstrByWidth(status.string, BOX - 24), x, y + 24, color, true)
        if (choosing) PanelText.timer(g, table, x + BOX, y + 24, alignRight = true)
    }

    /** The chest and its emeralds, then the emeralds carried in the cave, as pictures with numbers. */
    private fun renderCounts(g: GuiGraphicsExtractor, view: CaveView, seat: Seat, x: Int, y: Int) {
        drawChest(g, x, y - 1, 9)
        val chest = Component.literal(view.chest[seat].toString())
        g.text(font, chest, x + 11, y, TableScreen.WHITE, true)
        if (view.finished) return
        val px = x + 11 + font.width(chest) + 8
        CardArt.drawEmerald(g, px, y - 1, 9)
        g.text(font, Component.literal(view.pack[seat].toString()), px + 11, y, if (view.inside[seat]) TableScreen.YELLOW else TableScreen.GRAY, true)
    }

    private fun drawChest(g: GuiGraphicsExtractor, x: Int, y: Int, size: Int) {
        g.blitTexture(
            CardArt.ATLAS, x, y, CardArt.u(CardArt.CHEST_SIDE).toFloat(), CardArt.v(CardArt.CHEST_SIDE).toFloat(),
            size, size, CardArt.WIDTH, CardArt.WIDTH, CardArt.TEXTURE_WIDTH, CardArt.TEXTURE_HEIGHT,
        )
    }

    /**
     * The round, the path with the emeralds left on its cards, the latest news, and what is at
     * stake: how many emeralds lie on the path and how likely the next card is to end the round.
     */
    private fun renderMiddle(g: GuiGraphicsExtractor, table: ClientTable, view: CaveView, mouseX: Int, mouseY: Int) {
        val cx = width / 2
        var y = MIDDLE_Y
        g.centeredText(font, Component.translatable("tukuai.cave.round", view.round, view.rules.rounds), cx, y, TableScreen.GOLD)
        y += 12
        // The immersive view shows the path on the table itself.
        if (!screen.inWorld) y = renderPath(g, view.path, cx, y, mouseX, mouseY) + 4
        val ending = CaveUi.ending(table)
        val news = when {
            ending != null -> CaveUi.endLine(table, ending) to TableScreen.RED
            else -> newsLine(table, view)?.let { it to TableScreen.WHITE }
        }
        if (news != null) {
            g.centeredText(font, news.first, cx, y, news.second)
            y += 11
        }
        val stakes = Component.translatable("tukuai.cave.stakes", view.leftover, CaveUi.percent(view.danger()))
        g.centeredText(font, stakes, cx, y, TableScreen.YELLOW)
        y += 11
        val warned = view.warned
        if (warned.isNotEmpty()) {
            val names = warned.sorted().map(CaveUi::hazardName)
            val list = names.drop(1).fold(names.first().copy()) { a, b -> a.append(Component.translatable("tukuai.cave.list_gap")).append(b) }
            g.centeredText(font, Component.translatable("tukuai.cave.warned", list), cx, y, TableScreen.RED)
        }
    }

    /** The path in rows, each card small, the newest one framed; returns the y under it. */
    private fun renderPath(g: GuiGraphicsExtractor, path: List<PathCard>, cx: Int, top: Int, mouseX: Int, mouseY: Int): Int {
        if (path.isEmpty()) return top
        val room = width - 2 * (BOX + 16)
        val perRow = (room / PATH_STEP).coerceIn(4, 12)
        val rows = (path.size + perRow - 1) / perRow
        val scale = if (rows > 2) 0.55f else 0.75f
        val w = (CardArt.WIDTH * scale).toInt()
        val h = (CardArt.HEIGHT * scale).toInt()
        val step = if (rows > 2) w + 2 else PATH_STEP
        val perLine = if (rows > 2) (room / step).coerceAtLeast(perRow) else perRow
        var hovered: PathCard? = null
        path.forEachIndexed { i, card ->
            val row = i / perLine
            val inRow = minOf(perLine, path.size - row * perLine)
            val x = cx - (inRow * step - (step - w)) / 2 + (i % perLine) * step
            val y = top + row * (h + LEFT_ROW)
            CardArt.draw(g, CardArt.cave(card.card), x, y, scale)
            if (i == path.size - 1) frame(g, x, y, w, h, TableScreen.GOLD)
            // Emeralds left on a card show under it, clear of its value.
            if (card.left > 0) {
                val text = Component.literal(card.left.toString())
                val tx = x + (w - font.width(text) - 8) / 2
                CardArt.drawEmerald(g, tx, y + h + 1, 7)
                g.text(font, text, tx + 8, y + h + 1, TableScreen.GREEN, true)
            }
            if (mouseX in x until x + w && mouseY in y until y + h) hovered = card
        }
        hovered?.let { g.setTooltipForNextFrame(font, cardTip(it), mouseX, mouseY) }
        val lastRow = (path.size - 1) / perLine
        return top + (lastRow + 1) * (h + LEFT_ROW)
    }

    private fun frame(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, h: Int, color: Int) {
        g.fill(x - 1, y - 1, x + w + 1, y, color)
        g.fill(x - 1, y + h, x + w + 1, y + h + 1, color)
        g.fill(x - 1, y, x, y + h, color)
        g.fill(x + w, y, x + w + 1, y + h, color)
    }

    /** "Gold ore, worth 12; 2 left on it" or "Creeper". */
    private fun cardTip(card: PathCard): Component = when (val c = card.card) {
        is CaveCard.Vein -> Component.translatable("tukuai.cave.tip.vein", CaveUi.oreName(c.ore), c.value, card.left)
        is CaveCard.Danger -> Component.translatable("tukuai.cave.tip.danger", CaveUi.hazardName(c.hazard))
    }

    /** What the latest card brought, or who went back on the last choice. */
    private fun newsLine(table: ClientTable, view: CaveView): Component? {
        val log = view.roundLog
        val reveal = log.lastOrNull { it.kind == CaveEventKind.REVEAL } ?: return null
        val backs = log.subList(0, log.indexOf(reveal)).takeLastWhile { it.kind == CaveEventKind.BACK }
        val revealed = when (val card = reveal.card) {
            is CaveCard.Vein -> Component.translatable("tukuai.cave.news.vein", CaveUi.oreName(card.ore), card.value, reveal.count, reveal.amount)
            is CaveCard.Danger -> Component.translatable("tukuai.cave.news.danger", CaveUi.hazardName(card.hazard))
            null -> return null
        }
        if (backs.isEmpty()) return revealed
        val names = backs.map { CaveUi.name(table, it.seat) }
        val who = names.drop(1).fold(names.first().copy()) { a, b -> a.append(Component.translatable("tukuai.cave.list_gap")).append(b) }
        return Component.translatable("tukuai.cave.news.back", who).append(Component.translatable("tukuai.cave.list_gap")).append(revealed)
    }

    /** Your name and emeralds at the bottom left; over the buttons what you may do, or what you chose. */
    private fun renderSelf(g: GuiGraphicsExtractor, table: ClientTable, view: CaveView, me: Seat) {
        val seen = table.snapshot.you != null
        val x = 8
        val y = buttonY() - 8
        g.text(font, if (!seen) Component.translatable("tukuai.table.watching") else PanelText.seatLabel(table, me), x, y, TableScreen.WHITE, true)
        if (!seen) return
        g.text(font, Component.translatable("tukuai.cave.mine", view.chest[me], view.pack[me]), x, y + 12, TableScreen.YELLOW, true)
        if (myTurn(table, view)) PanelText.timer(g, table, x, y - 12, alignRight = false)
        if (table.snapshot.status != TableStatus.PLAYING) return
        val note = when {
            !view.inside[me] -> Component.translatable("tukuai.cave.note.out")
            myTurn(table, view) -> Component.translatable("tukuai.cave.note.choose", view.pack[me])
            view.myChoice == CaveAction.Deeper -> Component.translatable("tukuai.cave.note.chose_deeper")
            view.myChoice == CaveAction.Back -> Component.translatable("tukuai.cave.note.chose_back")
            else -> null
        }
        if (note != null) g.centeredText(font, note, width / 2, buttonY() - 14, if (myTurn(table, view)) TableScreen.YELLOW else TableScreen.GRAY)
    }

    private fun resultTop() = 56

    private fun resultHeight(view: CaveView) = 34 + view.players * 11 + 26

    /** Who won, then everyone from the richest down with their emeralds and points. */
    private fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: CaveView) {
        val w = 212
        val x = width / 2 - w / 2
        val top = resultTop()
        g.fill(x, top, x + w, top + resultHeight(view), 0xE0101010.toInt())
        val me = table.snapshot.you
        val result = CaveScoring.result(view.chest)
        val color = if (me == null) TableScreen.WHITE else if (me in result.winners) TableScreen.GREEN else TableScreen.RED
        g.centeredText(font, CaveUi.wonLine(table, result.winners), width / 2, top + 6, color)
        g.centeredText(font, Component.translatable("tukuai.cave.result_detail", view.rules.rounds), width / 2, top + 18, TableScreen.GRAY)
        val order = (0 until view.players).sortedByDescending { view.chest[it] }
        order.forEachIndexed { row, seat ->
            val y = top + 32 + row * 11
            val points = result.scores[seat]
            val score = Component.literal(if (points > 0) "+$points" else points.toString())
            val chest = Component.translatable("tukuai.cave.result.chest", view.chest[seat])
            val chestX = x + w - 40 - font.width(chest)
            g.text(font, font.plainSubstrByWidth(CaveUi.name(table, seat).string, chestX - x - 14), x + 8, y, if (seat == me) TableScreen.YELLOW else TableScreen.WHITE, false)
            g.text(font, chest, chestX, y, TableScreen.GRAY, false)
            g.text(font, score, x + w - 8 - font.width(score), y, if (points >= 0) TableScreen.GREEN else TableScreen.RED, false)
        }
    }

    companion object {
        /** Width of a player's box. */
        private const val BOX = 92

        /** Top of the middle, under the player across the table. */
        private const val MIDDLE_Y = 54

        /** Room under each row of the path for the emeralds left on its cards. */
        private const val LEFT_ROW = 11

        /** How far apart the cards of the path are in the screen. */
        private const val PATH_STEP = 26
    }
}
