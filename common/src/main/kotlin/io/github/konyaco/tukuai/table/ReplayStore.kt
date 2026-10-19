package io.github.konyaco.tukuai.table

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.engine.core.Replay
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource

/** Finished games, kept as small files in the world folder under `tukuai/replays`. */
object ReplayStore {
    private const val EXTENSION = "tkr"
    private val NAME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val VALID_NAME = Regex("[A-Za-z0-9_-]{1,64}")

    fun folder(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve(TuKuai.MOD_ID).resolve("replays")

    /** Saves a replay and returns its name, or null if it could not be written. */
    fun save(server: MinecraftServer, replay: Replay): String? = try {
        val dir = folder(server)
        Files.createDirectories(dir)
        val base = "${LocalDateTime.now().format(NAME_FORMAT)}-${replay.gameId}"
        var name = base
        var n = 1
        while (Files.exists(dir.resolve("$name.$EXTENSION"))) name = "$base-${++n}"
        Files.write(dir.resolve("$name.$EXTENSION"), replay.encode())
        name
    } catch (e: Exception) {
        TuKuai.LOGGER.warn("Could not save a replay", e)
        null
    }

    /** Replay names, newest first. */
    fun list(server: MinecraftServer): List<String> = try {
        val dir = folder(server)
        if (!Files.isDirectory(dir)) emptyList()
        else dir.listDirectoryEntries().filter { it.extension == EXTENSION }.map { it.nameWithoutExtension }.sortedDescending()
    } catch (e: Exception) {
        emptyList()
    }

    fun load(server: MinecraftServer, name: String): Replay? {
        if (!VALID_NAME.matches(name)) return null
        return try {
            Replay.decode(Files.readAllBytes(folder(server).resolve("$name.$EXTENSION")))
        } catch (e: Exception) {
            null
        }
    }
}
