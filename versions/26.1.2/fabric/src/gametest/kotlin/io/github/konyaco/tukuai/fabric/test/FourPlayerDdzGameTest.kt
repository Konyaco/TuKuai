package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.StoolBlock
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.doudizhu.DdzAction
import io.github.konyaco.tukuai.engine.doudizhu.DdzCodecs
import io.github.konyaco.tukuai.engine.doudizhu.DdzEventKind
import io.github.konyaco.tukuai.engine.doudizhu.DdzPhase
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.doudizhu.Patterns
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.ServerSettings
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Blocks
import org.lwjgl.glfw.GLFW

/**
 * Four-player Dou Dizhu at a 3x3 table with three computers: the table seats four for the
 * preset, everyone gets 25 cards, the landlord takes eight more, and a hand of two decks is
 * picked with a hint and played from the screen, then the computer finishes the game.
 * `TUKUAI_ONLY=ddz4` runs just this; `TUKUAI_LANG` picks the language of the pictures.
 */
class FourPlayerDdzGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (System.getenv("TUKUAI_SHOWCASE") != null || System.getenv("TUKUAI_MULTIPLAYER") != null) return
        // TUKUAI_ONLY=<name> runs just one of the tests, to try it quickly.
        if (System.getenv("TUKUAI_ONLY")?.let { it != "ddz4" } == true) return
        val lang = System.getenv("TUKUAI_LANG") ?: "en_us"
        if (lang != "en_us") setLanguage(context, lang)
        try {
            play(context)
        } finally {
            if (lang != "en_us") setLanguage(context, "en_us")
        }
    }

    private fun play(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            val table = server.computeOnServer<BlockPos, RuntimeException> { s -> buildScene(s) }
            context.waitTicks(40)
            world.clientLevel.waitForChunksRender()
            botDelay(server, 0.0)

            act(server, table, TableOp.TIMER, 0)
            act(server, table, TableOp.PRESET, text = "four")
            act(server, table, TableOp.SIT, 0)
            act(server, table, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
            context.waitFor({ ClientTables[table]?.snapshot?.seats?.let { s -> s.size == 4 && s.all { it.occupant != null } } == true }, 100)
            context.setScreen { TableScreen(table) }
            context.waitTicks(10)
            shot(context, "ddz4-lobby")

            context.setScreen { null }
            act(server, table, TableOp.START)
            context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
            context.input.lookAt(table)
            context.waitTicks(30)
            shot(context, "ddz4-world-dealt")
            val dealt = ClientTables[table]!!.view as DdzView
            check(dealt.handCounts == List(4) { 25 }) { "Dealt ${dealt.handCounts}, not 25 each" }

            context.setScreen { TableScreen(table) }
            context.waitTicks(20)
            shot(context, "ddz4-screen-bidding")

            // Bid 3 to be the landlord if nobody beat us to it, then wait for a turn with cards to play.
            var tries = 0
            while (true) {
                check(++tries < 60) { "Never got a turn to play cards" }
                context.waitFor({ (ClientTables[table]?.view as? DdzView)?.isMyTurn == true }, 2400)
                val view = ClientTables[table]!!.view as DdzView
                val action: DdzAction = when (view.phase) {
                    DdzPhase.BIDDING -> DdzAction.Bid(view.allowedBids.max())
                    DdzPhase.DOUBLING -> DdzAction.Double(false)
                    DdzPhase.PLAYING -> if (Patterns.options(view.hand, view.toBeat, view.rules).isEmpty()) DdzAction.Pass else break
                    DdzPhase.FINISHED -> error("The game ended before we played")
                }
                val step = ClientTables[table]!!.snapshot.step
                act(server, table, TableOp.ACT, data = DdzCodecs.ACTION.encode(action))
                context.waitFor({ (ClientTables[table]?.snapshot?.step ?: step) != step }, 100)
            }
            val before = (ClientTables[table]!!.view as DdzView)
            TuKuai.LOGGER.info("Four-player Dou Dizhu: landlord {}, we hold {}", before.landlord, before.hand.size)
            check(before.kitty.size == 8) { "The landlord took ${before.kitty.size} cards, not 8" }
            val landlord = before.landlord!!
            val played = before.log.filter { it.seat == landlord && it.kind == DdzEventKind.PLAY }.sumOf { it.cards.size }
            check(before.handCounts[landlord] + played == 33) { "The landlord did not start with 33 cards" }
            context.waitTicks(10)
            shot(context, "ddz4-screen-turn")

            // H picks what the computer would play; Enter plays it.
            context.input.pressKey(GLFW.GLFW_KEY_H)
            context.waitTicks(5)
            shot(context, "ddz4-screen-hint")
            context.input.pressKey(GLFW.GLFW_KEY_ENTER)
            context.waitFor({ (ClientTables[table]?.view as? DdzView)?.let { it.hand.size < before.hand.size } == true }, 100)
            context.waitTicks(10)
            shot(context, "ddz4-screen-played")

            context.runOnClient<RuntimeException> {
                ClientConfig.immersive = true
                (it.screen as? TableScreen)?.refresh()
            }
            context.waitTicks(15)
            shot(context, "ddz4-screen-immersive")
            context.runOnClient<RuntimeException> {
                ClientConfig.immersive = false
                (it.screen as? TableScreen)?.refresh()
            }

            // The computer plays the rest for us.
            act(server, table, TableOp.AUTO, 1)
            context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.FINISHED }, 6000)
            context.waitTicks(20)
            shot(context, "ddz4-screen-result")
            val result = ClientTables[table]!!.view as DdzView
            check(result.settlement!!.scores.sum() == 0) { "Scores ${result.settlement!!.scores} do not add up to zero" }
            context.setScreen { null }
            context.input.lookAt(table)
            context.waitTicks(20)
            shot(context, "ddz4-world-result")
        }
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

    /** A 3x3 table north of the player with a stool on every side, in daylight. */
    private fun buildScene(server: MinecraftServer): BlockPos {
        val player = server.playerList.players.first()
        val level = server.overworld()
        server.commands.performPrefixedCommand(server.createCommandSourceStack(), "time set noon")
        server.commands.performPrefixedCommand(server.createCommandSourceStack(), "weather clear")
        val base = player.blockPosition()
        for (x in -6..6) for (z in -8..4) {
            for (y in 0..4) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState())
            level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.SPRUCE_PLANKS.defaultBlockState())
        }
        val table = base.north(3)
        for (part in 0 until CardTableBlock.PARTS) {
            level.setBlockAndUpdate(
                CardTableBlock.posOf(table, part),
                TuKuaiContent.CARD_TABLE.defaultBlockState().setValue(CardTableBlock.FACING, Direction.SOUTH).setValue(CardTableBlock.PART, part),
            )
        }
        for (side in listOf(Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST)) {
            level.setBlockAndUpdate(table.relative(side, StoolBlock.TABLE_DISTANCE), TuKuaiContent.STOOL.defaultBlockState())
        }
        return table
    }

    /** Sets how long computers wait before they play; the setting is only read from the config file otherwise. */
    private fun botDelay(server: TestServerContext, seconds: Double) {
        server.runOnServer<RuntimeException> {
            val field = ServerSettings::class.java.getDeclaredField("botDelaySeconds")
            field.isAccessible = true
            field.set(null, seconds)
        }
    }

    private fun act(server: TestServerContext, pos: BlockPos, op: TableOp, a: Int = 0, b: Int = 0, text: String = "", data: ByteArray = ByteArray(0)) {
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            val table = s.overworld().getBlockEntity(pos) as CardTableBlockEntity
            table.handle(player, TableActionPayload(pos, op, a, b, text, data))
        }
    }

    private fun shot(context: ClientGameTestContext, name: String) {
        val path = context.takeScreenshot("tukuai-$name")
        TuKuai.LOGGER.info("SCREENSHOT {}", path.toAbsolutePath())
    }
}
