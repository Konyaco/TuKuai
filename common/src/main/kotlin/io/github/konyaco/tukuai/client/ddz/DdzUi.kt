package io.github.konyaco.tukuai.client.ddz

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.doudizhu.DDZ_ORDER
import io.github.konyaco.tukuai.engine.doudizhu.DdzCodecs
import io.github.konyaco.tukuai.engine.doudizhu.DdzEventKind
import io.github.konyaco.tukuai.engine.doudizhu.DdzPhase
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.core.Direction

object DdzUi : GameUi {
    override val gameId: String = DouDizhu.id

    override fun decodeView(data: ByteArray): Any = DdzCodecs.VIEW.decode(data)

    override fun createPanel(screen: TableScreen): GamePanel = DdzPanel(screen)

    /**
     * Each seat's hand lies along its edge of the table (face down, except your own and
     * everyone's once the game is over), its latest play lies nearer the middle, and the
     * landlord's three cards sit on the empty fourth side.
     */
    override fun scene(table: ClientTable, sides: List<Direction>): List<TableCard> {
        val view = table.view as? DdzView ?: return emptyList()
        val cards = ArrayList<TableCard>()
        val finished = table.snapshot.status == TableStatus.FINISHED
        val recent = DdzPanel.recentActions(view)
        var layer = 0

        fun row(side: Direction, distance: Float, sprites: List<Int>, step: Float, scale: Float) {
            if (sprites.isEmpty()) return
            // The player on this side faces the middle; cards run left to right as they see them.
            val upX = -side.stepX.toFloat()
            val upZ = -side.stepZ.toFloat()
            val rightX = -upZ
            val rightZ = upX
            val total = step * (sprites.size - 1)
            val cx = 0.5f + side.stepX * distance
            val cz = 0.5f + side.stepZ * distance
            sprites.forEachIndexed { i, sprite ->
                val offset = -total / 2 + i * step
                cards += TableCard(cx + rightX * offset, cz + rightZ * offset, upX, upZ, sprite, scale, layer++)
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
            row(side, HAND_DISTANCE, hand, HAND_STEP, HAND_SCALE)
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

    private const val HAND_DISTANCE = 0.33f
    private const val HAND_STEP = 0.024f
    private const val HAND_SCALE = 0.8f
    private const val PLAY_DISTANCE = 0.12f
    private const val PLAY_STEP = 0.035f
    private const val PLAY_SCALE = 0.75f
    private const val KITTY_DISTANCE = 0.3f
    private const val KITTY_STEP = 0.1f
}
