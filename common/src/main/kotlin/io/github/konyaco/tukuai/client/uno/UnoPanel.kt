package io.github.konyaco.tukuai.client.uno

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.uno.UnoAction
import io.github.konyaco.tukuai.engine.uno.UnoCard
import io.github.konyaco.tukuai.engine.uno.UnoCodecs
import io.github.konyaco.tukuai.engine.uno.UnoColor
import io.github.konyaco.tukuai.engine.uno.UnoEvent
import io.github.konyaco.tukuai.engine.uno.UnoEventKind
import io.github.konyaco.tukuai.engine.uno.UnoView
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
 * The UNO part of the table screen: your hand along the bottom, the other players around
 * the edges in the order they sit (the next seat number is on your right), and the discard
 * and draw piles in the middle. Click a card that fits to play it.
 */
class UnoPanel(private val screen: PanelHost) : GamePanel {
    /** A wild card waiting for its colour to be picked. */
    private var choosing: UnoCard? = null
    private var callUno = false
    private var lastStep = -1
    private var wasMyTurn = false

    private val width get() = screen.panelWidth
    private val height get() = screen.panelHeight
    private val font get() = Minecraft.getInstance().font

    private fun send(action: UnoAction) {
        choosing = null
        callUno = false
        screen.send(TableOp.ACT, data = UnoCodecs.ACTION.encode(action))
    }

    private fun play(view: UnoView, card: UnoCard, color: UnoColor? = null) =
        send(UnoAction.Play(card, color, uno = callUno && view.hand.size == 2))

    // ---- Layout --------------------------------------------------------------------------------

    private fun handY() = height - CardArt.HEIGHT - 8

    private fun buttonY() = handY() - 14 - 22

    override fun controlsY(): Int = buttonY()

    private fun handStep(n: Int): Int = if (n <= 1) 0 else minOf(22, (width - 120 - CardArt.WIDTH) / (n - 1))

    private fun handX(n: Int): Int = (width - (CardArt.WIDTH + handStep(n) * (n - 1))) / 2

    private fun sortedHand(view: UnoView): List<UnoCard> = UnoUi.sorted(view.hand)

    /** Top of the discard pile; the draw pile sits to its left. */
    private fun centerY() = 74

    // ---- Buttons -------------------------------------------------------------------------------

