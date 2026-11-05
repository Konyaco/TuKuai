package io.github.konyaco.tukuai.client.cave

import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.GamePanel
import io.github.konyaco.tukuai.client.GameUi
import io.github.konyaco.tukuai.client.PanelHost
import io.github.konyaco.tukuai.client.SeatPlace
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableParticles
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.client.render.TableArt
import io.github.konyaco.tukuai.engine.cave.Cave
import io.github.konyaco.tukuai.engine.cave.CaveCard
import io.github.konyaco.tukuai.engine.cave.CaveCodecs
import io.github.konyaco.tukuai.engine.cave.CaveDeck
import io.github.konyaco.tukuai.engine.cave.CaveEvent
import io.github.konyaco.tukuai.engine.cave.CaveEventKind
import io.github.konyaco.tukuai.engine.cave.CaveView
import io.github.konyaco.tukuai.engine.cave.Hazard
import io.github.konyaco.tukuai.engine.cave.Ore
import io.github.konyaco.tukuai.engine.cave.PathCard
import io.github.konyaco.tukuai.engine.core.Seat
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvents
import kotlin.math.roundToInt

object CaveUi : GameUi {
    override val gameId: String = Cave.id

    override fun decodeView(data: ByteArray): Any = CaveCodecs.VIEW.decode(data)

    override fun createPanel(host: PanelHost): GamePanel = CavePanel(host)

    /**
     * A round as it ended: the path with the card that ended it, and who was caught inside.
     * The table and the screen keep showing it for a moment before the next round, so that
     * everyone sees what happened.
     */
    class Ending(
        val at: Long,
        val round: Int,
        val path: List<PathCard>,
        /** The second card of a hazard, or null when everyone went back or the cards ran out. */
        val cause: CaveCard?,
        /**
         * Who was inside when it ended, with what they carried: dropped when a hazard ended it,
         * brought out when the cards ran out.
         */
        val caught: List<Pair<Seat, Int>>,
    )

    private val endings = HashMap<BlockPos, Ending>()

    /** The round that just ended at [table], while it is still being shown. */
    fun ending(table: ClientTable): Ending? = endings[table.pos]?.takeIf { ClientTables.ticks - it.at < LINGER_TICKS }

    /**
     * The path runs around the display in the middle, starting on the left in front of seat 0
     * and going round clockwise, every card facing seat 0. The deck lies face down on the
     * display. Each player has a chest on their right; while in the cave they also have a torch
     * before them, standing until they have chosen, and the emeralds they carry beside it.
     * Emeralds left over lie on the cards of the path.
     */
    override fun scene(table: ClientTable, seats: List<SeatPlace>): List<TableCard> {
        val view = table.view as? CaveView ?: return emptyList()
        val first = seats.firstOrNull()?.side ?: return emptyList()
        val frame = Frame(first)
        val cards = ArrayList<TableCard>()
        val ending = ending(table)
        val path = ending?.path ?: view.path

        path.forEachIndexed { i, step ->
            val (x, z) = frame.slot(i)
            cards += TableCard(
                x, z, frame.upX, frame.upZ, CardArt.cave(step.card), PATH_SCALE, layer = i / SLOTS,
                fromX = 0f, fromZ = 0f, key = KEY_PATH + CaveDeck.id(step.card),
            )
            for (k in 0 until minOf(step.left, MAX_LEFT)) {
                val gx = x + frame.rightX * ((k % 3 - 1) * GEM_STEP) + frame.upX * ((k / 3 - 0.5f) * GEM_STEP)
                val gz = z + frame.rightZ * ((k % 3 - 1) * GEM_STEP) + frame.upZ * ((k / 3 - 0.5f) * GEM_STEP)
                cards += gem(gx, gz, frame, KEY_LEFT + CaveDeck.id(step.card) * MAX_LEFT + k, x, z, GEM_ON_CARD)
            }
        }

        if (!view.finished) {
            val pile = minOf(view.deckLeft, PILE_MAX)
            for (i in 0 until pile) {
                cards += TableCard(0f, 0f, frame.upX, frame.upZ, CardArt.back(), DECK_SCALE, lift = TableArt.DISPLAY_HEIGHT + 0.002f + i * PILE_STEP)
            }
        }

        // Where new emeralds come from: the latest vein on the path.
        val lastVein = path.indexOfLast { it.card is CaveCard.Vein }
        val (fromX, fromZ) = if (lastVein >= 0) frame.slot(lastVein) else 0f to 0f
        for (seat in 0 until view.players) {
            val place = seats.getOrNull(seat) ?: continue
            val spot = SeatSpot(place)
            val (cx, cz) = spot.at(CHEST_DISTANCE, CHEST_ALONG)
            cards += TableCard(cx, cz, spot.upX, spot.upZ, CardArt.CHEST_SIDE, CHEST_SCALE, cube = true, backSprite = CardArt.CHEST_TOP)
            val inside = if (ending != null) ending.caught.any { it.first == seat } else view.inside[seat]
            if (!inside) continue
            val (tx, tz) = spot.at(TORCH_DISTANCE, TORCH_ALONG)
            val chosen = ending == null && view.decided[seat]
            cards += TableCard(
                tx, tz, spot.upX, spot.upZ, CardArt.TORCH, TORCH_SCALE, tilt = if (chosen) 0f else TORCH_TILT,
                backSprite = CardArt.TORCH, key = KEY_TORCH + seat,
            )
            val carried = if (ending != null) ending.caught.first { it.first == seat }.second else view.pack[seat]
            for (k in 0 until minOf(carried, MAX_PACK)) {
                val (gx, gz) = spot.at(PACK_DISTANCE - (k / 4) * PACK_STEP, PACK_ALONG + (k % 4) * PACK_STEP)
                cards += gem(gx, gz, frame, KEY_PACK + seat * MAX_PACK + k, fromX, fromZ, 0f)
            }
        }
        return cards
    }

