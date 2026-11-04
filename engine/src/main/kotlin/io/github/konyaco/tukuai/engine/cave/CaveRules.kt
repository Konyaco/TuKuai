package io.github.konyaco.tukuai.engine.cave

import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

data class CaveRules(
    /** 2 to 8; more than four need a big table. */
    val players: Int = 4,
    /** How many times everyone goes down into the cave. */
    val rounds: Int = 5,
) {
    init {
        require(players in 2..8) { "The cave is for 2 to 8 players" }
        require(rounds in 1..9) { "1 to 9 rounds" }
    }

    companion object {
        val CLASSIC = CaveRules()
        val QUICK = CaveRules(rounds = 3)
        val THREE = CaveRules(players = 3)
        val SIX = CaveRules(players = 6)
        val EIGHT = CaveRules(players = 8)

        val PRESETS = listOf(
            RulesPreset("classic", CLASSIC),
            RulesPreset("quick", QUICK),
            RulesPreset("three", THREE),
            RulesPreset("six", SIX),
            RulesPreset("eight", EIGHT),
        )

        val CODEC = object : WireCodec<CaveRules> {
            override fun write(writer: WireWriter, value: CaveRules) {
                writer.int(value.players).int(value.rounds)
            }

            override fun read(reader: WireReader): CaveRules = CaveRules(players = reader.int(), rounds = reader.int())
        }
    }
}
