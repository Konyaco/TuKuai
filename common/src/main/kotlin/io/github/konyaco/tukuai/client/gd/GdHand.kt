package io.github.konyaco.tukuai.client.gd

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.engine.card.PlayingCard
import net.minecraft.client.gui.GuiGraphicsExtractor
import org.lwjgl.glfw.GLFW

/**
 * Your Guandan hand along the bottom of the screen, where cards are picked before playing
 * them. Two decks mean two of every card, so picks are kept by position in the hand rather
 * than by card. A long hand is laid out in two rows, the first row behind the second,
 * showing the corners with the ranks.
 */
class GdHand(private val host: PanelHost) {
    private var cards: List<PlayingCard> = emptyList()
    private val picked = HashSet<Int>()
    private var hints: List<List<PlayingCard>> = emptyList()
    private var hintIndex = 0
    private var lastStep = -1

    /** The picked cards, in hand order. */
    val selected: List<PlayingCard> get() = cards.indices.filter { it in picked }.map { cards[it] }

    private val width get() = host.panelWidth

    private fun twoRows(n: Int = cards.size) = n > ONE_ROW_MOST

    /** Top of the front row of cards. */
    private fun frontY() = host.panelHeight - CardArt.HEIGHT - 8

    private fun topY(n: Int = cards.size) = if (twoRows(n)) frontY() - ROW_GAP else frontY()

    /** The row above the hand where the turn buttons go. */
    fun buttonY(n: Int = cards.size): Int = topY(maxOf(n, 0)) - RAISE - 4 - 22

    /** Which row each card is in and its place in that row. */
    private fun rows(n: Int): List<Pair<Int, Int>> {
        if (!twoRows(n)) return List(n) { 0 to it }
        val back = (n + 1) / 2
        return List(n) { if (it < back) 0 to it else 1 to it - back }
    }

    private fun rowSize(n: Int, row: Int): Int = if (!twoRows(n)) n else if (row == 0) (n + 1) / 2 else n / 2

    private fun step(count: Int): Int = if (count <= 1) 0 else minOf(18, (width - 40 - CardArt.WIDTH) / (count - 1))

    private fun position(n: Int, i: Int): Pair<Int, Int> {
        val (row, place) = rows(n)[i]
        val count = rowSize(n, row)
        val step = step(count)
        val x0 = (width - (CardArt.WIDTH + step * (count - 1))) / 2
        val y = (if (twoRows(n) && row == 0) frontY() - ROW_GAP else frontY()) - if (i in picked) RAISE else 0
        return x0 + place * step to y
    }

    /**
     * Called on every table update with the hand as shown: a new move clears the hints, and
     * the picks stay on the same cards where they are still held.
     */
    fun sync(step: Int, hand: List<PlayingCard>) {
        if (step == lastStep && hand == cards) return
        val keep = selected.toMutableList()
        cards = hand
        picked.clear()
        for (i in hand.indices) if (keep.remove(hand[i])) picked += i
        if (step != lastStep) {
            hints = emptyList()
            hintIndex = 0
        }
        lastStep = step
    }

    fun clear() {
        picked.clear()
        host.refresh()
    }

    /**
     * Picks the next hint, working the list out with [compute] the first time; the list
     * should start with what the computer would play. Returns false when there is none.
     */
    fun hint(compute: () -> List<List<PlayingCard>>): Boolean {
        if (hints.isEmpty()) {
            hints = compute().distinctBy { it.groupingBy { c -> c }.eachCount() }
            hintIndex = 0
        }
        if (hints.isEmpty()) return false
        pick(hints[hintIndex % hints.size])
        hintIndex++
        host.refresh()
        return true
    }

    private fun pick(wanted: List<PlayingCard>) {
        picked.clear()
        val left = wanted.toMutableList()
        for (i in cards.indices) if (left.remove(cards[i])) picked += i
    }

    /** Left-click picks or drops a card, right-click drops them all. */
    fun click(x: Double, y: Double, button: Int): Boolean {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (picked.isEmpty()) return false
            clear()
            return true
        }
        val i = cardAt(x, y) ?: return false
        if (!picked.remove(i)) picked += i
        host.refresh()
        return true
    }

    /** Front row first, and the right-hand card of each row before those it covers. */
    private fun drawOrder(n: Int): List<Int> = (0 until n).sortedWith(compareBy<Int> { rows(n)[it].first }.thenBy { rows(n)[it].second })

    private fun cardAt(mx: Double, my: Double): Int? {
        val n = cards.size
        for (i in drawOrder(n).asReversed()) {
            val (x, y) = position(n, i)
            if (mx >= x && mx < x + CardArt.WIDTH && my >= y && my < y + CardArt.HEIGHT) return i
        }
        return null
    }

    /** Draws the hand; [wild] says which cards to mark as wild. */
    fun render(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, wild: (PlayingCard) -> Boolean) {
        val n = cards.size
        if (n == 0) return
        val hovered = cardAt(mouseX.toDouble(), mouseY.toDouble())
        for (i in drawOrder(n)) {
            val (x, y) = position(n, i)
            CardArt.drawCard(g, cards[i], x, y)
            if (wild(cards[i])) GdUi.wildFrame(g, x, y, 1f)
            if (i == hovered) g.fill(x, y, x + CardArt.WIDTH, y + CardArt.HEIGHT, 0x30FFFFFF)
        }
    }

    private companion object {
        const val RAISE = 10

        /** Up to this many cards fit one row with the ranks still easy to click. */
        const val ONE_ROW_MOST = 16

        /** How much of each card in the back row shows above the front row. */
        const val ROW_GAP = 22
    }
}
