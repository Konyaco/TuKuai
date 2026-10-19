package io.github.konyaco.tukuai.engine.core

/**
 * One running game with its move list. The table in the world holds a [MatchHandle],
 * which only deals in bytes, so it never needs to know which game it is running.
 */
interface MatchHandle {
    val gameId: String
    val seatCount: Int
    val toAct: Seat?
    val result: GameResult?

    /** How many actions have been applied; clients use it to notice missed updates. */
    val step: Int

    /** Applies an encoded action from [seat]; returns why it was refused, or null if it was applied. */
    fun submit(seat: Seat, action: ByteArray): String?

    /** Lets a computer player choose and apply the move for [seat]. */
    fun playBot(seat: Seat, level: BotLevel)

    fun encodedView(seat: Seat?): ByteArray

    /** Like [encodedView] but with every hidden card shown, for replays. */
    fun encodedOpenView(seat: Seat?): ByteArray

    fun replay(players: List<String>): Replay
}

class Match<R : Any, S : Any, A : Any, V : Any>(
    val game: GameDefinition<R, S, A, V>,
    val rules: R,
    val seed: Long,
) : MatchHandle {
    var state: S = game.start(rules, seed)
        private set

    private val actions = mutableListOf<Pair<Seat, A>>()
    private val bots = HashMap<Pair<Seat, BotLevel>, Bot<V, A>>()

    override val gameId: String get() = game.id
    override val seatCount: Int = game.seatCount(rules)
    override val toAct: Seat? get() = game.toAct(state)
    override val result: GameResult? get() = game.result(state)
    override val step: Int get() = actions.size

    val history: List<Pair<Seat, A>> get() = actions

    fun apply(seat: Seat, action: A) {
        state = game.apply(state, seat, action)
        actions += seat to action
    }

    override fun submit(seat: Seat, action: ByteArray): String? {
        val decoded = try {
            game.actionCodec.decode(action)
        } catch (e: WireFormatException) {
            return e.message ?: "Bad action"
        }
        return try {
            apply(seat, decoded)
            null
        } catch (e: IllegalActionException) {
            e.message ?: "Illegal action"
        }
    }

    override fun playBot(seat: Seat, level: BotLevel) {
        val bot = bots.getOrPut(seat to level) { game.createBot(level, seed * 31 + seat) }
        val choice = bot.act(game.view(state, seat))
        try {
            apply(seat, choice)
        } catch (e: IllegalActionException) {
            // A bot bug must not freeze the table, so fall back to the simplest bot once.
            if (level == BotLevel.EASY) throw IllegalStateException("Easy bot made an illegal move", e)
            playBot(seat, BotLevel.EASY)
        }
    }

    fun view(seat: Seat?): V = game.view(state, seat)

    override fun encodedView(seat: Seat?): ByteArray = game.viewCodec.encode(view(seat))

    override fun encodedOpenView(seat: Seat?): ByteArray = game.viewCodec.encode(game.openView(state, seat))

    override fun replay(players: List<String>): Replay =
        Replay(
            gameId = game.id,
            rules = game.rulesCodec.encode(rules),
            seed = seed,
            players = players,
            steps = actions.map { (seat, action) -> ReplayStep(seat, game.actionCodec.encode(action)) },
        )
}

/**
 * A finished or unfinished game, stored as rules + seed + moves. Replaying those
 * through the game rebuilds every state, so a replay stays small.
 */
data class Replay(
    val gameId: String,
    val rules: ByteArray,
    val seed: Long,
    val players: List<String>,
    val steps: List<ReplayStep>,
) {
    fun encode(): ByteArray = CODEC.encode(this)

    /** Rebuilds the game after the first [stepCount] moves. */
    fun <R : Any, S : Any, A : Any, V : Any> rebuild(game: GameDefinition<R, S, A, V>, stepCount: Int = steps.size): Match<R, S, A, V> {
        require(game.id == gameId) { "Replay is for $gameId, not ${game.id}" }
        val match = Match(game, game.rulesCodec.decode(rules), seed)
        steps.take(stepCount).forEach { match.apply(it.seat, game.actionCodec.decode(it.action)) }
        return match
    }

    override fun equals(other: Any?): Boolean =
        other is Replay && gameId == other.gameId && rules.contentEquals(other.rules) && seed == other.seed &&
            players == other.players && steps == other.steps

    override fun hashCode(): Int = (gameId.hashCode() * 31 + seed.hashCode()) * 31 + steps.size

    companion object {
        private const val FORMAT_VERSION = 1

        val CODEC = object : WireCodec<Replay> {
            override fun write(writer: WireWriter, value: Replay) {
                writer.int(FORMAT_VERSION).string(value.gameId).bytes(value.rules).long(value.seed)
                    .list(value.players) { string(it) }
                    .list(value.steps) { int(it.seat).bytes(it.action) }
            }

            override fun read(reader: WireReader): Replay {
                val version = reader.int()
                if (version != FORMAT_VERSION) throw WireFormatException("Unknown replay version $version")
                return Replay(
                    gameId = reader.string(),
                    rules = reader.bytes(),
                    seed = reader.long(),
                    players = reader.list { string() },
                    steps = reader.list { ReplayStep(int(), bytes()) },
                )
            }
        }

        fun decode(data: ByteArray): Replay = CODEC.decode(data)
    }
}

class ReplayStep(val seat: Seat, val action: ByteArray) {
    override fun equals(other: Any?): Boolean = other is ReplayStep && seat == other.seat && action.contentEquals(other.action)

    override fun hashCode(): Int = seat * 31 + action.contentHashCode()
}
