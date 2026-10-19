package io.github.konyaco.tukuai.table

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import io.github.konyaco.tukuai.TuKuai
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.storage.LevelResource

/** One player's record in one game. */
data class GameRecord(var games: Int = 0, var wins: Int = 0, var points: Int = 0)

/** One player's records, by game id, under the name they last played with. */
class PlayerStats(var name: String) {
    val games = LinkedHashMap<String, GameRecord>()

    val total: GameRecord
        get() = games.values.fold(GameRecord()) { sum, r -> GameRecord(sum.games + r.games, sum.wins + r.wins, sum.points + r.points) }
}

/**
 * Every player's games, wins and points at the card tables, kept in the world folder in
 * `tukuai/stats.json`. Only people are counted; computer players have no record.
 */
object StatsStore {
    private val GSON = GsonBuilder().setPrettyPrinting().create()

    private var loadedFor: MinecraftServer? = null
    private val players = HashMap<UUID, PlayerStats>()

    private fun file(server: MinecraftServer): Path =
        server.getWorldPath(LevelResource.ROOT).resolve(TuKuai.MOD_ID).resolve("stats.json")

    /** All records of this server's world, read from disk the first time. */
    fun all(server: MinecraftServer): Map<UUID, PlayerStats> {
        if (loadedFor !== server) load(server)
        return players
    }

    fun of(server: MinecraftServer, uuid: UUID): PlayerStats? = all(server)[uuid]

    /** Adds a finished game: [scores] and [winners] by seat, for the seats people sat in. */
    fun record(server: MinecraftServer, gameId: String, seats: List<Occupant?>, scores: List<Int>, winners: Set<Int>) {
        if (!ServerSettings.keepStats) return
        all(server)
        var changed = false
        seats.forEachIndexed { seat, occupant ->
            if (occupant !is Occupant.Human) return@forEachIndexed
            val stats = players.getOrPut(occupant.uuid) { PlayerStats(occupant.name) }
            stats.name = occupant.name
            val record = stats.games.getOrPut(gameId) { GameRecord() }
            record.games++
            if (seat in winners) record.wins++
            record.points += scores.getOrElse(seat) { 0 }
            changed = true
        }
        if (changed) save(server)
    }

    private fun load(server: MinecraftServer) {
        loadedFor = server
        players.clear()
        try {
            val f = file(server)
            if (!Files.exists(f)) return
            val root = Files.newBufferedReader(f).use { JsonParser.parseReader(it) }.asJsonObject
            for ((id, value) in root.entrySet()) {
                val uuid = runCatching { UUID.fromString(id) }.getOrNull() ?: continue
                val obj = value.asJsonObject
                val stats = PlayerStats(obj.get("name")?.asString ?: "?")
                obj.getAsJsonObject("games")?.entrySet()?.forEach { (game, r) ->
                    val record = r.asJsonObject
                    stats.games[game] = GameRecord(
                        record.get("games")?.asInt ?: 0,
                        record.get("wins")?.asInt ?: 0,
                        record.get("points")?.asInt ?: 0,
                    )
                }
                players[uuid] = stats
            }
        } catch (e: Exception) {
            TuKuai.LOGGER.warn("Could not read the TuKuai player records", e)
        }
    }

    private fun save(server: MinecraftServer) {
        try {
            val root = JsonObject()
            for ((uuid, stats) in players) {
                val games = JsonObject()
                for ((game, r) in stats.games) {
                    games.add(game, JsonObject().apply {
                        addProperty("games", r.games)
                        addProperty("wins", r.wins)
                        addProperty("points", r.points)
                    })
                }
                root.add(uuid.toString(), JsonObject().apply {
                    addProperty("name", stats.name)
                    add("games", games)
                })
            }
            val f = file(server)
            Files.createDirectories(f.parent)
            // Write beside it first, so a crash halfway never leaves a broken file.
            val temp = f.resolveSibling("stats.json.tmp")
            Files.writeString(temp, GSON.toJson(root))
            Files.move(temp, f, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            TuKuai.LOGGER.warn("Could not save the TuKuai player records", e)
        }
    }
}
