package io.github.konyaco.tukuai.table

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.ChairBlock
import io.github.konyaco.tukuai.content.SeatEntity
import io.github.konyaco.tukuai.content.SeatSpot
import io.github.konyaco.tukuai.content.TableSeats
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameDefinition
import io.github.konyaco.tukuai.engine.core.GameRegistry
import io.github.konyaco.tukuai.engine.core.MatchHandle
import io.github.konyaco.tukuai.engine.core.Replay
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.core.Watching
import io.github.konyaco.tukuai.engine.core.newMatch
import io.github.konyaco.tukuai.engine.core.rebuildAny
import io.github.konyaco.tukuai.engine.core.seatCount
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import io.github.konyaco.tukuai.network.ReplayPayload
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.network.TableSyncPayload
import io.github.konyaco.tukuai.platform.Services
import java.util.Base64
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import kotlin.math.abs
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The server side of a card table: who sits where, the rules, the running match and
 * the points so far. It runs computer turns, plays for players who time out or walk
 * away, and sends each nearby player the table as they are allowed to see it.
 *
 * The match is saved as a replay (rules, seed and moves), so a game survives a restart.
 */
class CardTableBlockEntity(pos: BlockPos, state: BlockState) :
    CompatBlockEntity(TuKuaiContent.CARD_TABLE_ENTITY, pos, state) {

    var gameId: String = DouDizhu.id
        private set
    var presetId: String = DouDizhu.presets.first().id
        private set
    var felt: DyeColor = DyeColor.GREEN
        private set
    private var turnSeconds = 30

    /** Whether players who are not in the game see every hand; only the [host] may change it. */
    private var spectators = true

    /** How much a game that may hurt its players does so in the world, see [TableSnapshot.harm]; only the [host] may change it. */
    private var harm = TableSnapshot.HARM_LETHAL

    /** Seats to hurt, each with the tick it happens on, once the creeper on the table has hissed a while. */
    private val harms = ArrayList<Pair<Seat, Long>>()

    private var seats: MutableList<Occupant?> = MutableList(3) { null }

    /**
     * The place round the table of each seat, as [TableSeats] numbers them, in seat order;
     * see [placeList]. Players choose them, so a game for three may leave any side empty.
     */
    private var places: List<Int> = emptyList()

    /** Players who stood up from the table during a game: the computer plays for them until they sit down again. */
    private var stood = BooleanArray(3)
    private var auto = BooleanArray(3)
    private var present = BooleanArray(3)
    private var absentChecks = IntArray(3)
    private var timeouts = IntArray(3)
    private var tally = IntArray(3)
    private var rounds = 0
    private var match: MatchHandle? = null

    /** How many games make a match, see [matchOver]; 0 for no limit. */
    private var matchRounds = DEFAULT_MATCH_ROUNDS

    /**
     * The match has been played out, or a player stopped it: [tally] is final, and
     * the next game starts a new match from nothing.
     */
    private var matchOver = false

    /** Who is ready for the next game, see [TableOp.START]. */
    private var ready = BooleanArray(3)

    /** The seat each player was dealt into when [match] started; they see nothing else until it is over. */
    private var dealtIn: Map<UUID, Seat> = emptyMap()

    /** Players in the order they sat down, earliest first, to tell who the [host] is. */
    private val arrivals = ArrayList<UUID>()

    /** Goes up on every change a client should see; each player is sent the table once per revision. */
    private var revision = 0
    private val sentRevision = HashMap<UUID, Int>()

    private var ticks = 0L
    private var lastStep = -1

    /** Set once all nine parts of the table are known to be in place, see [checkParts]. */
    private var partsChecked = false

    /** The computers' moves being worked out on [BOT_THREADS], by seat. */
    private val thinking = HashMap<Seat, Plan>()

    /** A computer's move being worked out for [match] as it stood at [step]. */
    private class Plan(val future: CompletableFuture<ByteArray>, val match: MatchHandle, val step: Int)

    /** File name of the last finished game's replay, if it was saved since the world loaded. */
    private var lastReplay: String? = null
    private var turnTicks = 0

    /** How long each seat's computer waits before it acts, in ticks from the start of the turn. */
    private var actDelays = IntArray(0)

    /** The seats that could act when the turn last moved on, see [startTurn]. */
    private var lastActors: List<Seat> = emptyList()

    private val game: GameDefinition<*, *, *, *> get() = GameRegistry[gameId] ?: DouDizhu

    val status: TableStatus
        get() = when {
            match == null -> TableStatus.LOBBY
            match!!.result == null -> TableStatus.PLAYING
            else -> TableStatus.FINISHED
        }

    private val idle: Boolean get() = status != TableStatus.PLAYING

    /**
     * A game is on but every player in it has stood up or gone away, so it waits instead of
     * the computers playing it out by themselves. Anyone may then take a seat, see [takeOver].
     */
    private val paused: Boolean
        get() = status == TableStatus.PLAYING && seats.indices.none(::atTable)

    /**
     * Whether the player in [seat] still takes part: here, and either playing for themselves
     * or letting the computer play while they stay in their seat.
     */
    private fun atTable(seat: Seat): Boolean {
        val occupant = seats[seat] as? Occupant.Human ?: return false
        if (!present[seat]) return false
        if (!auto[seat]) return true
        return inPlace(seat, (level as? ServerLevel)?.server?.playerList?.getPlayer(occupant.uuid))
    }

    /**
     * Whether the player in [seat] is at their place: on its chair, or where it has none,
     * standing by the table and not having stood up from it.
     */
    private fun inPlace(seat: Seat, player: ServerPlayer?): Boolean {
        if (player == null) return false
        val vehicle = player.vehicle
        if (vehicle is SeatEntity && vehicle.table == worldPosition) return true
        if (stood[seat] || vehicle != null || hasChair(spotOf(seat))) return false
        return player.position().distanceToSqr(standPosition(spotOf(seat))) < STAND_RANGE * STAND_RANGE
    }

    private val center: Vec3 get() = Vec3.atCenterOf(worldPosition)

    fun seatOf(uuid: UUID): Seat? = seats.indexOfFirst { it is Occupant.Human && it.uuid == uuid }.takeIf { it >= 0 }

    /**
     * The player who sat down first of those at the table now, who decides whether others
     * may watch every hand; null when no player sits here, and then anyone may.
     */
    val host: UUID?
        get() = arrivals.firstOrNull { seatOf(it) != null } ?: seats.firstNotNullOfOrNull { (it as? Occupant.Human)?.uuid }

    /** The seat whose cards [uuid] may see, or null when they watch; see [Watching.seatFor]. */
    private fun viewSeatOf(uuid: UUID): Seat? = Watching.seatFor(dealtIn[uuid], seatOf(uuid), status != TableStatus.PLAYING)

    /** The 3x3 or the 5x5 table this is the middle of. */
    val table: CardTableBlock get() = blockState.block as? CardTableBlock ?: TuKuaiContent.CARD_TABLE

    private val facing: Direction get() = blockState.getValue(CardTableBlock.FACING)

    /** How many places there are round the table for this game, see [TableSeats.placeCount]. */
    private fun placeTotal(): Int = TableSeats.placeCount(table.radius, seats.size)

    /** Each seat's place, in seat order and so counterclockwise round the table. */
    private fun placeList(): List<Int> = TableSeats.orDefault(table.radius, seats.size, places)

    /** The seat at [place], or null when nobody may sit there now. */
    private fun seatAt(place: Int): Seat? = placeList().indexOf(place).takeIf { it >= 0 }

    /** Where [place] is, as it is now or would be with a seat there. */
    private fun spotAt(place: Int): SeatSpot {
        val used = placeList()
        return TableSeats.spotOf(facing, placeTotal(), place, if (place in used) used else used + place)
    }

    /** Where seat [seat] is at this table; play goes counterclockwise seen from above. */
    fun spotOf(seat: Seat): SeatSpot = spotAt(placeList()[seat])

    /** The side of the table seat [seat] sits on. */
    fun sideOf(seat: Seat): Direction = spotOf(seat).side

    /** Whether this table has room for every seat of [presetId] of [game]. */
    private fun fits(game: GameDefinition<*, *, *, *>, presetId: String): Boolean = game.seatCount(presetId) <= table.maxSeats

    // ---- Changes -------------------------------------------------------------------------------

    private fun changed() {
        revision++
        setChanged()
    }

    fun dye(color: DyeColor) {
        felt = color
        changed()
        level?.playSound(null, worldPosition, SoundEvents.WOOD_PLACE, SoundSource.BLOCKS, 1f, 1f)
    }

    private fun resizeSeats(count: Int) {
        if (count == seats.size) return
        val radius = table.radius
        val before = placeList()
        val beforeTotal = placeTotal()
        val total = TableSeats.placeCount(radius, count)
        // Players stay first, then computers, then empty seats; the rest go away and their players stand up.
        val keep = seats.indices.sortedBy { rank(seats[it]) }.take(count)
        val server = (level as? ServerLevel)?.server
        for (seat in seats.indices - keep.toSet()) {
            val occupant = seats[seat] as? Occupant.Human ?: continue
            server?.playerList?.getPlayer(occupant.uuid)?.let(::dismount)
        }
        // Each keeps their side of the table, between one place a side and two.
        val carried = keep.map { seat ->
            val p = before[seat]
            when {
                total == beforeTotal -> p
                total > beforeTotal -> p * 2
                else -> p / 2
            }
        }
        val taken = if (carried.distinct().size == carried.size) carried else TableSeats.defaultPlaces(radius, count).take(keep.size)
        // New seats take the free places the table would use first.
        val free = (TableSeats.defaultPlaces(radius, count) + (0 until total)).distinct().filter { it !in taken }
        val entries = keep.zip(taken) + free.take(count - keep.size).map { null to it }
        rebuild(entries)
        // The players who stay keep their state; only the scores start over.
        ready = BooleanArray(count)
        tally = IntArray(count)
        rounds = 0
        matchOver = false
    }

    /** Who keeps a seat when there are fewer: players, then computers, then nobody. */
    private fun rank(occupant: Occupant?): Int = when (occupant) {
        is Occupant.Human -> 0
        is Occupant.Computer -> 1
        null -> 2
    }

    /**
     * Sets the seats to [entries]: each an old seat with all that goes with it, or null for a
     * new empty one, and its place. They are put in the order of their places, so play goes
     * round the table. A finished game's cards lie by the old seats, so it is cleared away.
     */
    private fun rebuild(entries: List<Pair<Seat?, Int>>) {
        val sorted = entries.sortedBy { it.second }
        fun <T> pick(old: Seat?, from: (Seat) -> T, empty: T): T = if (old == null) empty else from(old)
        val oldSeats = seats
        val oldAuto = auto
        val oldPresent = present
        val oldAbsent = absentChecks
        val oldTimeouts = timeouts
        val oldReady = ready
        val oldTally = tally
        val oldStood = stood
        seats = sorted.map { (old, _) -> pick(old, { oldSeats.getOrNull(it) }, null) }.toMutableList()
        auto = BooleanArray(sorted.size) { pick(sorted[it].first, { s -> oldAuto.getOrElse(s) { false } }, false) }
        present = BooleanArray(sorted.size) { pick(sorted[it].first, { s -> oldPresent.getOrElse(s) { false } }, false) }
        absentChecks = IntArray(sorted.size) { pick(sorted[it].first, { s -> oldAbsent.getOrElse(s) { 0 } }, 0) }
        timeouts = IntArray(sorted.size) { pick(sorted[it].first, { s -> oldTimeouts.getOrElse(s) { 0 } }, 0) }
        ready = BooleanArray(sorted.size) { pick(sorted[it].first, { s -> oldReady.getOrElse(s) { false } }, false) }
        tally = IntArray(sorted.size) { pick(sorted[it].first, { s -> oldTally.getOrElse(s) { 0 } }, 0) }
        stood = BooleanArray(sorted.size) { pick(sorted[it].first, { s -> oldStood.getOrElse(s) { false } }, false) }
        places = sorted.map { it.second }
        if (status == TableStatus.FINISHED) {
            match = null
            dealtIn = emptyMap()
        }
        // Moving a seat can move others on its side of a big table, so whoever sits gets put right.
        val server = (level as? ServerLevel)?.server ?: return
        seats.forEachIndexed { seat, occupant ->
            val player = (occupant as? Occupant.Human)?.let { server.playerList.getPlayer(it.uuid) } ?: return@forEachIndexed
            val vehicle = player.vehicle
            if (vehicle is SeatEntity && vehicle.table == worldPosition) mount(player.level() as ServerLevel, player, seat)
        }
    }

    /**
     * Gives [place] a seat between games, so someone may sit there: [own] seat moves there
     * when given, or else an empty one. Null when neither can.
     */
    private fun seatFor(place: Int, own: Seat?): Seat? {
        seatAt(place)?.let { return it }
        if (!idle || place !in 0 until placeTotal()) return null
        val moving = own ?: seats.indices.firstOrNull { seats[it] == null } ?: return null
        val now = placeList()
        rebuild(seats.indices.map { it to if (it == moving) place else now[it] })
        return seatAt(place)
    }

    /**
     * The people at the table who are here and playing for themselves. They decide together
     * when the next game starts; one alone with computers decides alone.
     */
    private fun voters(): List<Seat> = seats.indices.filter { seats[it] is Occupant.Human && present[it] && !auto[it] }

    /** Seats or rules changed, so whoever was ready has to look again. */
    private fun lobbyChanged() {
        ready.fill(false)
        changed()
    }

    /** Starts the next game once everyone at the table is ready; a finished match starts over. */
    private fun startIfReady(level: ServerLevel) {
        if (!idle || seats.any { it == null } || ready.none { it }) return
        if (voters().any { !ready[it] }) return
        if (matchOver) {
            tally.fill(0)
            rounds = 0
            matchOver = false
        }
        start(level)
    }

    /** Ends the match for everyone; a game still being played is dropped. */
    private fun endMatch(level: ServerLevel) {
        ready.fill(false)
        if (status == TableStatus.PLAYING) {
            stopThinking()
            match = null
            dealtIn = emptyMap()
            auto.fill(false)
        }
        matchOver = rounds > 0
        changed()
        level.playSound(null, worldPosition, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, 1f, 0.8f)
    }

    /** Right-click on the table: take the free place on the side the player stands on, if there is one. */
    fun onUse(player: ServerPlayer) {
        val level = level as? ServerLevel ?: return
        sentRevision.remove(player.uuid)
        val mine = seatOf(player.uuid)
        if (mine != null) {
            stood[mine] = false
            mount(level, player, mine)
            return
        }
        if (paused) {
            nearestSeat(player, seats.indices.filter { seats[it] is Occupant.Human })?.let { takeOver(level, player, it) }
            return
        }
        if (!idle) return
        val anyFree = seats.any { it == null }
        val free = (0 until placeTotal()).filter { place -> seatAt(place)?.let { seats[it] == null } ?: anyFree }
        free.minByOrNull { standPosition(spotAt(it)).distanceToSqr(player.position()) }?.let { sitAtPlace(level, player, it) }
    }

    private fun nearestSeat(player: ServerPlayer, among: List<Seat>): Seat? =
        among.minByOrNull { standPosition(spotOf(it)).distanceToSqr(player.position()) }

    /**
     * Sitting on the chair on [side] of the table, [along] blocks to the right of its middle:
     * takes the nearest place on that side if it is free, or goes back to it. False if no seat
     * can be there, so the chair is just a chair.
     */
    fun sitAt(player: ServerPlayer, side: Direction, along: Int = 0): Boolean {
        val level = level as? ServerLevel ?: return false
        val place = (0 until placeTotal()).filter { spotAt(it).side == side }
            .minWithOrNull(compareBy<Int>({ abs(spotAt(it).along - along) }, { if (seatAt(it) != null) 0 else 1 })) ?: return false
        sentRevision.remove(player.uuid)
        // A place with a seat belongs to the table even when someone else sits there; one without is the table's only if a seat can move there.
        val seated = seatAt(place) != null
        return sitAtPlace(level, player, place) || seated
    }

    /**
     * Sits [player] at [place]: in the seat there, or between games in a seat that moves there,
     * their own or a free one. False if they may not.
     */
    private fun sitAtPlace(level: ServerLevel, player: ServerPlayer, place: Int): Boolean {
        val seat = seatAt(place) ?: seatFor(place, seatOf(player.uuid)) ?: return false
        return sitIn(level, player, seat)
    }

    /** Sits [player] in [seat], as [TableOp.SIT] does. False if they may not. */
    private fun sitIn(level: ServerLevel, player: ServerPlayer, seat: Seat): Boolean {
        val occupant = seats[seat]
        when {
            occupant is Occupant.Human && occupant.uuid == player.uuid -> {
                auto[seat] = false
                changed()
                mount(level, player, seat)
            }
            mayTake(player, seat) -> takeSeat(level, player, seat)
            occupant is Occupant.Human && paused && seatOf(player.uuid) == null -> takeOver(level, player, seat)
            else -> return false
        }
        return true
    }

    /**
     * Whether [player] may sit down in [seat] between games: it is free, a computer holds it,
     * or the player in it walked away. Changing players after a game needs no waiting.
     */
    private fun mayTake(player: ServerPlayer, seat: Seat): Boolean {
        if (!idle) return false
        return when (val occupant = seats[seat]) {
            null, is Occupant.Computer -> true
            is Occupant.Human -> occupant.uuid != player.uuid && !present[seat]
        }
    }

    private fun takeSeat(level: ServerLevel, player: ServerPlayer, seat: Seat) {
        seatOf(player.uuid)?.let { seats[it] = null }
        seats[seat] = Occupant.Human(player.uuid, player.plainTextName)
        arrivals.remove(player.uuid)
        arrivals.retainAll { seatOf(it) != null }
        arrivals += player.uuid
        present[seat] = true
        absentChecks[seat] = 0
        lobbyChanged()
        mount(level, player, seat)
    }

    /**
     * Sits [player] in [seat] of a [paused] game in place of whoever left it: they get its
     * cards and the game goes on with them, or they may end it.
     */
    private fun takeOver(level: ServerLevel, player: ServerPlayer, seat: Seat) {
        val gone = (seats[seat] as? Occupant.Human)?.uuid
        dealtIn = dealtIn.filterKeys { it != gone } + (player.uuid to seat)
        auto[seat] = false
        timeouts[seat] = 0
        takeSeat(level, player, seat)
        // Whoever is to act gets a full turn from now.
        restartTurn()
    }

    /** On the chair just outside the table's edge in front of the seat. */
    private fun chairSeatPosition(spot: SeatSpot): Vec3 {
        val chair = chairPos(spot)
        return Vec3(chair.x + 0.5, chair.y + ChairBlock.SEAT_HEIGHT - SeatEntity.HEIGHT, chair.z + 0.5)
    }

    /** On the floor just outside the table's edge at [spot], where a player stands when there is no chair. */
    private fun standPosition(spot: SeatSpot): Vec3 {
        val side = spot.side
        val distance = table.halfWidth + STAND_GAP
        return Vec3(
            worldPosition.x + 0.5 + side.stepX * distance + spot.right.stepX * spot.along,
            worldPosition.y.toDouble(),
            worldPosition.z + 0.5 + side.stepZ * distance + spot.right.stepZ * spot.along,
        )
    }

    private fun hasChair(spot: SeatSpot): Boolean = level?.getBlockState(chairPos(spot))?.block is ChairBlock

    /** Where a chair for [spot] stands: just outside the table's edge. */
    private fun chairPos(spot: SeatSpot): BlockPos = worldPosition.relative(spot.side, table.radius + 1).relative(spot.right, spot.along)

    /**
     * Turns every chair that could be a seat of this table to face it, so a chair is never
     * put the wrong way round at a table. Stools from older versions came back as chairs
     * facing north; this turns those too.
     */
    private fun faceChairs(level: ServerLevel) {
        for (side in Direction.Plane.HORIZONTAL) {
            for (along in -(table.radius - 1)..(table.radius - 1)) {
                val pos = chairPos(SeatSpot(side, along))
                if (!level.isLoaded(pos)) continue
                val state = level.getBlockState(pos)
                if (state.block is ChairBlock && state.getValue(ChairBlock.FACING) != side.opposite) {
                    level.setBlock(pos, state.setValue(ChairBlock.FACING, side.opposite), Block.UPDATE_ALL)
                }
            }
        }
    }

    /**
     * Puts [player] at [seat]'s place: on its chair, or where there is none, standing at the
     * table's edge facing its middle.
     */
    private fun mount(level: ServerLevel, player: ServerPlayer, seat: Seat) {
        stood[seat] = false
        val spot = spotOf(seat)
        val current = player.vehicle
        if (!hasChair(spot)) {
            if (player.isPassenger) player.stopRiding()
            stand(level, player, spot)
            return
        }
        if (current is SeatEntity && current.table == worldPosition) {
            placeSeat(current, seat)
            return
        }
        if (player.isPassenger) player.stopRiding()
        val entity = SeatEntity(TuKuaiContent.SEAT, level)
        entity.table = worldPosition
        placeSeat(entity, seat)
        if (level.addFreshEntity(entity) && !player.startRiding(entity)) entity.discard()
    }

    /** Moves [player] to stand at [spot] facing the middle of the table, unless something is in the way there. */
    private fun stand(level: ServerLevel, player: ServerPlayer, spot: SeatSpot) {
        val target = standPosition(spot)
        val box = player.boundingBox.move(target.subtract(player.position()))
        if (player.position().distanceToSqr(target) > 0.01 && level.noCollision(player, box)) {
            player.teleportTo(target.x, target.y, target.z)
        }
        player.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3(center.x, worldPosition.y + LOOK_HEIGHT, center.z))
    }

    private fun placeSeat(entity: SeatEntity, seat: Seat) {
        val p = chairSeatPosition(spotOf(seat))
        entity.setPos(p.x, p.y, p.z)
        entity.setYRot(sideOf(seat).opposite.toYRot())
    }

    private fun dismount(player: ServerPlayer) {
        val vehicle = player.vehicle
        if (vehicle is SeatEntity && vehicle.table == worldPosition) player.stopRiding()
    }

    // ---- Requests from players -----------------------------------------------------------------

    fun handle(player: ServerPlayer, request: TableActionPayload) {
        val level = level as? ServerLevel ?: return
        if (player.distanceToSqr(center) > REACH * REACH) return
        val mine = seatOf(player.uuid)
        when (request.op) {
            TableOp.SIT -> {
                val seat = request.a
                if (seat == -1 && mine == null && paused) {
                    // Take whichever seat is nearest, as right-clicking the table does.
                    nearestSeat(player, seats.indices.filter { seats[it] is Occupant.Human })?.let { takeOver(level, player, it) }
                } else if (seat in seats.indices) {
                    sitIn(level, player, seat)
                }
            }
            TableOp.SIT_AT -> sitAtPlace(level, player, request.a)
            TableOp.BOT_AT -> {
                if (!idle || !ServerSettings.allowBots) return
                val botLevel = BotLevel.entries.getOrNull(request.b) ?: return
                val seat = seatAt(request.a)?.takeIf { seats[it] == null } ?: seatFor(request.a, null) ?: return
                if (seats[seat] != null) return
                seats[seat] = Occupant.Computer(botLevel)
                lobbyChanged()
            }
            TableOp.STAND -> {
                if (mine == null) return
                if (idle) {
                    seats[mine] = null
                } else {
                    auto[mine] = true
                    stood[mine] = true
                }
                dismount(player)
                if (idle) lobbyChanged() else changed()
            }
            TableOp.ADD_BOT -> {
                if (!idle) return
                if (!ServerSettings.allowBots) {
                    player.sendOverlayMessage(Component.translatable("tukuai.table.no_bots"))
                    return
                }
                val botLevel = BotLevel.entries.getOrNull(request.b) ?: return
                val targets = if (request.a == -1) seats.indices.filter { seats[it] == null } else listOf(request.a)
                for (seat in targets) {
                    if (seat in seats.indices && seats[seat] == null) seats[seat] = Occupant.Computer(botLevel)
                }
                lobbyChanged()
            }
            TableOp.REMOVE_BOT -> {
                val seat = request.a
                if (idle && seat in seats.indices && seats[seat] is Occupant.Computer) {
                    seats[seat] = null
                    lobbyChanged()
                }
            }
            TableOp.PRESET -> {
                if (!idle || game.presets.none { it.id == request.text } || !fits(game, request.text)) return
                presetId = request.text
                resizeSeats(game.seatCount(presetId))
                if (match != null) match = null
                lobbyChanged()
            }
            TableOp.GAME -> {
                val next = GameRegistry[request.text] ?: return
                if (!idle || next.id == gameId) return
                gameId = next.id
                presetId = next.presets.firstOrNull { fits(next, it.id) }?.id ?: next.presets.first().id
                resizeSeats(next.seatCount(presetId))
                tally.fill(0)
                rounds = 0
                matchOver = false
                match = null
                lobbyChanged()
            }
            TableOp.TIMER -> {
                if (idle && request.a in TIMER_CHOICES) {
                    turnSeconds = request.a
                    lobbyChanged()
                }
            }
            TableOp.ROUNDS -> {
                if (idle && request.a in ROUND_CHOICES) {
                    matchRounds = request.a
                    // With fewer games to a match, it may be over already.
                    if (matchRounds in 1..rounds) matchOver = true
                    lobbyChanged()
                }
            }
            TableOp.START -> {
                if (!idle || mine == null || seats.any { it == null }) return
                ready[mine] = true
                changed()
                startIfReady(level)
            }
            TableOp.UNREADY -> {
                if (idle && mine != null && ready[mine]) {
                    ready[mine] = false
                    changed()
                }
            }
            TableOp.END -> {
                // Any one player in it may stop the match; nobody has to wait for the others to agree.
                if (mine == null || matchOver || (idle && rounds == 0)) return
                endMatch(level)
            }
            TableOp.ACT -> {
                val m = match ?: return
                if (mine == null || m.result != null || mine !in m.actors) {
                    sentRevision.remove(player.uuid)
                    return
                }
                val error = m.submit(mine, request.data)
                if (error != null) {
                    sentRevision.remove(player.uuid)
                    player.sendOverlayMessage(Component.translatable("tukuai.table.illegal", error))
                    return
                }
                timeouts[mine] = 0
                auto[mine] = false
                afterStep(level, m)
            }
            TableOp.AUTO -> {
                if (mine == null || idle) return
                auto[mine] = request.a != 0
                timeouts[mine] = 0
                changed()
            }
            TableOp.SPECTATE -> {
                val host = host
                if (!idle || (host != null && host != player.uuid)) return
                spectators = request.a != 0
                lobbyChanged()
            }
            TableOp.HURT -> {
                val host = host
                if (!idle || (host != null && host != player.uuid)) return
                harm = request.a.coerceIn(TableSnapshot.HARM_OFF, TableSnapshot.HARM_LETHAL)
                lobbyChanged()
            }
            TableOp.REPLAY -> {
                val m = match?.takeIf { it.result != null } ?: return
                Services.platform.sendToPlayer(player, ReplayPayload(lastReplay ?: "", m.replay(playerNames()).encode()))
            }
        }
    }

    private fun start(level: ServerLevel) {
        val m = game.newMatch(presetId, level.random.nextLong())
        resizeSeats(m.seatCount)
        match = m
        dealtIn = seats.indices.mapNotNull { seat -> (seats[seat] as? Occupant.Human)?.let { it.uuid to seat } }.toMap()
        auto.fill(false)
        timeouts.fill(0)
        ready = BooleanArray(m.seatCount)
        restartTurn()
        changed()
        level.playSound(null, worldPosition, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, 1f, 1f)
    }

    // ---- Ticking -------------------------------------------------------------------------------

    fun serverTick(level: ServerLevel) {
        ticks++
        if (!partsChecked) checkParts(level)
        if (isRemoved) return
        if (ticks % 20 == 0L) {
            checkPresence(level)
            faceChairs(level)
        }
        val m = match
        if (m != null && m.result == null) {
            if (paused) pause() else driveTurn(level, m)
        }
        hurtPlayers(level)
        sync(level)
    }

    /**
     * Tables saved before they were 3x3 load as just their middle block. Grow the other
     * parts where there is room, and move anyone already seated out to the new edge.
     * If something is in the way, the table drops as an item instead.
     */
    private fun checkParts(level: ServerLevel) {
        val state = blockState
        val table = table
        val missing = ArrayList<Int>()
        for (part in 0 until table.parts) {
            if (part == table.centerPart) continue
            val pos = table.posOf(worldPosition, part)
            if (!level.isLoaded(pos)) return
            if (!table.sameTable(level.getBlockState(pos), state, part)) missing += part
        }
        partsChecked = true
        if (missing.isEmpty()) return
        val blocked = missing.any { !level.getBlockState(table.posOf(worldPosition, it)).canBeReplaced() }
        if (blocked) {
            TuKuai.LOGGER.info("No room to make the card table at {} {}x{}, dropping it as an item", worldPosition, table.width, table.width)
            level.destroyBlock(worldPosition, true)
            return
        }
        for (part in missing) {
            level.setBlock(table.posOf(worldPosition, part), state.setValue(table.partProperty, part), Block.UPDATE_ALL)
        }
        val area = AABB(worldPosition).inflate(table.halfWidth + 1.0)
        for (entity in level.getEntitiesOfClass(SeatEntity::class.java, area) { it.table == worldPosition }) {
            val seat = entity.passengers.firstNotNullOfOrNull { seatOf(it.uuid) } ?: continue
            placeSeat(entity, seat)
        }
    }

    private fun isPresent(level: ServerLevel, uuid: UUID): Boolean {
        val player = level.server.playerList.getPlayer(uuid) ?: return false
        return player.level() == level && !player.hasDisconnected() && player.distanceToSqr(center) < PRESENCE_RANGE * PRESENCE_RANGE
    }

    /** Notices players who logged off or walked away. In the lobby their seat frees up after a while. */
    private fun checkPresence(level: ServerLevel) {
        var dirty = false
        for (seat in seats.indices) {
            val occupant = seats[seat] as? Occupant.Human ?: continue
            val here = isPresent(level, occupant.uuid)
            if (here != present[seat]) {
                present[seat] = here
                dirty = true
            }
            absentChecks[seat] = if (here) 0 else absentChecks[seat] + 1
            if (idle && absentChecks[seat] >= LOBBY_ABSENT_SECONDS) {
                seats[seat] = null
                ready.fill(false)
                dirty = true
            }
        }
        if (!dirty) return
        changed()
        // Someone who walked off no longer holds up the others.
        startIfReady(level)
    }

    /** Nothing moves while [paused]; the turn starts afresh once someone is back. */
    private fun pause() {
        stopThinking()
        if (lastStep != -1) {
            restartTurn()
            changed()
        }
    }

    private fun stopThinking() {
        thinking.values.forEach { it.future.cancel(false) }
        thinking.clear()
    }

    /** Makes the next tick start the turn clock afresh for whoever is to act. */
    private fun restartTurn() {
        lastStep = -1
        lastActors = emptyList()
    }

    /**
     * Moves every seat that may act now, see [MatchHandle.actors]: a computer once it has
     * thought, a player who is away through the stand-in, and one who ran out of time too.
     * Most games have one seat to act; where several decide at once, each move changes the
     * game, so who may still act is looked at again before every one.
     */
    private fun driveTurn(level: ServerLevel, m: MatchHandle) {
        val actors = m.actors
        if (actors.isEmpty()) return
        if (m.step != lastStep) startTurn(level, m)
        turnTicks++
        for (seat in thinking.keys - actors.toSet()) thinking.remove(seat)?.future?.cancel(false)
        for (seat in actors) {
            if (m.result != null || seat !in m.actors) continue
            val delay = actDelays.getOrElse(seat) { 0 }
            when (val occupant = seats.getOrNull(seat)) {
                is Occupant.Computer -> driveComputer(level, m, seat, occupant.level, delay)
                is Occupant.Human -> {
                    val away = auto[seat] || !present[seat]
                    if (away) {
                        if (turnTicks >= delay) {
                            m.playBot(seat, STAND_IN)
                            afterStep(level, m)
                        }
                    } else if (turnSeconds > 0 && turnTicks >= turnSeconds * 20) {
                        timeouts[seat]++
                        if (timeouts[seat] >= TIMEOUTS_BEFORE_AUTO) auto[seat] = true
                        m.playBot(seat, STAND_IN)
                        afterStep(level, m)
                    }
                }
                null -> {
                    m.playBot(seat, STAND_IN)
                    afterStep(level, m)
                }
            }
        }
    }

    /**
     * The computer thinks on a worker thread while it "considers" its move, so a hard
     * computer never holds up the server tick. If thinking takes far too long, a quick
     * NORMAL move is played instead. It acts [delay] ticks into the turn.
     */
    private fun driveComputer(level: ServerLevel, m: MatchHandle, seat: Seat, botLevel: BotLevel, delay: Int) {
        var plan = thinking[seat]
        if (plan == null || plan.match !== m || plan.step != m.step) {
            plan?.future?.cancel(false)
            val think = m.prepareBot(seat, botLevel)
            plan = Plan(CompletableFuture.supplyAsync({ think() }, BOT_THREADS), m, m.step)
            thinking[seat] = plan
        }
        if (turnTicks < delay) return
        if (plan.future.isDone) {
            thinking.remove(seat)
            val action = try {
                plan.future.join()
            } catch (e: Exception) {
                TuKuai.LOGGER.warn("A computer player failed to choose a move", e)
                null
            }
            if (action == null || m.submit(seat, action) != null) m.playBot(seat, BotLevel.EASY)
            afterStep(level, m)
        } else if (turnTicks >= delay + MAX_THINK_TICKS) {
            plan.future.cancel(false)
            thinking.remove(seat)
            m.playBot(seat, BotLevel.NORMAL)
            afterStep(level, m)
        }
    }

    /**
     * Starts the turn clock for whoever may act after the latest move. When several seats
     * decide at once and one of them just did, the rest keep the time they had: the clock
     * only starts afresh when the seats to act are not just fewer of the same.
     */
    private fun startTurn(level: ServerLevel, m: MatchHandle) {
        val actors = m.actors
        val fewer = actors.isNotEmpty() && actors.size < lastActors.size && lastActors.containsAll(actors)
        lastStep = m.step
        lastActors = actors
        if (fewer) return
        turnTicks = 0
        // Each somewhere between a bit quicker and a bit slower than the configured average.
        actDelays = IntArray(m.seatCount) { (ServerSettings.botDelaySeconds * 20 * (0.65 + level.random.nextDouble() * 0.7)).toInt() }
    }

    private fun afterStep(level: ServerLevel, m: MatchHandle) {
        val harmed = m.takeHarmed()
        if (harm != TableSnapshot.HARM_OFF && m.mayHarm) harmed.mapTo(harms) { it to ticks + HARM_DELAY }
        startTurn(level, m)
        changed()
        // Each client plays the sound for a move itself, since only the game's client side knows what kind of move it was.
        val result = m.result ?: return
        result.scores.forEachIndexed { seat, points -> if (seat < tally.size) tally[seat] += points }
        rounds++
        if (matchRounds in 1..rounds) matchOver = true
        // Whoever let the computer play while staying in their seat is back in charge for the next
        // game, and so has a say in when it starts; those who stood up are not.
        for (seat in seats.indices) {
            val occupant = seats[seat] as? Occupant.Human ?: continue
            if (auto[seat] && inPlace(seat, level.server.playerList.getPlayer(occupant.uuid))) auto[seat] = false
        }
        level.playSound(null, worldPosition, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.7f, 1f)
        StatsStore.record(level.server, gameId, seats, result.scores, result.winners)
        lastReplay = if (ServerSettings.saveReplays) ReplayStore.save(level.server, m.replay(playerNames())) else null
    }

    /**
     * Hurts the players whose turn has come, as if the creeper had blown up in their face: to
     * death, or with light harm by up to [LIGHT_HARM] but never below half a heart. Someone who
     * has left the table is spared.
     */
    private fun hurtPlayers(level: ServerLevel) {
        if (harms.isEmpty()) return
        val due = harms.filter { it.second <= ticks }
        harms -= due.toSet()
        for ((seat, _) in due) {
            val occupant = seats.getOrNull(seat) as? Occupant.Human ?: continue
            val player = level.server.playerList.getPlayer(occupant.uuid) ?: continue
            if (!inPlace(seat, player)) continue
            val sources = player.damageSources()
            if (harm == TableSnapshot.HARM_LETHAL) {
                player.hurtOnServer(level, sources.explosion(null, null), LETHAL_HARM)
            } else {
                // Explosions hit softer on easy difficulty; light harm takes the same on any.
                val amount = minOf(LIGHT_HARM, player.health - 1f)
                if (amount > 0f) player.hurtOnServer(level, sources.generic(), amount)
            }
        }
    }

    private fun playerNames(): List<String> = seats.map {
        when (it) {
            is Occupant.Human -> it.name
            is Occupant.Computer -> "bot:${it.level.name.lowercase()}"
            null -> ""
        }
    }

    // ---- Sending the table to players ----------------------------------------------------------

    private fun sync(level: ServerLevel) {
        val c = center
        var watcherView: ByteArray? = null
        var watcherDone = false
        val leaders by lazy { leaderboard(level) }
        val nearby = HashSet<UUID>()
        for (player in level.players()) {
            if (player.distanceToSqr(c) > SYNC_RANGE * SYNC_RANGE) continue
            nearby += player.uuid
            if (sentRevision[player.uuid] == revision) continue
            sentRevision[player.uuid] = revision
            val seat = viewSeatOf(player.uuid)
            val m = match
            val view = when {
                m == null -> null
                seat != null -> m.encodedView(seat)
                else -> {
                    // Everyone watching gets the same view, so it is worked out once.
                    if (!watcherDone) {
                        watcherView = Watching.encodedView(m, null, null, spectators)
                        watcherDone = true
                    }
                    watcherView
                }
            }
            Services.platform.sendToPlayer(player, TableSyncPayload(worldPosition, snapshot(level, seat, view, leaders).encode()))
        }
        // Whoever walks off, leaves this level or logs off gets the whole table again when they are back.
        sentRevision.keys.retainAll(nearby)
    }

    /** The best players at this table's game, sent between games for the lobby's leaderboard. */
    private fun leaderboard(level: ServerLevel): List<LeaderEntry> {
        if (status == TableStatus.PLAYING) return emptyList()
        return StatsStore.all(level.server).values
            .mapNotNull { stats -> stats.games[gameId]?.takeIf { it.games > 0 }?.let { LeaderEntry(stats.name, it.games, it.wins, it.points) } }
            .sortedWith(compareByDescending<LeaderEntry> { it.points }.thenByDescending { it.wins }.thenBy { it.games })
            .take(LEADERBOARD_SIZE)
    }

    private fun snapshot(level: ServerLevel, you: Seat?, view: ByteArray?, leaders: List<LeaderEntry>): TableSnapshot {
        val m = match
        val waiting = paused
        val timed = m != null && m.result == null && !waiting && turnSeconds > 0 &&
            m.actors.any { seats.getOrNull(it) is Occupant.Human && !auto[it] && present[it] }
        return TableSnapshot(
            gameId = gameId,
            presetId = presetId,
            felt = felt.id,
            turnSeconds = turnSeconds,
            seats = seats.indices.map { seat ->
                val occupant = seats[seat]
                val record = (occupant as? Occupant.Human)?.let { StatsStore.of(level.server, it.uuid)?.games?.get(gameId) }
                SeatInfo(occupant, present[seat], auto[seat], record?.games ?: 0, record?.wins ?: 0, ready[seat])
            },
            you = you,
            tally = tally.toList(),
            rounds = rounds,
            status = status,
            step = m?.step ?: 0,
            timerTicks = if (timed) (turnSeconds * 20 - turnTicks).coerceAtLeast(0) else -1,
            view = view,
            spectators = spectators,
            harm = harm,
            places = placeList(),
            host = host?.let(::seatOf),
            matchRounds = matchRounds,
            matchOver = matchOver,
            paused = waiting,
            leaderboard = leaders,
        )
    }

    // ---- Saving --------------------------------------------------------------------------------

    override fun saveAdditional(output: ValueOutput) {
        super.saveAdditional(output)
        output.putString("game", gameId)
        output.putString("preset", presetId)
        output.putInt("felt", felt.id)
        output.putInt("timer", turnSeconds)
        output.putInt("spectators", if (spectators) 1 else 0)
        output.putInt("harm", harm)
        output.putString("arrivals", arrivals.filter { seatOf(it) != null }.joinToString(","))
        output.putInt("rounds", rounds)
        output.putInt("match_rounds", matchRounds)
        output.putInt("match_over", if (matchOver) 1 else 0)
        output.putIntArray("tally", tally)
        output.putInt("seat_count", seats.size)
        output.putIntArray("places", placeList().toIntArray())
        seats.forEachIndexed { i, occupant -> output.putString("seat_$i", occupant?.save() ?: "") }
        output.putIntArray("auto", IntArray(auto.size) { if (auto[it]) 1 else 0 })
        match?.let { output.putString("match", Base64.getEncoder().encodeToString(it.replay(playerNames()).encode())) }
        output.putString("dealt_in", dealtIn.entries.joinToString(",") { (uuid, seat) -> "$uuid=$seat" })
    }

    override fun loadAdditional(input: ValueInput) {
        super.loadAdditional(input)
        gameId = input.getStringOr("game", DouDizhu.id).takeIf { GameRegistry[it] != null } ?: DouDizhu.id
        presetId = input.getStringOr("preset", game.presets.first().id)
        felt = DyeColor.byId(input.getIntOr("felt", DyeColor.GREEN.id))
        turnSeconds = input.getIntOr("timer", 30).takeIf { it in TIMER_CHOICES } ?: 30
        spectators = input.getIntOr("spectators", 1) != 0
        harm = input.getIntOr("harm", TableSnapshot.HARM_LETHAL).coerceIn(TableSnapshot.HARM_OFF, TableSnapshot.HARM_LETHAL)
        arrivals.clear()
        input.getStringOr("arrivals", "").split(',').mapNotNullTo(arrivals) { runCatching { UUID.fromString(it) }.getOrNull() }
        val count = input.getIntOr("seat_count", game.seatCount(presetId))
        seats = MutableList(count) { Occupant.load(input.getStringOr("seat_$it", "")) }
        places = input.getIntArray("places").map { it.toList() }.orElse(emptyList())
        auto = BooleanArray(count)
        stood = BooleanArray(count)
        input.getIntArray("auto").ifPresent { saved -> saved.take(count).forEachIndexed { i, v -> auto[i] = v != 0 } }
        present = BooleanArray(count)
        absentChecks = IntArray(count)
        timeouts = IntArray(count)
        tally = IntArray(count)
        input.getIntArray("tally").ifPresent { saved -> saved.take(count).forEachIndexed { i, v -> tally[i] = v } }
        rounds = input.getIntOr("rounds", 0)
        matchRounds = input.getIntOr("match_rounds", DEFAULT_MATCH_ROUNDS).takeIf { it in ROUND_CHOICES } ?: DEFAULT_MATCH_ROUNDS
        matchOver = input.getIntOr("match_over", 0) != 0
        ready = BooleanArray(count)
        match = input.getString("match").map { encoded ->
            try {
                Replay.decode(Base64.getDecoder().decode(encoded)).rebuildAny().takeIf { it.seatCount == count }
            } catch (e: Exception) {
                TuKuai.LOGGER.warn("Could not restore the game at {}", worldPosition, e)
                null
            }
        }.orElse(null)
        dealtIn = input.getStringOr("dealt_in", "").split(',').mapNotNull { entry ->
            val (uuid, seat) = entry.split('=').takeIf { it.size == 2 } ?: return@mapNotNull null
            val id = runCatching { UUID.fromString(uuid) }.getOrNull() ?: return@mapNotNull null
            seat.toIntOrNull()?.takeIf { it in 0 until count }?.let { id to it }
        }.toMap()
        restartTurn()
        revision++
    }

    companion object {
        /** How far outside the table's edge a seat is, in blocks, for whoever films the table. */
        const val SEAT_GAP = 0.35

        /** How far outside the table's edge a player stands at a place without a chair, in blocks. */
        const val STAND_GAP = 0.4

        /** A player standing this close to their place without a chair is at the table, see [inPlace]. */
        const val STAND_RANGE = 3.0

        /** How high above the floor a player who comes to stand at the table looks, at the middle of its top. */
        const val LOOK_HEIGHT = 0.9

        /** Players farther than this cannot change the table. */
        const val REACH = 8.0

        /** A seated player farther than this counts as away, and the computer plays for them. */
        const val PRESENCE_RANGE = 12.0

        /** Players this close are kept up to date so they can see the cards on the table. */
        const val SYNC_RANGE = 40.0

        /** How much light harm takes, in health points: two hearts. */
        const val LIGHT_HARM = 4f

        /** More than any armour lets through: only a totem of undying saves them. */
        const val LETHAL_HARM = 1000f

        /** Ticks between a creeper being lit and it going off, as the clients show it; see BluffUi.HISS_TICKS. */
        const val HARM_DELAY = 30L

        const val LOBBY_ABSENT_SECONDS = 30

        /** Ten seconds past the usual delay before giving up on a slow computer. */
        const val MAX_THINK_TICKS = 200

        /** Shared by every table; computers rarely think at the same moment. */
        private val BOT_THREADS = Executors.newFixedThreadPool(2) { task ->
            Thread(task, "TuKuai computer player").apply { isDaemon = true }
        }
        const val TIMEOUTS_BEFORE_AUTO = 2
        val TIMER_CHOICES = listOf(0, 15, 30, 60)

        /** Games in a match: short and long ones, and no limit. Stopping after a while keeps a session from going on forever. */
        val ROUND_CHOICES = listOf(4, 8, 12, 16, 0)
        const val DEFAULT_MATCH_ROUNDS = 8

        const val LEADERBOARD_SIZE = 10

        /** The computer that plays for a player who is away or out of time. */
        val STAND_IN = BotLevel.NORMAL
    }
}
