package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.table.Occupant
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
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

    /** Left-click picks or drops a card, right-click drops them all. */
    fun click(hand: List<PlayingCard>, x: Double, y: Double, button: Int): Boolean {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (selected.isEmpty()) return false
            selected.clear()
            host.refresh()
            return true
        }
        val card = cardAt(hand, x, y) ?: return false
        toggle(card)
        return true
    }

    /** A click on one of the player's cards on the table: picks or drops the card of [hand] it shows. */
    fun clickTableCard(hand: Collection<PlayingCard>, card: TableCard): Boolean {
        toggle(hand.firstOrNull { CardArt.face(it) == card.sprite } ?: return false)
        return true
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
        val name = TableScreen.seatName(info?.occupant)
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

    /** The rules at the top left, and for players at the table the round and who may watch. */
    fun title(g: GuiGraphicsExtractor, table: ClientTable, interactive: Boolean, width: Int) {
        val s = table.snapshot
        g.text(font, TableScreen.presetName(s.gameId, s.presetId), 6, 6, TableScreen.GRAY, true)
        if (!interactive) return
        val round = s.rounds + if (s.status == TableStatus.PLAYING) 1 else 0
        g.text(font, Component.translatable("tukuai.table.rounds", round), 6, 17, TableScreen.GRAY, true)
        // Wrapped to stay clear of what sits in the middle at the top.
        val watching = table.watchingLine() ?: return
        font.split(watching, width / 2 - 60).forEachIndexed { i, line ->
            g.text(font, line, 6, 28 + i * 10, if (s.spectators) TableScreen.YELLOW else TableScreen.GRAY, true)
        }
    }

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
