package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.SeatEntity
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer

/**
 * Plays one round at a card table in a real client: sits down, fills the table with
 * computers, lets the computer play for us, and takes a screenshot at each stage.
 * CI prints the screenshots so they can be looked at without downloading anything.
 */
class TuKuaiClientGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            world.clientLevel.waitForChunksRender()
            val server = world.server
            val table = server.computeOnServer<BlockPos, RuntimeException> { s -> buildScene(s) }
            context.waitTicks(40)
            world.clientLevel.waitForChunksRender()

            act(server, table, TableOp.SIT, 0)
            check(server.computeOnServer<Boolean, RuntimeException> { s -> s.playerList.players.first().vehicle is SeatEntity }) {
                "Sitting down did not put the player on a seat"
            }
            act(server, table, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
            context.waitTicks(20)
            context.input.lookAt(table)
            context.waitTicks(5)
            shot(context, "1-world-lobby")

            context.setScreen { TableScreen(table) }
            context.waitTicks(10)
            shot(context, "2-screen-lobby")

            act(server, table, TableOp.START)
            context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
            context.waitTicks(10)
            shot(context, "3-screen-bidding")

            context.runOnClient<RuntimeException> {
                ClientConfig.immersive = true
                (it.screen as? TableScreen)?.refresh()
            }
            context.waitTicks(5)
            shot(context, "4-screen-immersive")

            // Let the computer play for us until the round ends.
            act(server, table, TableOp.AUTO, 1)
            context.runOnClient<RuntimeException> {
                ClientConfig.immersive = false
                (it.screen as? TableScreen)?.refresh()
            }
            context.waitTicks(300)
            shot(context, "5-screen-midgame")
            context.setScreen { null }
            context.waitTicks(5)
            shot(context, "6-world-midgame")

            var waited = 0
            while (status(server, table) != TableStatus.FINISHED) {
                check(waited < 6000) { "The round did not finish" }
                context.waitTicks(100)
                waited += 100
            }
            context.waitTicks(20)
            context.setScreen { TableScreen(table) }
            context.waitTicks(10)
            shot(context, "7-screen-result")
            context.setScreen { null }
            context.waitTicks(5)
            shot(context, "8-world-result")
        }
    }

    /** Clears a flat floor around the player, puts a table north of them and seats them facing it. */
    private fun buildScene(server: MinecraftServer): BlockPos {
        val player = server.playerList.players.first()
        val level = server.overworld()
        val base = player.blockPosition()
        for (x in -4..4) for (z in -4..4) {
            for (y in 0..4) level.setBlockAndUpdate(base.offset(x, y, z), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState())
            level.setBlockAndUpdate(base.offset(x, -1, z), net.minecraft.world.level.block.Blocks.OAK_PLANKS.defaultBlockState())
        }
        val table = base.relative(Direction.NORTH, 2)
        level.setBlockAndUpdate(table, TuKuaiContent.CARD_TABLE.defaultBlockState().setValue(CardTableBlock.FACING, Direction.SOUTH))
        player.teleportTo(base.x + 0.5, base.y.toDouble(), base.z + 0.5)
        TuKuai.LOGGER.info("Test table placed at {}", table)
        return table
    }

    private fun act(server: TestServerContext, pos: BlockPos, op: TableOp, a: Int = 0, b: Int = 0) {
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            val table = s.overworld().getBlockEntity(pos) as CardTableBlockEntity
            table.handle(player, TableActionPayload(pos, op, a, b))
        }
    }

    private fun status(server: TestServerContext, pos: BlockPos): TableStatus =
        server.computeOnServer<TableStatus, RuntimeException> { s -> (s.overworld().getBlockEntity(pos) as CardTableBlockEntity).status }

    private fun shot(context: ClientGameTestContext, name: String) {
        val path = context.takeScreenshot("tukuai-$name")
        TuKuai.LOGGER.info("SCREENSHOT {}", path.toAbsolutePath())
    }
}
