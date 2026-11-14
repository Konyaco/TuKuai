package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.compat.currentScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.ChairBlock
import io.github.konyaco.tukuai.content.SeatEntity
import io.github.konyaco.tukuai.content.TableSeats
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.engine.core.BotLevel
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
import net.minecraft.world.InteractionHand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW

/**
 * Choosing where to sit. At a 3x3 table with no chairs, a game for three: the lobby shows
 * the table from above, the player clicks the side across from them and walks round to
 * stand there, computers go on the two sides beside it with a click, and the side the player
 * came from is left empty. The game is dealt to those three sides in turn round the table.
 * Then at a big table the player sits on a chair, and the table for six shows two places a
 * side. `TUKUAI_ONLY=seats` runs just this; `TUKUAI_LANG` picks the language of the pictures.
 */
class SeatsGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (System.getenv("TUKUAI_SHOWCASE") != null || System.getenv("TUKUAI_MULTIPLAYER") != null) return
        if (System.getenv("TUKUAI_ONLY")?.let { it != "seats" } == true) return
        val lang = System.getenv("TUKUAI_LANG") ?: "en_us"
        if (lang != "en_us") setLanguage(context, lang)
        try {
            look(context)
        } finally {
            if (lang != "en_us") setLanguage(context, "en_us")
            context.runOnClient<RuntimeException> { ClientConfig.immersive = false }
        }
    }

    private fun look(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            val (small, big) = server.computeOnServer<Pair<BlockPos, BlockPos>, RuntimeException> { s -> buildScene(s) }
            context.waitTicks(40)
            world.clientLevel.waitForChunksRender()
            context.runOnClient<RuntimeException> {
                ClientConfig.immersive = false
                ClientConfig.guideSeen = true
            }

            // A table for three, seen from the south side where the player stands: every side is free.
            context.setScreen { TableScreen(small) }
            context.waitFor({ ClientTables[small] != null }, 100)
            context.waitTicks(10)
            for (place in 0 until 4) check(boxAt(context, place) != null) { "Place $place is not in the lobby while seats are free" }
            shot(context, "seats-1-lobby")

            // The side across: the player walks round and stands there, with no chair to sit on.
            clickBox(context, 2, 0)
            context.waitFor({ ClientTables[small]?.snapshot?.you != null }, 100)
            context.waitTicks(10)
            val stand = server.computeOnServer<Vec3, RuntimeException> { s -> s.playerList.players.first().let { check(it.vehicle == null) { "The player sat down with no chair there" }; it.position() } }
            check(stand.z < small.z) { "The player did not go round to the north side: $stand" }
            check(ClientTables[small]!!.snapshot.let { it.places[it.you!!] } == 2) { "The player is not at the place across" }
            shot(context, "seats-2-across")

            // Computers on the sides on either hand: one by its button, one straight away.
            clickBox(context, 3, 12)
            context.waitFor({ ClientTables[small]?.snapshot?.seats?.count { it.occupant != null } == 2 }, 100)
            act(server, small, TableOp.BOT_AT, 1, BotLevel.NORMAL.ordinal)
            context.waitFor({ ClientTables[small]?.snapshot?.seats?.all { it.occupant != null } == true }, 100)
            context.waitTicks(5)
            val places = ClientTables[small]!!.snapshot.places
            check(places == listOf(1, 2, 3)) { "The seats are not at the east, north and west sides in turn: $places" }
            check(boxAt(context, 0) == null) { "The side nobody sits at still shows with every seat taken" }
            shot(context, "seats-3-three-sides")

            // The game: dealt to the three sides, the south one left bare.
            server.runOnServer<RuntimeException> {
                val field = ServerSettings::class.java.getDeclaredField("botDelaySeconds")
                field.isAccessible = true
                field.set(null, 3.0)
            }
            act(server, small, TableOp.START)
            context.waitFor({ ClientTables[small]?.snapshot?.status == TableStatus.PLAYING }, 200)
            context.waitTicks(40)
            shot(context, "seats-4-screen")
            context.runOnClient<RuntimeException> { ClientConfig.immersive = true }
            context.setScreen { TableScreen(small) }
            context.waitTicks(30)
            shot(context, "seats-5-immersive-standing")
            context.setScreen { null }
            context.waitTicks(10)
            shot(context, "seats-6-world-standing")
            context.runOnClient<RuntimeException> { ClientConfig.immersive = false }

            // Toggling auto play keeps a standing player at the table, so the game does not wait.
            act(server, small, TableOp.AUTO, 1)
            context.waitTicks(30)
            check(ClientTables[small]?.snapshot?.paused == false) { "A standing player on auto play counted as gone" }
            // Standing up from it does leave the table: the game waits for someone to sit down.
            act(server, small, TableOp.STAND)
            context.waitFor({ ClientTables[small]?.snapshot?.paused == true }, 100)
            act(server, small, TableOp.END)

            // A big table with a chair on its west side: the player sits on it.
            val west = server.computeOnServer<Int, RuntimeException> { s ->
                s.playerList.players.first().teleportTo(big.x + 0.5, big.y.toDouble(), big.z + 4.5)
                val facing = s.overworld().getBlockState(big).getValue(CardTableBlock.FACING)
                (0 until 4).first { TableSeats.turn(facing, it) == Direction.WEST }
            }
            context.waitTicks(10)
            context.setScreen { TableScreen(big) }
            act(server, big, TableOp.SIT_AT, west)
            context.waitFor({ ClientTables[big]?.snapshot?.you != null }, 100)
            context.waitTicks(10)
            check(server.computeOnServer<Boolean, RuntimeException> { s -> s.playerList.players.first().vehicle is SeatEntity }) { "The player did not sit on the chair" }
            shot(context, "seats-7-big-chair")

            // Six at the big table: two places a side, and the player keeps their side and chair.
            act(server, big, TableOp.GAME, text = "tutu")
            act(server, big, TableOp.PRESET, text = "six")
            context.waitFor({ ClientTables[big]?.snapshot?.presetId == "six" }, 100)
            context.waitTicks(10)
            check(ClientTables[big]!!.snapshot.let { it.places[it.you!!] } == west * 2) { "The player lost their side when the table took six" }
            check(server.computeOnServer<Boolean, RuntimeException> { s -> s.playerList.players.first().vehicle is SeatEntity }) { "The player left the chair when the table took six" }
            for (place in 0 until 8) check(boxAt(context, place) != null) { "Place $place of eight is not in the lobby" }
            shot(context, "seats-8-big-six")
            act(server, big, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
            context.waitFor({ ClientTables[big]?.snapshot?.seats?.all { it.occupant != null } == true }, 100)
            context.waitTicks(5)
            shot(context, "seats-9-big-six-full")
            context.setScreen { null }
        }
    }

    /** The middle of the lobby's box for [place], in GUI pixels, or null when it is not shown. */
    private fun boxAt(context: ClientGameTestContext, place: Int): Pair<Int, Int>? =
        context.computeOnClient<Pair<Int, Int>?, RuntimeException> { mc -> (mc.currentScreen as? TableScreen)?.placeBoxCenter(place) }

    /** Clicks the box of [place] in the lobby, [down] pixels below its first line (12 reaches its button). */
    private fun clickBox(context: ClientGameTestContext, place: Int, down: Int) {
        val (x, y) = boxAt(context, place) ?: error("Place $place is not in the lobby")
        val (sx, sy) = context.computeOnClient<Pair<Double, Double>, RuntimeException> { mc ->
            val window = mc.window
            x.toDouble() * window.screenWidth / window.guiScaledWidth to (y + down).toDouble() * window.screenHeight / window.guiScaledHeight
        }
        context.input.setCursorPos(sx, sy)
        context.waitTicks(2)
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

    /**
     * A 3x3 table north of the player facing them, with no chairs, and a big table to the
     * east with a chair in the middle of its west side.
     */
    private fun buildScene(server: MinecraftServer): Pair<BlockPos, BlockPos> {
        val player = server.playerList.players.first()
        val level = server.overworld()
        val base = player.blockPosition()
        server.commands.performPrefixedCommand(server.createCommandSourceStack(), "time set noon")
        for (x in -6..14) for (z in -9..4) {
            for (y in 0..5) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState())
            level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.SPRUCE_PLANKS.defaultBlockState())
        }
        val small = base.north(3)
        for (part in 0 until CardTableBlock.PARTS) {
            level.setBlockAndUpdate(
                CardTableBlock.posOf(small, part),
                TuKuaiContent.CARD_TABLE.defaultBlockState().setValue(CardTableBlock.FACING, Direction.SOUTH).setValue(CardTableBlock.PART, part),
            )
        }
        player.teleportTo(base.x + 0.5, base.y.toDouble(), base.z + 0.5)
        player.setYRot(180f)
        player.setXRot(30f)

        // The big table, placed from its item as a player would, facing south too.
        val clicked = base.offset(9, 0, -1)
        val stack = ItemStack(TuKuaiContent.BIG_CARD_TABLE_ITEM)
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        val hit = BlockHitResult(Vec3.atCenterOf(clicked.below()).add(0.0, 0.5, 0.0), Direction.UP, clicked.below(), false)
        stack.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))
        player.setItemInHand(InteractionHand.MAIN_HAND, ItemStack.EMPTY)
        val big = clicked.north(2)
        check(level.getBlockEntity(big) is CardTableBlockEntity) { "The big table has no block entity in the middle" }
        level.setBlockAndUpdate(big.west(3), TuKuaiContent.CHAIR.defaultBlockState().setValue(ChairBlock.FACING, Direction.EAST))
        return small to big
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
}
