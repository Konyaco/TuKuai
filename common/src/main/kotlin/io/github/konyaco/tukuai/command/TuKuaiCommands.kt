package io.github.konyaco.tukuai.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.network.ReplayPayload
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.PlayerStats
import io.github.konyaco.tukuai.table.ReplayStore
import io.github.konyaco.tukuai.table.ServerSettings
import io.github.konyaco.tukuai.table.StatsStore
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.Component

/**
 * `/tukuai replays` lists the saved games, newest first, each one clickable;
 * `/tukuai replay <name>` opens one. `/tukuai stats [player]` shows a player's record and
 * `/tukuai top` the players with the most points. Anyone may use them: replays hold
 * nothing secret once the game is over.
 */
object TuKuaiCommands {
    private const val LIST_LIMIT = 10

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        // Commands are registered when a world starts and again on /reload: the times to read the settings.
        ServerSettings.load()
        dispatcher.register(
            Commands.literal("tukuai")
                .then(
                    Commands.literal("stats")
                        .executes { ctx -> showStats(ctx.source, null) }
                        .then(
                            Commands.argument("player", StringArgumentType.word())
                                .suggests { ctx, builder ->
                                    SharedSuggestionProvider.suggest(StatsStore.all(ctx.source.server).values.map { it.name }, builder)
                                }
                                .executes { ctx -> showStats(ctx.source, StringArgumentType.getString(ctx, "player")) },
                        ),
                )
                .then(Commands.literal("top").executes { ctx -> showTop(ctx.source) })
                .then(Commands.literal("replays").executes { ctx -> listReplays(ctx.source) })
                .then(
                    Commands.literal("replay").then(
                        Commands.argument("name", StringArgumentType.word())
                            .suggests { ctx, builder -> SharedSuggestionProvider.suggest(ReplayStore.list(ctx.source.server), builder) }
                            .executes { ctx -> openReplay(ctx.source, StringArgumentType.getString(ctx, "name")) },
                    ),
                ),
        )
    }

    private fun listReplays(source: CommandSourceStack): Int {
        val names = ReplayStore.list(source.server)
        if (names.isEmpty()) {
            source.sendSuccess({ Component.translatable("tukuai.command.replays.none") }, false)
            return 0
        }
        source.sendSuccess({ Component.translatable("tukuai.command.replays.header", names.size) }, false)
        for (name in names.take(LIST_LIMIT)) {
            val line = Component.literal(" - ").append(
                Component.literal(name).withStyle { style ->
                    style.withColor(ChatFormatting.AQUA)
                        .withUnderlined(true)
                        .withRunCommand("/tukuai replay $name")
                        .withShowText(Component.translatable("tukuai.command.replays.hover"))
                },
            )
            source.sendSuccess({ line }, false)
        }
        return names.size
    }

    /** A player's games, wins and points in each game; yours when no name is given. */
    private fun showStats(source: CommandSourceStack, name: String?): Int {
        val all = StatsStore.all(source.server)
        val stats = if (name == null) {
            all[source.playerOrException.uuid]
        } else {
            all.values.firstOrNull { it.name.equals(name, ignoreCase = true) }
        }
        val shownName = stats?.name ?: name ?: source.textName
        if (stats == null || stats.games.isEmpty()) {
            source.sendSuccess({ Component.translatable("tukuai.command.stats.none", shownName) }, false)
            return 0
        }
        source.sendSuccess({ Component.translatable("tukuai.command.stats.header", shownName) }, false)
        for ((game, record) in stats.games) {
            val line = Component.translatable(
                "tukuai.command.stats.line",
                Component.translatable("tukuai.game.$game"),
                record.games,
                record.wins,
                percent(record.wins, record.games),
                signed(record.points),
            )
            source.sendSuccess({ line }, false)
        }
        return stats.total.games
    }

    /** The players with the most points over all games. */
    private fun showTop(source: CommandSourceStack): Int {
        val ranked = StatsStore.all(source.server).values
            .filter { it.total.games > 0 }
            .sortedWith(compareByDescending<PlayerStats> { it.total.points }.thenByDescending { it.total.wins })
        if (ranked.isEmpty()) {
            source.sendSuccess({ Component.translatable("tukuai.command.top.none") }, false)
            return 0
        }
        source.sendSuccess({ Component.translatable("tukuai.command.top.header") }, false)
        ranked.take(LIST_LIMIT).forEachIndexed { i, stats ->
            val total = stats.total
            val line = Component.translatable(
                "tukuai.command.top.line", i + 1, stats.name, signed(total.points), total.games, total.wins,
            )
            source.sendSuccess({ line }, false)
        }
        return ranked.size
    }

    private fun percent(part: Int, whole: Int): Int = if (whole == 0) 0 else Math.round(part * 100f / whole)

    private fun signed(points: Int): String = if (points > 0) "+$points" else points.toString()

    private fun openReplay(source: CommandSourceStack, name: String): Int {
        val player = source.playerOrException
        val replay = ReplayStore.load(source.server, name)
        if (replay == null) {
            source.sendFailure(Component.translatable("tukuai.command.replay.missing", name))
            return 0
        }
        Services.platform.sendToPlayer(player, ReplayPayload(name, replay.encode()))
        return 1
    }
}
