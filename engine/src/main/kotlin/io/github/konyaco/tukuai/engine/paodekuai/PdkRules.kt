package io.github.konyaco.tukuai.engine.paodekuai

import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

data class PdkRules(
    /**
     * 16 or 15. Both deal every card of a deck without jokers or three of the 2s: 16 each
     * also drops one ace, 15 each drops one ace and three kings.
     */
    val handSize: Int = 16,
    /** A player who can beat the play on the table has to; passing is only for those who can't. */
    val mustBeat: Boolean = true,
    /** Four of a kind may carry three other cards, as a play of its own that is not a bomb. */
    val fourWithThree: Boolean = true,
    /** What a bomb that wins its round collects from each other player; 0 for nothing. */
    val bombPoints: Int = 10,
) {
    init {
        require(handSize == 15 || handSize == 16) { "Pao De Kuai deals 15 or 16 cards each" }
    }

    companion object {
        val SIXTEEN = PdkRules()
        val FIFTEEN = PdkRules(handSize = 15)
        val RELAXED = PdkRules(mustBeat = false)

        val PRESETS = listOf(
            RulesPreset("sixteen", SIXTEEN),
            RulesPreset("fifteen", FIFTEEN),
            RulesPreset("relaxed", RELAXED),
        )

        val CODEC = object : WireCodec<PdkRules> {
            override fun write(writer: WireWriter, value: PdkRules) {
                writer.int(value.handSize).bool(value.mustBeat).bool(value.fourWithThree).int(value.bombPoints)
            }

            override fun read(reader: WireReader): PdkRules = PdkRules(
                handSize = reader.int(),
                mustBeat = reader.bool(),
                fourWithThree = reader.bool(),
                bombPoints = reader.int(),
            )
        }
    }
}