    private fun gem(x: Float, z: Float, frame: Frame, key: Int, fromX: Float, fromZ: Float, lift: Float) = TableCard(
        x, z, frame.upX, frame.upZ, CardArt.EMERALD, GEM_SCALE, lift = lift, fromX = fromX, fromZ = fromZ,
        key = key, cube = true, backSprite = CardArt.EMERALD,
    )

    /** Directions on the table as seat 0 sees it, and the places of the path around the display. */
    private class Frame(first: Direction) {
        /** Away from seat 0, the way the cards on the path point. */
        val upX = -first.stepX.toFloat()
        val upZ = -first.stepZ.toFloat()

        /** To seat 0's right. */
        val rightX = -upZ
        val rightZ = upX

        /** The middle of the [i]th card of the path, going round again over the first ones past [SLOTS]. */
        fun slot(i: Int): Pair<Float, Float> {
            val (a, b) = PATH[i % SLOTS]
            return rightX * a + upX * b to rightZ * a + upZ * b
        }
    }

    /** Places in front of one seat: [at] goes [distance] out from the middle towards it and [along] to its owner's right. */
    private class SeatSpot(val place: SeatPlace) {
        val upX = -place.side.stepX.toFloat()
        val upZ = -place.side.stepZ.toFloat()

        fun at(distance: Float, along: Float): Pair<Float, Float> {
            val side = place.side
            return side.stepX * distance + side.stepZ * along + place.shiftX to side.stepZ * distance - side.stepX * along + place.shiftZ
        }
    }

    /** Where each card of the path lies, as (right, away) from seat 0: a ring of [SLOTS] around the display. */
    private val PATH: List<Pair<Float, Float>> = buildList {
        val r = RING
        val step = 2 * r / 6
        for (i in 0..6) add(-r + i * step to -r)
        for (i in 1..6) add(r to -r + i * step)
        for (i in 1..6) add(r - i * step to r)
        for (i in 1..5) add(-r to r - i * step)
    }

    /** What the display says: the round, then how many are inside and how likely the next card ends it, or who won. */
    override fun displayLines(table: ClientTable): List<Component> {
        val view = table.view as? CaveView ?: return emptyList()
        if (view.finished) return listOf(wonLine(table, view.winners))
        val round = Component.translatable("tukuai.cave.round", view.round, view.rules.rounds)
        val ending = ending(table)
        if (ending != null) return listOf(Component.translatable("tukuai.cave.round", ending.round, view.rules.rounds), shortEndLine(ending))
        val inside = view.inside.count { it }
        return listOf(round, Component.translatable("tukuai.cave.inside_danger", inside, percent(view.danger())))
    }

    fun percent(chance: Double): Int = (chance * 100).roundToInt()

    fun name(table: ClientTable, seat: Seat): Component = TableScreen.seatName(table.snapshot.seats.getOrNull(seat)?.occupant, seat)

