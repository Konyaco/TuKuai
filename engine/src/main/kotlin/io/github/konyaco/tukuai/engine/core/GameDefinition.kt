package io.github.konyaco.tukuai.engine.core

/** A seat at the table, counted from 0. */
typealias Seat = Int

/**
 * Everything the platform needs to know about one game. Dou Dizhu is the first;
 * another card or board game plugs in by implementing this interface and adding
 * itself to [GameRegistry]. Nothing in here may touch Minecraft.
 *
 * The game is a pure state machine: [start] deals from a seed and [apply] returns the
 * next state, so the same rules, seed and actions always replay the same game.
 *
 * @param R the rule settings, such as how the landlord is chosen
 * @param S the full state, including hidden cards; it never leaves the server
 * @param A one move by one seat
 * @param V what one seat is allowed to see; this is what clients and bots receive
 */
interface GameDefinition<R : Any, S : Any, A : Any, V : Any> {
    /** Stable id used in saves, packets and language keys, for example `doudizhu`. */
    val id: String

    val rulesCodec: WireCodec<R>
    val actionCodec: WireCodec<A>
    val viewCodec: WireCodec<V>

    /** Named rule sets a table can pick from; the first one is the default. */
    val presets: List<RulesPreset<R>>

    fun seatCount(rules: R): Int

    fun start(rules: R, seed: Long): S

    /** The seat that must act next, or null once the game is over. */
    fun toAct(state: S): Seat?

    /** @throws IllegalActionException when [seat] may not make [action] now */
    fun apply(state: S, seat: Seat, action: A): S

    /** What [seat] can see; a null seat is a spectator, who sees no hidden cards. */
    fun view(state: S, seat: Seat?): V

    /**
     * What [seat] sees with nothing hidden, used when watching a replay. Games that hide
     * cards should show them here; by default it is the same as [view].
     */
    fun openView(state: S, seat: Seat?): V = view(state, seat)

    fun result(state: S): GameResult?

    fun createBot(level: BotLevel, seed: Long): Bot<V, A>

    /**
     * The rule book for players new to the game. Given [rules], it starts with a section
     * on those rules in particular ([GuideSection.TABLE]).
     */
    fun guide(rules: R?): List<GuideSection> = emptyList()
}

data class RulesPreset<R>(val id: String, val rules: R)

/** Final points per seat; they always sum to zero. */
data class GameResult(val scores: List<Int>, val winners: Set<Seat>)

class IllegalActionException(message: String) : RuntimeException(message)

enum class BotLevel { EASY, NORMAL, HARD }

/** A computer player. It decides from the same view a human in that seat would get. */
fun interface Bot<V, A> {
    fun act(view: V): A
}

object GameRegistry {
    private val games = linkedMapOf<String, GameDefinition<*, *, *, *>>()

    fun register(game: GameDefinition<*, *, *, *>) {
        require(games.putIfAbsent(game.id, game) == null) { "Game ${game.id} is already registered" }
    }

    operator fun get(id: String): GameDefinition<*, *, *, *>? = games[id]

    val all: Collection<GameDefinition<*, *, *, *>> get() = games.values
}

/** Starts a match of any registered game from one of its presets, without knowing its types. */
@Suppress("UNCHECKED_CAST")
fun GameDefinition<*, *, *, *>.newMatch(presetId: String, seed: Long): MatchHandle {
    val game = this as GameDefinition<Any, Any, Any, Any>
    val preset = game.presets.firstOrNull { it.id == presetId } ?: game.presets.first()
    return Match(game, preset.rules, seed)
}

/** Rebuilds a match of any registered game from a replay, without knowing its types. */
@Suppress("UNCHECKED_CAST")
fun Replay.rebuildAny(stepCount: Int = steps.size): MatchHandle {
    val game = GameRegistry[gameId] as? GameDefinition<Any, Any, Any, Any>
        ?: throw WireFormatException("Unknown game $gameId")
    return rebuild(game, stepCount)
}

/** How many seats a table needs for one of this game's presets. */
@Suppress("UNCHECKED_CAST")
fun GameDefinition<*, *, *, *>.seatCount(presetId: String): Int {
    val game = this as GameDefinition<Any, Any, Any, Any>
    return game.seatCount((game.presets.firstOrNull { it.id == presetId } ?: game.presets.first()).rules)
}

/** The rule book of any registered game, starting with one preset's rules when [presetId] is given. */
@Suppress("UNCHECKED_CAST")
fun GameDefinition<*, *, *, *>.guide(presetId: String?): List<GuideSection> {
    val game = this as GameDefinition<Any, Any, Any, Any>
    val rules = presetId?.let { id -> (game.presets.firstOrNull { it.id == id } ?: game.presets.first()).rules }
    return game.guide(rules)
}

/** The id of the preset whose rules encode to [rules], or null if the rules were customised. */
@Suppress("UNCHECKED_CAST")
fun GameDefinition<*, *, *, *>.presetIdOf(rules: ByteArray): String? {
    val game = this as GameDefinition<Any, Any, Any, Any>
    return game.presets.firstOrNull { game.rulesCodec.encode(it.rules).contentEquals(rules) }?.id
}
