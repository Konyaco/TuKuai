package io.github.konyaco.tukuai.engine.mahjong.sichuan

import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.mahjong.ClaimKind
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.TileSuit
import io.github.konyaco.tukuai.engine.mahjong.Wall

sealed interface ScAction {
    /** Passes three tiles of one suit on, before play starts. */
    data class Swap(val tiles: List<Tile>) : ScAction

    /** Picks the suit to get rid of (定缺). */
    data class ChooseVoid(val suit: TileSuit) : ScAction

    data class Discard(val tile: Tile) : ScAction

    /**
     * On your turn, a concealed kong of four in hand or a pung made a kong with the fourth;
     * as an answer to a discard, a kong of it with three in hand.
     */
    data class Kong(val tile: Tile) : ScAction

    /** Claims the discard to make a pung. */
    data object Pung : ScAction

    /** Wins: on your turn with the tile drawn, on a discard, or robbing a kong. */
    data object Win : ScAction

    /** Lets a discard or a kong go. */
    data object Pass : ScAction
}

enum class ScPhase {
    /** Everyone picks three tiles of one suit to pass on. */
    SWAP,

    /** Everyone picks their void suit. */
    VOID,

    /** [ScState.turn] has drawn, or claimed a pung, and discards, kongs or wins. */
    PLAY,

    /** The players who may claim [ScState.claimTile] answer in turn. */
    CLAIM,

    /** A pung was made a kong, and the players who could win on its fourth tile answer. */
    ROB,
    OVER,
}

/** Where everyone's three tiles go, the same for all, picked at random each hand. */
enum class SwapDirection(private val step: Int) {
    /** To the player who plays after you. */
    NEXT(1),
    ACROSS(2),

    /** To the player who plays before you. */
    PREVIOUS(3);

    fun target(seat: Seat): Seat = (seat + step) % SichuanMahjong.SEATS
}

/** What a winning hand is worth; a hand with none of these is plain and worth 0 fan. */
enum class ScFan(val fan: Int) {
    ALL_PUNGS(1),
    FULL_FLUSH(2),
    SEVEN_PAIRS(2),

    /** Seven pairs with four of a kind among them. */
    DRAGON_SEVEN_PAIRS(3),

    /** Four of a kind anywhere in the hand or its melds (根), each one. */
    GEN(1),

    /** Self-drawn with the tile drawn after a kong (杠上花). */
    KONG_BLOOM(1),

    /** On a discard made right after a kong (杠上炮). */
    KONG_DISCARD(1),

    /** Robbing a kong (抢杠胡). */
    ROB_KONG(1),

    /** On the last tile of the wall, drawn or discarded (海底). */
    LAST_TILE(1),

    /** The dealer wins on the hand dealt (天胡); worth the most there is. */
    HEAVENLY(0),

    /** Another player wins on their first tile (地胡); worth the most there is. */
    EARTHLY(0),
}

/**
 * A player won: on [tile], from [from]'s discard or kong, or self-drawn when [from] is
 * null. [fan] counts [fans], and each payer paid [points], plus one more on a self-draw.
 * [payments] is what that changed for each seat.
 */
data class ScWin(
    val seat: Seat,
    val tile: Tile,
    val from: Seat?,
    val fans: List<ScFan>,
    val fan: Int,
    val points: Int,
    val payments: List<Int>,
) {
    val selfDrawn: Boolean get() = from == null
}

enum class ScEventKind {
    /** The tiles were dealt; [ScEvent.seat] is the dealer. */
    DEAL,

    /** Everyone passed three tiles on. */
    SWAP,

    /** Everyone showed their void suit. */
    VOID,

    /** [ScEvent.seat] drew [ScEvent.tile], from the back of the wall after a kong when [ScEvent.other] is 1. */
    DRAW,
    DISCARD,

    /** [ScEvent.seat] made a pung of [ScEvent.tile] from [ScEvent.other]'s discard. */
    PUNG,

    /** [ScEvent.seat] made a kong of [ScEvent.tile], of the kind [ScEvent.meld]. */
    KONG,

    /** [ScEvent.seat] won on [ScEvent.tile]; see [ScState.wins]. */
    WIN,

    /** The wall ran out before three players won. */
    EXHAUSTED,

    /** At the end [ScEvent.seat] still held their void suit (花猪) and paid everyone else still in. */
    PIG,

    /** At the end [ScEvent.seat] was not ready and paid every player still in who was (查大叫). */
    NOT_READY,
}

data class ScEvent(
    val seat: Seat,
    val kind: ScEventKind,
    /** The tile, if there is one; in a view a tile someone else drew is left out. */
    val tile: Tile? = null,
    val other: Seat = -1,
    val meld: MeldKind? = null,
    /** What the event changed in each seat's points, or empty when nothing. */
    val payments: List<Int> = emptyList(),
)

data class ScState(
    val rules: ScRules,
    val seed: Long,
    val dealer: Seat,
    val wall: Wall,
    /** Each seat's concealed tiles in order; a winner's ends with the tile they won on. */
    val hands: List<List<Tile>>,
    val melds: List<List<Meld>>,
    /** What each seat discarded and nobody claimed. */
    val discards: List<List<Tile>>,
    /** Each seat's void suit, once picked. */
    val voids: List<TileSuit?>,
    /** The three tiles each seat picked to pass on, empty until picked. */
    val swaps: List<List<Tile>>,
    /** The three tiles each seat got in the swap. */
    val received: List<List<Tile>>,
    val swapDirection: SwapDirection,
    val phase: ScPhase,
    /** Whose turn it is in [ScPhase.PLAY]; who discarded, or made the kong, in [ScPhase.CLAIM] and [ScPhase.ROB]. */
    val turn: Seat,
    /** The tile [turn] just drew, if they drew one. */
    val drawn: Tile?,
    /** The discard, or the kong's fourth tile, being answered. */
    val claimTile: Tile?,
    /** Who still has to answer [claimTile], in turn order. */
    val pending: List<Seat>,
    val answers: List<Pair<Seat, ClaimKind>>,
    /** [drawn] came from the back of the wall after a kong. */
    val replacement: Boolean,
    /** [claimTile] was discarded right after a kong. */
    val kongDiscard: Boolean,
    /** Nobody has claimed a discard or made a kong yet, for heavenly and earthly hands. */
    val fresh: Boolean,
    /** Who has discarded at least once. */
    val played: List<Boolean>,
    val wins: List<ScWin>,
    val scores: List<Int>,
    val log: List<ScEvent>,
) {
    fun won(seat: Seat): Boolean = wins.any { it.seat == seat }

    /** The seats still playing: everyone who has not won. */
    val active: List<Seat> get() = hands.indices.filter { !won(it) }

    val finished: Boolean get() = phase == ScPhase.OVER

    val result: GameResult?
        get() = if (finished) GameResult(scores, wins.map { it.seat }.toSet()) else null
}
