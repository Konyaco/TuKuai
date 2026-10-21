package io.github.konyaco.tukuai.table

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.SeatEntity
import io.github.konyaco.tukuai.content.SeatSpot
import io.github.konyaco.tukuai.content.TableSeats
import io.github.konyaco.tukuai.content.StoolBlock
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
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput
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
    BlockEntity(TuKuaiContent.CARD_TABLE_ENTITY, pos, state) {

    var gameId: String = DouDizhu.id
        private set
    var presetId: String = DouDizhu.presets.first().id
        private set
    var felt: DyeColor = DyeColor.GREEN
        private set
    private var turnSeconds = 30

    /** Whether players who are not in the game see every hand; only the [host] may change it. */
    private var spectators = true

    private var seats: MutableList<Occupant?> = MutableList(3) { null }
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

    /** A computer's move being worked out on [BOT_THREADS], for [thinkingFor] at [thinkingStep]. */
    private var thinking: CompletableFuture<ByteArray>? = null
    private var thinkingFor: MatchHandle? = null
    private var thinkingStep = -1

    /** File name of the last finished game's replay, if it was saved since the world loaded. */
    private var lastReplay: String? = null
    private var turnTicks = 0
    private var actDelay = 0

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
        val vehicle = (level as? ServerLevel)?.server?.playerList?.getPlayer(occupant.uuid)?.vehicle
        return vehicle is SeatEntity && vehicle.table == worldPosition
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

    /** Where seat [seat] is at this table; play goes counterclockwise seen from above. */
    fun spotOf(seat: Seat): SeatSpot =
        TableSeats.spots(blockState.getValue(CardTableBlock.FACING), table.radius, seats.size)[seat]

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
        // Players in seats that go away stand up.
        val server = (level as? ServerLevel)?.server
        for (seat in count until seats.size) {
            val occupant = seats[seat] as? Occupant.Human ?: continue
            server?.playerList?.getPlayer(occupant.uuid)?.let(::dismount)
        }
        seats = MutableList(count) { seats.getOrNull(it) }
        // The players who stay keep their state; only the scores start over.
        auto = BooleanArray(count) { auto.getOrElse(it) { false } }
        present = BooleanArray(count) { present.getOrElse(it) { false } }
        absentChecks = IntArray(count) { absentChecks.getOrElse(it) { 0 } }
        timeouts = IntArray(count) { timeouts.getOrElse(it) { 0 } }
        ready = BooleanArray(count)
        tally = IntArray(count)
        rounds = 0
        matchOver = false
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
            thinking?.cancel(false)
            thinking = null
            match = null
            dealtIn = emptyMap()
            auto.fill(false)
        }
        matchOver = rounds > 0
        changed()
        level.playSound(null, worldPosition, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, 1f, 0.8f)
    }

    /** Right-click on the table: take the free seat on the side the player stands on, if there is one. */
    fun onUse(player: ServerPlayer) {
        val level = level as? ServerLevel ?: return
        sentRevision.remove(player.uuid)
        val mine = seatOf(player.uuid)
        if (mine != null) {
            mount(level, player, mine)
            return
        }
        if (paused) {
            nearestSeat(player, seats.indices.filter { seats[it] is Occupant.Human })?.let { takeOver(level, player, it) }
            return
        }
        if (!idle) return
        nearestSeat(player, seats.indices.filter { seats[it] == null })?.let { takeSeat(level, player, it) }
    }

    private fun nearestSeat(player: ServerPlayer, among: List<Seat>): Seat? =
        among.minByOrNull { seatPosition(it).distanceToSqr(player.position()) }

    /**
     * Sitting on the stool on [side] of the table, [along] blocks to the right of its middle:
     * takes the nearest seat on that side if it is free, or goes back to it. False if that
     * side has no seat, so the stool is just a stool.
     */
    fun sitAt(player: ServerPlayer, side: Direction, along: Int = 0): Boolean {
        val level = level as? ServerLevel ?: return false
        val seat = seats.indices.filter { spotOf(it).side == side }.minByOrNull { abs(spotOf(it).along - along) } ?: return false
        sentRevision.remove(player.uuid)
        val occupant = seats[seat]
        if (occupant is Occupant.Human && occupant.uuid == player.uuid) {
            auto[seat] = false
            changed()
            mount(level, player, seat)
        } else if (occupant == null && idle) {
            takeSeat(level, player, seat)
        } else if (occupant is Occupant.Human && paused && seatOf(player.uuid) == null) {
            takeOver(level, player, seat)
        }
        return true
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
        lastStep = -1
    }

    /**
     * On the stool just outside the table's edge in front of the seat, or on the floor there
     * when there is no stool.
     */
    private fun seatPosition(seat: Seat): Vec3 {
        val spot = spotOf(seat)
        val side = spot.side
        val stool = worldPosition.relative(side, table.radius + 1).relative(spot.right, spot.along)
        if (level?.getBlockState(stool)?.block is StoolBlock) {
            return Vec3(stool.x + 0.5, stool.y + StoolBlock.SEAT_HEIGHT - SeatEntity.HEIGHT, stool.z + 0.5)
        }
        val distance = table.halfWidth + SEAT_GAP
        return Vec3(
            worldPosition.x + 0.5 + side.stepX * distance + spot.right.stepX * spot.along,
            worldPosition.y.toDouble(),
            worldPosition.z + 0.5 + side.stepZ * distance + spot.right.stepZ * spot.along,
        )
    }

    private fun mount(level: ServerLevel, player: ServerPlayer, seat: Seat) {
        val current = player.vehicle
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

    private fun placeSeat(entity: SeatEntity, seat: Seat) {
        val p = seatPosition(seat)
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
                val occupant = seats.getOrNull(seat)
                if (seat == -1 && mine == null && paused) {
                    // Take whichever seat is nearest, as right-clicking the table does.
                    nearestSeat(player, seats.indices.filter { seats[it] is Occupant.Human })?.let { takeOver(level, player, it) }
                } else if (seat !in seats.indices) {
                    return
                } else if (occupant == null && idle) {
                    takeSeat(level, player, seat)
                } else if (occupant is Occupant.Human && occupant.uuid != player.uuid && mine == null && paused) {
                    takeOver(level, player, seat)
                } else if (occupant is Occupant.Human && occupant.uuid == player.uuid) {
                    auto[seat] = false
                    changed()
                    mount(level, player, seat)
                }
            }
            TableOp.STAND -> {
                if (mine == null) return
                if (idle) seats[mine] = null else auto[mine] = true
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
                if (mine == null || m.result != null || m.toAct != mine) {
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
        lastStep = -1
        changed()
        level.playSound(null, worldPosition, SoundEvents.BOOK_PUT, SoundSource.BLOCKS, 1f, 1f)
    }

    // ---- Ticking -------------------------------------------------------------------------------

    fun serverTick(level: ServerLevel) {
        ticks++
        if (!partsChecked) checkParts(level)
        if (isRemoved) return
        if (ticks % 20 == 0L) checkPresence(level)
        val m = match
        if (m != null && m.result == null) {
            if (paused) pause() else driveTurn(level, m)
        }
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
        thinking?.cancel(false)
        thinking = null
        if (lastStep != -1) {
            lastStep = -1
            changed()
        }
    }

    private fun driveTurn(level: ServerLevel, m: MatchHandle) {
        val seat = m.toAct ?: return
        if (m.step != lastStep) resetTurnClock(level, m)
        turnTicks++
        when (val occupant = seats.getOrNull(seat)) {
            is Occupant.Computer -> driveComputer(level, m, seat, occupant.level)
            is Occupant.Human -> {
                val away = auto[seat] || !present[seat]
                if (away) {
                    if (turnTicks >= actDelay) {
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

    /**
     * The computer thinks on a worker thread while it "considers" its move, so a hard
     * computer never holds up the server tick. If thinking takes far too long, a quick
     * NORMAL move is played instead.
     */
    private fun driveComputer(level: ServerLevel, m: MatchHandle, seat: Seat, botLevel: BotLevel) {
        var plan = thinking
        if (plan == null || thinkingFor !== m || thinkingStep != m.step) {
            plan?.cancel(false)
            val think = m.prepareBot(seat, botLevel)
            plan = CompletableFuture.supplyAsync({ think() }, BOT_THREADS)
            thinking = plan
            thinkingFor = m
            thinkingStep = m.step
        }
        if (turnTicks < actDelay) return
        if (plan.isDone) {
            thinking = null
            val action = try {
                plan.join()
            } catch (e: Exception) {
                TuKuai.LOGGER.warn("A computer player failed to choose a move", e)
                null
            }
            if (action == null || m.submit(seat, action) != null) m.playBot(seat, BotLevel.EASY)
            afterStep(level, m)
        } else if (turnTicks >= actDelay + MAX_THINK_TICKS) {
            plan.cancel(false)
            thinking = null
            m.playBot(seat, BotLevel.NORMAL)
            afterStep(level, m)
        }
    }

    private fun resetTurnClock(level: ServerLevel, m: MatchHandle) {
        lastStep = m.step
        turnTicks = 0
        // Somewhere between a bit quicker and a bit slower than the configured average.
        actDelay = (ServerSettings.botDelaySeconds * 20 * (0.65 + level.random.nextDouble() * 0.7)).toInt()
    }

    private fun afterStep(level: ServerLevel, m: MatchHandle) {
        resetTurnClock(level, m)
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
            val vehicle = level.server.playerList.getPlayer(occupant.uuid)?.vehicle
            if (auto[seat] && vehicle is SeatEntity && vehicle.table == worldPosition) auto[seat] = false
        }
        level.playSound(null, worldPosition, SoundEvents.PLAYER_LEVELUP, SoundSource.BLOCKS, 0.7f, 1f)
        StatsStore.record(level.server, gameId, seats, result.scores, result.winners)
        lastReplay = if (ServerSettings.saveReplays) ReplayStore.save(level.server, m.replay(playerNames())) else null
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
        val actor = m?.toAct
        val waiting = paused
        val timed = actor != null && m.result == null && !waiting && seats.getOrNull(actor) is Occupant.Human &&
            !auto[actor] && present[actor] && turnSeconds > 0
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
        output.putString("arrivals", arrivals.filter { seatOf(it) != null }.joinToString(","))
        output.putInt("rounds", rounds)
        output.putInt("match_rounds", matchRounds)
        output.putInt("match_over", if (matchOver) 1 else 0)
        output.putIntArray("tally", tally)
        output.putInt("seat_count", seats.size)
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
        arrivals.clear()
        input.getStringOr("arrivals", "").split(',').mapNotNullTo(arrivals) { runCatching { UUID.fromString(it) }.getOrNull() }
        val count = input.getIntOr("seat_count", game.seatCount(presetId))
        seats = MutableList(count) { Occupant.load(input.getStringOr("seat_$it", "")) }
        auto = BooleanArray(count)
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
        lastStep = -1
        revision++
    }

    companion object {
        /** How far outside the table's edge a seat is, in blocks. */
        const val SEAT_GAP = 0.35

        /** Players farther than this cannot change the table. */
        const val REACH = 8.0

        /** A seated player farther than this counts as away, and the computer plays for them. */
        const val PRESENCE_RANGE = 12.0

        /** Players this close are kept up to date so they can see the cards on the table. */
        const val SYNC_RANGE = 40.0

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
