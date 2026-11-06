package io.github.konyaco.tukuai.engine.mahjong.guobiao

import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MjTableView
import io.github.konyaco.tukuai.engine.mahjong.Tile

/**
 * What one seat can see: its own tiles, everyone's melds, flowers and discards, how many
 * tiles each other hand holds and how much of the wall is left; every hand once the hand is over.
 */
data class GbView(
    override val seat: Seat?,
    val rules: GbRules,
    val phase: GbPhase,
    /** Which hand of the game this is, from 0. */
    val handIndex: Int,
    override val dealer: Seat,
    val prevalentWind: Int,
    /** See [GbState.turn]. */
    val turn: Seat,
    override val toAct: Seat?,
    /** Every seat that may act now. */
    val actors: List<Seat>,
    override val hand: List<Tile>,
    override val drawn: Tile?,
    override val handCounts: List<Int>,
    override val shown: List<List<Tile>>,
    override val melds: List<List<Meld>>,
    override val discards: List<List<Tile>>,
    override val flowers: List<List<Tile>>,
    override val claimTile: Tile?,
    override val claimFrom: Seat?,
    /** What this seat may answer to [claimTile], when it is asked. */
    val options: GbOptions,
    override val wallSize: Int,
    override val drawnFront: Int,
    override val drawnBack: Int,
    /** On this seat's turn: what it would score winning now, or null when it cannot. */
    val selfDraw: GbScore?,
    /** Nobody has discarded or claimed yet this hand. */
    val fresh: Boolean,
    val result: GbHandResult?,
    val history: List<GbHandResult>,
    val scores: List<Int>,
    val ready: List<Boolean>,
    val log: List<GbEvent>,
) : MjTableView {
    val finished: Boolean get() = phase == GbPhase.OVER

    override val handOver: Boolean get() = phase == GbPhase.HAND_OVER || phase == GbPhase.OVER

    override val claimingDiscard: Boolean get() = phase == GbPhase.CLAIM

    val isMyTurn: Boolean get() = seat != null && seat in actors

    fun seatWind(seat: Seat): Int = (seat - dealer + Guobiao.SEATS) % Guobiao.SEATS

    val myMelds: List<Meld> get() = seat?.let { melds[it] }.orEmpty()

    override fun winningTile(seat: Seat): Tile? = result?.takeIf { it.winner == seat }?.tile

    override val latestMeld: Pair<Seat, Int>?
        get() {
            val last = log.lastOrNull { it.kind != GbEventKind.DRAW && it.kind != GbEventKind.FLOWER } ?: return null
            if (last.kind != GbEventKind.PUNG && last.kind != GbEventKind.KONG && last.kind != GbEventKind.CHOW) return null
            return last.seat to melds[last.seat].size - 1
        }

    /** On this seat's turn: the kongs it may make. */
    val kongs: List<Tile>
        get() = if (isMyTurn && phase == GbPhase.PLAY) Guobiao.kongs(hand, myMelds, wallLeft) else emptyList()

    /**
     * The hand this seat would win with on [tile]: self-drawn or on a discard. It knows the
     * table as this seat sees it, so the tile being the last of its kind counts when the other
     * three lie face up.
     */
    fun winOn(tile: Tile, concealed: List<Tile>, selfDrawn: Boolean): GbWin {
        val s = seat ?: 0
        return GbWin(
            concealed = concealed + tile, melds = myMelds, tile = tile, selfDrawn = selfDrawn,
            seatWind = seatWind(s), prevalentWind = prevalentWind, lastTile = wallLeft == 0,
            lastOfKind = Guobiao.visible(discards, melds, tile) == 3, flowers = flowers.getOrNull(s)?.size ?: 0,
        )
    }

    companion object {
        fun of(state: GbState, seat: Seat?, open: Boolean): GbView {
            val actors = Guobiao.actors(state)
            val over = state.phase == GbPhase.HAND_OVER || state.phase == GbPhase.OVER
            val asked = seat != null && seat in actors
            return GbView(
                seat = seat,
                rules = state.rules,
                phase = state.phase,
                handIndex = state.hand,
                dealer = state.dealer,
                prevalentWind = state.prevalentWind,
                turn = state.turn,
                toAct = actors.firstOrNull(),
                actors = actors,
                hand = seat?.let { state.hands[it] }.orEmpty(),
                drawn = if (seat != null && seat == state.turn && state.phase == GbPhase.PLAY) state.drawn else null,
                handCounts = state.hands.map { it.size },
                shown = state.hands.map { h -> if (open || over) h else emptyList() },
                melds = state.melds,
                discards = state.discards,
                flowers = state.flowers,
                claimTile = state.claimTile,
                claimFrom = if (state.claimTile != null) state.turn else null,
                options = when {
                    !asked -> GbOptions.NONE
                    state.phase == GbPhase.ROB -> GbOptions(win = true)
                    state.phase == GbPhase.CLAIM -> Guobiao.claimOptions(state, seat!!, state.claimTile!!, state.turn)
                    else -> GbOptions.NONE
                },
                wallSize = state.wall.tiles.size,
                drawnFront = state.wall.drawnFront,
                drawnBack = state.wall.drawnBack,
                selfDraw = if (asked) Guobiao.selfDrawScore(state, seat!!) else null,
                fresh = state.fresh,
                result = state.result,
                history = state.history,
                scores = state.scores,
                ready = state.ready,
                log = if (open) state.log else state.log.map { if (it.kind == GbEventKind.DRAW && it.seat != seat) it.copy(tile = null) else it },
            )
        }
    }
}
