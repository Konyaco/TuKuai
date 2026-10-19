package io.github.konyaco.tukuai

import io.github.konyaco.tukuai.engine.core.GameRegistry
import io.github.konyaco.tukuai.engine.doudizhu.DouDizhu
import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object TuKuai {
    const val MOD_ID = "tukuai"

    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)

    /** Runs once on both sides when the mod loads, before any world opens. */
    fun init() {
        if (GameRegistry[DouDizhu.id] == null) GameRegistry.register(DouDizhu)
    }
}
