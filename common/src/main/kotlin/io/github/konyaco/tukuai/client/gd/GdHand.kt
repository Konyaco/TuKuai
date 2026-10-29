package io.github.konyaco.tukuai.client.gd

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PlayingCardHand
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.card.PlayingCard
import org.lwjgl.glfw.GLFW

/**
 * Your Guandan hand along the bottom of the screen, where cards are picked before playing
 * them. Two decks mean two of every card, so picks are kept by position in the hand rather
 * than by card. A long hand is laid out in two rows, the first row behind the second,
 * showing the corners with the ranks. In the immersive view the hand is left out and its
 * cards are picked where they stand on the table instead, told apart by [GdUi.keys].
 * Four-player Dou Dizhu, also two decks, uses it too, keeping up to [oneRowMost] cards in
 * one row and [margin] clear at the sides, as three-player Dou Dizhu always did.
 */
class GdHand(private val host: PanelHost, private val oneRowMost: Int = ONE_ROW_MOST, private val margin: Int = 40) {
    private var cards: List<PlayingCard> = emptyList()
    private var keys: List<Int> = emptyList()
    private val picked = HashSet<Int>()
    private var hints: List<List<PlayingCard>> = emptyList()
    private var hintIndex = 0
    private var lastStep = -1

    /** While the left button is held after a click: whether that card got picked, and which row the sweep runs along. */
    private var sweep: Boolean? = null
    private var sweepRow = 0
    private var sweepX = 0.0

    /** The picked cards, in hand order. */
    val selected: List<PlayingCard> get() = cards.indices.filter { it in picked }.map { cards[it] }

    private val width get() = host.panelWidth

    private fun twoRows(n: Int = cards.size) = n > oneRowMost

    /** Top of the front row of cards. */
    private fun frontY() = host.panelHeight - CardArt.HEIGHT - 8

    private fun topY(n: Int = cards.size) = if (twoRows(n)) frontY() - ROW_GAP else frontY()

    /** The row above the hand where the turn buttons go; near the bottom when the hand is on the table. */
    fun buttonY(n: Int = cards.size): Int =
        if (host.inWorld) host.panelHeight - PlayingCardHand.BOTTOM_BUTTONS else topY(maxOf(n, 0)) - RAISE - 4 - 22

    /** Which row each card is in and its place in that row. */
    private fun rows(n: Int): List<Pair<Int, Int>> {
        if (!twoRows(n)) return List(n) { 0 to it }
        val back = (n + 1) / 2
        return List(n) { if (it < back) 0 to it else 1 to it - back }
    }

    private fun rowSize(n: Int, row: Int): Int = if (!twoRows(n)) n else if (row == 0) (n + 1) / 2 else n / 2

    private fun step(count: Int): Int = if (count <= 1) 0 else minOf(18, (width - margin - CardArt.WIDTH) / (count - 1))

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
        keys = GdUi.keys(hand)
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

    /**
     * Left-click picks or drops a card, and dragging on from there sweeps the other cards
     * of its row passed over the same way. Right-click drops them all.
     */
    fun click(x: Double, y: Double, button: Int): Boolean {
        if (button == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
            if (picked.isEmpty()) return false
            clear()
            return true
        }
        val i = cardAt(x, y) ?: return false
        if (!picked.remove(i)) picked += i
        sweep = i in picked
        sweepRow = rows(cards.size)[i].first
        sweepX = x
        host.refresh()
        return true
    }

    /** The mouse moved with the left button held: the cards of the sweep's row whose strip it crossed go the same way. */
    fun drag(x: Double): Boolean {
        val picking = sweep ?: return false
        if (host.inWorld) return false
        val from = minOf(sweepX, x)
        val to = maxOf(sweepX, x)
        sweepX = x
        val n = cards.size
        val layout = rows(n)
        val count = rowSize(n, sweepRow)
        val step = step(count)
        var changed = false
        for (i in 0 until n) {
            val (row, place) = layout[i]
            if (row != sweepRow) continue
            val left = position(n, i).first
            val right = left + if (place == count - 1 || step == 0) CardArt.WIDTH else step
            if (right <= from || left > to) continue
            changed = changed or if (picking) picked.add(i) else picked.remove(i)
        }
        if (changed) host.refresh()
        return true
    }

    fun release() {
        sweep = null
    }

    /** A click on one of the player's cards on the table: picks or drops it, and starts a sweep. */
    fun clickTableCard(card: TableCard): Boolean {
        val i = keys.indexOf(card.key).takeIf { it >= 0 } ?: return false
        if (!picked.remove(i)) picked += i
        sweep = i in picked
        host.refresh()
        return true
    }

    /** The pointer swept over [card] on the table with the button held: it goes the way of the first card. */
    fun dragTableCard(card: TableCard) {
        val picking = sweep ?: return
        val i = keys.indexOf(card.key).takeIf { it >= 0 } ?: return
        if (if (picking) picked.add(i) else picked.remove(i)) host.refresh()
    }

    /** Whether [card] on the table shows a picked card. */
    fun isRaised(card: TableCard): Boolean = keys.indexOf(card.key).let { it >= 0 && it in picked }

    /** Front row first, and the right-hand card of each row before those it covers. */
    private fun drawOrder(n: Int): List<Int> = (0 until n).sortedWith(compareBy<Int> { rows(n)[it].first }.thenBy { rows(n)[it].second })

    private fun cardAt(mx: Double, my: Double): Int? {
        if (host.inWorld) return null
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
        if (n == 0 || host.inWorld) return
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
