package io.github.konyaco.tukuai.engine.mahjong.guobiao

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

/** Guobiao mahjong: the rules a table may change. */
data class GbRules(
    /** How many hands make the game: 4 for one round of the winds, 16 for all four. */
    val hands: Int = 4,
    /** Whether the eight flowers are in the wall; each one a player draws is worth a fan. */
    val flowers: Boolean = true,
    /** The fan a hand needs to win, flowers not counted. */
    val minFan: Int = 8,
) {
    init {
        require(hands in 1..16) { "1 to 16 hands" }
        require(minFan in 0..88) { "A minimum of 0 to 88 fan" }
    }

    companion object {
        /** Points every player pays the winner on top of the fan. */
        const val BASE = 8

        val ONE_ROUND = GbRules()
        val FULL = GbRules(hands = 16)
        val NO_FLOWERS = GbRules(flowers = false)

        val PRESETS = listOf(
            RulesPreset("east", ONE_ROUND),
            RulesPreset("full", FULL),
            RulesPreset("no_flowers", NO_FLOWERS),
        )

        val CODEC = object : WireCodec<GbRules> {
            override fun write(writer: WireWriter, value: GbRules) {
                writer.int(value.hands).bool(value.flowers).int(value.minFan)
            }

            override fun read(reader: WireReader): GbRules = GbRules(hands = reader.int(), flowers = reader.bool(), minFan = reader.int())
        }
    }
}

sealed interface GbAction {
    data class Discard(val tile: Tile) : GbAction

    /**
     * On your turn, a concealed kong of four in hand or a pung made a kong with the fourth;
     * as an answer to a discard, a kong of it with three in hand.
     */
    data class Kong(val tile: Tile) : GbAction

    /** Claims the discard of the player before you for a run starting at [start]. */
    data class Chow(val start: Tile) : GbAction

    data object Pung : GbAction

    /** Wins: on your turn with the tile drawn, on a discard, or robbing a kong. */
    data object Win : GbAction

    /** Lets a discard or a kong go. */
    data object Pass : GbAction

    /** Done looking at a hand's result: the next hand may start once everyone is. */
    data object Next : GbAction
}

enum class GbPhase {
    /** [GbState.turn] has drawn, or claimed a set, and discards, kongs or wins. */
    PLAY,

    /** The players who may claim [GbState.claimTile] answer, all at once. */
    CLAIM,

    /** A pung was made a kong, and the players who could win on its fourth tile answer. */
    ROB,

    /** A hand is over; the next starts once everyone has looked at it. */
    HAND_OVER,
    OVER,
}

/**
 * How one hand ended: [winner] won on [tile], from [from]'s discard or kong, or self-drawn
 * when [from] is null; nobody won when [winner] is null. [payments] is what each seat won or lost.
 */
data class GbHandResult(
    val hand: Int,
    val dealer: Seat,
    val winner: Seat?,
    val from: Seat?,
    val tile: Tile?,
    val fans: List<GbFan>,
    val payments: List<Int>,
) {
    val fan: Int get() = fans.sumOf { it.fan }
}

enum class GbEventKind {
    /** The tiles were dealt; [GbEvent.seat] is the dealer. */
    DEAL,

    /** [GbEvent.seat] drew [GbEvent.tile], from the back of the wall when [GbEvent.other] is 1. */
    DRAW,

    /** [GbEvent.seat] set the flower [GbEvent.tile] aside. */
    FLOWER,
    DISCARD,

    /** [GbEvent.seat] made a run from [GbEvent.tile] up with [GbEvent.other]'s discard. */
    CHOW,
    PUNG,

    /** [GbEvent.seat] made a kong of [GbEvent.tile], of the kind [GbEvent.meld]. */
    KONG,
    WIN,

    /** The wall ran out and nobody won. */
    EXHAUSTED,
}

data class GbEvent(
    val seat: Seat,
    val kind: GbEventKind,
    /** The tile, if there is one; in a view a tile someone else drew is left out. */
    val tile: Tile? = null,
    val other: Seat = -1,
    val meld: MeldKind? = null,
)

data class GbState(
    val rules: GbRules,
    val seed: Long,
    /** Which hand of the game this is, from 0. */
    val hand: Int,
    /** Who dealt the first hand; the deal passes on after every hand. */
    val firstDealer: Seat,
    val wall: Wall,
    /** Each seat's concealed tiles in order; the winner's ends with the tile they won on. */
    val hands: List<List<Tile>>,
    val melds: List<List<Meld>>,
    /** What each seat discarded and nobody claimed. */
    val discards: List<List<Tile>>,
    /** The flowers each seat drew and set aside. */
    val flowers: List<List<Tile>>,
    val phase: GbPhase,
    /** Whose turn it is in [GbPhase.PLAY]; who discarded, or made the kong, in [GbPhase.CLAIM] and [GbPhase.ROB]. */
    val turn: Seat,
    /** The tile [turn] just drew, if they drew one. */
    val drawn: Tile?,
    /** The discard, or the kong's fourth tile, being answered. */
    val claimTile: Tile?,
    /** Who may still answer [claimTile]. */
    val pending: List<Seat>,
    val answers: Map<Seat, GbAction>,
    /** [drawn] came from the back of the wall after a kong. */
    val replacement: Boolean,
    /** Nobody has discarded, claimed or made a kong yet: the dealer may win on the hand dealt. */
    val fresh: Boolean,
    /** How this hand ended, once it has. */
    val result: GbHandResult?,
    /** Every hand before this one. */
    val history: List<GbHandResult>,
    val scores: List<Int>,
    /** Who has looked at the result, in [GbPhase.HAND_OVER]. */
    val ready: List<Boolean>,
    /** What happened in this hand. */
    val log: List<GbEvent>,
) {
    val dealer: Seat get() = (firstDealer + hand) % Guobiao.SEATS

    /** The round's wind, east first: it changes every four hands. */
    val prevalentWind: Int get() = (hand / Guobiao.SEATS) % 4

    /** Each seat's own wind: the dealer is east. */
    fun seatWind(seat: Seat): Int = (seat - dealer + Guobiao.SEATS) % Guobiao.SEATS

    val finished: Boolean get() = phase == GbPhase.OVER

    val gameResult: GameResult?
        get() {
            if (!finished) return null
            val top = scores.max()
            return GameResult(scores, scores.indices.filter { scores[it] == top }.toSet())
        }
}
