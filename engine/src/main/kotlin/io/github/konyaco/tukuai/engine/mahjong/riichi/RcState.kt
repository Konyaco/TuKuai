package io.github.konyaco.tukuai.engine.mahjong.riichi

import io.github.konyaco.tukuai.engine.core.GameResult
import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.Seat
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter
import io.github.konyaco.tukuai.engine.mahjong.Meld
import io.github.konyaco.tukuai.engine.mahjong.MeldKind
import io.github.konyaco.tukuai.engine.mahjong.Tile
import io.github.konyaco.tukuai.engine.mahjong.Wall

/** Riichi mahjong: the rules a table may change. */
data class RcRules(
    /** Play the south round after the east one (半荘戦), rather than the east round only (東風戦). */
    val south: Boolean = false,
    /** One five of each suit is red and counts as a dora. */
    val reds: Boolean = true,
    val startPoints: Int = 25000,
    /** The game ends at once when someone's points fall below zero. */
    val bust: Boolean = true,
    /** Open hands may count all simples (喰いタン). */
    val kuitan: Boolean = true,
) {
    init {
        require(startPoints in 1000..100000) { "Start with 1000 to 100000 points" }
    }

    /** The round the game ends after: 0 for east, 1 for south. */
    val lastRound: Int get() = if (south) 1 else 0

    companion object {
        val EAST = RcRules()
        val SOUTH = RcRules(south = true)
        val EAST_NO_RED = RcRules(reds = false)

        val PRESETS = listOf(
            RulesPreset("east", EAST),
            RulesPreset("south", SOUTH),
            RulesPreset("east_no_red", EAST_NO_RED),
        )

        val CODEC = object : WireCodec<RcRules> {
            override fun write(writer: WireWriter, value: RcRules) {
                writer.bool(value.south).bool(value.reds).int(value.startPoints).bool(value.bust).bool(value.kuitan)
            }

            override fun read(reader: WireReader): RcRules =
                RcRules(south = reader.bool(), reds = reader.bool(), startPoints = reader.int(), bust = reader.bool(), kuitan = reader.bool())
        }
    }
}

sealed interface RcAction {
    data class Discard(val tile: Tile) : RcAction

    /** Declares riichi, discarding [tile] sideways. */
    data class Riichi(val tile: Tile) : RcAction

    /** On your turn, a closed kong of four or a pung made a kong; as an answer to a discard, an open kong of it. */
    data class Kong(val tile: Tile) : RcAction

    /** Claims the discard of the player before you for a run starting at [start]. */
    data class Chow(val start: Tile) : RcAction

    data object Pung : RcAction

    /** Tsumo on your turn, ron on a discard, or robbing a kong. */
    data object Win : RcAction

    data object Pass : RcAction

    /** Calls the hand off with nine kinds of terminals and honours on your first draw (九種九牌). */
    data object Abort : RcAction

    /** Done looking at a hand's result. */
    data object Next : RcAction
}

enum class RcPhase { PLAY, CLAIM, ROB, HAND_OVER, OVER }

/** Why a hand was called off before the wall ran out. */
enum class RcAbort {
    /** Nine kinds of terminals and honours on a first draw. */
    NINE_TERMINALS,

    /** The first four discards were all the same wind. */
    FOUR_WINDS,

    /** All four players declared riichi. */
    FOUR_RIICHI,

    /** Four kongs were made by more than one player. */
    FOUR_KONGS,
}

/** One player's win in a hand, and what it scored. */
data class RcWinRecord(
    val seat: Seat,
    /** Who discarded the tile, or made the robbed kong; null for a tsumo. */
    val from: Seat?,
    val tile: Tile,
    val yaku: List<Pair<RcYaku, Int>>,
    val dora: Int,
    val aka: Int,
    val ura: Int,
    val han: Int,
    val fu: Int,
    val yakuman: Int,
    val limit: RcLimit,
    /** What the win itself is worth, before honba and riichi sticks. */
    val value: Int,
)

/** How a hand ended. */
data class RcHandResult(
    val round: Int,
    val hand: Int,
    val honba: Int,
    val dealer: Seat,
    val wins: List<RcWinRecord>,
    /** Who was ready when the wall ran out; empty otherwise. */
    val ready: List<Boolean>,
    /** Who made a mangan by discarding only terminals and honours, none taken (流し満貫). */
    val nagashi: List<Seat>,
    val abort: RcAbort?,
    val payments: List<Int>,
    val doraIndicators: List<Tile>,
    val uraIndicators: List<Tile>,
) {
    val exhausted: Boolean get() = wins.isEmpty() && abort == null
}

