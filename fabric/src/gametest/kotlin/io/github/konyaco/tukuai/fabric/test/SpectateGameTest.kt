package io.github.konyaco.tukuai.fabric.test

import com.mojang.authlib.GameProfile
import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.paodekuai.PdkView
import io.github.konyaco.tukuai.engine.uno.UnoView
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import java.util.UUID
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.fabricmc.fabric.api.entity.FakePlayer
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.DyeColor
import net.minecraft.world.level.block.Blocks

/**
 * Watching other players' games. Another player (a fake one on the server) sits at four
 * tables and starts a game at each with computers, while the test's own player looks on:
 * at three tables they see every hand, at the fourth the host has hidden them. At a fifth
 * table the test's player plays themself, stands up and walks off, and must still see
 * only their own hand.
 */
class SpectateGameTest : FabricClientGameTest {
    private companion object {
        /** Table centres as (x, z) from where the player starts. */
        val OPEN_DDZ = -4 to -5
        val OPEN_UNO = 4 to -5
        val CLOSED = -4 to 5
        val OWN = 4 to 5
        val OPEN_PDK = 0 to -5

        val HOST = GameProfile(UUID.fromString("6b1d3c2e-2f0a-4a51-9c39-3a5d2b7e9f10"), "Mika")
    }

    override fun runTest(context: ClientGameTestContext) {
        if (System.getenv("TUKUAI_SHOWCASE") != null) return
        context.worldBuilder().create().use { world ->
            val server = world.server
            val tables = server.computeOnServer<List<BlockPos>, RuntimeException> { s -> buildScene(s) }
            val (openDdz, openUno, closed, own) = tables
            val openPdk = tables[4]
            context.waitTicks(40)
            world.clientLevel.waitForChunksRender()
            context.runOnClient<RuntimeException> { ClientConfig.immersive = false }

            // Only the host may hide the hands: the test's player is not one here.
            hostAct(server, openDdz, TableOp.SIT, 0)
            act(server, openDdz, TableOp.SPECTATE, 0)
            hostAct(server, openDdz, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
            context.waitFor({ ClientTables[openDdz]?.snapshot?.let { it.host == 0 && it.seats.all { s -> s.occupant != null } } == true }, 100)
            check(ClientTables[openDdz]!!.snapshot.spectators) { "A player who is not the host hid the hands" }
            context.setScreen { TableScreen(openDdz) }
            context.waitTicks(10)
            shot(context, "spectate-lobby")
            context.setScreen { null }

            hostAct(server, openDdz, TableOp.START)
            context.waitFor({ ClientTables[openDdz]?.snapshot?.status == TableStatus.PLAYING }, 200)
            val ddz = ClientTables[openDdz]!!.view as DdzView
            check(ddz.seat == null && ddz.revealedHands.size == 3 && ddz.kitty.size == 3) { "A watcher did not see every hand: $ddz" }

            hostAct(server, openUno, TableOp.GAME, text = "uno")
            hostAct(server, openUno, TableOp.SIT, 0)
            hostAct(server, openUno, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
            hostAct(server, openUno, TableOp.START)
            context.waitFor({ ClientTables[openUno]?.snapshot?.let { it.gameId == "uno" && it.status == TableStatus.PLAYING } == true }, 200)
            val uno = ClientTables[openUno]!!.view as UnoView
            check(uno.seat == null && uno.revealedHands.size == 4) { "A watcher did not see every UNO hand: $uno" }

            hostAct(server, openPdk, TableOp.GAME, text = "paodekuai")
            hostAct(server, openPdk, TableOp.SIT, 0)
            hostAct(server, openPdk, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
            hostAct(server, openPdk, TableOp.START)
            context.waitFor({ ClientTables[openPdk]?.snapshot?.let { it.gameId == "paodekuai" && it.status == TableStatus.PLAYING } == true }, 200)
            val pdk = ClientTables[openPdk]!!.view as PdkView
            check(pdk.seat == null && pdk.hand.isEmpty() && pdk.revealedHands.size == 3) { "A watcher did not see every Pao De Kuai hand: $pdk" }

            // The host hides the hands at the third table before starting.
            hostAct(server, closed, TableOp.SIT, 0)
            hostAct(server, closed, TableOp.SPECTATE, 0)
            hostAct(server, closed, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
            hostAct(server, closed, TableOp.START)
            context.waitFor({ ClientTables[closed]?.snapshot?.let { !it.spectators && it.status == TableStatus.PLAYING } == true }, 200)
            val hidden = ClientTables[closed]!!.view as DdzView
            check(hidden.revealedHands.isEmpty() && hidden.hand.isEmpty() && hidden.kitty.isEmpty()) { "The hidden hands reached a watcher: $hidden" }

            // Let the computers play a while, then look on.
            context.waitTicks(300)
            lookFrom(context, server, openDdz, Direction.WEST)
            worldShot(context, server, openDdz, "spectate-ddz-world")
            context.setScreen { TableScreen(openDdz) }
            context.waitTicks(10)
            shot(context, "spectate-ddz-screen")
            context.setScreen { null }

            lookFrom(context, server, openUno, Direction.SOUTH)
            worldShot(context, server, openUno, "spectate-uno-world")
            context.setScreen { TableScreen(openUno) }
            context.waitTicks(10)
            shot(context, "spectate-uno-screen")
            context.setScreen { null }

            lookFrom(context, server, openPdk, Direction.SOUTH)
            worldShot(context, server, openPdk, "spectate-pdk-world")
            context.setScreen { TableScreen(openPdk) }
            context.waitTicks(10)
            shot(context, "spectate-pdk-screen")
            context.setScreen { null }

            lookFrom(context, server, closed, Direction.WEST)
            context.setScreen { TableScreen(closed) }
            context.waitTicks(10)
            shot(context, "spectate-closed-screen")
            context.setScreen { null }

            // A player in a game sees only their own hand, even after standing up and walking off.
            moveTo(server, own.offset(0, 0, 3))
            act(server, own, TableOp.SIT, 0)
            act(server, own, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
            act(server, own, TableOp.START)
            context.waitFor({ ClientTables[own]?.snapshot?.status == TableStatus.PLAYING }, 200)
            act(server, own, TableOp.STAND)
            moveTo(server, own.offset(0, 0, 4))
            context.waitTicks(60)
            val mine = ClientTables[own]!!
            val view = mine.view as DdzView
            check(mine.snapshot.status != TableStatus.PLAYING || (mine.snapshot.you == 0 && view.seat == 0 && view.revealedHands.isEmpty())) {
                "A player who stood up mid-game saw other hands: $view"
            }
            TuKuai.LOGGER.info("Watching works")
        }
    }

    /** Stands the player outside the [side] edge of [table], looking at its middle. */
    private fun lookFrom(context: ClientGameTestContext, server: TestServerContext, table: BlockPos, side: Direction) {
        moveTo(server, table.relative(side, 3))
        context.waitTicks(5)
        context.input.lookAt(table)
        context.waitTicks(10)
    }

    /**
     * The table from above one corner, where the standing hands on two sides show their
     * faces at a slant. Taken flying, without the hotbar, then back on the floor.
     */
    private fun worldShot(context: ClientGameTestContext, server: TestServerContext, table: BlockPos, name: String) {
        command(server, "gamemode spectator @a")
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            val target = "${table.x + 0.5} ${table.y + 1.0} ${table.z + 0.5}"
            s.commands.performPrefixedCommand(
                s.createCommandSourceStack(),
                "tp ${player.plainTextName} ${table.x - 0.6} ${table.y + 2.3} ${table.z + 2.6} facing $target",
            )
        }
        context.runOnClient<RuntimeException> { it.options.hideGui = true }
        context.waitTicks(10)
        context.input.lookAt(table)
        context.waitTicks(5)
        shot(context, name)
        context.runOnClient<RuntimeException> { it.options.hideGui = false }
        command(server, "gamemode creative @a")
        moveTo(server, table.relative(Direction.SOUTH, 3))
        context.waitTicks(5)
    }

    private fun command(server: TestServerContext, command: String) {
        server.runOnServer<RuntimeException> { s -> s.commands.performPrefixedCommand(s.createCommandSourceStack(), command) }
    }

    /** A flat floor around the player with five tables on it. */
    private fun buildScene(server: MinecraftServer): List<BlockPos> {
        val player = server.playerList.players.first()
        val level = server.overworld()
        val base = player.blockPosition()
        for (x in -10..10) for (z in -10..10) {
            for (y in 0..4) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState())
            level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.SPRUCE_PLANKS.defaultBlockState())
        }
        player.teleportTo(base.x + 0.5, base.y.toDouble(), base.z + 0.5)
        val tables = listOf(OPEN_DDZ, OPEN_UNO, CLOSED, OWN, OPEN_PDK).map { (x, z) -> base.offset(x, 0, z) }
        for (table in tables) {
            for (part in 0 until CardTableBlock.PARTS) {
                level.setBlockAndUpdate(
                    CardTableBlock.posOf(table, part),
                    TuKuaiContent.CARD_TABLE.defaultBlockState().setValue(CardTableBlock.FACING, Direction.SOUTH).setValue(CardTableBlock.PART, part),
                )
            }
        }
        (level.getBlockEntity(tables[1]) as CardTableBlockEntity).dye(DyeColor.BLUE)
        (level.getBlockEntity(tables[2]) as CardTableBlockEntity).dye(DyeColor.RED)
        return tables
    }

    private fun act(server: TestServerContext, pos: BlockPos, op: TableOp, a: Int = 0, b: Int = 0, text: String = "") {
        server.runOnServer<RuntimeException> { s -> send(s, s.playerList.players.first(), pos, op, a, b, text) }
    }

    /** The same for the other player, who stands right by the table to reach it. */
    private fun hostAct(server: TestServerContext, pos: BlockPos, op: TableOp, a: Int = 0, b: Int = 0, text: String = "") {
        server.runOnServer<RuntimeException> { s ->
            val host = FakePlayer.get(s.overworld(), HOST)
            if (host.isPassenger) host.stopRiding()
            host.setPos(pos.x + 0.5, pos.y.toDouble(), pos.z + 2.5)
            send(s, host, pos, op, a, b, text)
        }
    }

    private fun send(s: MinecraftServer, player: ServerPlayer, pos: BlockPos, op: TableOp, a: Int, b: Int, text: String) {
        val table = s.overworld().getBlockEntity(pos) as CardTableBlockEntity
        table.handle(player, TableActionPayload(pos, op, a, b, text))
    }

    private fun moveTo(server: TestServerContext, pos: BlockPos) {
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            if (player.isPassenger) player.stopRiding()
            player.teleportTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        }
    }

    private fun shot(context: ClientGameTestContext, name: String) {
        val path = context.takeScreenshot("tukuai-$name")
        TuKuai.LOGGER.info("SCREENSHOT {}", path.toAbsolutePath())
    }
}
