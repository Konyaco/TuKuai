package io.github.konyaco.tukuai.client.gd

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.SeatPlace
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Suit
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.doudizhu.DdzCards
import io.github.konyaco.tukuai.engine.guandan.GdCards
import io.github.konyaco.tukuai.engine.guandan.GdCodecs
import io.github.konyaco.tukuai.engine.guandan.GdEventKind
import io.github.konyaco.tukuai.engine.guandan.GdPhase
import io.github.konyaco.tukuai.engine.guandan.GdValue
import io.github.konyaco.tukuai.engine.guandan.GdView
import io.github.konyaco.tukuai.engine.guandan.Guandan
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

object GdUi : GameUi {
    override val gameId: String = Guandan.id

    override fun decodeView(data: ByteArray): Any = GdCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = GdPanel(host)

    /** Strongest first, as the hand is held. */
    fun sorted(view: GdView, cards: List<PlayingCard>): List<PlayingCard> = cards.sortedWith(GdCards.order(view.levelRank))

    /**
     * Each hand stands along its owner's edge, squeezed to fit the side of the table, and
     * lies face up once a round is over; each seat's latest play lies nearer the middle.
     * New cards are dealt from the middle of the table. Wild cards in a hand are framed in
     * gold, and your own hand is picked on the table in the immersive view.
     */
    override fun scene(table: ClientTable, seats: List<SeatPlace>): List<TableCard> {
        val view = table.view as? GdView ?: return emptyList()
        val over = view.phase == GdPhase.ROUND_OVER || view.phase == GdPhase.FINISHED
        val recent = GdPanel.recentActions(view)
        val cards = ArrayList<TableCard>()
        val wild = CardArt.face(PlayingCard.Regular(view.levelRank, Suit.HEARTS))

        for (seat in 0 until Guandan.SEATS) {
            val place = seats.getOrNull(seat) ?: continue
            val side = place.side
            val revealed = view.revealedHands.getOrNull(seat)
            val hand: List<Pair<Int, Int>> = when {
                seat == view.seat && !over -> keyed(sorted(view, view.hand))
                revealed != null -> keyed(sorted(view, revealed))
                else -> List(view.handCounts[seat]) { CardArt.back() to -1 }
            }
            val watched = revealed != null && seat != view.seat
            row(cards, place, HAND_DISTANCE, hand, HAND_STEP, HAND_WIDTH, HAND_SCALE, standing = !over, bothSides = watched, 0f, 0f, mine = seat == view.seat && !over, wild = wild)
            val play = recent[seat]
            if (play != null && play.kind == GdEventKind.PLAY) {
                row(cards, place, PLAY_DISTANCE, keyed(play.cards), PLAY_STEP, PLAY_WIDTH, PLAY_SCALE, standing = false, bothSides = false, side.stepX * HAND_DISTANCE + place.shiftX, side.stepZ * HAND_DISTANCE + place.shiftZ)
            }
        }
        return cards
    }

    /** Sprites with a key that tells the two copies of a card apart, so each moves from where it was. */
    private fun keyed(list: List<PlayingCard>): List<Pair<Int, Int>> = list.map(CardArt::face).zip(keys(list))

    /** For each card of [list], a key that tells it from the other copy of the same card; it is what [TableCard.key] holds. */
    fun keys(list: List<PlayingCard>): List<Int> {
        val seen = HashMap<PlayingCard, Int>()
        return list.map { card ->
            val copy = seen.merge(card, 1, Int::plus)!! - 1
            DdzCards.index(card) * 2 + copy
        }
    }

    private fun row(
        cards: MutableList<TableCard>,
        place: SeatPlace,
        distance: Float,
        sprites: List<Pair<Int, Int>>,
        most: Float,
        widest: Float,
        scale: Float,
        standing: Boolean,
        bothSides: Boolean,
        fromX: Float,
        fromZ: Float,
        mine: Boolean = false,
        wild: Int = -1,
    ) {
        if (sprites.isEmpty()) return
        val side = place.side
        val upX = -side.stepX.toFloat()
        val upZ = -side.stepZ.toFloat()
        val rightX = -upZ
        val rightZ = upX
        val step = if (sprites.size <= 1) 0f else minOf(most, widest / (sprites.size - 1))
        val total = step * (sprites.size - 1)
        val cx = side.stepX * distance + place.shiftX
        val cz = side.stepZ * distance + place.shiftZ
        sprites.forEachIndexed { i, (sprite, key) ->
            val offset = -total / 2 + i * step
            val nearer = if (standing) (i - (sprites.size - 1) / 2f) * HAND_DEPTH else 0f
            cards += TableCard(
                cx + rightX * offset + side.stepX * nearer,
                cz + rightZ * offset + side.stepZ * nearer,
                upX, upZ, sprite, scale,
                layer = if (standing) 0 else i,
                tilt = if (standing) HAND_TILT else 0f,
                fromX = fromX, fromZ = fromZ, key = key,
                backSprite = if (bothSides && standing) sprite else -1, mine = mine,
                outline = if (sprite == wild) WILD_COLOR else 0,
            )
        }
    }

