package io.github.konyaco.tukuai.client

import com.mojang.blaze3d.platform.InputConstants
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.cave.CaveCard
import io.github.konyaco.tukuai.engine.core.GameRegistry
import io.github.konyaco.tukuai.engine.core.GuideBlock
import io.github.konyaco.tukuai.engine.core.GuideSection
import io.github.konyaco.tukuai.engine.core.guide
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.client.mahjong.MjArt
import io.github.konyaco.tukuai.engine.tutu.TutuFace
import io.github.konyaco.tukuai.engine.uno.UnoFace
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.network.chat.CommonComponents
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import net.minecraft.util.FormattedCharSequence

/**
 * The rule book: a tab for each game, its sections down the left and the open page on the
 * right, with example cards drawn from the real card art. Opened from a table, it starts
 * with what that table's rules are and goes back to the table when closed.
 */
class RulesScreen(
    private val parent: Screen?,
    /** The table it was opened from, for its rules and to tell the player when it's their turn. */
    private val tablePos: BlockPos?,
    gameId: String,
) : CompatScreen(Component.translatable("tukuai.guide.title")) {
    private val games = GameRegistry.all.map { it.id }.filter { GameUis[it] != null && GameRegistry[it]!!.guide(null).isNotEmpty() }
    private var game = gameId.takeIf { it in games } ?: games.firstOrNull() ?: gameId
    private var sections: List<GuideSection> = emptyList()
    private var section = 0
    private var scroll = 0
    private var page: List<Piece> = emptyList()
    private var pageHeight = 0
    private var wasMyTurn = myTurn()

    init {
        openGame(game)
        if (!ClientConfig.guideSeen) {
            ClientConfig.guideSeen = true
            ClientConfig.save()
        }
    }

    /** Turns to the section called [id], such as `patterns`; used by the screenshot tests too. */
    fun showSection(id: String) {
        section = sections.indexOfFirst { it.id == id }.coerceAtLeast(0)
        scroll = 0
        listScroll = ((section * ROW - (listBottom() - top) / 2) / ROW * ROW).coerceAtLeast(0)
        if (minecraft != null) rebuildWidgets()
    }

    private fun table(): ClientTable? = tablePos?.let { ClientTables[it] }

    private fun openGame(id: String) {
        game = id
        // The table's own rules come first, but only in the book of the game it is set to.
        val preset = table()?.snapshot?.takeIf { it.gameId == id }?.presetId
        sections = GameRegistry[id]?.guide(preset) ?: emptyList()
        section = 0
        scroll = 0
    }

    // ---- Layout --------------------------------------------------------------------------------

    private fun sideWidth() = (width / 4).coerceIn(76, 120)

    private fun pageLeft() = MARGIN + sideWidth() + 10

    private fun pageRight() = width - MARGIN

    /** Room for the text, leaving space for the scroll bar on the right. */
    private fun textWidth() = pageRight() - pageLeft() - 12

    /** Where the sections and the page start, under the game tabs; lower when the tabs take two rows. */
    private var top = TOP

    private fun viewTop() = top + 22

    private fun viewBottom() = height - MARGIN - 4

    private fun maxScroll() = (pageHeight - (viewBottom() - viewTop())).coerceAtLeast(0)

    // ---- The list of sections -------------------------------------------------------------------

    /** How far the list of sections is scrolled, when it is longer than the room for it. */
    private var listScroll = 0

    private fun listBottom() = height - MARGIN - if (myTurn()) 24 else 0

    private fun maxListScroll() = (sections.size * ROW - (listBottom() - top)).coerceAtLeast(0)

    /** The section under ([x], [y]) in the list, or -1. */
    private fun sectionAt(x: Double, y: Double): Int {
        if (x < MARGIN || x >= MARGIN + sideWidth() || y < top || y >= listBottom()) return -1
        val i = ((y - top + listScroll) / ROW).toInt()
        return if (i in sections.indices) i else -1
    }

    /** The sections down the left, one row each, the open one lit; it scrolls when they do not all fit. */
    private fun renderSections(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val w = sideWidth()
        val bottom = listBottom()
        g.fill(MARGIN, top, MARGIN + w, bottom, 0xA0101010.toInt())
        g.enableScissor(MARGIN, top, MARGIN + w, bottom)
        val hover = sectionAt(mouseX.toDouble(), mouseY.toDouble())
        sections.forEachIndexed { i, s ->
            val y = top + i * ROW - listScroll
            if (y + ROW <= top || y >= bottom) return@forEachIndexed
            when {
                i == section -> {
                    g.fill(MARGIN, y, MARGIN + w, y + ROW, 0x50FFD24A)
                    g.fill(MARGIN, y, MARGIN + 2, y + ROW, 0xFFFFD24A.toInt())
                }
                i == hover -> g.fill(MARGIN, y, MARGIN + w, y + ROW, 0x30FFFFFF)
            }
            val title = sectionTitle(s).string
            val fitted = if (font.width(title) <= w - 10) title else font.plainSubstrByWidth(title, w - 10 - font.width("…")) + "…"
            g.text(font, fitted, MARGIN + 6, y + (ROW - 8) / 2, if (i == section) TableScreen.YELLOW else TableScreen.WHITE, false)
        }
        g.disableScissor()
        val max = maxListScroll()
        if (max > 0) {
            val track = bottom - top
            val thumb = maxOf(12, track * track / (track + max))
            val thumbY = top + (track - thumb) * listScroll / max
            g.fill(MARGIN + w - 2, thumbY, MARGIN + w, thumbY + thumb, 0xA0FFFFFF.toInt())
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val i = sectionAt(event.x(), event.y())
        if (i >= 0 && event.button() == InputConstants.MOUSE_BUTTON_LEFT) {
            if (i != section) {
                section = i
                scroll = 0
                mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1f))
                rebuildWidgets()
            }
            return true
        }
        return super.mouseClicked(event, doubleClick)
    }

    override fun init() {
        // The tabs share the row left of the done button. When they do not all fit, they take two
        // rows, and if even those are too narrow, the tabs in a row narrow alike.
        val widths = games.map { font.width(TableScreen.gameName(it)) + 16 }
        val room = width - 2 * MARGIN - 60 - 4
        val rows = if (widths.sum() + 4 * games.size <= room) listOf(games.indices.toList()) else games.indices.chunked((games.size + 1) / 2)
        top = TOP + (rows.size - 1) * TAB_ROW
        rows.forEachIndexed { row, tabs ->
            val total = tabs.sumOf { widths[it] }
            val fit = minOf(1f, (room - 4 * tabs.size).toFloat() / total)
            var x = MARGIN
            for (i in tabs) {
                val id = games[i]
                val w = (widths[i] * fit).toInt()
                val b = button(TableScreen.gameName(id), x, 6 + row * TAB_ROW, w) {
                    openGame(id)
                    rebuildWidgets()
                }
                b.active = id != game
                x += w + 4
            }
        }
        button(CommonComponents.GUI_DONE, width - MARGIN - 60, 6, 60) { onClose() }

        listScroll = listScroll.coerceIn(0, maxListScroll())
        layOut()
    }

    private fun button(text: Component, x: Int, y: Int, w: Int, h: Int = 20, onPress: () -> Unit) =
        addRenderableWidget(Button.builder(text) { onPress() }.bounds(x, y, w, h).build())

    private fun sectionTitle(s: GuideSection): Component = Component.translatable("tukuai.guide.$game.${s.id}")

    // ---- The page ------------------------------------------------------------------------------

    /** One laid-out piece of the page: so many pixels tall, drawn with its top-left at (x, y). */
    private class Piece(val height: Int, val draw: (GuiGraphicsExtractor, Int, Int) -> Unit)

    private fun layOut() {
        val s = sections.getOrNull(section)
        val w = textWidth()
        val pieces = ArrayList<Piece>()
        if (s != null) {
            if (s.id == GuideSection.TABLE) table()?.let { t -> pieces += tableHeader(t, w) }
            val examples = s.blocks.filterIsInstance<GuideBlock.Example>()
            // One card size for the whole page, so the examples line up.
            val scale = if (examples.all { cardsWidth(it, 1f) <= w }) 1f else 0.75f
            // When every named example is a card or two, as with the UNO action cards, the
            // cards go in a column on the left with their text beside them instead of below.
            val named = examples.filter { it.name != null }
            val beside = if (named.isNotEmpty() && named.all { cardsWidth(it, scale) <= w / 4 }) {
                named.maxOf { cardsWidth(it, scale) } + 14
            } else {
                null
            }
            for (block in s.blocks) pieces += piece(block, w, scale, beside)
            if (s.id == GuideSection.TABLE) table()?.let { t -> pieces += timer(t, w) }
        }
        page = pieces
        pageHeight = pieces.sumOf { it.height }
        scroll = scroll.coerceIn(0, maxScroll())
    }

    private fun tableHeader(t: ClientTable, w: Int): Piece {
        val s = t.snapshot
        return paragraph(Component.translatable("tukuai.table.rules", TableScreen.presetName(s.gameId, s.presetId)).withColor(YELLOW_RGB), w, 0, 6)
    }

    private fun timer(t: ClientTable, w: Int): Piece {
        val seconds = t.snapshot.turnSeconds
        val text = if (seconds > 0) Component.translatable("tukuai.guide.table.timer", seconds) else Component.translatable("tukuai.guide.table.timer.off")
        return bullet(text, w)
    }

    private fun piece(block: GuideBlock, w: Int, scale: Float, beside: Int?): Piece = when (block) {
        is GuideBlock.Text -> paragraph(translate(block.key, block.args), w, 0, 6)
        is GuideBlock.Point -> bullet(translate(block.key, block.args), w)
        is GuideBlock.Example -> if (beside != null && block.name != null) exampleBeside(block, w, scale, beside) else example(block, w, scale)
    }

    private fun translate(key: String, args: List<Int>): Component = Component.translatable(key, *args.toTypedArray())

    private fun paragraph(text: Component, w: Int, indent: Int, gap: Int): Piece {
        val lines = font.split(text, w - indent)
        return Piece(lines.size * LINE + gap) { g, x, y -> drawLines(g, lines, x + indent, y) }
    }

    private fun bullet(text: Component, w: Int): Piece {
        val lines = font.split(text, w - BULLET_INDENT)
        return Piece(lines.size * LINE + 4) { g, x, y ->
            g.text(font, "•", x + 2, y, TableScreen.GRAY, false)
            drawLines(g, lines, x + BULLET_INDENT, y)
        }
    }

    private fun drawLines(g: GuiGraphicsExtractor, lines: List<FormattedCharSequence>, x: Int, y: Int) {
        lines.forEachIndexed { i, line -> g.text(font, line, x, y + i * LINE, TableScreen.WHITE, false) }
    }

    private fun caption(block: GuideBlock.Example): Component {
        val caption = Component.empty()
        block.name?.let { caption.append(Component.translatable(it).withColor(YELLOW_RGB)) }
        if (block.name != null && block.text != null) caption.append("  ")
        block.text?.let { caption.append(Component.translatable(it)) }
        return caption
    }

    /** Cards on the left, [column] wide, and the text beside them, centred against the cards. */
    private fun exampleBeside(block: GuideBlock.Example, w: Int, scale: Float, column: Int): Piece {
        val lines = font.split(caption(block), w - column)
        val cardHeight = (CardArt.HEIGHT * scale).toInt()
        val textHeight = lines.size * LINE
        return Piece(maxOf(cardHeight, textHeight) + 8) { g, x, y ->
            drawCards(g, block, x + 4, y, column - 4, scale)
            drawLines(g, lines, x + column, y + ((cardHeight - textHeight) / 2).coerceAtLeast(0))
        }
    }

    private fun example(block: GuideBlock.Example, w: Int, scale: Float): Piece {
        val lines = if (block.name == null && block.text == null) emptyList() else font.split(caption(block), w)
        val cardHeight = (CardArt.HEIGHT * scale).toInt()
        val textHeight = lines.size * LINE + if (lines.isEmpty()) 0 else 3
        return Piece(textHeight + cardHeight + 10) { g, x, y ->
            drawLines(g, lines, x, y)
            drawCards(g, block, x + 4, y + textHeight, w - 4, scale)
        }
    }

    private fun gap(block: GuideBlock.Example) = if (block.follows == null) 8 else 20

    /** Mahjong tiles stand side by side, where cards overlap. */
    private fun isTiles(block: GuideBlock.Example) = block.groups.firstOrNull()?.firstOrNull() is Tile

    /** How wide the example's cards are at their usual spacing for [scale]. */
    private fun cardsWidth(block: GuideBlock.Example, scale: Float, step: Int = step(scale)): Int {
        val cardW = (CardArt.WIDTH * scale).toInt()
        if (isTiles(block)) return block.groups.sumOf { it.size } * cardW + (block.groups.size - 1) * gap(block)
        return block.groups.sumOf { cardW + (it.size - 1) * step } + (block.groups.size - 1) * gap(block)
    }

    private fun step(scale: Float) = if (scale >= 1f) 18 else 13

    private fun drawCards(g: GuiGraphicsExtractor, block: GuideBlock.Example, left: Int, top: Int, room: Int, scale: Float) {
        if (isTiles(block)) return drawTiles(g, block, left, top, room, scale)
        val cardW = (CardArt.WIDTH * scale).toInt()
        val cardH = (CardArt.HEIGHT * scale).toInt()
        val overlaps = block.groups.sumOf { it.size - 1 }
        // Squeeze the cards together when the page is narrow, down to where the corner still shows.
        val step = if (cardsWidth(block, scale) <= room || overlaps == 0) {
            step(scale)
        } else {
            ((room - cardsWidth(block, scale, 0)) / overlaps).coerceIn(6, step(scale))
        }
        var x = left
        block.groups.forEachIndexed { i, group ->
            if (i > 0) {
                val gap = gap(block)
                when (block.follows) {
                    true -> g.centeredText(font, Component.literal("→"), x - gap / 2, top + cardH / 2 - 4, TableScreen.GREEN)
                    false -> g.centeredText(font, Component.literal("✗"), x - gap / 2, top + cardH / 2 - 4, TableScreen.RED)
                    null -> {}
                }
            }
            group.forEachIndexed { j, card -> sprite(card)?.let { CardArt.draw(g, it, x + j * step, top, scale) } }
            x += cardW + (group.size - 1) * step + gap(block)
        }
    }

    /** Tiles side by side, smaller when the page is too narrow for them, each group a set. */
    private fun drawTiles(g: GuiGraphicsExtractor, block: GuideBlock.Example, left: Int, top: Int, room: Int, scale: Float) {
        val gaps = (block.groups.size - 1) * gap(block)
        val count = block.groups.sumOf { it.size }.coerceAtLeast(1)
        val tileScale = minOf(scale, (room - gaps).toFloat() / count / CardArt.WIDTH)
        val tileW = MjArt.width(tileScale)
        val cardH = (CardArt.HEIGHT * scale).toInt()
        var x = left
        block.groups.forEachIndexed { i, group ->
            if (i > 0) {
                val gap = gap(block)
                if (block.follows == true) g.centeredText(font, Component.literal("→"), x - gap / 2, top + cardH / 2 - 4, TableScreen.GREEN)
            }
            for (tile in group) {
                MjArt.draw(g, tile as Tile, x, top, tileScale)
                x += tileW
            }
            x += gap(block)
        }
    }

    private fun sprite(card: Any): Int? = when (card) {
        is PlayingCard -> CardArt.face(card)
        is UnoFace -> CardArt.unoFace(card)
        is TutuFace -> CardArt.tutu(card)
        is CaveCard -> CardArt.cave(card)
        else -> null
    }

    // ---- Drawing -------------------------------------------------------------------------------

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        val left = pageLeft()
        val right = pageRight()
        g.fill(left - 6, top, right, height - MARGIN, 0xC0101010.toInt())
        sections.getOrNull(section)?.let { s ->
            g.text(font, sectionTitle(s), left, top + 6, TableScreen.YELLOW, true)
            g.fill(left, top + 17, right - 6, top + 18, 0x60FFFFFF)
        }
        val top = viewTop()
        val bottom = viewBottom()
        g.enableScissor(left - 6, top, right, bottom)
        var y = top - scroll
        for (piece in page) {
            if (y + piece.height > top && y < bottom) piece.draw(g, left, y)
            y += piece.height
        }
        g.disableScissor()
        val max = maxScroll()
        if (max > 0) {
            val track = bottom - top
            val thumb = maxOf(16, track * track / (track + max))
            val thumbY = top + (track - thumb) * scroll / max
            g.fill(right - 5, top, right - 3, bottom, 0x40FFFFFF)
            g.fill(right - 5, thumbY, right - 3, thumbY + thumb, 0xC0FFFFFF.toInt())
        }
        if (myTurn()) {
            // Under the list of sections, which leaves room for it.
            val lines = font.split(Component.translatable("tukuai.guide.your_turn"), sideWidth()).take(2)
            val y0 = listBottom() + 4
            lines.forEachIndexed { i, line -> g.text(font, line, MARGIN, y0 + i * LINE, TableScreen.YELLOW, true) }
        }
        renderSections(g, mouseX, mouseY)
        super.extractRenderState(g, mouseX, mouseY, partialTick)
    }

    private fun myTurn(): Boolean {
        val t = table() ?: return false
        val you = t.snapshot.you ?: return false
        return t.snapshot.status == TableStatus.PLAYING && t.ui?.turnSeat(t) == you
    }

    override fun tick() {
        val mine = myTurn()
        if (mine && !wasMyTurn) mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f))
        wasMyTurn = mine
    }

    // ---- Input ---------------------------------------------------------------------------------

    override fun mouseScrolled(x: Double, y: Double, scrollX: Double, scrollY: Double): Boolean {
        if (x < MARGIN + sideWidth()) {
            listScroll = (listScroll - (scrollY * ROW).toInt()).coerceIn(0, maxListScroll())
            if (listScroll != maxListScroll()) listScroll = listScroll / ROW * ROW
        } else {
            scrollBy((-scrollY * 24).toInt())
        }
        return true
    }

    private fun scrollBy(amount: Int) {
        scroll = (scroll + amount).coerceIn(0, maxScroll())
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val view = viewBottom() - viewTop()
        when (event.key()) {
            InputConstants.KEY_UP -> scrollBy(-LINE * 2)
            InputConstants.KEY_DOWN -> scrollBy(LINE * 2)
            InputConstants.KEY_PAGEUP -> scrollBy(-(view - 20))
            InputConstants.KEY_PAGEDOWN, InputConstants.KEY_SPACE -> scrollBy(view - 20)
            InputConstants.KEY_HOME -> scroll = 0
            InputConstants.KEY_END -> scroll = maxScroll()
            InputConstants.KEY_LEFT, InputConstants.KEY_RIGHT -> {
                val next = section + if (event.key() == InputConstants.KEY_LEFT) -1 else 1
                if (next in sections.indices) {
                    section = next
                    scroll = 0
                    listScroll = listScroll.coerceIn((section + 1) * ROW - (listBottom() - top), section * ROW).coerceAtLeast(0)
                    rebuildWidgets()
                }
            }
            InputConstants.KEY_TAB -> if (games.size > 1) {
                openGame(games[(games.indexOf(game) + 1).mod(games.size)])
                rebuildWidgets()
            }
            else -> return super.keyPressed(event)
        }
        return true
    }

    override fun onClose() {
        mc.openScreen(parent)
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private const val MARGIN = 8
        /** Where the sections and the page start, below the game tabs. */
        private const val TOP = 32

        /** How far down a second row of game tabs goes. */
        private const val TAB_ROW = 24
        private const val LINE = 10

        /** Height of one section in the list down the left. */
        private const val ROW = 14
        private const val BULLET_INDENT = 10
        private const val YELLOW_RGB = 0xFFDD55

        /** Opens the book on top of [parent], on the section about the table's rules when there is one. */
        fun open(parent: Screen?, table: ClientTable?, gameId: String) {
            net.minecraft.client.Minecraft.getInstance().openScreen(RulesScreen(parent, table?.pos, gameId))
        }
    }
}
