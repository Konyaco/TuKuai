package io.github.konyaco.tukuai.engine.bluff

import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

/** What happens to whoever is caught out, or called someone honest a liar. */
enum class BluffPenalty {
    /**
     * They light the fuse of their creeper. It blows on one of [BluffRules.chances] lightings,
     * nobody knows which, and then they are out; every lighting it survives brings that nearer.
     */
    FUSE,

    /** They lose one of [BluffRules.chances] hearts, and are out with none left. No luck in it. */
    HEARTS,
}

data class BluffRules(
    /** 2 to 8; more than four need a big table. */
    val players: Int = 4,
    val handSize: Int = 5,
    /** The most cards one play may lay down. */
    val maxPlay: Int = 3,
    val penalty: BluffPenalty = BluffPenalty.FUSE,
    /** How long each creeper's fuse is, or how many hearts each player has; see [BluffPenalty]. */
    val chances: Int = 6,
    /** Two jokers in the deck, which count as whatever is called for. */
    val jokers: Boolean = true,
) {
    init {
        require(players in 2..8) { "Creeper bluff is for 2 to 8 players" }
        require(handSize in 2..6) { "Hands of 2 to 6 cards" }
        require(maxPlay in 1..handSize) { "Plays of 1 to $handSize cards" }
        require(chances in 1..9) { "1 to 9 chances" }
    }

    companion object {
        val CLASSIC = BluffRules()
        val HEARTS = BluffRules(penalty = BluffPenalty.HEARTS, chances = 3)
        val THREE = BluffRules(players = 3)
        val SIX = BluffRules(players = 6)

        val PRESETS = listOf(
            RulesPreset("classic", CLASSIC),
            RulesPreset("hearts", HEARTS),
            RulesPreset("three", THREE),
            RulesPreset("six", SIX),
        )

        val CODEC = object : WireCodec<BluffRules> {
            override fun write(writer: WireWriter, value: BluffRules) {
                writer.int(value.players).int(value.handSize).int(value.maxPlay).int(value.penalty.ordinal)
                    .int(value.chances).bool(value.jokers)
            }

            override fun read(reader: WireReader): BluffRules = BluffRules(
                players = reader.int(),
                handSize = reader.int(),
                maxPlay = reader.int(),
                penalty = BluffPenalty.entries[reader.int()],
                chances = reader.int(),
                jokers = reader.bool(),
            )
        }
    }
}
