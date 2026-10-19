package io.github.konyaco.tukuai.engine.uno

import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

data class UnoRules(
    /** 2 to 4; a card table has four sides. */
    val players: Int = 4,
    val handSize: Int = 7,
    /**
     * A player hit by a draw two may pass it on with another draw two, or a wild draw
     * four; a wild draw four may only be answered with another one. The player who
     * cannot answer draws everything that piled up.
     */
    val stacking: Boolean = false,
    /** A wild draw four may only be played by someone holding nothing of the colour to follow. */
    val strictWildFour: Boolean = true,
    /** Cards drawn by a player who goes down to one card without calling UNO. */
    val unoPenalty: Int = 2,
) {
    init {
        require(players in 2..4) { "UNO here is for 2 to 4 players" }
    }

    companion object {
        val CLASSIC = UnoRules()
        val STACKING = UnoRules(stacking = true)
        val THREE = UnoRules(players = 3)
        val TWO = UnoRules(players = 2)

        val PRESETS = listOf(
            RulesPreset("classic", CLASSIC),
            RulesPreset("stacking", STACKING),
            RulesPreset("three", THREE),
            RulesPreset("two", TWO),
        )

        val CODEC = object : WireCodec<UnoRules> {
            override fun write(writer: WireWriter, value: UnoRules) {
                writer.int(value.players).int(value.handSize).bool(value.stacking).bool(value.strictWildFour)
                    .int(value.unoPenalty)
            }

            override fun read(reader: WireReader): UnoRules = UnoRules(
                players = reader.int(),
                handSize = reader.int(),
                stacking = reader.bool(),
                strictWildFour = reader.bool(),
                unoPenalty = reader.int(),
            )
        }
    }
}
