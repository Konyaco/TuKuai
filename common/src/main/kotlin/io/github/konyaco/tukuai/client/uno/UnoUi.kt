package io.github.konyaco.tukuai.client.uno

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.client.render.CardTableRenderer
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.uno.Uno
import io.github.konyaco.tukuai.engine.uno.UnoCard
import io.github.konyaco.tukuai.engine.uno.UnoCodecs
import io.github.konyaco.tukuai.engine.uno.UnoColor
import io.github.konyaco.tukuai.engine.uno.UnoEventKind
import io.github.konyaco.tukuai.engine.uno.UnoKind
import io.github.konyaco.tukuai.engine.uno.UnoView
import kotlin.math.cos
import kotlin.math.sin
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

object UnoUi : GameUi {
    override val gameId: String = Uno.id

    override fun decodeView(data: ByteArray): Any = UnoCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = UnoPanel(host)

    /**
     * Each seat's hand stands along its edge of the table, facing its owner; once the hand
     * is over they lie face up. The discard pile lies on top of the display in the middle,
     * so every seat can see it, and the draw pile sits in the corner to the right of seat 0.
     */
    override fun scene(table: ClientTable, sides: List<Direction>): List<TableCard> {
        val view = table.view as? UnoView ?: return emptyList()
        val cards = ArrayList<TableCard>()
        val first = sides.firstOrNull() ?: return emptyList()
        val corner = first.clockWise
        val pileX = (first.stepX + corner.stepX) * PILE_DISTANCE
        val pileZ = (first.stepZ + corner.stepZ) * PILE_DISTANCE

        // The draw pile: a short stack of backs, taller the more cards are left.
        val pileCards = if (view.drawCount == 0) 0 else (view.drawCount + PILE_CARDS_PER_LAYER - 1) / PILE_CARDS_PER_LAYER
        repeat(pileCards) { i ->
            cards += TableCard(
                pileX, pileZ, -first.stepX.toFloat(), -first.stepZ.toFloat(), CardArt.back(), PILE_SCALE,
                lift = i * CardTableRenderer.CARD_THICKNESS * PILE_SCALE,
            )
        }

        for (seat in 0 until view.players) {
            val side = sides.getOrNull(seat) ?: continue
            val revealed = view.revealedHands.getOrNull(seat)
            val hand: List<Pair<Int, Int>> = when {
                revealed != null -> sorted(revealed).map { CardArt.uno(it) to it.id }
                seat == view.seat -> sorted(view.hand).map { CardArt.uno(it) to it.id }
                else -> List(view.handCounts[seat]) { CardArt.back() to -1 }
            }
            layHand(cards, side, hand, standing = revealed == null, fromX = pileX, fromZ = pileZ)
        }

        // The top of the discard pile, each card a little turned, as if tossed there.
        val playedBy = HashMap<UnoCard, Seat>()
        val named = HashMap<UnoCard, UnoColor>()
        for (event in view.log) {
            if (event.kind != UnoEventKind.PLAY) continue
            playedBy[event.card!!] = event.seat
            event.color?.let { named[event.card!!] = it }
        }
        val buried = view.discardCount - view.discardTop.size
        view.discardTop.forEachIndexed { i, card ->
            val depth = buried + i
            val angle = (((depth * 7919) % 41) - 20) * TOSS_DEGREES / 20f
            val radians = Math.toRadians(angle.toDouble())
            val upX = -first.stepX * cos(radians).toFloat() + first.stepZ * sin(radians).toFloat()
            val upZ = -first.stepZ * cos(radians).toFloat() - first.stepX * sin(radians).toFloat()
            val from = playedBy[card]?.let { sides.getOrNull(it) }
            cards += TableCard(
                ((depth * 31) % 7 - 3) * TOSS_SHIFT,
                ((depth * 17) % 7 - 3) * TOSS_SHIFT,
                upX, upZ, CardArt.uno(card, named[card]), DISCARD_SCALE, layer = i,
                lift = CardTableRenderer.DISPLAY_HEIGHT + 0.002f,
                fromX = from?.let { it.stepX * HAND_DISTANCE } ?: pileX,
                fromZ = from?.let { it.stepZ * HAND_DISTANCE } ?: pileZ,
                key = card.id,
            )
        }
        return cards
    }

    private fun layHand(cards: MutableList<TableCard>, side: Direction, hand: List<Pair<Int, Int>>, standing: Boolean, fromX: Float, fromZ: Float) {
        if (hand.isEmpty()) return
        val upX = -side.stepX.toFloat()
        val upZ = -side.stepZ.toFloat()
        val rightX = -upZ
        val rightZ = upX
        val step = if (hand.size <= 1) 0f else minOf(HAND_STEP, HAND_WIDTH / (hand.size - 1))
        val total = step * (hand.size - 1)
        val cx = side.stepX * HAND_DISTANCE
        val cz = side.stepZ * HAND_DISTANCE
        hand.forEachIndexed { i, (sprite, key) ->
            val offset = -total / 2 + i * step
            val nearer = if (standing) (i - (hand.size - 1) / 2f) * HAND_DEPTH else 0f
            cards += TableCard(
                cx + rightX * offset + side.stepX * nearer,
                cz + rightZ * offset + side.stepZ * nearer,
                upX, upZ, sprite, HAND_SCALE,
                layer = if (standing) 0 else i,
                tilt = if (standing) HAND_TILT else 0f,
                fromX = fromX, fromZ = fromZ, key = key,
            )
        }
    }