enum class RcEventKind {
    DEAL,

    /** [RcEvent.seat] drew [RcEvent.tile], from the dead wall after a kong when [RcEvent.other] is 1. */
    DRAW,
    DISCARD,

    /** [RcEvent.seat] declared riichi with the discard after this event. */
    RIICHI,
    CHOW,
    PUNG,

    /** [RcEvent.seat] made a kong of [RcEvent.tile], of the kind [RcEvent.meld]. */
    KONG,

    /** A new dora indicator was turned up: [RcEvent.tile]. */
    DORA,
    WIN,
    EXHAUSTED,
    ABORT,
}

data class RcEvent(
    val seat: Seat,
    val kind: RcEventKind,
    val tile: Tile? = null,
    val other: Seat = -1,
    val meld: MeldKind? = null,
)

data class RcState(
    val rules: RcRules,
    val seed: Long,
    val firstDealer: Seat,
    /** 0 for the east round, 1 for south. */
    val round: Int,
    /** Which hand of the round, from 0; it moves on when the deal does. */
    val kyoku: Int,
    val honba: Int,
    /** Riichi sticks lying on the table, 1000 points each. */
    val sticks: Int,
    val points: List<Int>,
    /** How many hands have been dealt, for shuffling each one differently. */
    val dealt: Int,
    val wall: Wall,
    val kongs: List<Seat>,
    val hands: List<List<Tile>>,
    val melds: List<List<Meld>>,
    /** What each seat discarded and nobody took, in order: the pond. */
    val ponds: List<List<Tile>>,
    /** Everything each seat discarded, taken or not: what furiten looks at. */
    val discarded: List<List<Tile>>,
    /** Which of each pond's tiles lies sideways for riichi; -1 for none. */
    val sideways: List<Int>,
    /** The riichi tile was taken, so the next discard lies sideways instead. */
    val sidewaysNext: List<Boolean>,
    val riichi: List<Boolean>,
    val doubleRiichi: List<Boolean>,
    val ippatsu: List<Boolean>,
    /** Let a winning tile go since their last discard. */
    val missed: List<Boolean>,
    /** Let a winning tile go after declaring riichi: furiten for the rest of the hand. */
    val riichiMissed: List<Boolean>,
    /** Has not discarded yet this hand. */
    val firstGo: List<Boolean>,
    /** Someone has called or made a kong this hand. */
    val interrupted: Boolean,
    /** Someone took one of this seat's discards. */
    val taken: List<Boolean>,
    val phase: RcPhase,
    val turn: Seat,
    val drawn: Tile?,
    /** [drawn] came from the dead wall after a kong. */
    val rinshan: Boolean,
    /** Tiles [turn] may not discard right after a call (喰い替え). */
    val forbidden: List<Tile>,
    /** The discard being answered is a riichi declaration. */
    val declaring: Boolean,
    val claimTile: Tile?,
    val pending: List<Seat>,
    val answers: Map<Seat, RcAction>,
    val result: RcHandResult?,
    /** Where the next hand is, once this one is over: its round, hand and honba. */
    val nextRound: Int = 0,
    val nextKyoku: Int = 0,
    val nextHonba: Int = 0,
    val history: List<RcHandResult>,
    val ready: List<Boolean>,
    val log: List<RcEvent>,
) {
    val dealer: Seat get() = (firstDealer + kyoku) % Riichi.SEATS

    fun seatWind(seat: Seat): Int = (seat - dealer + Riichi.SEATS) % Riichi.SEATS

    /** Tiles left to draw before the dead wall. */
    val liveLeft: Int get() = wall.remaining - Riichi.DEAD_WALL

    val doraIndicators: List<Tile> get() = (0..kongs.size).map { Riichi.doraIndicator(wall, it) }

    val uraIndicators: List<Tile> get() = (0..kongs.size).map { Riichi.uraIndicator(wall, it) }

    val finished: Boolean get() = phase == RcPhase.OVER

    val gameResult: GameResult?
        get() {
            if (!finished) return null
            val scores = points.map { it - rules.startPoints }
            val top = scores.max()
            return GameResult(scores, scores.indices.filter { scores[it] == top }.toSet())
        }
}
