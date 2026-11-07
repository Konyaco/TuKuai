package io.github.konyaco.tukuai.engine.mahjong.riichi

import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.MjTableView
import io.github.konyaco.tukuai.engine.mahjong.Tile

/**
 * What one seat can see: its own tiles, everyone's melds, ponds and riichi, the dora
 * indicators, the points and sticks, and every hand once the hand is over.
 */
data class RcView(
    override val seat: Seat?,
    val rules: RcRules,
    val phase: RcPhase,
    val round: Int,
    val kyoku: Int,
    val honba: Int,
    val sticks: Int,
    val points: List<Int>,
    override val dealer: Seat,
    val turn: Seat,
    override val toAct: Seat?,
    val actors: List<Seat>,
    override val hand: List<Tile>,
    override val drawn: Tile?,
    override val handCounts: List<Int>,
    override val shown: List<List<Tile>>,
    override val melds: List<List<Meld>>,
    /** The ponds: what each seat discarded and nobody took. */
    override val discards: List<List<Tile>>,
    /** Everything each seat discarded, taken or not. */
    val discarded: List<List<Tile>>,
    val sidewaysAt: List<Int>,
    val riichi: List<Boolean>,
    val doubleRiichi: List<Boolean>,
    /** This seat may still win in its first go round after riichi. */
    val ippatsu: Boolean,
    val doraIndicators: List<Tile>,
    /** The tiles under the dora indicators, shown once a hand someone declared riichi in is over. */
    val uraIndicators: List<Tile>,
    override val claimTile: Tile?,
    override val claimFrom: Seat?,
    /** What this seat may answer to [claimTile], when it is asked. */
    val options: RcOptions,
    /** On this seat's turn: what a tsumo would score, or null when it cannot win. */
    val tsumo: RcScore?,
    val riichiTiles: List<Tile>,
    val kongs: List<Tile>,
    val discardable: List<Tile>,
    val canAbort: Boolean,
    /** This seat is furiten: it may not win on a discard. */
    val furiten: Boolean,
    /** The tile this seat just drew came from the dead wall. */
    val rinshan: Boolean,
    override val wallSize: Int,
    override val drawnFront: Int,
    override val drawnBack: Int,
    val result: RcHandResult?,
    val history: List<RcHandResult>,
    val ready: List<Boolean>,
    val log: List<RcEvent>,
) : MjTableView {
    val finished: Boolean get() = phase == RcPhase.OVER

    override val handOver: Boolean get() = phase == RcPhase.HAND_OVER || phase == RcPhase.OVER

    override val claimingDiscard: Boolean get() = phase == RcPhase.CLAIM

    val isMyTurn: Boolean get() = seat != null && seat in actors

    fun seatWind(seat: Seat): Int = (seat - dealer + Riichi.SEATS) % Riichi.SEATS

    val myMelds: List<Meld> get() = seat?.let { melds[it] }.orEmpty()

    val liveLeft: Int get() = wallLeft - Riichi.DEAD_WALL

    val menzen: Boolean get() = myMelds.all { it.kind == MeldKind.CONCEALED_KONG }

    override fun sideways(seat: Seat): Int = sidewaysAt.getOrElse(seat) { -1 }

    override fun winningTile(seat: Seat): Tile? = result?.wins?.firstOrNull { it.seat == seat }?.tile

    override val latestMeld: Pair<Seat, Int>?
        get() {
            val last = log.lastOrNull { it.kind != RcEventKind.DRAW && it.kind != RcEventKind.DORA } ?: return null
            if (last.kind != RcEventKind.PUNG && last.kind != RcEventKind.KONG && last.kind != RcEventKind.CHOW) return null
            return last.seat to melds[last.seat].size - 1
        }

    override val wallFaceUp: Map<Int, Tile>
        get() {
            val out = HashMap<Int, Tile>()
            doraIndicators.forEachIndexed { i, t -> out[wallSize - 5 - 2 * i] = t }
            uraIndicators.forEachIndexed { i, t -> out[wallSize - 6 - 2 * i] = t }
            return out
        }

    /** This seat's hand won on [tile], as far as this seat can tell how: ready to win on a discard or a draw. */
    fun winOn(tile: Tile, concealed: List<Tile>, tsumo: Boolean, declaringRiichi: Boolean = false): RcWin {
        val s = seat ?: 0
        return RcWin(
            concealed = concealed + tile, melds = myMelds, tile = tile, tsumo = tsumo, seatWind = seatWind(s), roundWind = round,
            riichi = riichi.getOrElse(s) { false } || declaringRiichi, doubleRiichi = doubleRiichi.getOrElse(s) { false },
            doraIndicators = doraIndicators, kuitan = rules.kuitan,
        )
    }

    companion object {
        fun of(state: RcState, seat: Seat?, open: Boolean): RcView {
            val actors = Riichi.actors(state)
            val over = state.phase == RcPhase.HAND_OVER || state.phase == RcPhase.OVER
            val asked = seat != null && seat in actors
            return RcView(
                seat = seat,
                rules = state.rules,
                phase = state.phase,
                round = state.round,
                kyoku = state.kyoku,
                honba = state.honba,
                sticks = state.sticks,
                points = state.points,
                dealer = state.dealer,
                turn = state.turn,
                toAct = actors.firstOrNull(),
                actors = actors,
                hand = seat?.let { state.hands[it] }.orEmpty(),
                drawn = if (seat != null && seat == state.turn && state.phase == RcPhase.PLAY) state.drawn else null,
                handCounts = state.hands.map { it.size },
                shown = state.hands.map { h -> if (open || over) h else emptyList() },
                melds = state.melds,
                discards = state.ponds,
                discarded = state.discarded,
                sidewaysAt = state.sideways,
                riichi = state.riichi,
                doubleRiichi = state.doubleRiichi,
                ippatsu = seat != null && state.ippatsu[seat],
                doraIndicators = state.doraIndicators,
                uraIndicators = if (over) state.result?.uraIndicators.orEmpty() else if (open) state.uraIndicators else emptyList(),
                claimTile = state.claimTile.takeIf { state.phase == RcPhase.CLAIM || state.phase == RcPhase.ROB },
                claimFrom = if (state.claimTile != null && (state.phase == RcPhase.CLAIM || state.phase == RcPhase.ROB)) state.turn else null,
                options = when {
                    !asked -> RcOptions.NONE
                    state.phase == RcPhase.ROB -> RcOptions(win = true)
                    state.phase == RcPhase.CLAIM -> Riichi.claimOptions(state, seat!!, state.claimTile!!, state.turn)
                    else -> RcOptions.NONE
                },
                tsumo = if (asked) Riichi.tsumoScore(state, seat!!) else null,
                riichiTiles = if (asked) Riichi.riichiTiles(state, seat!!) else emptyList(),
                kongs = if (asked) Riichi.kongs(state, seat!!) else emptyList(),
                discardable = if (asked) Riichi.discardable(state, seat!!) else emptyList(),
                canAbort = asked && Riichi.canAbort(state, seat!!),
                furiten = seat != null && !over && Riichi.furiten(state, seat),
                rinshan = state.rinshan && seat == state.turn,
                wallSize = state.wall.tiles.size,
                drawnFront = state.wall.drawnFront,
                drawnBack = state.wall.drawnBack,
                result = state.result,
                history = state.history,
                ready = state.ready,
                log = if (open) state.log else state.log.map { if (it.kind == RcEventKind.DRAW && it.seat != seat) it.copy(tile = null) else it },
            )
        }
    }
}