    /** "Alex wins with 48 emeralds", or for a tie, "Alex and Sam tie with 48 emeralds". */
    fun wonLine(table: ClientTable, winners: Set<Seat>): Component {
        val view = table.view as? CaveView
        val best = winners.firstOrNull()?.let { view?.chest?.getOrNull(it) } ?: 0
        val names = winners.sorted().map { name(table, it) }
        return when (names.size) {
            0 -> Component.empty()
            1 -> Component.translatable("tukuai.cave.won", names[0], best)
            else -> Component.translatable("tukuai.cave.tied", names.reduce { a, b -> Component.translatable("tukuai.cave.and", a, b) }, best)
        }
    }

    /** How the round ended: which hazard came back, or that everyone went back or the cards ran out. */
    fun endLine(table: ClientTable, ending: Ending): Component {
        val cause = ending.cause as? CaveCard.Danger
        return when {
            cause != null -> Component.translatable("tukuai.cave.ended.${cause.hazard.name.lowercase()}", ending.caught.size)
            ending.caught.isNotEmpty() -> Component.translatable("tukuai.cave.ended.empty")
            else -> Component.translatable("tukuai.cave.ended.all_back")
        }
    }

    /** [endLine] short enough for the display. */
    private fun shortEndLine(ending: Ending): Component {
        val cause = ending.cause as? CaveCard.Danger
        return when {
            cause != null -> Component.translatable("tukuai.cave.display.ended", hazardName(cause.hazard))
            ending.caught.isNotEmpty() -> Component.translatable("tukuai.cave.display.empty")
            else -> Component.translatable("tukuai.cave.display.all_back")
        }
    }

    fun hazardName(hazard: Hazard): Component = Component.translatable("tukuai.cave.hazard.${hazard.name.lowercase()}")

    fun oreName(ore: Ore): Component = Component.translatable("tukuai.cave.ore.${ore.name.lowercase()}")

    /** Your own seat while you have to choose, so the display lights your side; otherwise whoever is still choosing. */
    override fun turnSeat(table: ClientTable): Int? {
        val view = table.view as? CaveView ?: return null
        val deciding = view.deciding
        return view.seat?.takeIf { it in deciding } ?: deciding.firstOrNull()
    }

    // ---- Sounds and particles ------------------------------------------------------------------

    override fun onUpdate(previous: ClientTable?, table: ClientTable) {
        val view = table.view as? CaveView ?: return
        val before = previous?.view as? CaveView ?: return
        if (before.log.size > view.log.size || before.round > view.round) return
        val news = view.log.subList(before.log.size, view.log.size)
        if (news.isEmpty()) return
        val pos = table.pos
        val next = news.indexOfFirst { it.kind == CaveEventKind.ROUND }
        if (next >= 0 || (view.finished && !before.finished)) {
            endings[pos] = ending(before, news.subList(0, if (next >= 0) next else news.size))
        }
        for (event in news) {
            when (event.kind) {
                CaveEventKind.ROUND -> if (event.round > 1) ClientTables.playAt(pos, SoundEvents.BOOK_PUT, 1f, 0.8f)
                CaveEventKind.REVEAL -> when (val card = event.card) {
                    is CaveCard.Vein -> {
                        ClientTables.playAt(pos, SoundEvents.STONE_BREAK, 1f, 0.9f + card.ore.ordinal * 0.1f)
                        if (card.ore >= Ore.GOLD) ClientTables.playAt(pos, SoundEvents.AMETHYST_BLOCK_CHIME, 1f, 1f + card.ore.ordinal * 0.1f)
                    }
                    is CaveCard.Danger -> ClientTables.playAt(pos, SoundEvents.NOTE_BLOCK_BASS, 1f, 0.6f)
                    null -> {}
                }
                CaveEventKind.BACK -> ClientTables.playAt(pos, SoundEvents.CHEST_CLOSE, 0.7f, 1.1f)
                CaveEventKind.COLLAPSE -> when ((event.card as? CaveCard.Danger)?.hazard) {
                    Hazard.LAVA -> ClientTables.playAt(pos, SoundEvents.BUCKET_EMPTY_LAVA, 1f, 1f)
                    Hazard.CREEPER -> ClientTables.playAt(pos, SoundEvents.CREEPER_PRIMED, 1f, 1f)
                    Hazard.GRAVEL -> ClientTables.playAt(pos, SoundEvents.GRAVEL_BREAK, 1f, 0.7f)
                    Hazard.SPIDER -> ClientTables.playAt(pos, SoundEvents.SPIDER_AMBIENT, 1f, 1.2f)
                    null -> {}
                }
                CaveEventKind.EMPTY -> ClientTables.playAt(pos, SoundEvents.CHEST_OPEN, 0.8f, 1f)
                CaveEventKind.LOST -> {}
            }
        }
    }

