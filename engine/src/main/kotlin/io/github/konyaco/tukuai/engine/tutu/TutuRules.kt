package io.github.konyaco.tukuai.engine.tutu

import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

data class TutuRules(
    /** 2 to 4; a card table has four sides. */
    val players: Int = 4,
    val handSize: Int = 7,
    /** Adds [TutuDeck.PARTY_EXTRA] more lightning, eggs, TNT and cacti to the deck. */
    val party: Boolean = false,
) {
    init {
        require(players in 2..4) { "Tutu cards are for 2 to 4 players" }
        require(handSize in 1..10) { "Hands of 1 to 10 cards" }
    }

    companion object {
        val CLASSIC = TutuRules()
        val PARTY = TutuRules(party = true)
        val THREE = TutuRules(players = 3)
        val TWO = TutuRules(players = 2)

        val PRESETS = listOf(
            RulesPreset("classic", CLASSIC),
            RulesPreset("party", PARTY),
            RulesPreset("three", THREE),
            RulesPreset("two", TWO),
        )

        val CODEC = object : WireCodec<TutuRules> {
            override fun write(writer: WireWriter, value: TutuRules) {
                writer.int(value.players).int(value.handSize).bool(value.party)
            }

            override fun read(reader: WireReader): TutuRules = TutuRules(
                players = reader.int(),
                handSize = reader.int(),
                party = reader.bool(),
            )
        }
    }
}
