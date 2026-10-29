package io.github.konyaco.tukuai.client.bluff

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.PlayingCardScene
import io.github.konyaco.tukuai.client.SeatPlace
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.client.render.TableArt
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.engine.bluff.Bluff
import io.github.konyaco.tukuai.engine.bluff.BluffCodecs
import io.github.konyaco.tukuai.engine.bluff.BluffEvent
import io.github.konyaco.tukuai.engine.bluff.BluffEventKind
import io.github.konyaco.tukuai.engine.bluff.BluffPenalty
import io.github.konyaco.tukuai.engine.bluff.BluffPhase
import io.github.konyaco.tukuai.engine.bluff.BluffView
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.card.Suit
import io.github.konyaco.tukuai.engine.doudizhu.DDZ_ORDER
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import kotlin.math.cos
import kotlin.math.sin

object BluffUi : GameUi {
    override val gameId: String = Bluff.id

    override fun decodeView(data: ByteArray): Any = BluffCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = BluffPanel(host)

    /**
     * Each hand stands along its owner's edge, as at Pao De Kuai, with the player's creeper
     * on their right. The rank called for lies face up on the display in the middle, the
     * cards laid down this round lie face down in a corner, and a play that was called lies
     * turned over beside it until the penalty is taken.
     */
    override fun scene(table: ClientTable, seats: List<SeatPlace>): List<TableCard> {
        val view = table.view as? BluffView ?: return emptyList()
        val scene = PlayingCardScene()
        val first = seats.firstOrNull()?.side ?: return emptyList()
        val upX = -first.stepX.toFloat()
        val upZ = -first.stepZ.toFloat()

        for (seat in 0 until view.players) {
            val place = seats.getOrNull(seat) ?: continue
            val revealed = view.revealedHands.getOrNull(seat)
            val hand = when {
                seat == view.seat && !view.finished -> sorted(view.hand).map(CardArt::face)
                revealed != null -> sorted(revealed).map(CardArt::face)
                else -> List(view.handCounts[seat]) { CardArt.back() }
            }
            val watched = revealed != null && seat != view.seat
            scene.row(place, HAND_DISTANCE, hand, HAND_STEP, HAND_SCALE, 0f, 0f, standing = true, bothSides = watched, mine = seat == view.seat && !view.finished)
            if (!view.out[seat]) {
                val (x, z) = creeperSpot(place)
                scene.cards += TableCard(
                    x, z, -place.side.stepX.toFloat(), -place.side.stepZ.toFloat(), CardArt.CREEPER_SIDE, CREEPER_SCALE,
                    cube = true, backSprite = CardArt.CREEPER_TOP, fromX = x, fromZ = z,
                )
            }
        }
        if (view.finished) return scene.cards

        // What is called for, on the display.
        scene.cards += TableCard(0f, 0f, upX, upZ, CardArt.face(called(view.target)), TARGET_SCALE, lift = TableArt.DISPLAY_HEIGHT + 0.002f)

        // The pile in the corner on seat 0's right: each card face down and a little turned, flown in from whoever laid it down.
        val corner = first.clockWise
        val pileX = (first.stepX + corner.stepX) * PILE_DISTANCE
        val pileZ = (first.stepZ + corner.stepZ) * PILE_DISTANCE
        val plays = view.roundLog.filter { it.kind == BluffEventKind.PLAY }.flatMap { e -> List(e.count) { e.seat } }
        plays.forEachIndexed { i, seat ->
            val angle = Math.toRadians(((i * 7919) % 61 - 30).toDouble())
            val from = seats.getOrNull(seat)
            scene.cards += TableCard(
                pileX + ((i * 31) % 7 - 3) * PILE_SHIFT, pileZ + ((i * 17) % 7 - 3) * PILE_SHIFT,
                (upX * cos(angle) - upZ * sin(angle)).toFloat(), (upZ * cos(angle) + upX * sin(angle)).toFloat(),
                CardArt.back(), PILE_SCALE, layer = i,
                fromX = from?.let { it.side.stepX * HAND_DISTANCE + it.shiftX } ?: 0f,
                fromZ = from?.let { it.side.stepZ * HAND_DISTANCE + it.shiftZ } ?: 0f,
            )
        }

        // A called play, turned over in front of whoever laid it down.
        val reveal = view.reveal
        if (view.phase == BluffPhase.PENALTY && reveal != null) {
            val place = seats.getOrNull(reveal.claimer)
            if (place != null) scene.row(place, REVEAL_DISTANCE, reveal.cards.map(CardArt::face), REVEAL_STEP, REVEAL_SCALE, 0f, 0f)
        }
        return scene.cards
    }

    /** Where a seat's creeper stands: in front of the hand, on its owner's right. */
    private fun creeperSpot(place: SeatPlace): Pair<Float, Float> {
        val side = place.side
        val rightX = side.stepZ.toFloat()
        val rightZ = -side.stepX.toFloat()
        return side.stepX * CREEPER_DISTANCE + rightX * CREEPER_ALONG + place.shiftX to
            side.stepZ * CREEPER_DISTANCE + rightZ * CREEPER_ALONG + place.shiftZ
    }

    /** A card to show which rank is called for. */
    fun called(rank: Rank): PlayingCard = PlayingCard.Regular(rank, Suit.HEARTS)

    fun sorted(cards: List<PlayingCard>): List<PlayingCard> = cards.sortedWith(DDZ_ORDER.reversed())

    fun rankName(rank: Rank): Component = Component.translatable("tukuai.bluff.rank.${rank.name.lowercase()}")

    private fun name(table: ClientTable, seat: Int): Component = TableScreen.seatName(table.snapshot.seats.getOrNull(seat)?.occupant, seat)

