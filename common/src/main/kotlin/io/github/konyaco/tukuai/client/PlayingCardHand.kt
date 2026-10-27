package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.table.Occupant
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Your own hand of playing cards along the bottom of the screen, where cards are picked
 * before playing them, as in Dou Dizhu and Pao De Kuai. In the immersive view the hand is
 * left out and cards are picked on the table instead. It also keeps the hints: pressing
 * hint again picks the next play.
 */
class PlayingCardHand(private val host: PanelHost) {
    val selected = LinkedHashSet<PlayingCard>()
    private var hints: List<List<PlayingCard>> = emptyList()
    private var hintIndex = 0
    private var lastStep = -1

    /**
     * While the left button is held after picking or dropping a card: true if that card was
     * picked, so every card swept over is picked too, false if they are all dropped.
     */
    private var sweep: Boolean? = null
    private var sweepX = 0.0

    fun y() = host.panelHeight - CardArt.HEIGHT - 8

    /** The row above the hand where the turn buttons go; near the bottom when the hand is on the table. */
    fun buttonY() = if (host.inWorld) host.panelHeight - BOTTOM_BUTTONS else y() - 14 - 22

    private fun step(n: Int): Int = if (n <= 1) 0 else minOf(18, (host.panelWidth - 120 - CardArt.WIDTH) / (n - 1))

    private fun x(n: Int): Int = (host.panelWidth - (CardArt.WIDTH + step(n) * (n - 1))) / 2

    /** Called on every table update: a new move clears the hints and drops cards no longer held. */
    fun sync(step: Int, hand: Collection<PlayingCard>) {
        if (step == lastStep) return
        lastStep = step
        selected.retainAll(hand.toSet())
        hints = emptyList()
        hintIndex = 0
    }

    /**
     * Selects the next hint, working the list out with [compute] the first time; the list
     * should start with what the computer would play. Returns false when there is none.
     */
    fun hint(compute: () -> List<List<PlayingCard>>): Boolean {
        if (hints.isEmpty()) {
            hints = compute().distinctBy { it.toSet() }
            hintIndex = 0
        }
        if (hints.isEmpty()) return false
        selected.clear()
        selected += hints[hintIndex % hints.size]
        hintIndex++
        host.refresh()
        return true
    }

    /**
     * Left-click picks or drops a card, and dragging on from there sweeps the cards passed
     * over the same way. Right-click drops them all.
     */
    fun click(hand: List<PlayingCard>, x: Double, y: Double, button: Int): Boolean {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (selected.isEmpty()) return false
            selected.clear()
            host.refresh()
            return true
        }
        val card = cardAt(hand, x, y) ?: return false
        toggle(card)
        sweep = card in selected
        sweepX = x
        return true
    }

    /**
     * The mouse moved with the left button held: every card whose column it crossed since
     * the last move goes the way of the first one. Only the left and right matter, so the
     * sweep may wander up and down over raised and lowered cards.
     */
    fun drag(hand: List<PlayingCard>, x: Double): Boolean {
        val picking = sweep ?: return false
        if (host.inWorld) return false
        val from = minOf(sweepX, x)
        val to = maxOf(sweepX, x)
        sweepX = x
        val n = hand.size
        val step = step(n)
        val x0 = x(n)
        var changed = false
        hand.forEachIndexed { i, card ->
            // The part of the card that shows: all of the last one, the strip left of the next for the others.
            val left = x0 + i * step
            val right = left + if (i == n - 1 || step == 0) CardArt.WIDTH else step
            if (right <= from || left > to) return@forEachIndexed
            changed = changed or if (picking) selected.add(card) else selected.remove(card)
        }
        if (changed) host.refresh()
        return true
    }

    /** The left button went up: the sweep is over. */
    fun release() {
        sweep = null
    }

    /** A click on one of the player's cards on the table: picks or drops the card of [hand] it shows, and starts a sweep. */
    fun clickTableCard(hand: Collection<PlayingCard>, card: TableCard): Boolean {
        val picked = hand.firstOrNull { CardArt.face(it) == card.sprite } ?: return false
        toggle(picked)
        sweep = picked in selected
        return true
    }

    /** The pointer swept over [card] on the table with the button held: it goes the way of the first card. */
    fun dragTableCard(hand: Collection<PlayingCard>, card: TableCard) {
        val picking = sweep ?: return
        val swept = hand.firstOrNull { CardArt.face(it) == card.sprite } ?: return
        if (if (picking) selected.add(swept) else selected.remove(swept)) host.refresh()
    }

    /** Whether [card] on the table shows a picked card. */
    fun isRaised(card: TableCard): Boolean = selected.any { CardArt.face(it) == card.sprite }

    private fun toggle(card: PlayingCard) {
        if (!selected.remove(card)) selected += card
        host.refresh()
    }

    private fun cardAt(hand: List<PlayingCard>, mx: Double, my: Double): PlayingCard? {
        if (host.inWorld) return null
        val n = hand.size
        val step = step(n)
        val x0 = x(n)
        for (i in n - 1 downTo 0) {
            val card = hand[i]
            val x = x0 + i * step
            val y = y() - if (card in selected) RAISE else 0
            if (mx >= x && mx < x + CardArt.WIDTH && my >= y && my < y + CardArt.HEIGHT) return card
        }
        return null
    }

    fun render(g: GuiGraphicsExtractor, hand: List<PlayingCard>, mouseX: Int, mouseY: Int) {
        if (hand.isEmpty() || host.inWorld) return
        val step = step(hand.size)
        val x0 = x(hand.size)
        val hovered = cardAt(hand, mouseX.toDouble(), mouseY.toDouble())
        hand.forEachIndexed { i, card ->
            val y = y() - if (card in selected) RAISE else 0
            val x = x0 + i * step
            CardArt.drawCard(g, card, x, y)
            if (card == hovered) g.fill(x, y, x + if (i == hand.size - 1) CardArt.WIDTH else step, y + CardArt.HEIGHT, 0x30FFFFFF)
        }
    }

    companion object {
        private const val RAISE = 10

        /**
         * How far above the bottom of the screen the turn buttons go when there is no hand
         * under them: just above the hotbar, the health and the food.
         */
        const val BOTTOM_BUTTONS = 64
    }
}

