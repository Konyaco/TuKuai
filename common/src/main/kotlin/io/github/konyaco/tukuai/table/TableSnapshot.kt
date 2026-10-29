package io.github.konyaco.tukuai.table

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter
import java.util.UUID

/** Who sits in a seat. An empty seat is null. */
sealed interface Occupant {
    data class Human(val uuid: UUID, val name: String) : Occupant

    data class Computer(val level: BotLevel) : Occupant

    /** Short text form used in saves. */
    fun save(): String = when (this) {
        is Human -> "p:$uuid:$name"
        is Computer -> "b:${level.name}"
    }

    companion object {
        fun load(text: String): Occupant? = when {
            text.startsWith("p:") -> text.substring(2).split(':', limit = 2).takeIf { it.size == 2 }
                ?.let { (id, name) -> runCatching { Human(UUID.fromString(id), name) }.getOrNull() }
            text.startsWith("b:") -> BotLevel.entries.firstOrNull { it.name == text.substring(2) }?.let(::Computer)
            else -> null
        }
    }
}

enum class TableStatus {
    /** No game yet; players pick seats and rules. */
    LOBBY,
    PLAYING,

    /** A game just ended; its result stays on the table until the next one starts. */
    FINISHED,
}

/** One seat as a client sees it. */
data class SeatInfo(
    val occupant: Occupant?,
    /** For a human: online and close to the table. */
    val present: Boolean,
    /** For a human: the computer is playing for them. */
    val auto: Boolean,
    /** For a human: how many games of this table's game they have played and won, anywhere in this world. */
    val games: Int = 0,
    val wins: Int = 0,
    /** Wants the next game to start; it starts once every player at the table does. */
    val ready: Boolean = false,
)

/** One line of a game's leaderboard: a player's record in this world. */
data class LeaderEntry(val name: String, val games: Int, val wins: Int, val points: Int)

/**
 * Everything one player needs to draw a table, sent whenever it changes. [view] is the
 * game's own view for the player's seat (or a spectator view), encoded by the game.
 */
