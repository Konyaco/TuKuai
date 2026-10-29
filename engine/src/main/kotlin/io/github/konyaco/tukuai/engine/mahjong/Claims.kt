package io.github.konyaco.tukuai.engine.mahjong

import io.github.konyaco.tukuai.engine.core.Seat

/** What a player may answer when someone else discards; [precedence] decides between players who claim the same tile. */
enum class ClaimKind(val precedence: Int) {
    PASS(0),
    CHOW(1),
    PUNG(2),
    KONG(2),
    WIN(3),
}

object Claims {
    /** The other seats in turn order after [from], counting from [seats] round the table. */
    fun after(from: Seat, seats: Int): List<Seat> = (1 until seats).map { (from + it) % seats }

    /**
     * Which answers to one discard go through, given in turn order after the discarder. A
     * win beats everything; when [multipleWins] every player who wins on it does, otherwise
     * the first in turn order. Without a win, the highest claim goes through, and with a
     * tie the first in turn order. Passes never do.
     */
    fun resolve(answers: List<Pair<Seat, ClaimKind>>, multipleWins: Boolean): List<Pair<Seat, ClaimKind>> {
        val wins = answers.filter { it.second == ClaimKind.WIN }
        if (wins.isNotEmpty()) return if (multipleWins) wins else wins.take(1)
        val best = answers.filter { it.second != ClaimKind.PASS }.maxByOrNull { it.second.precedence } ?: return emptyList()
        return listOf(answers.first { it.second.precedence == best.second.precedence })
    }

    /** Whether a hand with [counts] can pung [tile]: it holds two more. */
    fun canPung(counts: IntArray, tile: Tile): Boolean = counts[tile.id] >= 2

    /** Whether a hand with [counts] can kong a discarded [tile]: it holds the other three. */
    fun canKong(counts: IntArray, tile: Tile): Boolean = counts[tile.id] >= 3
}