    /** What is called for and the round, then whose turn it is, who takes the penalty, or who won. */
    override fun displayLines(table: ClientTable): List<Component> {
        val view = table.view as? BluffView ?: return emptyList()
        val winner = view.winner
        if (winner != null) return listOf(Component.translatable("tukuai.bluff.won", name(table, winner)))
        val first = Component.translatable("tukuai.bluff.calling", rankName(view.target), view.round)
        val second = when (view.phase) {
            BluffPhase.PENALTY -> Component.translatable(penaltyKey(view), name(table, view.reveal!!.loser))
            else -> Component.translatable("tukuai.table.turn", name(table, view.turn))
        }
        return listOf(first, second)
    }

    fun penaltyKey(view: BluffView) = if (view.rules.penalty == BluffPenalty.FUSE) "tukuai.bluff.must_light" else "tukuai.bluff.must_heart"

    override fun turnSeat(table: ClientTable): Int? = (table.view as? BluffView)?.toAct

    // ---- Sounds and particles ------------------------------------------------------------------

    /** A lighting that has hissed but not yet gone off, at the table at [pos]. */
    private class Fuse(val pos: BlockPos, val seat: Int, val boom: Boolean, val at: Long)

    private val fuses = ArrayList<Fuse>()

    /** When each table's latest lighting was heard, and what it was, so the screen can keep the outcome back while it hisses. */
    private val lastLit = HashMap<BlockPos, Pair<Long, BluffEvent>>()

    /** The latest lighting at [table] while it is still hissing or just went off, with how many ticks ago it was lit. */
    fun recentLight(table: ClientTable): Pair<BluffEvent, Long>? {
        val (at, event) = lastLit[table.pos] ?: return null
        val age = ClientTables.ticks - at
        return if (age < HISS_TICKS + SHOW_TICKS) event to age else null
    }

    override fun onUpdate(previous: ClientTable?, table: ClientTable) {
        val view = table.view as? BluffView ?: return
        val before = (previous?.view as? BluffView)?.log
        val log = view.log
        if (before == null || before.size > log.size) return
        val pos = table.pos
        for (event in log.subList(before.size, log.size)) {
            when (event.kind) {
                BluffEventKind.DEAL -> if (event.seat >= 0 && view.round > 1) ClientTables.playAt(pos, SoundEvents.BOOK_PUT, 1f, 1f)
                BluffEventKind.PLAY -> ClientTables.playAt(pos, SoundEvents.BOOK_PAGE_TURN, 1f, 0.9f + event.count * 0.05f)
                BluffEventKind.CALL -> {
                    ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BASS, 1f, 0.7f)
                    if (event.flag) ClientTables.playAt(pos, SoundEvents.VILLAGER_NO, 0.8f, 1f) else ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_CHIME, 0.8f, 1f)
                }
                BluffEventKind.LIGHT -> {
                    ClientTables.playAt(pos, SoundEvents.CREEPER_PRIMED, 1f, 1f)
                    fuses += Fuse(pos, event.seat, event.flag, ClientTables.ticks)
                    lastLit[pos] = ClientTables.ticks to event
                }
            }
        }
    }

    /** Lit fuses go off, or fizzle, once they have hissed a while; a blown creeper leaves an explosion at its seat. */
    override fun tick(table: ClientTable) {
        val due = fuses.filter { it.pos == table.pos && ClientTables.ticks - it.at >= HISS_TICKS }
        if (due.isEmpty()) return
        fuses -= due.toSet()
        for (fuse in due) {
            val place = table.seatPlaces().getOrNull(fuse.seat)
            val (x, z) = place?.let(::creeperSpot) ?: (0f to 0f)
            if (fuse.boom) {
                ClientTables.playAt(table.pos, SoundEvents.GENERIC_EXPLODE, 0.8f, 1.1f)
                burst(table.pos, ParticleTypes.EXPLOSION, x, z, 3)
            } else {
                ClientTables.playAt(table.pos, SoundEvents.FIRE_EXTINGUISH, 0.6f, 1.6f)
                burst(table.pos, ParticleTypes.SMOKE, x, z, 6)
            }
        }
    }

    /** A few particles just above the table top, [x] and [z] blocks from its middle. */
    private fun burst(pos: BlockPos, particle: ParticleOptions, x: Float, z: Float, count: Int) {
        val level = Minecraft.getInstance().level ?: return
        repeat(count) {
            level.addParticle(
                particle,
                pos.x + 0.5 + x + (Math.random() - 0.5) * 0.3, pos.y + CardTableBlock.TOP_HEIGHT + 0.25, pos.z + 0.5 + z + (Math.random() - 0.5) * 0.3,
                0.0, 0.04, 0.0,
            )
        }
    }

    /** How long a lit fuse hisses before it goes off or fizzles, and how long the screen then says which. */
    const val HISS_TICKS = 30L
    const val SHOW_TICKS = 50L

    // Distances are from the middle of the 3-block table; its cloth reaches 1.375 out.
    private const val HAND_DISTANCE = 1.05f
    private const val HAND_STEP = 0.1f
    private const val HAND_SCALE = 0.7f
    private const val CREEPER_DISTANCE = 0.85f
    private const val CREEPER_ALONG = 0.36f
    private const val CREEPER_SCALE = 0.4f
    private const val TARGET_SCALE = 0.8f
    private const val PILE_DISTANCE = 0.6f
    private const val PILE_SHIFT = 0.015f
    private const val PILE_SCALE = 0.6f
    private const val REVEAL_DISTANCE = 0.68f
    private const val REVEAL_STEP = 0.16f
    private const val REVEAL_SCALE = 0.7f
}