data class TableSnapshot(
    val gameId: String,
    val presetId: String,
    val felt: Int,
    val turnSeconds: Int,
    val seats: List<SeatInfo>,
    val you: Seat?,
    val tally: List<Int>,
    val rounds: Int,
    val status: TableStatus,
    val step: Int,
    /** Ticks left before the computer plays for the seat to act, or -1 when nobody is timed. */
    val timerTicks: Int,
    val view: ByteArray?,
    /** Whether players who are not in the game may see every hand. */
    val spectators: Boolean = true,
    /** The seat of the player who may change [spectators], or null when anyone may. */
    val host: Seat? = null,
    /** How many games make a match; after that the points are settled. 0 for no limit. */
    val matchRounds: Int = 0,
    /** The match is over, by reaching [matchRounds] or because a player stopped it; [tally] is final. */
    val matchOver: Boolean = false,
    /** A game is on but every player in it has left, so it waits; anyone may sit down and play on or end it. */
    val paused: Boolean = false,
    /** The players with the most points at this game in this world, best first; sent only between games. */
    val leaderboard: List<LeaderEntry> = emptyList(),
    /** Whether the game may hurt its players in the world, see [io.github.konyaco.tukuai.engine.core.GameDefinition.mayHarm]. */
    val hurts: Boolean = false,
) {
    /** The people at the table who are here and playing for themselves; they decide together when to start. */
    val voters: List<Seat>
        get() = seats.indices.filter { seats[it].occupant is Occupant.Human && seats[it].present && !seats[it].auto }

    fun encode(): ByteArray = CODEC.encode(this)

    override fun equals(other: Any?): Boolean = other is TableSnapshot && encode().contentEquals(other.encode())

    override fun hashCode(): Int = encode().contentHashCode()

    companion object {
        private const val VERSION = 6

        fun decode(data: ByteArray): TableSnapshot = CODEC.decode(data)

        val CODEC = object : WireCodec<TableSnapshot> {
            override fun write(writer: WireWriter, value: TableSnapshot) {
                writer.int(VERSION).string(value.gameId).string(value.presetId).int(value.felt).int(value.turnSeconds)
                writer.list(value.seats) { seat ->
                    when (val o = seat.occupant) {
                        null -> int(0)
                        is Occupant.Human -> int(1).long(o.uuid.mostSignificantBits).long(o.uuid.leastSignificantBits).string(o.name)
                        is Occupant.Computer -> int(2).int(o.level.ordinal)
                    }
                    bool(seat.present).bool(seat.auto).int(seat.games).int(seat.wins).bool(seat.ready)
                }
                writer.int(value.you ?: -1).ints(value.tally).int(value.rounds).int(value.status.ordinal)
                    .int(value.step).int(value.timerTicks).nullable(value.view) { bytes(it) }
                    .bool(value.spectators).int(value.host ?: -1).int(value.matchRounds).bool(value.matchOver)
                    .bool(value.paused)
                writer.list(value.leaderboard) { string(it.name).int(it.games).int(it.wins).int(it.points) }
                writer.bool(value.hurts)
            }

            override fun read(reader: WireReader): TableSnapshot {
                val version = reader.int()
                if (version != VERSION) throw io.github.konyaco.tukuai.engine.core.WireFormatException("Unknown table version $version")
                return TableSnapshot(
                    gameId = reader.string(),
                    presetId = reader.string(),
                    felt = reader.int(),
                    turnSeconds = reader.int(),
                    seats = reader.list {
                        val occupant = when (int()) {
                            1 -> Occupant.Human(UUID(long(), long()), string())
                            2 -> Occupant.Computer(BotLevel.entries[int()])
                            else -> null
                        }
                        SeatInfo(occupant, bool(), bool(), int(), int(), bool())
                    },
                    you = reader.int().takeIf { it >= 0 },
                    tally = reader.ints(),
                    rounds = reader.int(),
                    status = TableStatus.entries[reader.int()],
                    step = reader.int(),
                    timerTicks = reader.int(),
                    view = reader.nullable { bytes() },
                    spectators = reader.bool(),
                    host = reader.int().takeIf { it >= 0 },
                    matchRounds = reader.int(),
                    matchOver = reader.bool(),
                    paused = reader.bool(),
                    leaderboard = reader.list { LeaderEntry(string(), int(), int(), int()) },
                    hurts = reader.bool(),
                )
            }
        }
    }
}

/** What a client asks the table to do. The meaning of `a`, `b` and `text` depends on the op. */
enum class TableOp {
    /**
     * Sit in seat `a`, or get back on it after standing up during a game. While a game is
     * paused because its players all left, anyone may take a seat; `a` = -1 takes the nearest.
     */
    SIT,

    /** Leave the seat; during a game the computer keeps playing for you. */
    STAND,

    /** Put a computer of level `b` in seat `a`; `a` = -1 fills every empty seat. */
    ADD_BOT,
    REMOVE_BOT,

    /** Switch to the rule preset named `text`. */
    PRESET,

    /** Set the turn time limit to `a` seconds; 0 turns it off. */
    TIMER,
    /**
     * Ready for the next game. It starts once every player at the table is ready; a player
     * alone with computers starts it straight away. After a finished match it starts a new one.
     */
    START,

    /** A move for your seat, encoded by the game. */
    ACT,

    /** `a` = 1 lets the computer play for you, 0 takes back control. */
    AUTO,

    /** Send me the replay of the game that just ended. */
    REPLAY,

    /** Switch to the game with the id `text`, with its first rule preset. */
    GAME,

    /** `a` = 1 lets players who are not in the game see every hand, 0 hides them. Only the host may. */
    SPECTATE,

    /** No longer ready, see [START]. */
    UNREADY,

    /** End the match now, for everyone at the table; a game being played is dropped. */
    END,

    /** Set how many games make a match to `a`; 0 means no limit. */
    ROUNDS,

    /** `a` = 1 lets a game that may hurt its players do so in the world, 0 keeps it on the table. Only the host may. */
    HURT,
}
