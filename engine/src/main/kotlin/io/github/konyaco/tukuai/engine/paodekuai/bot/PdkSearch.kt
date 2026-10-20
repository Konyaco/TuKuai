package io.github.konyaco.tukuai.engine.paodekuai.bot

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.paodekuai.PaoDeKuai
import io.github.konyaco.tukuai.engine.paodekuai.PdkAction
import io.github.konyaco.tukuai.engine.paodekuai.PdkEventKind
import io.github.konyaco.tukuai.engine.paodekuai.PdkState
import io.github.konyaco.tukuai.engine.paodekuai.PdkView
import kotlin.random.Random

/**
 * How the HARD computer picks a play, as in Dou Dizhu: it guesses the other hands many
 * times, plays each guess to the end with NORMAL players in every seat, and keeps the
 * candidate that earned its seat the most points on average. Every candidate is tried on
 * the same guesses. Guesses use only what the seat could know: its own hand, the cards
 * played so far, and how many cards each player holds.
 */
class PdkSearch(private val random: Random, private val playouts: Int = PLAYOUTS) {

    /** Returns the best of [candidates]; on a tie the earlier one wins, so put the favourite first. */
    fun choose(view: PdkView, seat: Seat, candidates: List<PdkAction>): PdkAction {
        if (candidates.size <= 1) return candidates.first()
        val rounds = (playouts / candidates.size).coerceAtLeast(MIN_ROUNDS)
        val totals = LongArray(candidates.size)
        repeat(rounds) {
            val state = guess(view, seat) ?: return candidates.first()
            val seed = random.nextLong()
            candidates.forEachIndexed { i, action ->
                totals[i] += playOut(PaoDeKuai.apply(state, seat, action), seat, seed)
            }
        }
        return candidates[totals.indices.maxBy { totals[it] }]
    }

    /** A full state that matches everything [seat] has seen, with the unknown cards dealt at random. */
    fun guess(view: PdkView, seat: Seat): PdkState? {
        val known = view.hand.toSet() + view.playedCards
        val pool = PaoDeKuai.deck(view.rules).filter { it !in known }.shuffled(random)
        var next = 0
        val hands = List(PaoDeKuai.SEATS) { s ->
            if (s == seat) {
                view.hand
            } else {
                val need = view.handCounts[s]
                if (next + need > pool.size) return null
                pool.subList(next, next + need).also { next += need }
            }
        }
        if (next != pool.size) return null
        return PdkState(
            rules = view.rules,
            seed = 0,
            hands = hands,
            starter = view.starter,
            turn = view.turn,
            lead = view.lead,
            playCounts = List(PaoDeKuai.SEATS) { s -> view.log.count { it.seat == s && it.kind == PdkEventKind.PLAY } },
            bombs = view.bombs,
            log = view.log,
            settlement = null,
        )
    }

    /** Plays [start] to the end with NORMAL players and returns the points [seat] ends with. */
    private fun playOut(start: PdkState, seat: Seat, seed: Long): Long {
        var state = start
        val bots = Array(PaoDeKuai.SEATS) { PdkBot(BotLevel.NORMAL, seed + it) }
        var moves = 0
        while (!state.finished && moves++ < MAX_MOVES) {
            val turn = state.turn
            state = try {
                PaoDeKuai.apply(state, turn, bots[turn].act(PaoDeKuai.view(state, turn)))
            } catch (e: IllegalActionException) {
                PaoDeKuai.apply(state, turn, PdkBot(BotLevel.EASY, seed).act(PaoDeKuai.view(state, turn)))
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
