package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.ReplayScreen
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.SeatEntity
import io.github.konyaco.tukuai.content.StoolBlock
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.ReplayStore
import io.github.konyaco.tukuai.table.StatsStore
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Plays one round at a card table in a real client: sits down, fills the table with
 * computers, lets the computer play for us, and takes a screenshot at each stage.
 * CI prints the screenshots so they can be looked at without downloading anything.
 */
class TuKuaiClientGameTest : FabricClientGameTest {
    private companion object {
        /** Where the two old one-block tables go, as (x, z) from the player. */
        val OLD_ROOMY = 0 to 3
        val OLD_CRAMPED = 3 to 0
    }

    override fun runTest(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            world.clientLevel.waitForChunksRender()
            val server = world.server
            val table = server.computeOnServer<BlockPos, RuntimeException> { s -> buildScene(s) }
            check(server.computeOnServer<Boolean, RuntimeException> { s ->
                s.recipeManager.byKey(ResourceKey.create(Registries.RECIPE, TuKuai.id("card_table"))).isPresent
            }) { "The card table recipe did not load" }
            context.waitTicks(40)
            world.clientLevel.waitForChunksRender()
            server.runOnServer<RuntimeException> { s -> checkOldTables(s) }

            // The whole table with its stools, before anyone sits down.
            context.input.lookAt(table)
            context.waitTicks(5)
            shot(context, "0-world-overview")

            act(server, table, TableOp.SIT, 0)
            check(server.computeOnServer<Boolean, RuntimeException> { s ->
                val seat = s.playerList.players.first().vehicle
                seat is SeatEntity && seat.y > table.y + 0.3 && seat.blockPosition() == table.south(StoolBlock.TABLE_DISTANCE)
            }) { "Sitting down did not put the player on the stool" }
            act(server, table, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
            context.waitTicks(20)
            context.input.lookAt(table)
            context.waitTicks(5)
            shot(context, "1-world-lobby")

            context.setScreen { TableScreen(table) }
            context.waitTicks(10)
            shot(context, "2-screen-lobby")

            // Cards are dealt from a deck on the empty side of the table.
            context.setScreen { null }
            act(server, table, TableOp.START)
            context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
            context.waitTicks(8)
            shot(context, "3-world-dealing")

            context.setScreen { TableScreen(table) }
            context.waitTicks(30)
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
            check(server.computeOnServer<Boolean, RuntimeException> { s ->
                val player = s.playerList.players.first()
                s.commands.performPrefixedCommand(player.createCommandSourceStack(), "tukuai stats")
                s.commands.performPrefixedCommand(player.createCommandSourceStack(), "tukuai top")
                StatsStore.of(s, player.uuid)?.total?.games == 1
            }) { "The finished game was not added to the player's record" }
            context.waitTicks(20)
            context.setScreen { TableScreen(table) }
            context.waitTicks(10)
            shot(context, "7-screen-result")
            context.setScreen { null }
            context.waitTicks(5)
            shot(context, "8-world-result")

            // The finished game, watched again from the table.
            act(server, table, TableOp.REPLAY)
            context.waitFor({ it.screen is ReplayScreen }, 100)
            context.runOnClient<RuntimeException> {
                val replay = it.screen as ReplayScreen
                replay.pause()
                replay.seek(replay.total / 2)
            }
            context.waitTicks(5)
            shot(context, "9-replay-middle")
            context.runOnClient<RuntimeException> {
                val replay = it.screen as ReplayScreen
                replay.setSeat(replay.seat + 1)
            }
            context.waitTicks(5)
            shot(context, "10-replay-other-seat")

            // The same game, found through the commands.
            context.setScreen { null }
            context.waitTicks(5)
            val name = server.computeOnServer<String, RuntimeException> { s ->
                val names = ReplayStore.list(s)
                check(names.isNotEmpty()) { "The finished game was not saved" }
                val player = s.playerList.players.first()
                s.commands.performPrefixedCommand(player.createCommandSourceStack(), "tukuai replays")
                s.commands.performPrefixedCommand(player.createCommandSourceStack(), "tukuai replay ${names.first()}")
                names.first()
            }
            TuKuai.LOGGER.info("Opened replay {}", name)
            context.waitFor({ it.screen is ReplayScreen }, 100)
            context.runOnClient<RuntimeException> {
                val replay = it.screen as ReplayScreen
                replay.pause()
                replay.seek(replay.total)
            }
            context.waitTicks(5)
            shot(context, "11-replay-end")

            playUno(context, server, table)
        }
    }

