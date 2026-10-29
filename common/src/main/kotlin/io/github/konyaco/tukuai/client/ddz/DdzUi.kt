package io.github.konyaco.tukuai.client.ddz

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.SeatPlace
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PlayingCardScene
import io.github.konyaco.tukuai.client.gd.GdUi
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.doudizhu.DDZ_ORDER
import io.github.konyaco.tukuai.engine.doudizhu.DdzCodecs
import io.github.konyaco.tukuai.engine.doudizhu.DdzEventKind
import io.github.konyaco.tukuai.engine.doudizhu.DdzPhase
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import io.github.konyaco.tukuai.engine.doudizhu.PatternType
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

object DdzUi : GameUi {
    override val gameId: String = DouDizhu.id

    override fun decodeView(data: ByteArray): Any = DdzCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = DdzPanel(host)

    /**
     * Each seat's hand stands along its edge of the table, faces towards its owner, so
     * everyone else sees the backs; once the game is over the hands lie face up. Each
     * seat's latest play lies nearer the middle, and the landlord's three cards sit on
     * the empty fourth side. Hands and the landlord's cards are dealt from a deck on that
     * fourth side; a play comes from its player's hand. A watcher who may see every hand
     * sees the standing hands face out on both sides.
     *
     * Four players fill every side, so cards are dealt from the middle, the landlord's eight
     * cards wait in front of it until the landlord takes them, and the long
     * hands of two decks squeeze closer to fit their side.
     */
    override fun scene(table: ClientTable, seats: List<SeatPlace>): List<TableCard> {
        val view = table.view as? DdzView ?: return emptyList()
        val finished = table.snapshot.status == TableStatus.FINISHED
        val recent = DdzPanel.recentActions(view)
        val four = view.handCounts.size == 4
        val kittySide = PlayingCardScene.emptySide(seats).takeUnless { four }
        val deckX = (kittySide?.stepX ?: 0) * DECK_DISTANCE
        val deckZ = (kittySide?.stepZ ?: 0) * DECK_DISTANCE

        val scene = PlayingCardScene()
        fun sorted(list: List<PlayingCard>) = list.sortedWith(DDZ_ORDER.reversed())
        fun faces(list: List<PlayingCard>) = sorted(list).map(CardArt::face)
        // Keys tell the two copies of a card apart; your own hand's are how you pick it on the table.
        fun keys(list: List<PlayingCard>) = GdUi.keys(sorted(list))
        fun step(most: Float, widest: Float, n: Int) = if (n <= 1) most else minOf(most, widest / (n - 1))
        val handScale = if (four) FOUR_HAND_SCALE else HAND_SCALE

        for (seat in view.handCounts.indices) {
            val place = seats.getOrNull(seat) ?: continue
            val side = place.side
            val revealed = view.revealedHands.getOrNull(seat)
            val shown = when {
                seat == view.seat && !finished -> view.hand
                else -> revealed
            }
            val hand = shown?.let(::faces) ?: List(view.handCounts[seat]) { CardArt.back() }
            val watched = revealed != null && seat != view.seat
            scene.row(
                place, HAND_DISTANCE, hand, step(HAND_STEP, HAND_WIDTH, hand.size), handScale, deckX, deckZ,
                standing = !finished, bothSides = watched, mine = seat == view.seat && !finished,
                keys = shown?.let(::keys),
            )
            val play = recent[seat]
            if (play != null && play.kind == DdzEventKind.PLAY) {
                scene.row(
                    place, if (four) FOUR_PLAY_DISTANCE else PLAY_DISTANCE, faces(play.cards), step(PLAY_STEP, PLAY_WIDTH, play.cards.size), PLAY_SCALE,
                    side.stepX * HAND_DISTANCE + place.shiftX, side.stepZ * HAND_DISTANCE + place.shiftZ, keys = keys(play.cards),
                )
            }
        }

        if (four && view.landlord == null) {
            // The middle holds the table's sign, so the landlord's cards wait just in front of it.
            scene.row(SeatPlace(seats.firstOrNull()?.side ?: Direction.NORTH), FOUR_KITTY_DISTANCE, List(view.rules.kittySize) { CardArt.back() }, FOUR_KITTY_STEP, PLAY_SCALE, 0f, 0f)
        } else if (kittySide != null && view.phase != DdzPhase.FINISHED) {
            val kitty = if (view.kitty.isNotEmpty()) faces(view.kitty) else List(view.rules.kittySize) { CardArt.back() }
            scene.row(SeatPlace(kittySide), KITTY_DISTANCE, kitty, KITTY_STEP, PLAY_SCALE, deckX, deckZ)
        }
        return scene.cards
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
        val turn = TableScreen.seatName(table.snapshot.seats.getOrNull(view.turn)?.occupant, view.turn)
        return listOf(
            Component.translatable("tukuai.ddz.stake", view.rules.baseScore, DdzPanel.multiplier(view)),
            Component.translatable("tukuai.table.turn", turn),
        )
    }