/** Drawing bits the card game panels share. */
object PanelText {
    enum class Align { LEFT, CENTER, RIGHT }

    private val font get() = Minecraft.getInstance().font

    /** A seat's name, marked when its player is away or the computer is playing for them. */
    fun seatLabel(table: ClientTable, seat: Seat): Component {
        val info = table.snapshot.seats.getOrNull(seat)
        val name = TableScreen.seatName(info?.occupant, seat)
        return when {
            info?.occupant is Occupant.Human && !info.present -> Component.translatable("tukuai.table.name_away", name)
            info?.auto == true -> Component.translatable("tukuai.table.name_auto", name)
            else -> name
        }
    }

    /** The seconds left for the turn, red for the last five. */
    fun timer(g: GuiGraphicsExtractor, table: ClientTable, x: Int, y: Int, alignRight: Boolean) {
        val ticks = table.timerTicks()
        if (ticks < 0) return
        val seconds = (ticks + 19) / 20
        val text = Component.translatable("tukuai.table.seconds", seconds)
        val color = if (seconds <= 5) TableScreen.RED else TableScreen.YELLOW
        g.text(font, text, if (alignRight) x - font.width(text) else x, y, color, true)
    }

    /** A row of overlapping cards, lined up on [x] as [align] says. */
    fun cards(g: GuiGraphicsExtractor, cards: List<PlayingCard>, x: Int, y: Int, scale: Float, step: Int, align: Align) {
        if (cards.isEmpty()) return
        val total = (CardArt.WIDTH * scale).toInt() + step * (cards.size - 1)
        val x0 = when (align) {
            Align.LEFT -> x
            Align.CENTER -> x - total / 2
            Align.RIGHT -> x - total
        }
        cards.forEachIndexed { i, card -> CardArt.drawCard(g, card, x0 + i * step, y, scale) }
    }

    /**
     * The scores of the match so far at the top left, see [Scoreboard]; in a replay, which
     * rules it was played by.
     */
    fun title(g: GuiGraphicsExtractor, table: ClientTable, interactive: Boolean, width: Int) {
        if (interactive) {
            Scoreboard.render(g, table)
            return
        }
        val s = table.snapshot
        g.text(font, TableScreen.presetName(s.gameId, s.presetId), 6, 6, TableScreen.GRAY, true)
    }

    /**
     * Beside the turn buttons while cards are picked: what they make, in green, or in red
     * why they cannot go out, so a greyed out play button says why.
     */
    fun picked(g: GuiGraphicsExtractor, text: Component, ok: Boolean, x: Int, y: Int) =
        text(g, text, x, y, if (ok) TableScreen.GREEN else TableScreen.RED, Align.LEFT)

    /** One line of text lined up on [x] as [align] says. */
    fun text(g: GuiGraphicsExtractor, text: Component, x: Int, y: Int, color: Int, align: Align) {
        val w = font.width(text)
        val tx = when (align) {
            Align.LEFT -> x
            Align.CENTER -> x - w / 2
            Align.RIGHT -> x - w
        }
        g.text(font, text, tx, y, color, true)
    }
}
