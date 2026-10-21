package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.ReplayScreen
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.BigCardTableBlock
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.SeatEntity
import io.github.konyaco.tukuai.content.StoolBlock
import io.github.konyaco.tukuai.content.TableSeats
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.content.Wood
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.tutu.TutuView
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.server.MinecraftServer
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.BlockItemStateProperties
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * The 5x5 table for eight: placing one from its item, its eight stools and who sits on
 * which, a 3x3 table refusing eight-player rules, and a hand of Tutu Cards for eight with
 * us and seven computers. `TUKUAI_ONLY=bigtable` runs just this; `TUKUAI_LANG` picks the
 * language of the pictures.
 */
class BigTableGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (System.getenv("TUKUAI_SHOWCASE") != null || System.getenv("TUKUAI_MULTIPLAYER") != null) return
        if (System.getenv("TUKUAI_ONLY")?.let { it != "bigtable" } == true) return
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
            val (table, small) = server.computeOnServer<Pair<BlockPos, BlockPos>, RuntimeException> { s -> buildScene(s) }
            context.waitTicks(40)
            world.clientLevel.waitForChunksRender()

            // The table and its stools from a corner, standing.
            moveTo(server, table.offset(5, 0, 6))
            context.waitTicks(5)
            context.runOnClient<RuntimeException> { it.player!!.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(table).add(0.0, -0.6, 0.0)) }
            context.waitTicks(10)
            shot(context, "bigtable-0-world")

            // The stool left of the middle on the south side is seat 0, the one right of it seat 1.
            server.runOnServer<RuntimeException> { s ->
                val player = s.playerList.players.first()
                val level = s.overworld()
                val be = level.getBlockEntity(table) as CardTableBlockEntity
                be.handle(player, TableActionPayload(table, TableOp.GAME, 0, 0, "tutu", ByteArray(0)))
                be.handle(player, TableActionPayload(table, TableOp.PRESET, 0, 0, "eight", ByteArray(0)))
                check(be.presetId == "eight") { "The big table did not take the rules for eight" }
                val stool = table.offset(1, 0, 3)
                val (found, spot) = StoolBlock.tableNextTo(level, stool) ?: error("The stool right of the middle found no table")
                check(found === be && spot.side == Direction.SOUTH && spot.along == 1) { "The stool right of the middle is at $spot" }
                check(be.sitAt(player, spot.side, spot.along)) { "Sitting on a big table's stool did nothing" }
                check(be.seatOf(player.uuid) == 1) { "The stool right of the middle gave seat ${be.seatOf(player.uuid)}" }
                val seat = player.vehicle
                check(seat is SeatEntity && seat.blockPosition() == stool) { "Sitting put the player at ${seat?.blockPosition()}, not on $stool" }

                // A 3x3 table has no room for eight.
                val other = level.getBlockEntity(small) as CardTableBlockEntity
                other.handle(player, TableActionPayload(small, TableOp.GAME, 0, 0, "tutu", ByteArray(0)))
                other.handle(player, TableActionPayload(small, TableOp.PRESET, 0, 0, "eight", ByteArray(0)))
                check(other.presetId != "eight") { "A 3x3 table took the rules for eight" }
                be.handle(player, TableActionPayload(table, TableOp.SIT, 0, 0, "", ByteArray(0)))
                check(be.seatOf(player.uuid) == 0) { "Could not move to seat 0" }
            }
            act(server, table, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
            context.waitFor({ ClientTables[table]?.snapshot?.let { it.seats.size == 8 && it.seats.all { s -> s.occupant != null } } == true }, 100)
            context.setScreen { TableScreen(table) }
            context.waitTicks(10)
            shot(context, "bigtable-1-lobby")

            context.setScreen { null }
            act(server, table, TableOp.START)
            context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
            context.runOnClient<RuntimeException> { it.player!!.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(table).add(0.0, -0.5, 0.0)) }
            context.waitTicks(40)
            shot(context, "bigtable-2-world-seated")

            context.setScreen { TableScreen(table) }
            context.waitFor({ (ClientTables[table]?.view as? TutuView)?.isMyTurn == true }, 2400)
            context.waitTicks(10)
            shot(context, "bigtable-3-screen-turn")

            act(server, table, TableOp.AUTO, 1)
            context.waitFor({ (ClientTables[table]?.view as? TutuView)?.let { it.finished || it.log.size > 40 } == true }, 6000)
            context.waitTicks(5)
            shot(context, "bigtable-4-screen-midgame")
            context.setScreen { null }
            // From high above one corner, to see all eight hands.
            moveTo(server, table.offset(4, 4, 4))
            context.waitTicks(5)
            context.runOnClient<RuntimeException> { it.player!!.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(table)) }
            context.waitTicks(10)
            shot(context, "bigtable-5-world-above")

            var waited = 0
            while (status(server, table) != TableStatus.FINISHED) {
                check(waited < 12000) { "The hand for eight did not finish" }
                context.waitTicks(100)
                waited += 100
            }
            context.waitFor({ (ClientTables[table]?.view as? TutuView)?.finished == true }, 200)
            val view = ClientTables[table]!!.view as TutuView
            check(view.players == 8 && view.settlement!!.scores.sum() == 0) { "The hand for eight ended oddly: $view" }
            context.setScreen { TableScreen(table) }
            context.waitTicks(10)
            shot(context, "bigtable-6-screen-result")
            act(server, table, TableOp.REPLAY)
            context.waitFor({ it.screen is ReplayScreen }, 100)
            context.runOnClient<RuntimeException> {
                val replay = it.screen as ReplayScreen
                replay.pause()
                replay.seek(replay.total / 2)
            }
            context.waitTicks(5)
            shot(context, "bigtable-7-replay")
            context.setScreen { null }
        }
    }

    /**
     * Places a big table from its item, checks its parts, and puts a stool at each of its
     * eight seats; a 3x3 table stands off to the east. Returns both tables' middles.
     */
    private fun buildScene(server: MinecraftServer): Pair<BlockPos, BlockPos> {
        val player = server.playerList.players.first()
        val level = server.overworld()
        val base = player.blockPosition()
        server.commands.performPrefixedCommand(server.createCommandSourceStack(), "time set noon")
        for (x in -8..12) for (z in -10..8) {
            for (y in 0..5) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState())
            level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.SPRUCE_PLANKS.defaultBlockState())
        }

        // Placed by a player looking north: the table reaches north from the clicked block.
        player.teleportTo(base.x + 0.5, base.y.toDouble(), base.z + 0.5)
        player.setYRot(180f)
        val clicked = base.north()
        val stack = ItemStack(TuKuaiContent.BIG_CARD_TABLE_ITEM)
        stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(CardTableBlock.WOOD, Wood.CHERRY))
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        val hit = BlockHitResult(Vec3.atCenterOf(clicked.below()).add(0.0, 0.5, 0.0), Direction.UP, clicked.below(), false)
        stack.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))
        val block = TuKuaiContent.BIG_CARD_TABLE
        val table = clicked.north(2)
        for (part in 0 until block.parts) {
            val state = level.getBlockState(block.posOf(table, part))
            check(
                state.block is BigCardTableBlock && state.getValue(block.partProperty) == part &&
                    state.getValue(CardTableBlock.FACING) == Direction.SOUTH && state.getValue(CardTableBlock.WOOD) == Wood.CHERRY,
            ) { "Placing a big table left part $part as $state" }
        }
        check(level.getBlockEntity(table) is CardTableBlockEntity) { "The big table has no block entity in the middle" }

        val colors = listOf(DyeColor.RED, DyeColor.ORANGE, DyeColor.YELLOW, DyeColor.LIME, DyeColor.CYAN, DyeColor.BLUE, DyeColor.PURPLE, DyeColor.PINK)
        TableSeats.spots(Direction.SOUTH, block.radius, 8).forEachIndexed { i, spot ->
            val stool = table.relative(spot.side, block.radius + 1).relative(spot.right, spot.along)
            level.setBlockAndUpdate(
                stool,
                TuKuaiContent.STOOL.defaultBlockState().setValue(StoolBlock.COLOR, colors[i]).setValue(StoolBlock.WOOD, Wood.CHERRY),
            )
        }

        val small = table.east(8)
        for (part in 0 until CardTableBlock.PARTS) {
            level.setBlockAndUpdate(
                CardTableBlock.posOf(small, part),
                TuKuaiContent.CARD_TABLE.defaultBlockState().setValue(CardTableBlock.FACING, Direction.SOUTH).setValue(CardTableBlock.PART, part),
            )
        }
        TuKuai.LOGGER.info("Big test table placed at {}", table)
        return table to small
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

    private fun act(server: TestServerContext, pos: BlockPos, op: TableOp, a: Int = 0, b: Int = 0, text: String = "") {
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            (s.overworld().getBlockEntity(pos) as CardTableBlockEntity).handle(player, TableActionPayload(pos, op, a, b, text, ByteArray(0)))
        }
    }

    private fun moveTo(server: TestServerContext, pos: BlockPos) {
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            player.stopRiding()
            player.teleportTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        }
    }

    private fun status(server: TestServerContext, pos: BlockPos): TableStatus =
        server.computeOnServer<TableStatus, RuntimeException> { s -> (s.overworld().getBlockEntity(pos) as CardTableBlockEntity).status }

    private fun shot(context: ClientGameTestContext, name: String) {
        val path = context.takeScreenshot("tukuai-$name")
        TuKuai.LOGGER.info("SCREENSHOT {}", path.toAbsolutePath())
    }
}