    override fun turnSeat(table: ClientTable): Int? {
        val view = table.view as? DdzView ?: return null
        return if (view.phase == DdzPhase.FINISHED) null else view.turn
    }

    /** A sound for each thing that happened since the last update: a card for a play, a bang for a bomb. */
    override fun onUpdate(previous: ClientTable?, table: ClientTable) {
        val view = table.view as? DdzView ?: return
        val before = (previous?.view as? DdzView)?.log
        val log = view.log
        // A shorter log, or one that no longer starts the same, is a new game: nothing to replay.
        if (before == null || before.size > log.size || log.subList(0, before.size) != before) return
        val pos = table.pos
        for (event in log.subList(before.size, log.size)) {
            when (event.kind) {
                DdzEventKind.PLAY -> when (event.pattern?.type) {
                    PatternType.ROCKET -> {
                        ClientTables.playAt(pos, SoundEvents.FIREWORK_ROCKET_LAUNCH, 1f, 1f)
                        ClientTables.playAt(pos, SoundEvents.FIREWORK_ROCKET_LARGE_BLAST, 1f, 1f)
                    }
                    PatternType.BOMB -> ClientTables.playAt(pos, SoundEvents.GENERIC_EXPLODE, 0.5f, 1.4f)
                    else -> ClientTables.playAt(pos, SoundEvents.BOOK_PAGE_TURN, 1f, 1.1f + event.cards.size * 0.02f)
                }
                DdzEventKind.BID, DdzEventKind.CALL, DdzEventKind.ROB, DdzEventKind.DOUBLE ->
                    ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_PLING, 0.6f, 1f + event.value * 0.15f)
                DdzEventKind.LANDLORD -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BELL, 0.8f, 1f)
                else -> {}
            }
        }
    }

    // Distances are from the middle of the 3-block table; its cloth reaches 1.375 out.
    private const val HAND_DISTANCE = 1.05f
    private const val HAND_STEP = 0.085f
    private const val HAND_SCALE = 0.7f

    /** The widest a hand may spread: 20 cards still at [HAND_STEP], 33 squeezed onto their side. */
    private const val HAND_WIDTH = 1.62f
    private const val FOUR_HAND_SCALE = 0.6f

    private const val PLAY_DISTANCE = 0.6f
    private const val PLAY_STEP = 0.1f
    private const val PLAY_SCALE = 0.75f
    private const val PLAY_WIDTH = 1.5f

    /** With four players the plays come in from every side, so they lie a little nearer their owners. */
    private const val FOUR_PLAY_DISTANCE = 0.68f
    private const val FOUR_KITTY_STEP = 0.1f
    private const val FOUR_KITTY_DISTANCE = 0.5f
    private const val KITTY_DISTANCE = 0.95f
    private const val KITTY_STEP = 0.3f

    /** Where the deck lies while cards are dealt, out from the middle towards the empty side. */
    private const val DECK_DISTANCE = 0.6f
}
