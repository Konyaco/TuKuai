package io.github.konyaco.tukuai.engine.bluff

import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.card.Rank
import io.github.konyaco.tukuai.engine.core.Seat

/**
 * What one seat can see: its own hand and plays, how many cards everyone else holds and laid
 * down, every call turned over, and how often each player lit their fuse, but not how long
 * the fuses are. Clients draw the table from this and computers decide from it.
 */
data class BluffView(
    /** The seat this view is for, or null for a spectator. */
    val seat: Seat?,
    val rules: BluffRules,
    val round: Int,
    val target: Rank,
    val phase: BluffPhase,
    /** Who plays or calls next while [phase] is [BluffPhase.PLAY]. */
    val turn: Seat,
    val hand: List<PlayingCard>,
    val handCounts: List<Int>,
    val lit: List<Int>,
    /** Who is out. */
    val out: List<Boolean>,
    /** The last play: who laid it down and how many cards; see [claimCards]. */
    val claimSeat: Seat?,
    val claimCount: Int,
    /** The cards of the last play, for the one who laid them down, or in a replay; empty otherwise. */
    val claimCards: List<PlayingCard>,
    /** Cards laid down this round, face down. */
    val pileCount: Int,
    val reveal: BluffReveal?,
    val log: List<BluffEvent>,
    /** When each creeper blows; only in replays and once the game is over, empty otherwise. */
    val fuses: List<Int>,
    /** Every hand, in replays and once the game is over; empty while playing. */
    val revealedHands: List<List<PlayingCard>>,
) {
    val players: Int get() = handCounts.size

    val finished: Boolean get() = phase == BluffPhase.OVER

    /** Whoever has to act now, or null once the game is over. */
    val toAct: Seat?
        get() = when (phase) {
            BluffPhase.PLAY -> turn
            BluffPhase.PENALTY -> reveal!!.loser
            BluffPhase.OVER -> null
        }

    val isMyTurn: Boolean get() = seat != null && toAct == seat

    /** Whether the seat to play may call the last play a lie. */
    val canCall: Boolean get() = phase == BluffPhase.PLAY && claimSeat != null && claimSeat != turn

    /** The seat to play has no cards left, so all it can do is call. */
    val mustCall: Boolean get() = phase == BluffPhase.PLAY && handCounts[turn] == 0

    val winner: Seat? get() = if (finished) out.indexOfFirst { !it } else null

    /** Lightings each seat has left before it is out, with hearts; with fuses nobody knows. */
    fun heartsLeft(seat: Seat): Int = rules.chances - lit[seat]

    /** The events of the round being played, starting with its deal. */
    val roundLog: List<BluffEvent>
        get() = log.subList(log.indexOfLast { it.kind == BluffEventKind.DEAL }.coerceAtLeast(0), log.size)

    /** The cards this seat laid down this round. */
    val myPlayed: List<PlayingCard>
        get() = roundLog.filter { it.kind == BluffEventKind.PLAY && it.seat == seat }.flatMap { it.cards }

    companion object {
        fun of(state: BluffState, seat: Seat?, open: Boolean): BluffView {
            val claim = state.claim
            return BluffView(
                seat = seat,
                rules = state.rules,
                round = state.round,
                target = state.target,
                phase = state.phase,
                turn = state.turn,
                hand = if (seat != null) state.hands[seat] else emptyList(),
                handCounts = state.hands.map { it.size },
                lit = state.lit,
                out = state.hands.indices.map(state::isOut),
                claimSeat = claim?.seat,
                claimCount = claim?.cards?.size ?: 0,
                claimCards = if (claim != null && (open || claim.seat == seat)) claim.cards else emptyList(),
                pileCount = state.pile.size,
                reveal = state.reveal,
                log = if (open) state.log else state.log.map { if (it.kind == BluffEventKind.PLAY && it.seat != seat) it.copy(cards = emptyList()) else it },
                fuses = if (open || state.finished) state.fuses else emptyList(),
                revealedHands = if (open || state.finished) state.hands else emptyList(),
            )
        }
    }
}
