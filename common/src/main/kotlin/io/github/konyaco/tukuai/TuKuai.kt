package io.github.konyaco.tukuai

import net.minecraft.resources.Identifier
import org.slf4j.Logger
import org.slf4j.LoggerFactory

object TuKuai {
    const val MOD_ID = "tukuai"

    val LOGGER: Logger = LoggerFactory.getLogger(MOD_ID)

    fun id(path: String): Identifier = Identifier.fromNamespaceAndPath(MOD_ID, path)
}
