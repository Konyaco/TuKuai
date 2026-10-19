package io.github.konyaco.tukuai.client.ddz

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.doudizhu.DDZ_ORDER
import io.github.konyaco.tukuai.engine.doudizhu.DdzCodecs
import io.github.konyaco.tukuai.engine.doudizhu.DdzEventKind
import io.github.konyaco.tukuai.engine.doudizhu.DdzPhase
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component

object DdzUi : GameUi {
    override val gameId: String = DouDizhu.id

    override fun decodeView(data: ByteArray): Any = DdzCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = DdzPanel(host)

    /**
     * Each seat's hand stands along its edge of the table, faces towards its owner, so
     * everyone else sees the backs; once the game is over the hands lie face up. Each
     * seat's latest play lies nearer the middle, and the landlord's three cards sit on
     * the empty fourth side.
     */
    override fun scene(table: ClientTable, sides: List<Direction>): List<TableCard> {
        val view = table.view as? DdzView ?: return emptyList()
        val cards = ArrayList<TableCard>()
        val finished = table.snapshot.status == TableStatus.FINISHED
        val recent = DdzPanel.recentActions(view)

        fun row(side: Direction, distance: Float, sprites: List<Int>, step: Float, scale: Float, standing: Boolean = false) {
            if (sprites.isEmpty()) return
            // The player on this side faces the middle; cards run left to right as they see them.
            val upX = -side.stepX.toFloat()
            val upZ = -side.stepZ.toFloat()
            val rightX = -upZ
            val rightZ = upX
            val total = step * (sprites.size - 1)
            val cx = side.stepX * distance
            val cz = side.stepZ * distance
            sprites.forEachIndexed { i, sprite ->
                val offset = -total / 2 + i * step
                if (standing) {
                    // Like a fanned hand: each card stands a little nearer its owner than the one
                    // on its left, covering all of it but the corner with the rank.
                    val nearer = (i - (sprites.size - 1) / 2f) * HAND_DEPTH
                    cards += TableCard(
                        cx + rightX * offset + side.stepX * nearer,
                        cz + rightZ * offset + side.stepZ * nearer,
                        upX, upZ, sprite, scale, tilt = HAND_TILT,
                    )
                } else {
                    cards += TableCard(cx + rightX * offset, cz + rightZ * offset, upX, upZ, sprite, scale, i)
                }
            }
        }

        fun faces(list: List<PlayingCard>) = list.sortedWith(DDZ_ORDER.reversed()).map(CardArt::face)

        for (seat in 0 until DouDizhu.SEATS) {
            val side = sides.getOrNull(seat) ?: continue
            val revealed = view.revealedHands.getOrNull(seat)
            val hand = when {
                finished && revealed != null -> faces(revealed)
                seat == view.seat -> faces(view.hand)
                else -> List(view.handCounts[seat]) { CardArt.back() }
            }
            row(side, HAND_DISTANCE, hand, HAND_STEP, HAND_SCALE, standing = !finished)
            val play = recent[seat]
            if (play != null && play.kind == DdzEventKind.PLAY) row(side, PLAY_DISTANCE, faces(play.cards), PLAY_STEP, PLAY_SCALE)
        }

        val kittySide = sides.firstOrNull()?.let { first -> Direction.entries.firstOrNull { it.axis.isHorizontal && it !in sides } ?: first }
        if (kittySide != null && view.phase != DdzPhase.FINISHED) {
            val kitty = if (view.kitty.isNotEmpty()) faces(view.kitty) else List(3) { CardArt.back() }
            row(kittySide, KITTY_DISTANCE, kitty, KITTY_STEP, PLAY_SCALE)
        }
        return cards
    }

    /** The stake, then whose turn it is, or who won once the game is over. */
    override fun displayLines(table: ClientTable): List<Component> {
        val view = table.view as? DdzView ?: return emptyList()
        val settlement = view.settlement
        if (settlement != null) {
            return listOf(
                Component.translatable(if (settlement.landlordWon) "tukuai.ddz.landlord_won" else "tukuai.ddz.farmers_won"),
                Component.translatable("tukuai.ddz.stake", view.rules.baseScore, settlement.multiplier),
            )
        }
        val turn = TableScreen.seatName(table.snapshot.seats.getOrNull(view.turn)?.occupant)
        return listOf(
            Component.translatable("tukuai.ddz.stake", view.rules.baseScore, DdzPanel.multiplier(view)),
            Component.translatable("tukuai.table.turn", turn),
        )
    }

    override fun turnSeat(table: ClientTable): Int? {
        val view = table.view as? DdzView ?: return null
        return if (view.phase == DdzPhase.FINISHED) null else view.turn
    }

    // Distances are from the middle of the 3-block table; its cloth reaches 1.375 out.
    private const val HAND_DISTANCE = 1.05f
    private const val HAND_STEP = 0.085f
    private const val HAND_SCALE = 0.7f

    /** How far each standing card is from the next, towards its owner. */
    private const val HAND_DEPTH = 0.008f

    /** Degrees up from flat: the face points at the eyes of someone sitting behind it. */
    private const val HAND_TILT = 60f
    private const val PLAY_DISTANCE = 0.6f
    private const val PLAY_STEP = 0.1f
    private const val PLAY_SCALE = 0.75f
    private const val KITTY_DISTANCE = 0.95f
    private const val KITTY_STEP = 0.3f
}
