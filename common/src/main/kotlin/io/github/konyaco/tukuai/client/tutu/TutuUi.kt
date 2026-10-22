package io.github.konyaco.tukuai.client.tutu

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.SeatPlace
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.client.render.TableArt
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.tutu.Tutu
import io.github.konyaco.tukuai.engine.tutu.TutuCard
import io.github.konyaco.tukuai.engine.tutu.TutuCodecs
import io.github.konyaco.tukuai.engine.tutu.TutuColor
import io.github.konyaco.tukuai.engine.tutu.TutuEventKind
import io.github.konyaco.tukuai.engine.tutu.TutuFace
import io.github.konyaco.tukuai.engine.tutu.TutuKind
import io.github.konyaco.tukuai.engine.tutu.TutuView
import kotlin.math.cos
import kotlin.math.sin
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents

object TutuUi : GameUi {
    override val gameId: String = Tutu.id

    override fun decodeView(data: ByteArray): Any = TutuCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = TutuPanel(host)

    /**
     * As at UNO: each hand stands along its owner's edge of the table, the discard pile lies
     * on the display in the middle and the draw pile sits in the corner to the right of seat
     * 0. Planted cacti stick out of the draw pile at their depth, face up for all to see, and
     * lit TNT stands as little blocks in the opposite corner.
     */
    override fun scene(table: ClientTable, seats: List<SeatPlace>): List<TableCard> {
        val view = table.view as? TutuView ?: return emptyList()
        val cards = ArrayList<TableCard>()
        val first = seats.firstOrNull()?.side ?: return emptyList()
        val (pileX, pileZ) = pileSpot(first)
        val upX = -first.stepX.toFloat()
        val upZ = -first.stepZ.toFloat()

        // The draw pile: a short stack of backs, taller the more cards are left.
        val layers = (view.drawCount + PILE_CARDS_PER_LAYER - 1) / PILE_CARDS_PER_LAYER
        repeat(layers) { i ->
            cards += TableCard(pileX, pileZ, upX, upZ, CardArt.back(), PILE_SCALE, lift = i * LAYER_HEIGHT)
        }
        // Each cactus pokes out of the pile at its depth, towards the middle of the table.
        val cactus = CardArt.tutu(TutuFace(null, TutuKind.CACTUS))
        view.cacti.forEachIndexed { i, below ->
            val out = CACTUS_OUT + i * 0.02f
            cards += TableCard(
                pileX * (1 - out), pileZ * (1 - out), upX, upZ, cactus, PILE_SCALE * 0.8f,
                lift = below / PILE_CARDS_PER_LAYER.toFloat() * LAYER_HEIGHT + 0.001f,
            )
        }
        // Burning TNT.
        bombSpots(first, view.bombs).forEach { (x, z) ->
            cards += TableCard(
                x, z, upX, upZ, CardArt.TNT_SIDE, TNT_SCALE, cube = true, backSprite = CardArt.TNT_TOP,
                fromX = pileX, fromZ = pileZ,
            )
        }

        for (seat in 0 until view.players) {
            val place = seats.getOrNull(seat) ?: continue
            val revealed = view.revealedHands.getOrNull(seat)
            val hand: List<Pair<Int, Int>> = when {
                seat == view.seat && !view.finished -> sorted(view.hand).map { CardArt.tutu(it) to it.id }
                revealed != null -> sorted(revealed).map { CardArt.tutu(it) to it.id }
                else -> List(view.handCounts[seat]) { CardArt.back() to -1 }
            }
            val watched = revealed != null && seat != view.seat
            layHand(
                cards, place, hand, standing = !view.finished, bothSides = watched, fromX = pileX, fromZ = pileZ,
                mine = seat == view.seat && !view.finished,
            )
        }

        // The top of the discard pile, each card a little turned, as if tossed there.
        val playedBy = HashMap<TutuCard, Seat>()
        val named = HashMap<TutuCard, TutuColor>()
        for (event in view.log) {
            if (event.kind != TutuEventKind.PLAY) continue
            playedBy[event.card!!] = event.seat
            event.color?.let { named[event.card!!] = it }
        }
        val buried = view.discardCount - view.discardTop.size
        view.discardTop.forEachIndexed { i, card ->
            val depth = buried + i
            val angle = (((depth * 7919) % 41) - 20) * TOSS_DEGREES / 20f
            val radians = Math.toRadians(angle.toDouble())
            val tossX = -first.stepX * cos(radians).toFloat() + first.stepZ * sin(radians).toFloat()
            val tossZ = -first.stepZ * cos(radians).toFloat() - first.stepX * sin(radians).toFloat()
            val from = playedBy[card]?.let { seats.getOrNull(it) }
            cards += TableCard(
                ((depth * 31) % 7 - 3) * TOSS_SHIFT,
                ((depth * 17) % 7 - 3) * TOSS_SHIFT,
                tossX, tossZ, CardArt.tutu(view.shownFace(card), named[card]), DISCARD_SCALE, layer = i,
                lift = TableArt.DISPLAY_HEIGHT + 0.002f,
                fromX = from?.let { it.side.stepX * HAND_DISTANCE + it.shiftX } ?: pileX,
                fromZ = from?.let { it.side.stepZ * HAND_DISTANCE + it.shiftZ } ?: pileZ,
                key = card.id,
            )
        }
        return cards
    }

