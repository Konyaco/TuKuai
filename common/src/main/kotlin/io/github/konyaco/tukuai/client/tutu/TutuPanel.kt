package io.github.konyaco.tukuai.client.tutu

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PanelText
import io.github.konyaco.tukuai.client.PlayingCardHand
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.tutu.Tutu
import io.github.konyaco.tukuai.engine.tutu.TutuAction
import io.github.konyaco.tukuai.engine.tutu.TutuCard
import io.github.konyaco.tukuai.engine.tutu.TutuCodecs
import io.github.konyaco.tukuai.engine.tutu.TutuColor
import io.github.konyaco.tukuai.engine.tutu.TutuEvent
import io.github.konyaco.tukuai.engine.tutu.TutuEventKind
import io.github.konyaco.tukuai.engine.tutu.TutuFace
import io.github.konyaco.tukuai.engine.tutu.TutuKind
import io.github.konyaco.tukuai.engine.tutu.TutuView
import io.github.konyaco.tukuai.table.Occupant
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import org.lwjgl.glfw.GLFW

/**
 * The Tutu part of the table screen, laid out as for UNO: your hand along the bottom, the
 * other players around the edges in the order they sit (the next seat number is on your
 * right), and the discard and draw piles in the middle, with the cactus and TNT news under
 * them. Click a card that fits to play it, in the hand or, in the immersive view, on the table.
 */
class TutuPanel(private val screen: PanelHost) : GamePanel {
    /** TNT or a cactus waiting for its colour to be picked. */
    private var choosing: TutuCard? = null
    private var lastStep = -1
    private var wasMyTurn = false

    private val width get() = screen.panelWidth
    private val height get() = screen.panelHeight
    private val font get() = Minecraft.getInstance().font

    private fun send(action: TutuAction) {
        choosing = null
        screen.send(TableOp.ACT, data = TutuCodecs.ACTION.encode(action))
    }

    private fun play(card: TutuCard, color: TutuColor? = null) = send(TutuAction.Play(card, color))

    // ---- Layout --------------------------------------------------------------------------------

    private fun handY() = height - CardArt.HEIGHT - 8

    private fun buttonY() = if (screen.inWorld) height - PlayingCardHand.BOTTOM_BUTTONS else handY() - 14 - 22

    override fun controlsY(): Int = buttonY()

    private fun handStep(n: Int): Int = if (n <= 1) 0 else minOf(22, (width - 120 - CardArt.WIDTH) / (n - 1))

    private fun handX(n: Int): Int = (width - (CardArt.WIDTH + handStep(n) * (n - 1))) / 2

    /** Your own hand, or for a watcher who may see every hand, that of seat 0 at the bottom. */
    private fun sortedHand(view: TutuView): List<TutuCard> =
        TutuUi.sorted(if (view.seat == null) view.revealedHands.getOrNull(0).orEmpty() else view.hand)

    /** Top of the discard pile; the draw pile sits to its left. */
    private fun centerY() = 74

    // ---- Buttons -------------------------------------------------------------------------------

