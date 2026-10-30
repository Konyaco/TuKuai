package io.github.konyaco.tukuai.engine.mahjong.sichuan

import io.github.konyaco.tukuai.engine.core.RulesPreset
import io.github.konyaco.tukuai.engine.core.WireCodec
import io.github.konyaco.tukuai.engine.core.WireReader
import io.github.konyaco.tukuai.engine.core.WireWriter

/** Sichuan mahjong, blood battle (血战到底): the rules a table may change. */
data class ScRules(
    /** Before play each player passes three tiles of one suit on (换三张). */
    val swapThree: Boolean = true,
    /** The most fan a hand counts for; 0 for no limit. */
    val maxFan: Int = 3,
    /** A dealer who wins on the hand dealt, or another player on their first tile, scores the most there is. */
    val heavenly: Boolean = true,
) {
    init {
        require(maxFan in 0..8) { "A limit of 0 to 8 fan" }
    }

    /** The fan a hand at the limit counts: [maxFan], or with no limit [UNCAPPED_TOP]. */
    val topFan: Int get() = if (maxFan > 0) maxFan else UNCAPPED_TOP

    /** Points for a hand of [fan]: the base doubled once for each, up to the limit. */
    fun points(fan: Int): Int = BASE shl (if (maxFan > 0) minOf(fan, maxFan) else fan)

    companion object {
        /** Points everything is counted in: a hand of no fan wins one. */
        const val BASE = 1

        /** Heavenly hands and players caught holding their void suit count this with no limit. */
        const val UNCAPPED_TOP = 5

        val BLOOD = ScRules()
        val NO_SWAP = ScRules(swapThree = false)

        val PRESETS = listOf(
            RulesPreset("blood", BLOOD),
            RulesPreset("no_swap", NO_SWAP),
        )

        val CODEC = object : WireCodec<ScRules> {
            override fun write(writer: WireWriter, value: ScRules) {
                writer.bool(value.swapThree).int(value.maxFan).bool(value.heavenly)
            }

            override fun read(reader: WireReader): ScRules = ScRules(
                swapThree = reader.bool(),
                maxFan = reader.int(),
                heavenly = reader.bool(),
            )
        }
    }
}
