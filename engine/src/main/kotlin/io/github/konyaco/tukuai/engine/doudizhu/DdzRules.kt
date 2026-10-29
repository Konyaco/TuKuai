package io.github.konyaco.tukuai.engine.doudizhu

import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

/** How the landlord is picked. */
enum class BiddingMode {
    /** Each seat bids 1, 2 or 3 points once; the highest bid wins and sets the stake. */
    SCORE,

    /** Call the landlord, then the others may rob it; every rob doubles the stake. */
    ROB,
}

enum class DealMode {
    SHUFFLED,

    /** Cards stay roughly grouped by value and are dealt in clumps, so bombs and planes are common. */
    CLUMPED,
}

data class DdzRules(
    val bidding: BiddingMode = BiddingMode.SCORE,
    /** After the landlord is chosen, each seat may double its own stake. */
    val doubling: Boolean = false,
    val allowFourWithTwo: Boolean = true,
    val deal: DealMode = DealMode.SHUFFLED,
    val baseScore: Int = 1,
    /** Highest multiplier a single settlement may reach; 0 means no cap. */
    val maxMultiplier: Int = 0,
    val bombsDouble: Boolean = true,
    val springDoubles: Boolean = true,
    /** After this many deals where nobody wanted the landlord, the first bidder must take it. */
    val redealLimit: Int = 3,
    /**
     * 3, or 4 for the four-player game: one landlord against three farmers, with two decks
     * dealt 25 cards each and 8 for the landlord.
     */
    val players: Int = 3,
    /** Trios and planes may carry singles; without, they carry only pairs. */
    val trioSingles: Boolean = true,
) {
    init {
        require(players == 3 || players == 4) { "Dou Dizhu is for 3 or 4 players, not $players" }
    }

    /** One deck for three players, two for four. */
    val decks: Int get() = if (players == 4) 2 else 1

    val handSize: Int get() = if (players == 4) 25 else 17

    /** The landlord's extra cards, which everyone sees once they are taken. */
    val kittySize: Int get() = if (players == 4) 8 else 3

    companion object {
        val CLASSIC = DdzRules()
        val ROB = DdzRules(bidding = BiddingMode.ROB, doubling = true)
        val UNSHUFFLED = ROB.copy(deal = DealMode.CLUMPED)

        /**
         * The four-player game as it is mostly played: score bidding, trios and planes carry
         * only pairs, no four with two, and bombs that grow with the decks.
         */
        val FOUR = DdzRules(players = 4, trioSingles = false, allowFourWithTwo = false, maxMultiplier = 64)

        val PRESETS = listOf(
            RulesPreset("classic", CLASSIC),
            RulesPreset("rob", ROB),
            RulesPreset("unshuffled", UNSHUFFLED),
            RulesPreset("four", FOUR),
        )

        /**
         * Rules from before the four-player game are written as they always were, so saved
         * tables and replays still read and still match their preset; others start with this.
         */
        private const val WITH_PLAYERS = -2

        val CODEC = object : WireCodec<DdzRules> {
            override fun write(writer: WireWriter, value: DdzRules) {
                if (value.players != 3 || !value.trioSingles) writer.int(WITH_PLAYERS).int(value.players).bool(value.trioSingles)
                writer.int(value.bidding.ordinal).bool(value.doubling).bool(value.allowFourWithTwo)
                    .int(value.deal.ordinal).int(value.baseScore).int(value.maxMultiplier)
                    .bool(value.bombsDouble).bool(value.springDoubles).int(value.redealLimit)
            }

            override fun read(reader: WireReader): DdzRules {
                var first = reader.int()
                var players = 3
                var trioSingles = true
                if (first == WITH_PLAYERS) {
                    players = reader.int()
                    trioSingles = reader.bool()
                    first = reader.int()
                }
                return read(reader, BiddingMode.entries[first], players, trioSingles)
            }

            private fun read(reader: WireReader, bidding: BiddingMode, players: Int, trioSingles: Boolean) = DdzRules(
                bidding = bidding,
                doubling = reader.bool(),
                allowFourWithTwo = reader.bool(),
                deal = DealMode.entries[reader.int()],
                baseScore = reader.int(),
                maxMultiplier = reader.int(),
                bombsDouble = reader.bool(),
                springDoubles = reader.bool(),
                redealLimit = reader.int(),
                players = players,
                trioSingles = trioSingles,
            )
        }
    }
}