    /** How the round that [segment] ended looked, from the view [before] it. */
    private fun ending(before: CaveView, segment: List<CaveEvent>): Ending {
        var path = before.path
        // Whoever went back as the round ended took their share of the path first.
        val leaving = segment.takeWhile { it.kind == CaveEventKind.BACK }.size
        if (leaving > 0) path = path.map { it.copy(left = it.left % leaving) }
        for (event in segment) {
            if (event.kind != CaveEventKind.REVEAL) continue
            val card = event.card ?: continue
            path = path + PathCard(card, if (card is CaveCard.Vein) card.value - event.amount * event.count else 0)
        }
        val caught = segment.filter { it.kind == CaveEventKind.LOST }.map { it.seat to it.amount } +
            segment.dropWhile { it.kind != CaveEventKind.EMPTY }.filter { it.kind == CaveEventKind.BACK }.map { it.seat to it.amount }
        val cause = segment.firstOrNull { it.kind == CaveEventKind.COLLAPSE }?.card
        return Ending(ClientTables.ticks, before.round, path, cause, caught)
    }

    /**
     * While a round's end is shown: the hazard that ended it acts up on its card, a creeper
     * going off a moment after it hissed. Once the moment is over the table shows the next round.
     */
    override fun tick(table: ClientTable) {
        val ending = endings[table.pos] ?: return
        val age = ClientTables.ticks - ending.at
        val hazard = (ending.cause as? CaveCard.Danger)?.hazard
        val first = table.seatPlaces().firstOrNull()?.side
        val (x, z) = if (first != null) Frame(first).slot(maxOf(0, ending.path.size - 1)) else 0f to 0f
        when {
            age >= LINGER_TICKS -> {
                endings.remove(table.pos)
                table.redraw()
            }
            hazard == Hazard.CREEPER && age == HISS_TICKS -> {
                ClientTables.playAt(table.pos, SoundEvents.GENERIC_EXPLODE, 0.8f, 1.1f)
                TableParticles.burst(table.pos, ParticleTypes.EXPLOSION, x, z, 3)
            }
            hazard == Hazard.LAVA && age < HISS_TICKS && age % 3 == 0L -> TableParticles.burst(table.pos, ParticleTypes.LAVA, x, z, 1)
            hazard == Hazard.GRAVEL && age < HISS_TICKS && age % 4 == 0L -> TableParticles.burst(table.pos, ParticleTypes.POOF, x, z, 2, height = 0.5, rise = -0.05)
            hazard == Hazard.SPIDER && age == 4L -> TableParticles.burst(table.pos, ParticleTypes.SMOKE, x, z, 6)
            hazard == null && age == 1L && ending.caught.isEmpty() -> TableParticles.burst(table.pos, ParticleTypes.HAPPY_VILLAGER, 0f, 0f, 6)
        }
    }

    /** How long a round's end stays on the table, and when a creeper that ended it goes off, matching the harm it does. */
    const val LINGER_TICKS = 60L
    const val HISS_TICKS = 30L

    // Distances are from the middle of the table; the display covers 0.35 around it and the cloth of a 3x3 table reaches 1.375 out.
    private const val RING = 0.56f
    private const val SLOTS = 24
    private const val PATH_SCALE = 0.48f
    private const val DECK_SCALE = 0.55f
    private const val PILE_MAX = 8
    private const val PILE_STEP = 0.004f
    private const val GEM_SCALE = 0.1f
    private const val GEM_STEP = 0.04f
    private const val GEM_ON_CARD = 0.004f
    private const val MAX_LEFT = 8
    private const val MAX_PACK = 16
    private const val CHEST_DISTANCE = 0.92f
    private const val CHEST_ALONG = 0.36f
    private const val CHEST_SCALE = 0.42f
    private const val TORCH_DISTANCE = 0.95f
    private const val TORCH_ALONG = 0f
    private const val TORCH_SCALE = 0.5f
    private const val TORCH_TILT = 60f
    private const val PACK_DISTANCE = 1.0f
    private const val PACK_ALONG = -0.42f
    private const val PACK_STEP = 0.045f

    /** Keys that tell apart the path's cards, the emeralds and the torches, so each moves from where it was. */
    private const val KEY_PATH = 1000
    private const val KEY_LEFT = 2000
    private const val KEY_PACK = 3000
    private const val KEY_TORCH = 4000
}