    /** Cards grouped by colour, wild cards last, numbers before symbols. */
    fun sorted(cards: List<UnoCard>): List<UnoCard> =
        cards.sortedWith(compareBy<UnoCard>({ it.face.color?.ordinal ?: UnoColor.entries.size }, { it.face.kind.ordinal }, { it.face.number }, { it.id }))

    /** The colour to follow and which way play goes, then whose turn it is or who won. */
    override fun displayLines(table: ClientTable): List<Component> {
        val view = table.view as? UnoView ?: return emptyList()
        val settlement = view.settlement
        if (settlement != null) {
            return listOf(Component.translatable("tukuai.uno.won", TableScreen.seatName(table.snapshot.seats.getOrNull(settlement.winner)?.occupant)))
        }
        val turn = TableScreen.seatName(table.snapshot.seats.getOrNull(view.turn)?.occupant)
        return listOf(stateLine(view), Component.translatable("tukuai.table.turn", turn))
    }

    /**
     * For example "Red · counterclockwise +4": the colour to follow, which way play goes seen
     * from above (seat numbers go counterclockwise), and cards piled up to draw.
     */
    fun stateLine(view: UnoView): Component {
        val line = colorName(view.color).copy().append(" · ")
            .append(Component.translatable(if (view.direction > 0) "tukuai.uno.ccw" else "tukuai.uno.cw"))
        if (view.pendingDraw > 0) line.append(" +${view.pendingDraw}")
        return line
    }

    fun colorName(color: UnoColor): Component =
        Component.translatable("tukuai.uno.color.${color.name.lowercase()}").withColor(COLORS.getValue(color))

    override fun turnSeat(table: ClientTable): Int? {
        val view = table.view as? UnoView ?: return null
        return if (view.finished) null else view.turn
    }

    override fun onUpdate(previous: ClientTable?, table: ClientTable) {
        val view = table.view as? UnoView ?: return
        val before = (previous?.view as? UnoView)?.log
        val log = view.log
        if (before == null || before.size > log.size || log.subList(0, before.size) != before) return
        val pos = table.pos
        for (event in log.subList(before.size, log.size)) {
            when (event.kind) {
                UnoEventKind.PLAY -> when (event.card!!.face.kind) {
                    UnoKind.WILD -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_CHIME, 0.8f, 1f)
                    UnoKind.WILD_DRAW_FOUR -> ClientTables.playAt(pos, SoundEvents.GENERIC_EXPLODE, 0.4f, 1.6f)
                    UnoKind.DRAW_TWO -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BASS, 0.9f, 0.8f)
                    UnoKind.SKIP, UnoKind.REVERSE -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_SNARE, 0.9f, 1.2f)
                    UnoKind.NUMBER -> ClientTables.playAt(pos, SoundEvents.BOOK_PAGE_TURN, 1f, 1.1f)
                }
                UnoEventKind.DRAW -> ClientTables.playAt(pos, SoundEvents.ITEM_PICKUP, 0.5f, 0.8f + minOf(event.count, 6) * 0.1f)
                UnoEventKind.UNO -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BELL, 1f, 1.2f)
                UnoEventKind.PENALTY -> ClientTables.playAt(pos, SoundEvents.VILLAGER_NO, 0.7f, 1f)
                UnoEventKind.RESHUFFLE -> ClientTables.playAt(pos, SoundEvents.BOOK_PUT, 1f, 0.8f)
                UnoEventKind.PASS, UnoEventKind.SKIPPED -> {}
            }
        }
    }

    val COLORS: Map<UnoColor, Int> = mapOf(
        UnoColor.RED to 0xFF5A50,
        UnoColor.YELLOW to 0xFFD23C,
        UnoColor.GREEN to 0x5AC85A,
        UnoColor.BLUE to 0x5A96FF,
    )

    // Distances are from the middle of the 3-block table; its cloth reaches 1.375 out.
    private const val HAND_DISTANCE = 1.05f
    private const val HAND_STEP = 0.085f

    /** The widest a hand may spread, so long hands squeeze together instead of reaching the corners. */
    private const val HAND_WIDTH = 1.3f
    private const val HAND_SCALE = 0.7f
    private const val HAND_DEPTH = 0.006f
    private const val HAND_TILT = 60f
    private const val DISCARD_SCALE = 0.8f

    /** How far, in degrees, cards on the discard pile turn from straight. */
    private const val TOSS_DEGREES = 25f
    private const val TOSS_SHIFT = 0.012f
    private const val PILE_DISTANCE = 0.62f
    private const val PILE_SCALE = 0.75f
    private const val PILE_CARDS_PER_LAYER = 4
}
