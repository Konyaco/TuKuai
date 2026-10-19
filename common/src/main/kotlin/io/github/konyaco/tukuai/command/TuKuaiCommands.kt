package io.github.konyaco.tukuai.command

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import io.github.konyaco.tukuai.network.ReplayPayload
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.ReplayStore
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.SharedSuggestionProvider
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/**
 * `/tukuai replays` lists the saved games, newest first, each one clickable;
 * `/tukuai replay <name>` opens one. Anyone may use them: replays hold nothing secret
 * once the game is over.
 */
object TuKuaiCommands {
    private const val LIST_LIMIT = 10

    fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("tukuai")
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
                        .withClickEvent(ClickEvent.RunCommand("/tukuai replay $name"))
                        .withHoverEvent(HoverEvent.ShowText(Component.translatable("tukuai.command.replays.hover")))
                },
            )
            source.sendSuccess({ line }, false)
        }
        return names.size
    }

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
