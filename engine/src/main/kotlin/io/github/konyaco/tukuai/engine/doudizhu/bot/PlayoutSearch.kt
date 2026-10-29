package io.github.konyaco.tukuai.engine.doudizhu.bot

import io.github.konyaco.tukuai.engine.doudizhu.DdzCards
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.doudizhu.DdzAction
import io.github.konyaco.tukuai.engine.doudizhu.DdzEventKind
import io.github.konyaco.tukuai.engine.doudizhu.DdzPhase
import io.github.konyaco.tukuai.engine.doudizhu.DdzState
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import kotlin.random.Random

/**
 * How the HARD computer picks a play. It guesses the hidden hands many times, plays each
 * guess to the end with NORMAL players in every seat, and keeps the candidate that earned
 * its seat the most points on average. Every candidate is tried on the same guesses, so
 * luck in the guessing does not favour one over another.
 *
 * Guesses use only what the seat could know: its own hand, the cards played so far, how
 * many cards each player holds, and the landlord's kitty cards, which everyone saw.
 */
class PlayoutSearch(private val random: Random, private val playouts: Int = PLAYOUTS) {

    /** Returns the best of [candidates]; on a tie the earlier one wins, so put the favourite first. */
    fun choose(view: DdzView, seat: Seat, candidates: List<DdzAction>): DdzAction {
        if (candidates.size <= 1) return candidates.first()
        val rounds = (playouts / candidates.size).coerceAtLeast(MIN_ROUNDS)
        val totals = LongArray(candidates.size)
        repeat(rounds) {
            val state = guess(view, seat) ?: return candidates.first()
            val seed = random.nextLong()
            candidates.forEachIndexed { i, action ->
                totals[i] += playOut(DouDizhu.apply(state, seat, action), seat, seed)
            }
        }
        return candidates[totals.indices.maxBy { totals[it] }]
    }

    /** A full state that matches everything [seat] has seen, with the unknown cards dealt at random. */
    fun guess(view: DdzView, seat: Seat): DdzState? {
        val landlord = view.landlord ?: return null
        val played = view.playedCards
        // The landlord's cards were shown to everyone; any the landlord has not played yet are
        // still in their hand. With two decks a card may be held twice, so copies are counted:
        // whichever copy the landlord played, they still hold the rest.
        val kittyLeft = if (landlord == seat) {
            emptyList()
        } else {
            val left = view.kitty.toMutableList()
            view.log.filter { it.seat == landlord && it.kind == DdzEventKind.PLAY }.forEach { e -> e.cards.forEach { left.remove(it) } }
            left
        }
        val pool = DdzCards.minus(DdzCards.deck(view.rules.decks), view.hand + played + kittyLeft)?.shuffled(random) ?: return null
        var next = 0
        val hands = List(view.rules.players) { s ->
            when {
                s == seat -> view.hand
                else -> {
                    val extra = if (s == landlord) kittyLeft else emptyList()
                    val need = view.handCounts[s] - extra.size
                    if (need < 0 || next + need > pool.size) return null
                    extra + pool.subList(next, next + need).also { next += need }
                }
            }
        }
        if (next != pool.size) return null
        val playCounts = List(view.rules.players) { s -> view.log.count { it.seat == s && it.kind == DdzEventKind.PLAY } }
        return DdzState(
            rules = view.rules,
            seed = 0,
            redeals = 0,
            hands = hands,
            kitty = view.kitty,
            phase = view.phase,
            turn = view.turn,
            firstBidder = view.firstBidder,
            bids = view.bids,
            landlord = landlord,
            bidMultiplier = view.bidMultiplier,
            doubles = view.doubles,
            lead = view.lead,
            bombs = view.bombs,
            playCounts = playCounts,
            log = view.log,
            settlement = null,
        )
    }

    /** Plays [start] to the end with NORMAL players and returns the points [seat] ends with. */
    private fun playOut(start: DdzState, seat: Seat, seed: Long): Long {
        var state = start
        val bots = Array(start.rules.players) { DdzBot(BotLevel.NORMAL, seed + it) }
        var moves = 0
        while (state.phase != DdzPhase.FINISHED && moves++ < MAX_MOVES) {
            val turn = state.turn
            state = try {
                DouDizhu.apply(state, turn, bots[turn].act(DouDizhu.view(state, turn)))
            } catch (e: IllegalActionException) {
                DouDizhu.apply(state, turn, DdzBot(BotLevel.EASY, seed).act(DouDizhu.view(state, turn)))
            }
        }
        return state.settlement?.scores?.get(seat)?.toLong() ?: 0L
    }

    companion object {
        /** Games played out per decision, shared among the candidates. */
        const val PLAYOUTS = 160
        private const val MIN_ROUNDS = 12
        private const val MAX_MOVES = 300
    }
}