    private fun pileSpot(first: Direction): Pair<Float, Float> {
        val corner = first.clockWise
        return (first.stepX + corner.stepX) * PILE_DISTANCE to (first.stepZ + corner.stepZ) * PILE_DISTANCE
    }

    /** Where each burning TNT stands: in a row in the corner across from the draw pile. */
    private fun bombSpots(first: Direction, count: Int): List<Pair<Float, Float>> {
        val corner = first.counterClockWise
        val along = first.clockWise
        return List(count) { i ->
            val shift = (i - (count - 1) / 2f) * TNT_GAP
            (first.stepX + corner.stepX) * PILE_DISTANCE + along.stepX * shift to
                (first.stepZ + corner.stepZ) * PILE_DISTANCE + along.stepZ * shift
        }
    }

    private fun layHand(
        cards: MutableList<TableCard>,
        place: SeatPlace,
        hand: List<Pair<Int, Int>>,
        standing: Boolean,
        bothSides: Boolean,
        fromX: Float,
        fromZ: Float,
        mine: Boolean,
    ) {
        if (hand.isEmpty()) return
        val side = place.side
        val upX = -side.stepX.toFloat()
        val upZ = -side.stepZ.toFloat()
        val rightX = -upZ
        val rightZ = upX
        val step = if (hand.size <= 1) 0f else minOf(HAND_STEP, HAND_WIDTH / (hand.size - 1))
        val total = step * (hand.size - 1)
        val cx = side.stepX * HAND_DISTANCE + place.shiftX
        val cz = side.stepZ * HAND_DISTANCE + place.shiftZ
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
                backSprite = if (bothSides && standing) sprite else -1, mine = mine,
            )
        }
    }

    /** Cards grouped by colour, wild cards last, numbers before specials. */
    fun sorted(cards: List<TutuCard>): List<TutuCard> =
        cards.sortedWith(compareBy<TutuCard>({ it.face.color?.ordinal ?: TutuColor.entries.size }, { it.face.kind.ordinal }, { it.face.number }, { it.id }))

    /** The colour to follow and which way play goes, then whose turn it is or who won. */
    override fun displayLines(table: ClientTable): List<Component> {
        val view = table.view as? TutuView ?: return emptyList()
        val settlement = view.settlement
        if (settlement != null) return listOf(wonLine(table, settlement.winners))
        val turn = TableScreen.seatName(table.snapshot.seats.getOrNull(view.turn)?.occupant)
        return listOf(stateLine(view), Component.translatable("tukuai.table.turn", turn))
    }

    fun wonLine(table: ClientTable, winners: Set<Seat>): Component {
        val separator = Component.translatable("tukuai.tutu.separator").string
        val names = winners.joinToString(separator) { TableScreen.seatName(table.snapshot.seats.getOrNull(it)?.occupant).string }
        return Component.translatable("tukuai.tutu.won", names)
    }

    /** For example "Purple · counterclockwise": the colour to follow and which way play goes, seen from above. */
    fun stateLine(view: TutuView): Component =
        colorName(view.color).copy().append(" · ")
            .append(Component.translatable(if (view.direction > 0) "tukuai.uno.ccw" else "tukuai.uno.cw"))

    fun colorName(color: TutuColor): Component =
        Component.translatable("tukuai.tutu.color.${color.name.lowercase()}").withColor(COLORS.getValue(color))

    override fun turnSeat(table: ClientTable): Int? {
        val view = table.view as? TutuView ?: return null
        return if (view.finished) null else view.turn
    }

    // ---- Sounds and particles ------------------------------------------------------------------

    override fun onUpdate(previous: ClientTable?, table: ClientTable) {
        val view = table.view as? TutuView ?: return
        val before = (previous?.view as? TutuView)?.log
        val log = view.log
        if (before == null || before.size > log.size || log.subList(0, before.size) != before) return
        val pos = table.pos
        for (event in log.subList(before.size, log.size)) {
            when (event.kind) {
                TutuEventKind.PLAY -> {
                    if (event.card!!.face.kind == TutuKind.EGG) {
                        ClientTables.playAt(pos, SoundEvents.CHICKEN_EGG, 1f, 1f)
                        burst(pos, ParticleTypes.POOF, 0.5f, 0.5f, 8)
                    }
                    when (event.face!!.kind) {
                        TutuKind.NUMBER -> ClientTables.playAt(pos, SoundEvents.BOOK_PAGE_TURN, 1f, 1.1f)
                        TutuKind.SNOWBALL -> ClientTables.playAt(pos, SoundEvents.SNOWBALL_THROW, 1f, 1f)
                        TutuKind.TRIDENT -> ClientTables.playAt(pos, SoundEvents.TRIDENT_RETURN, 0.8f, 1f)
                        TutuKind.SHOVEL -> ClientTables.playAt(pos, SoundEvents.SHOVEL_FLATTEN, 1f, 1f)
                        TutuKind.LIGHTNING -> ClientTables.playAt(pos, SoundEvents.LIGHTNING_BOLT_THUNDER, 0.25f, 1.6f)
                        TutuKind.TNT -> ClientTables.playAt(pos, SoundEvents.TNT_PRIMED, 1f, 1f)
                        TutuKind.CACTUS -> ClientTables.playAt(pos, SoundEvents.GRASS_PLACE, 1f, 0.9f)
                        TutuKind.EGG -> {}
                    }
                }
                TutuEventKind.DRAW -> ClientTables.playAt(pos, SoundEvents.ITEM_PICKUP, 0.5f, 0.8f + minOf(event.count, 6) * 0.1f)
                TutuEventKind.SKIPPED -> ClientTables.playAt(pos, SoundEvents.POWDER_SNOW_STEP, 1f, 1f)
                TutuEventKind.CACTUS -> {
                    ClientTables.playAt(pos, SoundEvents.PLAYER_HURT_SWEET_BERRY_BUSH, 1f, 1f)
                    table.seatPlaces().getOrNull(event.seat)?.let {
                        burst(pos, ParticleTypes.DAMAGE_INDICATOR, it.side.stepX * HAND_DISTANCE + it.shiftX, it.side.stepZ * HAND_DISTANCE + it.shiftZ, 4)
                    }
                }
                TutuEventKind.BOOM -> {
                    ClientTables.playAt(pos, SoundEvents.GENERIC_EXPLODE, 0.7f, 1.3f)
                    facing(table)?.let { first ->
                        val (x, z) = bombSpots(first, 1).single()
                        burst(pos, ParticleTypes.EXPLOSION, x, z, 2)
                    }
                }
                TutuEventKind.PASS -> {}
            }
        }
    }

    /** Smoke curling up from each burning TNT. */
    override fun tick(table: ClientTable) {
        val view = table.view as? TutuView ?: return
        if (view.bombs == 0 || view.finished || ClientTables.ticks % 4 != 0L) return
        val first = facing(table) ?: return
        val level = Minecraft.getInstance().level ?: return
        for ((x, z) in bombSpots(first, view.bombs)) {
            level.addParticle(
                ParticleTypes.SMOKE,
                table.pos.x + 0.5 + x, table.pos.y + CardTableBlock.TOP_HEIGHT + TNT_HEIGHT + 0.02, table.pos.z + 0.5 + z,
                0.0, 0.02, 0.0,
            )
        }
    }

    private fun facing(table: ClientTable): Direction? {
        val state = Minecraft.getInstance().level?.getBlockState(table.pos) ?: return null
        return if (state.hasProperty(CardTableBlock.FACING)) state.getValue(CardTableBlock.FACING) else null
    }

    /** A few particles just above the table top, [x] and [z] blocks from its middle. */
    private fun burst(pos: BlockPos, particle: ParticleOptions, x: Float, z: Float, count: Int) {
        val level = Minecraft.getInstance().level ?: return
        repeat(count) {
            level.addParticle(
                particle,
                pos.x + 0.5 + x + (Math.random() - 0.5) * 0.3, pos.y + CardTableBlock.TOP_HEIGHT + 0.2, pos.z + 0.5 + z + (Math.random() - 0.5) * 0.3,
                0.0, 0.05, 0.0,
            )
        }
    }

    val COLORS: Map<TutuColor, Int> = mapOf(
        TutuColor.YELLOW to 0xFFD23C,
        TutuColor.PURPLE to 0xC08CFF,
        TutuColor.BLUE to 0x5A96FF,
        TutuColor.GREEN to 0x5AC85A,
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
    private const val LAYER_HEIGHT = TableArt.CARD_THICKNESS * PILE_SCALE

    /** How far a planted cactus sticks out of the pile towards the middle, as a share of the pile's distance. */
    private const val CACTUS_OUT = 0.22f

    /** TNT blocks are this wide as a share of a card at scale 1, and this far apart. */
    private const val TNT_SCALE = 0.55f
    private const val TNT_GAP = 0.2f

    /** Height of a TNT block, for the smoke to start from its top. */
    private const val TNT_HEIGHT = 0.3 * TNT_SCALE
}
