package io.github.konyaco.tukuai

import io.github.konyaco.tukuai.engine.core.GameRegistry
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import io.github.konyaco.tukuai.engine.guandan.Guandan
import io.github.konyaco.tukuai.engine.paodekuai.PaoDeKuai
import io.github.konyaco.tukuai.engine.uno.Uno
import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object TuKuai {
    const val MOD_ID = "tukuai"

    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)

    /** Runs once on both sides when the mod loads, before any world opens. */
    fun init() {
        for (game in listOf(DouDizhu, Uno, PaoDeKuai, Guandan)) if (GameRegistry[game.id] == null) GameRegistry.register(game)
    }
}
