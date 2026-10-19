package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.engine.core.BotLevel
import java.nio.file.Files
import java.util.Properties
import net.minecraft.client.Minecraft

/** Personal choices that only change how this client draws things, saved in `config/tukuai-client.properties`. */
object ClientConfig {
    /** Which card back to use, see [CardArt.BACK_COUNT]. */
    var cardBack = 0
    var immersive = false
    var botLevel = BotLevel.NORMAL

    init {
        load()
    }

    private val file get() = Minecraft.getInstance().gameDirectory.toPath().resolve("config").resolve("tukuai-client.properties")

    private fun load() {
        try {
            val f = file
            if (!Files.exists(f)) return
            val p = Properties()
            Files.newBufferedReader(f).use(p::load)
            cardBack = p.getProperty("cardBack")?.toIntOrNull()?.takeIf { it in 0 until CardArt.BACK_COUNT } ?: 0
            immersive = p.getProperty("immersive") == "true"
            botLevel = BotLevel.entries.firstOrNull { it.name == p.getProperty("botLevel") } ?: BotLevel.NORMAL
        } catch (e: Exception) {
            TuKuai.LOGGER.warn("Could not read the TuKuai client settings", e)
        }
    }

    fun save() {
        try {
            val f = file
            Files.createDirectories(f.parent)
            val p = Properties()
            p.setProperty("cardBack", cardBack.toString())
            p.setProperty("immersive", immersive.toString())
            p.setProperty("botLevel", botLevel.name)
            Files.newBufferedWriter(f).use { p.store(it, "TuKuai client settings") }
        } catch (e: Exception) {
            TuKuai.LOGGER.warn("Could not save the TuKuai client settings", e)
        }
    }
}
