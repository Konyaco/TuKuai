package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.compat.currentScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.core.GameRegistry
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.ServerSettings
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.components.Button
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW

/**
 * The lobby of a big table for every game and a few of their rules, from two seats to
 * eight: its buttons stay where they are as the game and the rules change. A game is picked
 * from the list under the game button with the mouse, and at a 3x3 table the rules for more
 * than four are in the list but cannot be picked. After a game the result offers ending the
 * match, and once a seat empties it sends the player to fill the seats; in the lobby a
 * player may take a computer's seat. `TUKUAI_ONLY=lobby` runs just this;
 * `TUKUAI_LANG` picks the language of the pictures.
 */
class LobbyGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (System.getenv("TUKUAI_SHOWCASE") != null || System.getenv("TUKUAI_MULTIPLAYER") != null) return
        if (System.getenv("TUKUAI_ONLY")?.let { it != "lobby" } == true) return
        val lang = System.getenv("TUKUAI_LANG") ?: "en_us"
        if (lang != "en_us") setLanguage(context, lang)
        try {
            look(context)
        } finally {
            if (lang != "en_us") setLanguage(context, "en_us")
        }
    }

    private fun look(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            val table = server.computeOnServer<BlockPos, RuntimeException> { s -> buildScene(s) }
            val small = table.east(5)
            context.waitTicks(40)
            world.clientLevel.waitForChunksRender()

            act(server, table, TableOp.SIT, 0)
            context.setScreen { TableScreen(table) }
            context.waitTicks(10)
            val buttons = HashSet<List<Int>>()
            for (game in GameRegistry.all) {
                for (preset in game.presets) {
                    if (preset.id != game.presets.first().id && preset.id !in WIDE) continue
                    act(server, table, TableOp.GAME, text = game.id)
                    act(server, table, TableOp.PRESET, text = preset.id)
                    context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == game.id && it.presetId == preset.id } == true }, 100)
                    context.waitTicks(5)
                    shot(context, "lobby-${game.id}-${preset.id}")
                    buttons.add(
                        listOf("tukuai.table.game", "tukuai.table.rules", "tukuai.table.start").flatMap { key ->
                            context.computeOnClient<List<Int>, RuntimeException> { mc -> button(mc, key).let { listOf(it.x, it.y, it.width) } }
                        },
                    )
                }
            }
            check(buttons.size == 1) { "The lobby's buttons moved as the game changed: $buttons" }

            // The game button opens the list of games; a click on one picks it.
            act(server, table, TableOp.GAME, text = "doudizhu")
            context.waitFor({ ClientTables[table]?.snapshot?.gameId == "doudizhu" }, 100)
            context.waitTicks(5)
            click(context, "tukuai.table.game")
            val games = GameRegistry.all.map { it.id }
            hoverRow(context, "tukuai.table.game", games.indexOf("tutu"))
            context.waitTicks(5)
            shot(context, "lobby-list-games")
            clickRow(context, "tukuai.table.game", games.indexOf("uno"))
            context.waitFor({ ClientTables[table]?.snapshot?.gameId == "uno" }, 100)
            context.waitTicks(5)
            shot(context, "lobby-picked-uno")

            // At a 3x3 table the rules for six and eight are in the list, but picking them does nothing.
            act(server, table, TableOp.STAND)
            act(server, small, TableOp.SIT, 0)
            act(server, small, TableOp.GAME, text = "tutu")
            context.setScreen { TableScreen(small) }
            context.waitFor({ ClientTables[small]?.snapshot?.gameId == "tutu" }, 100)
            context.waitTicks(10)
            click(context, "tukuai.table.rules")
            context.waitTicks(5)
            val eight = GameRegistry["tutu"]!!.presets.indexOfFirst { it.id == "eight" }
            hoverRow(context, "tukuai.table.rules", eight)
            context.waitTicks(5)
            shot(context, "lobby-list-rules-small")
            context.input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
            context.waitTicks(10)
            check(ClientTables[small]!!.snapshot.presetId != "eight") { "A 3x3 table took the rules for eight" }
            clickRow(context, "tukuai.table.rules", GameRegistry["tutu"]!!.presets.indexOfFirst { it.id == "two" })
            context.waitFor({ ClientTables[small]?.snapshot?.presetId == "two" }, 100)
            context.waitTicks(5)
            shot(context, "lobby-small-two")
            context.setScreen { TableScreen(table) }
            act(server, table, TableOp.SIT, 0)
            // A table with computers and a match on, as it looks between games.
            act(server, table, TableOp.GAME, text = "doudizhu")
            context.waitFor({ ClientTables[table]?.snapshot?.gameId == "doudizhu" }, 100)
            // The start button says why it is off.
            hover(context, "tukuai.table.start")
            context.waitTicks(15)
            shot(context, "lobby-doudizhu-empty-seats")
            act(server, table, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
            context.waitFor({ ClientTables[table]?.snapshot?.seats?.all { it.occupant != null } == true }, 100)
            context.waitTicks(5)
            shot(context, "lobby-doudizhu-bots")

            // After a game: play again, the replay, and ending the match are all under the result.
            server.runOnServer<RuntimeException> {
                val field = ServerSettings::class.java.getDeclaredField("botDelaySeconds")
                field.isAccessible = true
                field.set(null, 0.0)
            }
            act(server, table, TableOp.START)
            context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
            act(server, table, TableOp.AUTO, 1)
            context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.FINISHED }, 6000)
            context.waitTicks(20)
            shot(context, "after-game")
            context.computeOnClient<Boolean, RuntimeException> { mc -> button(mc, "tukuai.table.end.short").active }.let { check(it) { "No way to end the match after a game" } }

            // A computer is sent off (as a player standing up would leave it): the seats come first.
            act(server, table, TableOp.REMOVE_BOT, 1)
            context.waitFor({ ClientTables[table]?.snapshot?.seats?.get(1)?.occupant == null }, 100)
            context.waitTicks(5)
            hover(context, "tukuai.table.fill_seats")
            context.waitTicks(15)
            shot(context, "after-game-seat-empty")
            click(context, "tukuai.table.fill_seats")
            context.waitTicks(10)
            shot(context, "after-game-lobby")

            // In the lobby a player may take a computer's seat straight away, and end the match from there.
            act(server, table, TableOp.SIT, 2)
            context.waitFor({ ClientTables[table]?.snapshot?.you == 2 }, 100)
            check(ClientTables[table]!!.snapshot.seats[0].occupant == null) { "Moving to a computer's seat did not free the old one" }
            context.waitTicks(5)
            click(context, "tukuai.table.end.short")
            context.waitTicks(5)
            shot(context, "after-game-end-prompt")
            context.input.pressKey(GLFW.GLFW_KEY_ESCAPE)
        }
    }

    /** The lobby button whose text starts with the language key [key]. */
    private fun button(mc: Minecraft, key: String): Button = (mc.currentScreen as TableScreen).children().filterIsInstance<Button>().first { b ->
        (b.message.contents as? TranslatableContents)?.key == key || (b.message.siblings.firstOrNull()?.contents as? TranslatableContents)?.key == key
    }

    /** Moves the mouse to ([x], [y]) in GUI pixels. */
    private fun moveMouse(context: ClientGameTestContext, x: Double, y: Double) {
        val (sx, sy) = context.computeOnClient<Pair<Double, Double>, RuntimeException> { mc ->
            val window = mc.window
            x * window.screenWidth / window.guiScaledWidth to y * window.screenHeight / window.guiScaledHeight
        }
        context.input.setCursorPos(sx, sy)
        context.waitTicks(2)
    }

    private fun hover(context: ClientGameTestContext, key: String) {
        val (x, y) = context.computeOnClient<Pair<Double, Double>, RuntimeException> { mc ->
            button(mc, key).let { it.x + it.width / 2.0 to it.y + it.height / 2.0 }
        }
        moveMouse(context, x, y)
    }

    private fun click(context: ClientGameTestContext, key: String) {
        hover(context, key)
        context.input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
    }

    /** Puts the mouse on line [row] of the list that dropped down from the button of [key]. */
    private fun hoverRow(context: ClientGameTestContext, key: String, row: Int) {
        val (x, y) = context.computeOnClient<Pair<Double, Double>, RuntimeException> { mc ->
            button(mc, key).let { it.x + 30.0 to it.y + it.height + 1 + 2 + row * 14 + 7.0 }
        }
        moveMouse(context, x, y)
    }

    private fun clickRow(context: ClientGameTestContext, key: String, row: Int) {
        hoverRow(context, key, row)
        context.input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
    }

    private fun setLanguage(context: ClientGameTestContext, code: String) {
        context.runOnClient<RuntimeException> { mc ->
            mc.options.languageCode = code
            mc.languageManager.setSelected(code)
            mc.reloadResourcePacks()
        }
        context.waitTicks(2)
        context.waitFor({ it.overlay == null }, 1200)
        context.waitTicks(10)
    }

    /** A big table north of the player, placed from its item as a player would. */
    private fun buildScene(server: MinecraftServer): BlockPos {
        val player = server.playerList.players.first()
        val level = server.overworld()
        val base = player.blockPosition()
        server.commands.performPrefixedCommand(server.createCommandSourceStack(), "time set noon")
        for (x in -6..6) for (z in -8..4) {
            for (y in 0..5) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState())
            level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.SPRUCE_PLANKS.defaultBlockState())
        }
        player.teleportTo(base.x + 0.5, base.y.toDouble(), base.z + 0.5)
        player.setYRot(180f)
        val clicked = base.north()
        val stack = ItemStack(TuKuaiContent.BIG_CARD_TABLE_ITEM)
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        val hit = BlockHitResult(Vec3.atCenterOf(clicked.below()).add(0.0, 0.5, 0.0), Direction.UP, clicked.below(), false)
        stack.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))
        val table = clicked.north(2)
        check(level.getBlockEntity(table) is CardTableBlockEntity) { "The big table has no block entity in the middle" }
        val small = table.east(5)
        for (part in 0 until CardTableBlock.PARTS) {
            level.setBlockAndUpdate(
                CardTableBlock.posOf(small, part),
                TuKuaiContent.CARD_TABLE.defaultBlockState().setValue(CardTableBlock.FACING, Direction.SOUTH).setValue(CardTableBlock.PART, part),
            )
        }
        return table
    }

    private fun act(server: TestServerContext, pos: BlockPos, op: TableOp, a: Int = 0, b: Int = 0, text: String = "") {
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            (s.overworld().getBlockEntity(pos) as CardTableBlockEntity).handle(player, TableActionPayload(pos, op, a, b, text, ByteArray(0)))
        }
    }

    private fun shot(context: ClientGameTestContext, name: String) {
        val path = context.takeScreenshot("tukuai-$name")
        TuKuai.LOGGER.info("SCREENSHOT {}", path.toAbsolutePath())
    }

    companion object {
        /** Rules besides each game's first that change the seats: two players, and six or eight at the big table. */
        private val WIDE = setOf("two", "six", "eight")
    }
}
