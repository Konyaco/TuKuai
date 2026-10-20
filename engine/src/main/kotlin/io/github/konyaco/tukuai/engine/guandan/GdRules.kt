package io.github.konyaco.tukuai.engine.guandan

import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

data class GdRules(
    /**
     * Keep dealing rounds, with the levels and the tribute carried over, until a team at A
     * wins a round played at its own A with the partner not last. Otherwise the game is one
     * round at level 2, scored by how far the winners would go up.
     */
    val toAce: Boolean = false,
) {
    companion object {
        val ONE_ROUND = GdRules()
        val TO_ACE = GdRules(toAce = true)

        val PRESETS = listOf(
            RulesPreset("one_round", ONE_ROUND),
            RulesPreset("to_ace", TO_ACE),
        )

        val CODEC = object : WireCodec<GdRules> {
            override fun write(writer: WireWriter, value: GdRules) {
                writer.bool(value.toAce)
            }

            override fun read(reader: WireReader): GdRules = GdRules(toAce = reader.bool())
        }
    }
}