    /** Switches the same table to UNO, which needs a fourth seat, and plays a hand of it. */
    private fun playUno(context: ClientGameTestContext, server: TestServerContext, table: BlockPos) {
        context.setScreen { null }
        act(server, table, TableOp.GAME, text = "uno")
        act(server, table, TableOp.ADD_BOT, -1, BotLevel.HARD.ordinal)
        context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == "uno" && it.seats.size == 4 && it.seats.all { s -> s.occupant != null } } == true }, 100)
        context.setScreen { TableScreen(table) }
        context.waitTicks(10)
        shot(context, "12-uno-lobby")

        context.setScreen { null }
        act(server, table, TableOp.START)
        context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == "uno" && it.status == TableStatus.PLAYING } == true }, 200)
        context.input.lookAt(table)
        context.waitTicks(12)
        shot(context, "13-uno-world-dealing")
        context.waitTicks(30)
        shot(context, "14-uno-world")

        context.setScreen { TableScreen(table) }
        context.waitTicks(10)
        shot(context, "15-uno-screen")

        act(server, table, TableOp.AUTO, 1)
        context.waitTicks(200)
        shot(context, "16-uno-screen-midgame")

        var waited = 0
        while (status(server, table) != TableStatus.FINISHED) {
            check(waited < 12000) { "The UNO hand did not finish" }
            context.waitTicks(100)
            waited += 100
        }
        context.waitTicks(20)
        shot(context, "17-uno-screen-result")
        context.setScreen { null }
        context.waitTicks(10)
        shot(context, "18-uno-world-result")

        act(server, table, TableOp.REPLAY)
        context.waitFor({ it.screen is ReplayScreen }, 100)
        context.runOnClient<RuntimeException> {
            val replay = it.screen as ReplayScreen
            replay.pause()
            replay.seek(replay.total / 2)
        }
        context.waitTicks(5)
        shot(context, "19-uno-replay")
    }

    /**
     * Clears a flat floor around the player and puts a 3x3 table north of them. Also checks
     * that placing a table from the item builds all nine parts and breaking one part takes
     * the whole table down with a single drop, and leaves two one-block tables as older
     * versions saved them for [checkOldTables].
     */
    private fun buildScene(server: MinecraftServer): BlockPos {
        val player = server.playerList.players.first()
        val level = server.overworld()
        val base = player.blockPosition()
        for (x in -5..5) for (z in -5..5) {
            for (y in 0..4) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState())
            level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.OAK_PLANKS.defaultBlockState())
        }
        player.teleportTo(base.x + 0.5, base.y.toDouble(), base.z + 0.5)

        // Placed by a player looking west: the table reaches west from the clicked block and seat 0 faces them.
        player.setYRot(90f)
        val clicked = base.west(2)
        val stack = ItemStack(TuKuaiContent.CARD_TABLE_ITEM)
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        val hit = BlockHitResult(Vec3.atCenterOf(clicked.below()).add(0.0, 0.5, 0.0), Direction.UP, clicked.below(), false)
        stack.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))
        val placed = clicked.west()
        for (part in 0 until CardTableBlock.PARTS) {
            val state = level.getBlockState(CardTableBlock.posOf(placed, part))
            check(state.block is CardTableBlock && state.getValue(CardTableBlock.PART) == part && state.getValue(CardTableBlock.FACING) == Direction.EAST) {
                "Placing a table left part $part as $state"
            }
        }
        check(level.getBlockEntity(placed) is CardTableBlockEntity) { "The placed table has no block entity in the middle" }
        level.destroyBlock(CardTableBlock.posOf(placed, CardTableBlock.partAt(-1, 0)), true)
        for (part in 0 until CardTableBlock.PARTS) {
            check(level.getBlockState(CardTableBlock.posOf(placed, part)).isAir) { "Breaking one part left part $part behind" }
        }
        val drops = tableItems(level, placed)
        check(drops == 1) { "Breaking a table dropped $drops tables" }

        val table = base.relative(Direction.NORTH, 3)
        for (part in 0 until CardTableBlock.PARTS) {
            level.setBlockAndUpdate(
                CardTableBlock.posOf(table, part),
                TuKuaiContent.CARD_TABLE.defaultBlockState().setValue(CardTableBlock.FACING, Direction.SOUTH).setValue(CardTableBlock.PART, part),
            )
        }
        // Stools on the three seat sides; sitting down at the table puts you on yours.
        for ((side, color) in listOf(Direction.SOUTH to DyeColor.RED, Direction.EAST to DyeColor.YELLOW, Direction.NORTH to DyeColor.GREEN)) {
            level.setBlockAndUpdate(table.relative(side, StoolBlock.TABLE_DISTANCE), TuKuaiContent.STOOL.defaultBlockState().setValue(StoolBlock.COLOR, color))
        }
        // A lone middle block is how a table from before 3x3 tables loads. One has room to grow, one does not.
        level.setBlockAndUpdate(OLD_ROOMY.offsetFrom(base), TuKuaiContent.CARD_TABLE.defaultBlockState())
        level.setBlockAndUpdate(OLD_CRAMPED.offsetFrom(base), TuKuaiContent.CARD_TABLE.defaultBlockState())
        level.setBlockAndUpdate(OLD_CRAMPED.offsetFrom(base).east(), Blocks.STONE.defaultBlockState())
        oldBase = base
        TuKuai.LOGGER.info("Test table placed at {}", table)
        return table
    }

    private var oldBase: BlockPos = BlockPos.ZERO

    /** The roomy old table grew into a 3x3 one; the cramped one dropped as an item. */
    private fun checkOldTables(server: MinecraftServer) {
        val level = server.overworld()
        val roomy = OLD_ROOMY.offsetFrom(oldBase)
        for (part in 0 until CardTableBlock.PARTS) {
            val state = level.getBlockState(CardTableBlock.posOf(roomy, part))
            check(state.block is CardTableBlock && state.getValue(CardTableBlock.PART) == part) { "The old table did not grow part $part: $state" }
        }
        val cramped = OLD_CRAMPED.offsetFrom(oldBase)
        check(level.getBlockState(cramped).isAir) { "The old table with no room is still there" }
        check(tableItems(level, cramped) == 1) { "The old table with no room did not drop as an item" }
    }

    private fun tableItems(level: ServerLevel, around: BlockPos): Int =
        level.getEntitiesOfClass(ItemEntity::class.java, AABB(around).inflate(2.5)) { it.item.`is`(TuKuaiContent.CARD_TABLE_ITEM) }
            .sumOf { it.item.count }

    private fun act(server: TestServerContext, pos: BlockPos, op: TableOp, a: Int = 0, b: Int = 0, text: String = "") {
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            val table = s.overworld().getBlockEntity(pos) as CardTableBlockEntity
            table.handle(player, TableActionPayload(pos, op, a, b, text))
        }
    }

    private fun status(server: TestServerContext, pos: BlockPos): TableStatus =
        server.computeOnServer<TableStatus, RuntimeException> { s -> (s.overworld().getBlockEntity(pos) as CardTableBlockEntity).status }

    private fun Pair<Int, Int>.offsetFrom(base: BlockPos): BlockPos = base.offset(first, 0, second)

    private fun shot(context: ClientGameTestContext, name: String) {
        val path = context.takeScreenshot("tukuai-$name")
        TuKuai.LOGGER.info("SCREENSHOT {}", path.toAbsolutePath())
    }
}
