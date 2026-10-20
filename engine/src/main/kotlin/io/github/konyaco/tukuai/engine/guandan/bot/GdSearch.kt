package io.github.konyaco.tukuai.engine.guandan.bot

import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.IllegalActionException
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.guandan.GdAction
import io.github.konyaco.tukuai.engine.guandan.GdCards
import io.github.konyaco.tukuai.engine.guandan.GdEventKind
import io.github.konyaco.tukuai.engine.guandan.GdPhase
import io.github.konyaco.tukuai.engine.guandan.GdState
import io.github.konyaco.tukuai.engine.guandan.GdView
import io.github.konyaco.tukuai.engine.guandan.Guandan
import kotlin.random.Random

/**
 * How the HARD computer picks a play, as in the other games: it guesses the other hands a
 * number of times, plays each guess to the end of the round with NORMAL players in every
 * seat, and keeps the candidate that earned its team the most on average. Every candidate
 * is tried on the same guesses. Guesses use only what the seat could know: its own hand,
 * the cards played this round, and how many cards each player holds.
 */
class GdSearch(private val random: Random, private val playouts: Int = PLAYOUTS) {

    /** Returns the best of [candidates]; on a tie the earlier one wins, so put the favourite first. */
    fun choose(view: GdView, seat: Seat, candidates: List<GdAction>): GdAction {
        if (candidates.size <= 1) return candidates.first()
        val rounds = (playouts / candidates.size).coerceAtLeast(MIN_ROUNDS)
        val totals = LongArray(candidates.size)
        repeat(rounds) {
            val state = guess(view, seat) ?: return candidates.first()
            val seed = random.nextLong()
            candidates.forEachIndexed { i, action ->
                totals[i] += try {
                    playOut(Guandan.apply(state, seat, action), seat, seed)
                } catch (e: IllegalActionException) {
                    Long.MIN_VALUE / 1024
                }
            }
        }
        // The favourite already plays for the team; only a clearly better guess replaces it.
        val best = totals.indices.maxBy { totals[it] }
        return if (totals[best] - totals[0] > MARGIN * rounds) candidates[best] else candidates[0]
    }

    /** A full state that matches everything [seat] has seen, with the unknown cards dealt at random. */
    fun guess(view: GdView, seat: Seat): GdState? {
        val known = view.hand + view.playedCards
        val pool = (GdCards.minus(GdCards.DECK, known) ?: return null).shuffled(random)
        var next = 0
        val hands = List(Guandan.SEATS) { s ->
            if (s == seat) {
                view.hand
            } else {
                val need = view.handCounts[s]
                if (next + need > pool.size) return null
                pool.subList(next, next + need).sortedWith(GdCards.ascending(view.levelRank)).also { next += need }
            }
        }
        if (next != pool.size) return null
        return GdState(
            rules = view.rules,
            seed = 0,
            round = view.round,
            levels = view.levels,
            levelTeam = view.levelTeam,
            level = view.level,
            phase = view.phase,
            hands = hands,
            turn = view.turn,
            lead = view.lead,
            passes = passesSinceLead(view),
            order = view.order,
            tributes = view.tributes,
            leader = view.turn,
            log = view.log,
            rounds = view.rounds,
            scores = view.scores,
            winners = view.winners,
        )
    }

    private fun passesSinceLead(view: GdView): Int {
        if (view.lead == null) return 0
        var passes = 0
        for (e in view.log.asReversed()) {
            when (e.kind) {
                GdEventKind.PASS -> passes++
                GdEventKind.PLAY -> return passes
                else -> {}
            }
        }
        return passes
    }

    /** Plays [start] to the end of the round with NORMAL players and returns what [seat] gained. */
    private fun playOut(start: GdState, seat: Seat, seed: Long): Long {
        var state = start
        val before = start.scores[seat]
        val bots = Array(Guandan.SEATS) { GdBot(BotLevel.NORMAL, seed + it, 0) }
        var moves = 0
        while (state.phase == GdPhase.PLAYING && moves++ < MAX_MOVES) {
            val turn = state.turn
            state = try {
                Guandan.apply(state, turn, bots[turn].act(Guandan.view(state, turn)))
            } catch (e: IllegalActionException) {
                Guandan.apply(state, turn, GdBot(BotLevel.EASY, seed).act(Guandan.view(state, turn)))
            }
        }
        // How far the round got still counts a little when it did not finish.
        return (state.scores[seat] - before).toLong() * 100 - state.hands[seat].size
    }

    companion object {
        /** Rounds played out per decision, shared among the candidates. */
        const val PLAYOUTS = 30
        private const val MIN_ROUNDS = 6
        private const val MAX_MOVES = 400

        /** How much better on average, in hundredths of a level, another play must do. */
        private const val MARGIN = 60
    }
}