    override fun rebuild(table: ClientTable) {
        val view = table.view as? TutuView ?: return
        val s = table.snapshot
        if (s.step != lastStep) {
            lastStep = s.step
            choosing = choosing?.takeIf { view.playable(it) }
        }
        if (!screen.interactive) return
        val myTurn = s.status == TableStatus.PLAYING && view.isMyTurn
        if (myTurn && !wasMyTurn) {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f))
        }
        wasMyTurn = myTurn

        if (s.status == TableStatus.FINISHED) screen.finishButtons(table, width / 2, resultTop() + resultHeight(view) - 26)
        if (myTurn) addTurnButtons(view)
    }

    private fun addTurnButtons(view: TutuView) {
        val y = buttonY()
        val wild = choosing
        val buttons = ArrayList<Triple<Component, Boolean, () -> Unit>>()
        if (wild != null) {
            for (color in TutuColor.entries) buttons += Triple(TutuUi.colorName(color), true) { play(wild, color) }
        } else if (view.drawn != null) {
            buttons += Triple(Component.translatable("tukuai.uno.keep"), true) { send(TutuAction.Pass) }
        } else {
            buttons += Triple(Component.translatable("tukuai.uno.draw"), true) { send(TutuAction.Draw) }
        }
        val w = 60
        val gap = 6
        var x = (width - (buttons.size * w + (buttons.size - 1) * gap)) / 2
        for ((label, active, action) in buttons) {
            screen.button(label, x, y, w, active) { action() }
            x += w + gap
        }
    }

    // ---- Input ---------------------------------------------------------------------------------

    override fun mouseClicked(table: ClientTable, x: Double, y: Double, button: Int): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? TutuView ?: return false
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (choosing == null) return false
            choosing = null
            screen.refresh()
            return true
        }
        pick(table, view, cardAt(view, x, y) ?: return false)
        return true
    }

    override fun clickTableCard(table: ClientTable, card: TableCard): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? TutuView ?: return false
        pick(table, view, view.hand.firstOrNull { it.id == card.key } ?: return false)
        return true
    }

    override fun isRaised(table: ClientTable, card: TableCard): Boolean {
        val view = table.view as? TutuView ?: return false
        return view.isMyTurn && view.hand.firstOrNull { it.id == card.key }?.let { raised(view, it) } == true
    }

    /** Plays [card], or for TNT and cacti asks for the colour first; a card that does not fit does nothing. */
    private fun pick(table: ClientTable, view: TutuView, card: TutuCard) {
        if (!view.playable(card) || table.snapshot.status != TableStatus.PLAYING) return
        if (card.face.kind.namesColor) {
            choosing = card
            screen.refresh()
        } else {
            play(card)
        }
    }

    private fun cardAt(view: TutuView, mx: Double, my: Double): TutuCard? {
        if (screen.inWorld) return null
        val hand = sortedHand(view)
        val n = hand.size
        val step = handStep(n)
        val x0 = handX(n)
        for (i in n - 1 downTo 0) {
            val card = hand[i]
            val x = x0 + i * step
            val y = handY() - if (raised(view, card)) RAISE else 0
            if (mx >= x && mx < x + CardArt.WIDTH && my >= y && my < y + CardArt.HEIGHT) return card
        }
        return null
    }

    private fun raised(view: TutuView, card: TutuCard) = card == choosing || (screen.interactive && view.playable(card))

    override fun keyPressed(table: ClientTable, key: Int): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? TutuView ?: return false
        if (!view.isMyTurn || table.snapshot.status != TableStatus.PLAYING) return false
        return when (key) {
            GLFW.GLFW_KEY_D -> {
                if (view.drawn == null && choosing == null) send(TutuAction.Draw)
                true
            }
            GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_ENTER -> {
                if (view.drawn != null && choosing == null) send(TutuAction.Pass)
                true
            }
            else -> false
        }
    }

    // ---- Drawing -------------------------------------------------------------------------------

    override fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int) {
        val view = table.view as? TutuView ?: return
        val s = table.snapshot
        val me = s.you ?: 0
        val recent = recentActions(view)
        renderTitle(g, table)
        for (seat in 0 until view.players) {
            if (seat == me) continue
            renderOpponent(g, table, view, seat, recent[seat], placeOf(view, me, seat))
        }
        if (!view.finished) renderPiles(g, view, mouseX, mouseY)
        renderSelf(g, table, view, me, recent[me])
        renderHand(g, view, mouseX, mouseY)
        if (view.finished) renderResult(g, table, view)
    }

    private fun renderTitle(g: GuiGraphicsExtractor, table: ClientTable) = PanelText.title(g, table, screen.interactive, width)

    private enum class Place { LEFT, TOP, RIGHT }

    /** The next seat number sits on your right, then across, then on your left. */
    private fun placeOf(view: TutuView, me: Seat, seat: Seat): Place {
        val rel = (seat - me).mod(view.players)
        return when (view.players) {
            2 -> Place.TOP
            3 -> if (rel == 1) Place.RIGHT else Place.LEFT
            else -> when (rel) {
                1 -> Place.RIGHT
                2 -> Place.TOP
                else -> Place.LEFT
            }
        }
    }

    private fun seatLabel(table: ClientTable, seat: Seat): Component {
        val info = table.snapshot.seats.getOrNull(seat)
        val name = TableScreen.seatName(info?.occupant)
        return when {
            info?.occupant is Occupant.Human && !info.present -> Component.translatable("tukuai.table.name_away", name)
            info?.auto == true -> Component.translatable("tukuai.table.name_auto", name)
            else -> name
        }
    }

    private fun renderOpponent(g: GuiGraphicsExtractor, table: ClientTable, view: TutuView, seat: Seat, recent: TutuEvent?, place: Place) {
        val boxW = 96
        val x = when (place) {
            Place.LEFT -> 8
            Place.TOP -> width / 2 - boxW / 2
            Place.RIGHT -> width - 8 - boxW
        }
        val y = if (place == Place.TOP) 30 else 74
        val acting = view.turn == seat && table.snapshot.status == TableStatus.PLAYING
        g.fill(x - 2, y - 2, x + boxW + 2, y + 40, if (acting) 0x90806020.toInt() else 0x70000000)
        g.text(font, font.plainSubstrByWidth(seatLabel(table, seat).string, boxW), x, y + 2, TableScreen.WHITE, true)
        // Who plays after the current player, so the tag never lands on the one already playing.
        if (!view.finished && seat == view.next(view.turn)) {
            val next = Component.translatable("tukuai.uno.next")
            g.text(font, next, x + boxW - font.width(next), y + 2, TableScreen.GRAY, true)
        }
        CardArt.draw(g, CardArt.back(), x, y + 14, 0.5f)
        g.text(font, Component.literal("× ${view.handCounts[seat]}"), x + 20, y + 22, TableScreen.YELLOW, true)
        if (acting) renderTimer(g, table, x + boxW - 4, y + 30, alignRight = true)

        // Their whole hand once it is shown: beside the box across the table, below the others.
        val revealed = view.revealedHands.getOrNull(seat)
        if (revealed != null) {
            val cards = TutuUi.sorted(revealed)
            val small = CardArt.WIDTH / 2
            val step = if (cards.size > 1) minOf(7, (boxW - small) / (cards.size - 1)) else 0
            val total = small + step * (cards.size - 1)
            val left = when (place) {
                Place.LEFT -> x
                Place.TOP -> x + boxW + 8
                Place.RIGHT -> x + boxW - total
            }
            val top = if (place == Place.TOP) y + 4 else y + 44
            cards.forEachIndexed { i, card -> CardArt.draw(g, CardArt.tutu(card), left + i * step, top, 0.5f) }
            if (view.finished || recent == null) return
            // A watcher sees hands while play goes on; across the table the hand takes the right of the box.
            if (place == Place.TOP) {
                renderEvent(g, view, recent, x - 8, y + 4, Align.RIGHT)
                return
            }
        }
        if (recent == null) return
        when (place) {
            Place.LEFT -> renderEvent(g, view, recent, x + boxW + 8, y + 4, Align.LEFT)
            Place.RIGHT -> renderEvent(g, view, recent, x - 8, y + 4, Align.RIGHT)
            Place.TOP -> renderEvent(g, view, recent, x + boxW + 8, y + 4, Align.LEFT)
        }
    }

    /**
     * The draw pile with how many cards are left, and the discard pile with the colour to
     * follow; under them the cactus to watch out for and the TNT burning.
     */
    private fun renderPiles(g: GuiGraphicsExtractor, view: TutuView, mouseX: Int, mouseY: Int) {
        val cy = centerY()
        val scale = PILE_SCALE
        val w = (CardArt.WIDTH * scale).toInt()
        val h = (CardArt.HEIGHT * scale).toInt()
        val discardX = width / 2 + 6
        val pileX = width / 2 - 6 - w
        if (view.drawCount > 0) CardArt.draw(g, CardArt.back(), pileX, cy, scale)
        g.centeredText(font, Component.literal("${view.drawCount}"), pileX + w / 2, cy + h + 5, TableScreen.GRAY)

        // The colour to follow, as a frame around the top card.
        val color = 0xFF000000.toInt() or TutuUi.COLORS.getValue(view.color)
        g.fill(discardX - 3, cy - 3, discardX + w + 3, cy + h + 3, color)
        val named = view.log.lastOrNull { it.kind == TutuEventKind.PLAY }?.color
        CardArt.draw(g, CardArt.tutu(view.top, named), discardX, cy, scale)
        g.text(font, TutuUi.stateLine(view), discardX - 3, cy + h + 5, TableScreen.WHITE, true)

        // The next cactus pokes out of the draw pile, with how many draws are left before it;
        // burning TNT shows after the colour to follow. Both keep clear of the news beside the
        // players on the left and right.
        val cactusIn = view.cactusIn
        if (cactusIn >= 0) {
            val x = pileX + 3
            CardArt.draw(g, CardArt.tutu(TutuFace(null, TutuKind.CACTUS)), x, cy + 6, 0.5f)
            val text = Component.literal(if (cactusIn == 0) "!" else cactusIn.toString())
            g.centeredText(font, text, x + 8, cy + 31, if (cactusIn == 0) TableScreen.RED else TableScreen.GREEN)
            if (mouseX in x until x + 16 && mouseY in cy + 6 until cy + 40) {
                val tip = if (cactusIn == 0) Component.translatable("tukuai.tutu.cactus_next") else Component.translatable("tukuai.tutu.cactus_in", cactusIn)
                g.setTooltipForNextFrame(font, tip, mouseX, mouseY)
            }
        }
        if (view.bombs > 0) {
            val x = discardX - 3 + font.width(TutuUi.stateLine(view)) + 6
            val y = cy + h + 3
            CardArt.drawTnt(g, x, y, 11)
            g.text(font, Component.literal("×${view.bombs}"), x + 13, y + 2, TableScreen.RED, true)
            if (mouseX in x until x + 30 && mouseY in y until y + 11) {
                g.setTooltipForNextFrame(font, Component.translatable("tukuai.tutu.tnt_burning", view.bombs), mouseX, mouseY)
            }
        }
    }

    private fun renderSelf(g: GuiGraphicsExtractor, table: ClientTable, view: TutuView, me: Seat, recent: TutuEvent?) {
        val y = buttonY() + 4
        val x = 8
        // A watcher who sees every hand watches from seat 0; one who does not just watches.
        val seen = table.snapshot.you != null || view.revealedHands.isNotEmpty()
        val label = if (!seen) Component.translatable("tukuai.table.watching") else seatLabel(table, me)
        g.text(font, label, x, y, TableScreen.WHITE, true)
        // On the far side of the buttons, clear of the piles. Your own plays already show on the discard pile.
        if (recent != null && recent.kind != TutuEventKind.PLAY && !view.finished) {
            renderEvent(g, view, recent, width - 8, y - 10, Align.RIGHT)
        }
        if (view.turn == me && table.snapshot.status == TableStatus.PLAYING && seen) {
            renderTimer(g, table, x, y + 12, alignRight = false)
        }
    }

    private fun renderTimer(g: GuiGraphicsExtractor, table: ClientTable, x: Int, y: Int, alignRight: Boolean) {
        val ticks = table.timerTicks()
        if (ticks < 0) return
        val seconds = (ticks + 19) / 20
        val text = Component.translatable("tukuai.table.seconds", seconds)
        val color = if (seconds <= 5) TableScreen.RED else TableScreen.YELLOW
        g.text(font, text, if (alignRight) x - font.width(text) else x, y, color, true)
    }

    private fun renderHand(g: GuiGraphicsExtractor, view: TutuView, mouseX: Int, mouseY: Int) {
        val hand = sortedHand(view)
        if (hand.isEmpty() || screen.inWorld) return
        val step = handStep(hand.size)
        val x0 = handX(hand.size)
        val hovered = cardAt(view, mouseX.toDouble(), mouseY.toDouble())
        val dim = screen.interactive && view.isMyTurn
        hand.forEachIndexed { i, card ->
            val y = handY() - if (raised(view, card)) RAISE else 0
            val x = x0 + i * step
            CardArt.draw(g, CardArt.tutu(card), x, y)
            val right = x + if (i == hand.size - 1) CardArt.WIDTH else step
            if (dim && !view.playable(card)) g.fill(x, y, right, y + CardArt.HEIGHT, 0x60000000)
            if (card == hovered) g.fill(x, y, right, y + CardArt.HEIGHT, 0x30FFFFFF)
        }
    }

    private enum class Align { LEFT, CENTER, RIGHT }

    private fun renderEvent(g: GuiGraphicsExtractor, view: TutuView, event: TutuEvent, x: Int, y: Int, align: Align) {
        if (event.kind == TutuEventKind.PLAY) {
            val w = (CardArt.WIDTH * 0.75f).toInt()
            val left = when (align) {
                Align.LEFT -> x
                Align.CENTER -> x - w / 2
                Align.RIGHT -> x - w
            }
            CardArt.draw(g, CardArt.tutu(event.face ?: event.card!!.face, event.color), left, y, 0.75f)
            // An egg shows what hatched, with the egg itself tucked into its corner.
            if (event.card!!.face.kind == TutuKind.EGG) CardArt.draw(g, CardArt.tutu(event.card!!), left + w - 10, y + 20, 0.4f)
            return
        }
        val text = eventText(event) ?: return
        val w = font.width(text)
        val tx = when (align) {
            Align.LEFT -> x
            Align.CENTER -> x - w / 2
            Align.RIGHT -> x - w
        }
        g.text(font, text, tx, y + 10, TableScreen.YELLOW, true)
    }

    /** Below the player across the table, between the players on the left and right. */
    private fun resultTop() = 72

    private fun resultHeight(view: TutuView) = 36 + view.players * 11 + 26

    private fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: TutuView) {
        val settlement = view.settlement ?: return
        val w = RESULT_WIDTH
        val x = width / 2 - w / 2
        val top = resultTop()
        g.fill(x, top, x + w, top + resultHeight(view), 0xE0101010.toInt())
        val me = table.snapshot.you
        val color = when {
            me == null -> TableScreen.WHITE
            me in settlement.winners -> TableScreen.GREEN
            else -> TableScreen.RED
        }
        g.centeredText(font, TutuUi.wonLine(table, settlement.winners), width / 2, top + 6, color)
        val detail = if (settlement.pileEmpty) "tukuai.tutu.result_pile" else "tukuai.tutu.result_detail"
        g.centeredText(font, Component.translatable(detail), width / 2, top + 18, TableScreen.GRAY)
        for (seat in 0 until view.players) {
            val y = top + 32 + seat * 11
            // Cards left sit right-aligned before the score column, and the name gives way to them.
            val winner = seat in settlement.winners
            val left = Component.translatable("tukuai.uno.cards_left", view.handCounts[seat], settlement.handPoints[seat])
            val leftX = x + w - SCORE_COLUMN - font.width(left)
            val nameRoom = (if (!winner) leftX - 6 else x + w - SCORE_COLUMN) - (x + 8)
            val name = font.plainSubstrByWidth(seatLabel(table, seat).string, nameRoom)
            g.text(font, Component.literal(name), x + 8, y, if (seat == me) TableScreen.YELLOW else TableScreen.WHITE, false)
            if (!winner) g.text(font, left, leftX, y, TableScreen.GRAY, false)
            val points = settlement.scores[seat]
            val text = Component.literal(if (points > 0) "+$points" else points.toString())
            g.text(font, text, x + w - 8 - font.width(text), y, if (points >= 0) TableScreen.GREEN else TableScreen.RED, false)
        }
    }

    companion object {
        private const val RAISE = 8
        private const val PILE_SCALE = 1.25f
        private const val RESULT_WIDTH = 212
        /** Room for the score, "-129" at most in practice, plus a gap. */
        private const val SCORE_COLUMN = 40

        fun eventText(event: TutuEvent): Component? = when (event.kind) {
            TutuEventKind.DRAW -> Component.translatable("tukuai.uno.drew", event.count)
            TutuEventKind.PASS -> Component.translatable("tukuai.uno.kept")
            TutuEventKind.SKIPPED -> Component.translatable("tukuai.uno.skipped")
            TutuEventKind.CACTUS -> Component.translatable("tukuai.tutu.pricked", event.count)
            TutuEventKind.BOOM -> Component.translatable("tukuai.tutu.boom", event.count * Tutu.TNT_CARDS)
            TutuEventKind.PLAY -> null
        }

        private val SHOWN = setOf(TutuEventKind.PLAY, TutuEventKind.DRAW, TutuEventKind.PASS, TutuEventKind.SKIPPED, TutuEventKind.CACTUS, TutuEventKind.BOOM)

        /**
         * Each seat's latest news since its last turn; the seat to act shows nothing, unless TNT
         * just blew up in their face or they were pricked by a cactus.
         */
        fun recentActions(view: TutuView): Map<Seat, TutuEvent> {
            val out = HashMap<Seat, TutuEvent>()
            val log = view.log
            for (i in log.indices.reversed()) {
                if (out.size == view.players) break
                val event = log[i]
                if (event.kind !in SHOWN) continue
                // Made to draw and then skipped: the drawing is the news, and what made them draw is bigger news still.
                var shown = i
                if (event.kind == TutuEventKind.SKIPPED && log.getOrNull(i - 1)?.let { it.kind == TutuEventKind.DRAW && it.seat == event.seat } == true) shown--
                val cause = log.getOrNull(shown - 1)?.takeIf {
                    log[shown].kind == TutuEventKind.DRAW && it.seat == event.seat && (it.kind == TutuEventKind.BOOM || it.kind == TutuEventKind.CACTUS)
                }
                out.putIfAbsent(event.seat, cause ?: log[shown])
            }
            if (!view.finished) {
                val turn = out[view.turn]
                if (turn == null || (turn.kind != TutuEventKind.BOOM && turn.kind != TutuEventKind.CACTUS)) out.remove(view.turn)
            }
            return out
        }
    }
}
