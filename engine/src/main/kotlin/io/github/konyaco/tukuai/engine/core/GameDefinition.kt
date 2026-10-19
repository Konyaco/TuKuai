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

    fun result(state: S): GameResult?

    fun createBot(level: BotLevel, seed: Long): Bot<V, A>
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