    /** The level as it is printed on the cards: 2 to 10, J, Q, K or A. */
    fun levelName(level: Int): String = GdValue.label(GdValue.natural(GdValue.LADDER[level]))

    fun name(table: ClientTable, seat: Seat): Component = TableScreen.seatName(table.snapshot.seats.getOrNull(seat)?.occupant, seat)

    /** For example "Level 7 · 1st Bob": the level, and who is out so far. */
    private fun levelLine(table: ClientTable, view: GdView): Component {
        val line = Component.translatable("tukuai.guandan.level", levelName(view.level)).copy()
        view.order.forEachIndexed { place, seat ->
            line.append(" · ").append(Component.translatable("tukuai.guandan.place.${place + 1}")).append(" ").append(name(table, seat))
        }
        return line
    }

    override fun displayLines(table: ClientTable): List<Component> {
        val view = table.view as? GdView ?: return emptyList()
        return when (view.phase) {
            GdPhase.ROUND_OVER, GdPhase.FINISHED -> {
                val round = view.rounds.last()
                val winner = round.order[0]
                listOf(
                    Component.translatable("tukuai.guandan.won", name(table, winner), name(table, Guandan.partner(winner))),
                    Component.translatable("tukuai.guandan.gain", round.gain),
                )
            }
            GdPhase.RETURN -> listOf(levelLine(table, view), Component.translatable("tukuai.guandan.giving_back", name(table, view.turn)))
            GdPhase.PLAYING -> listOf(levelLine(table, view), Component.translatable("tukuai.table.turn", name(table, view.turn)))
        }
    }

    override fun turnSeat(table: ClientTable): Int? {
        val view = table.view as? GdView ?: return null
        return if (view.phase == GdPhase.PLAYING || view.phase == GdPhase.RETURN) view.turn else null
    }

    /** A card for a play, a bang for a bomb, a bell when someone goes out, a rustle for tributes. */
    override fun onUpdate(previous: ClientTable?, table: ClientTable) {
        val view = table.view as? GdView ?: return
        val before = (previous?.view as? GdView)?.takeIf { it.round == view.round }?.log
        val log = view.log
        if (before == null || before.size > log.size || log.subList(0, before.size) != before) return
        val pos = table.pos
        for (event in log.subList(before.size, log.size)) {
            when (event.kind) {
                GdEventKind.PLAY -> if (event.pattern?.isBomb == true) {
                    ClientTables.playAt(pos, SoundEvents.GENERIC_EXPLODE, 0.5f, 1.3f + event.pattern.tier * 0.03f)
                } else {
                    ClientTables.playAt(pos, SoundEvents.BOOK_PAGE_TURN, 1f, 1.1f + event.cards.size * 0.02f)
                }
                GdEventKind.OUT -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BELL, 1f, 1.5f - event.other * 0.15f)
                GdEventKind.TRIBUTE, GdEventKind.RETURN -> ClientTables.playAt(pos, SoundEvents.ITEM_PICKUP, 0.6f, 1f)
                GdEventKind.PASS, GdEventKind.PARTNER_LEADS, GdEventKind.NO_TRIBUTE -> {}
            }
        }
    }

    /** A gold frame that marks a wild card. */
    fun wildFrame(g: GuiGraphicsExtractor, x: Int, y: Int, scale: Float) {
        val w = (CardArt.WIDTH * scale).toInt()
        val h = (CardArt.HEIGHT * scale).toInt()
        val c = WILD_COLOR
        g.fill(x, y, x + w, y + 1, c)
        g.fill(x, y + h - 1, x + w, y + h, c)
        g.fill(x, y, x + 1, y + h, c)
        g.fill(x + w - 1, y, x + w, y + h, c)
    }

    const val WILD_COLOR = 0xFFFFC020.toInt()

    // Distances are from the middle of the 3-block table; its cloth reaches 1.375 out.
    private const val HAND_DISTANCE = 1.08f
    private const val HAND_STEP = 0.08f

    /** The widest a hand may spread, so 27 cards squeeze along one side without reaching the corners. */
    private const val HAND_WIDTH = 1.45f
    private const val HAND_SCALE = 0.6f
    private const val HAND_DEPTH = 0.006f
    private const val HAND_TILT = 60f
    private const val PLAY_DISTANCE = 0.6f
    private const val PLAY_STEP = 0.09f
    private const val PLAY_WIDTH = 0.6f
    private const val PLAY_SCALE = 0.6f
}
