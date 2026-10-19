package io.github.konyaco.tukuai.table

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.platform.Services
import java.nio.file.Files
import java.util.Properties

/**
 * Choices for whoever runs the server, in `config/tukuai-server.properties`. The file is
 * read, and written back with any missing settings at their defaults, when a world starts
 * and on `/reload`.
 */
object ServerSettings {
    /** Whether computer players may take seats. */
    var allowBots = true
        private set

    /** How long a computer waits before it acts, on average, in seconds. */
    var botDelaySeconds = 1.1
        private set

    /** Whether finished games are kept as replays. */
    var saveReplays = true
        private set

    /** How many replays to keep; the oldest go first. 0 keeps them all. */
    var maxReplays = 500
        private set

    /** Whether finished games count towards each player's record. */
    var keepStats = true
        private set

    private val file get() = Services.platform.configDir().resolve("tukuai-server.properties")

    fun load() {
        try {
            val f = file
            val p = Properties()
            if (Files.exists(f)) Files.newBufferedReader(f).use(p::load)
            allowBots = p.getProperty("allowBots")?.toBooleanStrictOrNull() ?: true
            botDelaySeconds = p.getProperty("botDelaySeconds")?.toDoubleOrNull()?.coerceIn(0.0, 10.0) ?: 1.1
            saveReplays = p.getProperty("saveReplays")?.toBooleanStrictOrNull() ?: true
            maxReplays = p.getProperty("maxReplays")?.toIntOrNull()?.coerceAtLeast(0) ?: 500
            keepStats = p.getProperty("keepStats")?.toBooleanStrictOrNull() ?: true
            // Write it back, so the file lists every setting, new ones included.
            p.setProperty("allowBots", allowBots.toString())
            p.setProperty("botDelaySeconds", botDelaySeconds.toString())
            p.setProperty("saveReplays", saveReplays.toString())
            p.setProperty("maxReplays", maxReplays.toString())
            p.setProperty("keepStats", keepStats.toString())
            Files.createDirectories(f.parent)
            Files.newBufferedWriter(f).use { p.store(it, COMMENT) }
        } catch (e: Exception) {
            TuKuai.LOGGER.warn("Could not read the TuKuai server settings", e)
        }
    }

    private val COMMENT = """
        TuKuai server settings. Changes take effect on /reload or the next time the world starts.
        allowBots: whether computer players may take seats (true/false)
        botDelaySeconds: how long a computer waits before acting, on average
        saveReplays: whether finished games are saved as replays (true/false)
        maxReplays: how many replays to keep, oldest removed first; 0 keeps all
        keepStats: whether finished games count towards each player's record (true/false)
    """.trimIndent()
}
