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
) {
    companion object {
        val CLASSIC = DdzRules()
        val ROB = DdzRules(bidding = BiddingMode.ROB, doubling = true)
        val UNSHUFFLED = ROB.copy(deal = DealMode.CLUMPED)

        val PRESETS = listOf(
            RulesPreset("classic", CLASSIC),
            RulesPreset("rob", ROB),
            RulesPreset("unshuffled", UNSHUFFLED),
        )

        val CODEC = object : WireCodec<DdzRules> {
            override fun write(writer: WireWriter, value: DdzRules) {
                writer.int(value.bidding.ordinal).bool(value.doubling).bool(value.allowFourWithTwo)
                    .int(value.deal.ordinal).int(value.baseScore).int(value.maxMultiplier)
                    .bool(value.bombsDouble).bool(value.springDoubles).int(value.redealLimit)
            }

            override fun read(reader: WireReader): DdzRules = DdzRules(
                bidding = BiddingMode.entries[reader.int()],
                doubling = reader.bool(),
                allowFourWithTwo = reader.bool(),
                deal = DealMode.entries[reader.int()],
                baseScore = reader.int(),
                maxMultiplier = reader.int(),
                bombsDouble = reader.bool(),
                springDoubles = reader.bool(),
                redealLimit = reader.int(),
            )
        }
    }
}
