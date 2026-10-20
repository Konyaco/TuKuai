package io.github.konyaco.tukuai.client.pdk

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PlayingCardScene
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.doudizhu.DDZ_ORDER
import io.github.konyaco.tukuai.engine.paodekuai.PaoDeKuai
import io.github.konyaco.tukuai.engine.paodekuai.PdkCodecs
import io.github.konyaco.tukuai.engine.paodekuai.PdkEventKind
import io.github.konyaco.tukuai.engine.paodekuai.PdkView
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

object PdkUi : GameUi {
    override val gameId: String = PaoDeKuai.id

    override fun decodeView(data: ByteArray): Any = PdkCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = PdkPanel(host)

    /**
     * As in Dou Dizhu: each hand stands along its owner's edge of the table, facing them,
     * and lies face up once the game is over; each seat's latest play lies nearer the
     * middle. The cards are dealt from a deck on the empty fourth side.
     */
    override fun scene(table: ClientTable, sides: List<Direction>): List<TableCard> {
        val view = table.view as? PdkView ?: return emptyList()
        val finished = table.snapshot.status == TableStatus.FINISHED
        val recent = PdkPanel.recentActions(view)
        val deckSide = PlayingCardScene.emptySide(sides)
        val deckX = (deckSide?.stepX ?: 0) * DECK_DISTANCE
        val deckZ = (deckSide?.stepZ ?: 0) * DECK_DISTANCE
        val scene = PlayingCardScene()

        fun faces(list: List<PlayingCard>) = list.sortedWith(DDZ_ORDER.reversed()).map(CardArt::face)

        for (seat in 0 until PaoDeKuai.SEATS) {
            val side = sides.getOrNull(seat) ?: continue
            val revealed = view.revealedHands.getOrNull(seat)
            val hand = when {
                seat == view.seat && !finished -> faces(view.hand)
                revealed != null -> faces(revealed)
                else -> List(view.handCounts[seat]) { CardArt.back() }
            }
            val watched = revealed != null && seat != view.seat
            scene.row(
                side, HAND_DISTANCE, hand, HAND_STEP, HAND_SCALE, deckX, deckZ,
                standing = !finished, bothSides = watched, mine = seat == view.seat && !finished,
            )
            val play = recent[seat]
            if (play != null && play.kind == PdkEventKind.PLAY) {
                scene.row(side, PLAY_DISTANCE, faces(play.cards), PLAY_STEP, PLAY_SCALE, side.stepX * HAND_DISTANCE, side.stepZ * HAND_DISTANCE)
            }
        }
        return scene.cards
    }

    /**
     * The cards each seat has left, or a warning when someone is down to one, then whose
     * turn it is; once the game is over, who went out and what is left.
     */
    override fun displayLines(table: ClientTable): List<Component> {
        val view = table.view as? PdkView ?: return emptyList()
        val settlement = view.settlement
        if (settlement != null) {
            return listOf(Component.translatable("tukuai.pdk.won", name(table, settlement.winner)), cardsLeft(view))
        }
        val warned = (0 until PaoDeKuai.SEATS).firstOrNull { view.handCounts[it] == 1 }
        val first = if (warned != null) {
            Component.translatable("tukuai.pdk.one_card", name(table, warned)).withColor(WARNING_RGB)
        } else {
            cardsLeft(view)
        }
        return listOf(first, Component.translatable("tukuai.table.turn", name(table, view.turn)))
    }

    /** For example "Left: 12 · 5 · 9", in seat order. */
    fun cardsLeft(view: PdkView): Component = Component.translatable("tukuai.pdk.left", view.handCounts.joinToString(" · "))

    private fun name(table: ClientTable, seat: Int): Component = TableScreen.seatName(table.snapshot.seats.getOrNull(seat)?.occupant)

    override fun turnSeat(table: ClientTable): Int? {
        val view = table.view as? PdkView ?: return null
        return if (view.finished) null else view.turn
    }

    /** A card for a play, a bang for a bomb, a bell when a bomb wins its round or someone is down to one card. */
    override fun onUpdate(previous: ClientTable?, table: ClientTable) {
        val view = table.view as? PdkView ?: return
        val before = (previous?.view as? PdkView)?.log
        val log = view.log
        if (before == null || before.size > log.size || log.subList(0, before.size) != before) return
        val pos = table.pos
        for (event in log.subList(before.size, log.size)) {
            when (event.kind) {
                PdkEventKind.PLAY -> if (event.pattern?.isBomb == true) {
                    ClientTables.playAt(pos, SoundEvents.GENERIC_EXPLODE, 0.5f, 1.4f)
                } else {
                    ClientTables.playAt(pos, SoundEvents.BOOK_PAGE_TURN, 1f, 1.1f + event.cards.size * 0.02f)
                }
                PdkEventKind.BOMB_WON -> ClientTables.playAt(pos, SoundEvents.EXPERIENCE_ORB_PICKUP, 0.8f, 0.8f)
                PdkEventKind.PASS -> {}
            }
        }
        val counts = (previous?.view as? PdkView)?.handCounts ?: return
        if (!view.finished && view.handCounts.indices.any { view.handCounts[it] == 1 && counts.getOrNull(it) != 1 }) {
            ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BELL, 1f, 1.5f)
        }
    }

    /** The warning colour for a player down to their last card, as RGB. */
    const val WARNING_RGB = 0xFF6655

    // Distances are from the middle of the 3-block table; its cloth reaches 1.375 out.
    private const val HAND_DISTANCE = 1.05f
    private const val HAND_STEP = 0.08f
    private const val HAND_SCALE = 0.7f
    private const val PLAY_DISTANCE = 0.6f
    private const val PLAY_STEP = 0.1f
    private const val PLAY_SCALE = 0.75f

    /** Where the deck lies while cards are dealt, out from the middle towards the empty side. */
    private const val DECK_DISTANCE = 0.6f
}