    override fun rebuild(table: ClientTable) {
        val view = table.view as? UnoView ?: return
        val s = table.snapshot
        if (s.step != lastStep) {
            lastStep = s.step
            choosing = choosing?.takeIf { view.playable(it) }
            if (!view.isMyTurn) callUno = false
        }
        if (!screen.interactive) return
        val myTurn = s.status == TableStatus.PLAYING && view.isMyTurn
        if (myTurn && !wasMyTurn) {
            Minecraft.getInstance().soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f))
        }
        wasMyTurn = myTurn

        val me = s.you
        if (s.status == TableStatus.PLAYING && me != null) {
            val auto = s.seats.getOrNull(me)?.auto == true
            screen.button(Component.translatable(if (auto) "tukuai.table.auto.off" else "tukuai.table.auto.on"), width - 74, 48, 70) {
                screen.send(TableOp.AUTO, if (auto) 0 else 1)
            }
        }
        if (s.status == TableStatus.FINISHED) {
            val ready = me != null && s.seats.all { it.occupant != null }
            screen.button(Component.translatable("tukuai.table.again"), width / 2 - 100, resultTop() + resultHeight(view) - 26, 98, ready) {
                screen.send(TableOp.START)
            }
            screen.button(Component.translatable("tukuai.table.replay"), width / 2 + 2, resultTop() + resultHeight(view) - 26, 98) {
                screen.send(TableOp.REPLAY)
            }
        }
        if (myTurn) addTurnButtons(view)
    }

    private fun addTurnButtons(view: UnoView) {
        val y = buttonY()
        val wild = choosing
        val buttons = ArrayList<Triple<Component, Boolean, () -> Unit>>()
        if (wild != null) {
            for (color in UnoColor.entries) buttons += Triple(UnoUi.colorName(color), true) { play(view, wild, color) }
        } else {
            val drawn = view.drawn
            if (drawn != null) {
                buttons += Triple(Component.translatable("tukuai.uno.keep"), true) { send(UnoAction.Pass) }
            } else {
                val label = if (view.pendingDraw > 0) Component.translatable("tukuai.uno.draw_n", view.pendingDraw) else Component.translatable("tukuai.uno.draw")
                buttons += Triple(label, true) { send(UnoAction.Draw) }
            }
            if (view.hand.size == 2) {
                val text = Component.translatable("tukuai.uno.call")
                buttons += Triple(if (callUno) text.copy().withColor(0xFFDD55) else text, true) {
                    callUno = !callUno
                    screen.refresh()
                }
            }
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
        val view = table.view as? UnoView ?: return false
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (choosing == null) return false
            choosing = null
            screen.refresh()
            return true
        }
        val card = cardAt(view, x, y) ?: return false
        if (!view.playable(card) || table.snapshot.status != TableStatus.PLAYING) return true
        if (card.face.isWild) {
            choosing = card
            screen.refresh()
        } else {
            play(view, card)
        }
        return true
    }

    private fun cardAt(view: UnoView, mx: Double, my: Double): UnoCard? {
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

    private fun raised(view: UnoView, card: UnoCard) = card == choosing || (screen.interactive && view.playable(card))

    override fun keyPressed(table: ClientTable, key: Int): Boolean {
        if (!screen.interactive) return false
        val view = table.view as? UnoView ?: return false
        if (!view.isMyTurn || table.snapshot.status != TableStatus.PLAYING) return false
        return when (key) {
            GLFW.GLFW_KEY_D -> {
                if (view.drawn == null && choosing == null) send(UnoAction.Draw)
                true
            }
            GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_ENTER -> {
                if (view.drawn != null && choosing == null) send(UnoAction.Pass)
                true
            }
            GLFW.GLFW_KEY_U -> {
                if (view.hand.size == 2) {
                    callUno = !callUno
                    screen.refresh()
                }
                true
            }
            else -> false
        }
    }

    // ---- Drawing -------------------------------------------------------------------------------

    override fun render(g: GuiGraphicsExtractor, table: ClientTable, mouseX: Int, mouseY: Int) {
        val view = table.view as? UnoView ?: return
        val s = table.snapshot
        val me = s.you ?: 0
        val recent = recentActions(view)
        renderTitle(g, table)
        for (seat in 0 until view.players) {
            if (seat == me) continue
            renderOpponent(g, table, view, seat, recent[seat], placeOf(view, me, seat))
        }
        if (!view.finished) renderPiles(g, view)
        renderSelf(g, table, view, me, recent[me])
        renderHand(g, view, mouseX, mouseY)
        if (view.finished) renderResult(g, table, view)
    }

    private fun renderTitle(g: GuiGraphicsExtractor, table: ClientTable) {
        val s = table.snapshot
        g.text(font, TableScreen.presetName(s.gameId, s.presetId), 6, 6, TableScreen.GRAY, true)
        if (!screen.interactive) return
        val round = s.rounds + if (s.status == TableStatus.PLAYING) 1 else 0
        g.text(font, Component.translatable("tukuai.table.rounds", round), 6, 17, TableScreen.GRAY, true)
    }

    private enum class Place { LEFT, TOP, RIGHT }

    /** The next seat number sits on your right, then across, then on your left. */
    private fun placeOf(view: UnoView, me: Seat, seat: Seat): Place {
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

    private fun renderOpponent(g: GuiGraphicsExtractor, table: ClientTable, view: UnoView, seat: Seat, recent: UnoEvent?, place: Place) {
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
        if (seat in view.unoCalled) g.text(font, Component.translatable("tukuai.uno.call"), x + 56, y + 22, TableScreen.RED, true)
        if (acting) renderTimer(g, table, x + boxW - 4, y + 30, alignRight = true)

        // Their whole hand once it is shown: beside the box across the table, below the others.
        val revealed = view.revealedHands.getOrNull(seat)
        if (revealed != null) {
            val cards = UnoUi.sorted(revealed)
            val small = CardArt.WIDTH / 2
            val step = if (cards.size > 1) minOf(7, (boxW - small) / (cards.size - 1)) else 0
            val total = small + step * (cards.size - 1)
            val left = when (place) {
                Place.LEFT -> x
                Place.TOP -> x + boxW + 8
                Place.RIGHT -> x + boxW - total
            }
            val top = if (place == Place.TOP) y + 4 else y + 44
            cards.forEachIndexed { i, card -> CardArt.draw(g, CardArt.uno(card), left + i * step, top, 0.5f) }
            return
        }
        if (recent == null) return
        when (place) {
            Place.LEFT -> renderEvent(g, view, recent, x + boxW + 8, y + 4, Align.LEFT)
            Place.RIGHT -> renderEvent(g, view, recent, x - 8, y + 4, Align.RIGHT)
            Place.TOP -> renderEvent(g, view, recent, x + boxW + 8, y + 4, Align.LEFT)
        }
    }

    /** The draw pile with how many cards are left, and the discard pile with the colour to follow. */
    private fun renderPiles(g: GuiGraphicsExtractor, view: UnoView) {
        val cy = centerY()
        val scale = PILE_SCALE
        val w = (CardArt.WIDTH * scale).toInt()
        val h = (CardArt.HEIGHT * scale).toInt()
        val discardX = width / 2 + 6
        val pileX = width / 2 - 6 - w
        if (view.drawCount > 0) CardArt.draw(g, CardArt.back(), pileX, cy, scale)
        g.centeredText(font, Component.literal("${view.drawCount}"), pileX + w / 2, cy + h + 5, TableScreen.GRAY)

        // The colour to follow, as a frame around the top card.
        val color = 0xFF000000.toInt() or UnoUi.COLORS.getValue(view.color)
        g.fill(discardX - 3, cy - 3, discardX + w + 3, cy + h + 3, color)
        val named = view.log.lastOrNull { it.kind == UnoEventKind.PLAY }?.takeIf { it.card == view.top }?.color
        CardArt.draw(g, CardArt.uno(view.top, named), discardX, cy, scale)
        g.text(font, UnoUi.stateLine(view), discardX - 3, cy + h + 5, TableScreen.WHITE, true)
    }

    private fun renderSelf(g: GuiGraphicsExtractor, table: ClientTable, view: UnoView, me: Seat, recent: UnoEvent?) {
        val y = buttonY() + 4
        val x = 8
        val label = if (table.snapshot.you == null) Component.translatable("tukuai.table.watching") else seatLabel(table, me)
        g.text(font, label, x, y, TableScreen.WHITE, true)
        if (me in view.unoCalled && table.snapshot.you != null) {
            g.text(font, Component.translatable("tukuai.uno.call"), x + font.width(label) + 6, y, TableScreen.RED, true)
        }
        // On the far side of the buttons, clear of the piles. Your own plays already show on the discard pile.
        if (recent != null && recent.kind != UnoEventKind.PLAY && !view.finished) {
            renderEvent(g, view, recent, width - 8, y - 10, Align.RIGHT)
        }
        if (view.turn == me && table.snapshot.status == TableStatus.PLAYING && table.snapshot.you != null) {
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

    private fun renderHand(g: GuiGraphicsExtractor, view: UnoView, mouseX: Int, mouseY: Int) {
        val hand = sortedHand(view)
        if (hand.isEmpty()) return
        val step = handStep(hand.size)
        val x0 = handX(hand.size)
        val hovered = cardAt(view, mouseX.toDouble(), mouseY.toDouble())
        val dim = screen.interactive && view.isMyTurn
        hand.forEachIndexed { i, card ->
            val y = handY() - if (raised(view, card)) RAISE else 0
            val x = x0 + i * step
            CardArt.draw(g, CardArt.uno(card), x, y)
            val right = x + if (i == hand.size - 1) CardArt.WIDTH else step
            if (dim && !view.playable(card)) g.fill(x, y, right, y + CardArt.HEIGHT, 0x60000000)
            if (card == hovered) g.fill(x, y, right, y + CardArt.HEIGHT, 0x30FFFFFF)
        }
    }

    private enum class Align { LEFT, CENTER, RIGHT }

    private fun renderEvent(g: GuiGraphicsExtractor, view: UnoView, event: UnoEvent, x: Int, y: Int, align: Align) {
        if (event.kind == UnoEventKind.PLAY) {
            val w = (CardArt.WIDTH * 0.75f).toInt()
            val left = when (align) {
                Align.LEFT -> x
                Align.CENTER -> x - w / 2
                Align.RIGHT -> x - w
            }
            CardArt.draw(g, CardArt.uno(event.card!!, event.color), left, y, 0.75f)
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

    private fun resultHeight(view: UnoView) = 36 + view.players * 11 + 26

    private fun renderResult(g: GuiGraphicsExtractor, table: ClientTable, view: UnoView) {
        val settlement = view.settlement ?: return
        val w = RESULT_WIDTH
        val x = width / 2 - w / 2
        val top = resultTop()
        g.fill(x, top, x + w, top + resultHeight(view), 0xE0101010.toInt())
        val me = table.snapshot.you
        val winner = TableScreen.seatName(table.snapshot.seats.getOrNull(settlement.winner)?.occupant)
        val color = when {
            me == null -> TableScreen.WHITE
            me == settlement.winner -> TableScreen.GREEN
            else -> TableScreen.RED
        }
        g.centeredText(font, Component.translatable("tukuai.uno.won", winner), width / 2, top + 6, color)
        g.centeredText(font, Component.translatable("tukuai.uno.result_detail"), width / 2, top + 18, TableScreen.GRAY)
        for (seat in 0 until view.players) {
            val y = top + 32 + seat * 11
            // Cards left sit right-aligned before the score column, and the name gives way to them.
            val left = Component.translatable("tukuai.uno.cards_left", view.handCounts[seat], settlement.handPoints[seat])
            val leftX = x + w - SCORE_COLUMN - font.width(left)
            val nameRoom = (if (seat != settlement.winner) leftX - 6 else x + w - SCORE_COLUMN) - (x + 8)
            val name = font.plainSubstrByWidth(seatLabel(table, seat).string, nameRoom)
            g.text(font, Component.literal(name), x + 8, y, if (seat == me) TableScreen.YELLOW else TableScreen.WHITE, false)
            if (seat != settlement.winner) g.text(font, left, leftX, y, TableScreen.GRAY, false)
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

        fun eventText(event: UnoEvent): Component? = when (event.kind) {
            UnoEventKind.DRAW -> Component.translatable("tukuai.uno.drew", event.count)
            UnoEventKind.PASS -> Component.translatable("tukuai.uno.kept")
            UnoEventKind.SKIPPED -> Component.translatable("tukuai.uno.skipped")
            UnoEventKind.PENALTY -> Component.translatable("tukuai.uno.penalty", event.count)
            UnoEventKind.UNO -> Component.translatable("tukuai.uno.call")
            UnoEventKind.PLAY, UnoEventKind.RESHUFFLE -> null
        }

        private val SHOWN = setOf(UnoEventKind.PLAY, UnoEventKind.DRAW, UnoEventKind.PASS, UnoEventKind.SKIPPED, UnoEventKind.PENALTY)

        /** Each seat's latest move since its last turn; the seat to act shows nothing. */
        fun recentActions(view: UnoView): Map<Seat, UnoEvent> {
            val out = HashMap<Seat, UnoEvent>()
            val log = view.log
            for (i in log.indices.reversed()) {
                if (out.size == view.players) break
                val event = log[i]
                if (event.kind !in SHOWN) continue
                // Made to draw and then skipped: the drawing is the news.
                val before = log.getOrNull(i - 1)
                val shown = if (event.kind == UnoEventKind.SKIPPED && before?.kind == UnoEventKind.DRAW && before.seat == event.seat) before else event
                out.putIfAbsent(event.seat, shown)
            }
            if (!view.finished) out.remove(view.turn)
            return out
        }
    }
}
