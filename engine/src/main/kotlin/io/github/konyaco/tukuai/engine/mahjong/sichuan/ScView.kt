package io.github.konyaco.tukuai.engine.mahjong.sichuan

import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.ClaimKind
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit

/**
 * What one seat can see: its own tiles, everyone's melds and discards, winners' hands face
 * up, how many tiles each other hand holds and how much of the wall is left. Void suits show
 * once everyone has picked, and the tiles passed on only to those who passed or got them.
 */
data class ScView(
    /** The seat this view is for, or null for a spectator. */
    val seat: Seat?,
    val rules: ScRules,
    val phase: ScPhase,
    val dealer: Seat,
    /** See [ScState.turn]. */
    val turn: Seat,
    val toAct: Seat?,
    /** This seat's concealed tiles in order, with [drawn] among them. */
    val hand: List<Tile>,
    /** The tile this seat just drew, if it is their turn after a draw. */
    val drawn: Tile?,
    val handCounts: List<Int>,
    /** Each seat's concealed tiles where they may be seen: winners', and everyone's once the hand is over or in a replay. */
    val shown: List<List<Tile>>,
    val melds: List<List<Meld>>,
    val discards: List<List<Tile>>,
    /** Each seat's void suit, once everyone has picked; this seat's own as soon as it has. */
    val voids: List<TileSuit?>,
    /** Who has made their pick, while everyone picks tiles to pass on or a void suit. */
    val picked: List<Boolean>,
    val swapDirection: SwapDirection?,
    /** The tiles this seat picked to pass on, and those it got once everyone had. */
    val gave: List<Tile>,
    val got: List<Tile>,
    /** The discard or kong tile being answered, and who discarded it or made the kong. */
    val claimTile: Tile?,
    val claimFrom: Seat?,
    /** What this seat may answer to [claimTile] when it is asked; it may always pass. */
    val options: List<ClaimKind>,
    val wallSize: Int,
    val drawnFront: Int,
    val drawnBack: Int,
    val wins: List<ScWin>,
    val scores: List<Int>,
    val log: List<ScEvent>,
) {
    val finished: Boolean get() = phase == ScPhase.OVER

    val wallLeft: Int get() = wallSize - drawnFront - drawnBack

    val isMyTurn: Boolean get() = seat != null && toAct == seat

    fun won(seat: Seat): Boolean = wins.any { it.seat == seat }

    fun winOf(seat: Seat): ScWin? = wins.firstOrNull { it.seat == seat }

    /** This seat's melds, or none for a spectator. */
    val myMelds: List<Meld> get() = seat?.let { melds[it] }.orEmpty()

    val myVoid: TileSuit? get() = seat?.let { voids[it] }

    /** On this seat's turn: the tiles it may discard. */
    val discardable: List<Tile>
        get() = if (isMyTurn && phase == ScPhase.PLAY) SichuanMahjong.discardable(hand, myVoid) else emptyList()

    /** On this seat's turn: the kongs it may make. */
    val kongs: List<Tile>
        get() = if (isMyTurn && phase == ScPhase.PLAY) SichuanMahjong.kongs(hand, myMelds, myVoid, wallLeft) else emptyList()

    /** On this seat's turn: whether its hand is complete, so it may win. */
    val canWin: Boolean
        get() = isMyTurn && phase == ScPhase.PLAY && (drawn != null || (dealer == seat && log.none { it.kind == ScEventKind.DISCARD || it.kind == ScEventKind.KONG })) &&
            ScScoring.isWin(hand, myMelds, myVoid)

    /** The tiles this seat's hand waits on when it is one short of complete. */
    val waits: List<Tile>
        get() = if (seat == null || won(seat) || hand.size % 3 != 1) emptyList() else ScScoring.waits(hand, myMelds, myVoid)

    /** How many of [tile] this seat can see: in its hand, in every discard and every meld. */
    fun seen(tile: Tile): Int =
        hand.count { it == tile } + discards.sumOf { d -> d.count { it == tile } } +
            melds.sumOf { m -> m.sumOf { meld -> meld.tiles.count { it == tile } } } +
            shown.withIndex().filter { it.index != seat }.sumOf { (_, h) -> h.count { it == tile } }

    companion object {
        fun of(state: ScState, seat: Seat?, open: Boolean): ScView {
            val toAct = SichuanMahjong.toAct(state)
            val swapped = state.phase != ScPhase.SWAP
            val voidsShown = state.phase != ScPhase.SWAP && state.phase != ScPhase.VOID
            return ScView(
                seat = seat,
                rules = state.rules,
                phase = state.phase,
                dealer = state.dealer,
                turn = state.turn,
                toAct = toAct,
                hand = seat?.let { state.hands[it] }.orEmpty(),
                drawn = if (seat != null && seat == state.turn && state.phase == ScPhase.PLAY) state.drawn else null,
                handCounts = state.hands.map { it.size },
                shown = state.hands.indices.map { s -> if (open || state.finished || state.won(s)) state.hands[s] else emptyList() },
                melds = state.melds,
                discards = state.discards,
                voids = state.voids.mapIndexed { s, v -> if (open || voidsShown || s == seat) v else null },
                picked = when (state.phase) {
                    ScPhase.SWAP -> state.swaps.map { it.isNotEmpty() }
                    ScPhase.VOID -> state.voids.map { it != null }
                    else -> List(state.hands.size) { true }
                },
                swapDirection = if (state.rules.swapThree && swapped) state.swapDirection else null,
                gave = seat?.let { state.swaps[it] }.orEmpty(),
                got = if (seat != null && swapped) state.received[seat] else emptyList(),
                claimTile = state.claimTile,
                claimFrom = if (state.claimTile != null) state.turn else null,
                options = when {
                    seat == null || toAct != seat -> emptyList()
                    state.phase == ScPhase.ROB -> listOf(ClaimKind.WIN)
                    state.phase == ScPhase.CLAIM -> SichuanMahjong.claimOptions(state, seat, state.claimTile!!)
                    else -> emptyList()
                },
                wallSize = state.wall.tiles.size,
                drawnFront = state.wall.drawnFront,
                drawnBack = state.wall.drawnBack,
                wins = state.wins,
                scores = state.scores,
                log = if (open) state.log else state.log.map { if (it.kind == ScEventKind.DRAW && it.seat != seat) it.copy(tile = null) else it },
            )
        }
    }
}
