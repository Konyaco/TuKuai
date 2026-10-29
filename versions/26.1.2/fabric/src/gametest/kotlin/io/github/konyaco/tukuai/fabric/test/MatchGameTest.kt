package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.SeatEntity
import io.github.konyaco.tukuai.content.StoolBlock
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.doudizhu.BiddingMode
import io.github.konyaco.tukuai.engine.doudizhu.DdzAction
import io.github.konyaco.tukuai.engine.doudizhu.DdzCodecs
import io.github.konyaco.tukuai.engine.doudizhu.DdzPhase
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
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
import net.minecraft.commands.arguments.EntityAnchorArgument
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.contents.TranslatableContents
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW

/**
 * A short match of Dou Dizhu from start to final standings: the scoreboard during play,
 * the menu with the card looks, the leaderboard, picking a run of cards by dragging
 * across the hand, the result of the last game of a match, the standings, a game that
 * waits while we are out of our seat, and a match stopped halfway. Also the table and stool from the side and from the stool, at their
 * new heights. `TUKUAI_LANG=zh_cn` takes the pictures in another language.
 */
class MatchGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (System.getenv("TUKUAI_SHOWCASE") != null || System.getenv("TUKUAI_MULTIPLAYER") != null) return
        if (System.getenv("TUKUAI_ONLY")?.let { it != "match" } == true) return
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

            // The table and a stool from the side, standing.
            moveTo(server, table.offset(-3, 0, 3))
            context.waitTicks(5)
            context.runOnClient<RuntimeException> { it.player!!.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(table).add(0.0, -0.4, 0.0)) }
            context.waitTicks(10)
            shot(context, "match-0-world-side")

            act(server, table, TableOp.TIMER, 0)
            act(server, table, TableOp.SIT, 0)
            act(server, table, TableOp.ADD_BOT, -1, BotLevel.EASY.ordinal)
            context.waitFor({ ClientTables[table]?.snapshot?.seats?.all { it.occupant != null } == true }, 100)
            check(server.computeOnServer<Boolean, RuntimeException> { s ->
                val seat = s.playerList.players.first().vehicle
                seat is SeatEntity && seat.y > table.y + 0.4
            }) { "Sitting down did not put the player on the stool" }

            // Seated on the stool, looking at the middle of the table.
            context.runOnClient<RuntimeException> { it.player!!.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3(table.x + 0.5, table.y + CardTableBlock.TOP_HEIGHT.toDouble(), table.z + 0.5)) }
            context.waitTicks(10)
            val eye = context.computeOnClient<Double, RuntimeException> { it.player!!.eyeY - table.y }
            TuKuai.LOGGER.info("Seated eyes are {} above the floor, {} above the table top", eye, eye - CardTableBlock.TOP_HEIGHT)
            shot(context, "match-1-world-seated")

            // The lobby, with a match of four games, and the leaderboard from it.
            act(server, table, TableOp.ROUNDS, 4)
            context.waitFor({ ClientTables[table]?.snapshot?.matchRounds == 4 }, 100)
            context.setScreen { TableScreen(table) }
            context.waitTicks(10)
            shot(context, "match-2-lobby")
            click(context, "tukuai.table.leaderboard")
            context.waitTicks(5)
            shot(context, "match-3-leaderboard-empty")
            context.input.pressKey(GLFW.GLFW_KEY_ESCAPE)
            context.waitTicks(3)
            check(context.computeOnClient<Boolean, RuntimeException> { it.screen is TableScreen }) { "Escape closed the table instead of the leaderboard" }

            // Game one: we play our own cards, picking a run of them by dragging along the hand.
            act(server, table, TableOp.START)
            context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
            playUntilOurCards(context, server, table)
            context.waitTicks(10)
            shot(context, "match-4-screen-playing")
            val picked = dragAcross(context, table, 5)
            context.waitTicks(5)
            shot(context, "match-5-drag-picked")
            check(picked.size == 5) { "Dragging across five cards picked ${picked.size}: $picked" }

            // The menu with the card looks.
            clickLiteral(context, "☰")
            context.waitTicks(5)
            shot(context, "match-6-menu")
            clickLiteral(context, "☰")
            context.waitTicks(3)

            // The computer finishes the game for us; then the result, with the scoreboard beside it.
            act(server, table, TableOp.AUTO, 1)
            waitStatus(context, server, table, TableStatus.FINISHED)
            context.waitTicks(10)
            shot(context, "match-7-result")

            // Three more games end the match.
            for (game in 2..4) {
                act(server, table, TableOp.START)
                context.waitFor({ ClientTables[table]?.snapshot?.let { it.status == TableStatus.PLAYING && it.rounds == game - 1 } == true }, 200)
                act(server, table, TableOp.AUTO, 1)
                waitStatus(context, server, table, TableStatus.FINISHED)
            }
            context.waitFor({ ClientTables[table]?.snapshot?.matchOver == true }, 100)
            context.waitTicks(10)
            shot(context, "match-8-last-result")
            click(context, "tukuai.table.standings")
            context.waitTicks(5)
            shot(context, "match-9-standings")

            // A new match from the standings starts the points from nothing.
            click(context, "tukuai.table.new_match")
            context.waitFor({ ClientTables[table]?.snapshot?.let { it.status == TableStatus.PLAYING && it.rounds == 0 && !it.matchOver } == true }, 200)
            check(ClientTables[table]!!.snapshot.tally.all { it == 0 }) { "The new match did not start from nothing" }
            act(server, table, TableOp.AUTO, 1)
            waitStatus(context, server, table, TableStatus.FINISHED)
            act(server, table, TableOp.START)
            context.waitFor({ ClientTables[table]?.snapshot?.let { it.status == TableStatus.PLAYING && it.rounds == 1 } == true }, 200)
            context.waitTicks(10)

            // Standing up leaves nobody in the game, so it waits for us instead of the computers playing on.
            act(server, table, TableOp.STAND)
            context.waitFor({ ClientTables[table]?.snapshot?.paused == true }, 100)
            val pausedAt = ClientTables[table]!!.snapshot.step
            context.waitTicks(60)
            check(ClientTables[table]!!.snapshot.let { it.paused && it.step == pausedAt }) { "The computers went on playing after we stood up" }
            context.waitTicks(10)
            shot(context, "match-9b-paused")
            act(server, table, TableOp.SIT, 0)
            context.waitFor({ ClientTables[table]?.snapshot?.paused == false }, 100)
            context.waitTicks(10)

            // Ending the match halfway takes one more click to be sure; then the screen shows the standings.
            clickLiteral(context, "☰")
            context.waitTicks(3)
            click(context, "tukuai.table.end")
            context.waitTicks(5)
            shot(context, "match-9c-end-confirm")
            check(ClientTables[table]!!.snapshot.status == TableStatus.PLAYING) { "The match ended before we were sure" }
            click(context, "tukuai.table.end.confirm")
            context.waitFor({ ClientTables[table]?.snapshot?.let { it.status == TableStatus.LOBBY && it.matchOver } == true }, 200)
            context.waitTicks(10)
            shot(context, "match-10-ended-standings")
            click(context, "tukuai.table.lobby")
            context.waitTicks(5)
            shot(context, "match-11-lobby-after")
            click(context, "tukuai.table.leaderboard")
            context.waitTicks(5)
            shot(context, "match-12-leaderboard")
            context.setScreen { null }

            // In the world view at the table, during a game.
            context.runOnClient<RuntimeException> { ClientConfig.immersive = true }
            act(server, table, TableOp.START)
            context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
            playUntilOurCards(context, server, table)
            context.setScreen { TableScreen(table) }
            context.runOnClient<RuntimeException> { it.player!!.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3(table.x + 0.5, table.y + CardTableBlock.TOP_HEIGHT.toDouble(), table.z + 0.5)) }
            context.waitTicks(15)
            shot(context, "match-13-immersive")
            context.setScreen { null }
            context.runOnClient<RuntimeException> { ClientConfig.immersive = false }
        }
    }

    /** Bids and doubles for us through the server until it is our turn to play cards from a full hand. */
    private fun playUntilOurCards(context: ClientGameTestContext, server: TestServerContext, table: BlockPos) {
        var tries = 0
        while (true) {
            check(++tries < 40) { "Never got a turn to play cards" }
            context.waitFor({ (ClientTables[table]?.view as? DdzView)?.isMyTurn == true }, 2400)
            val view = ClientTables[table]!!.view as DdzView
            val action: DdzAction = when (view.phase) {
                DdzPhase.BIDDING -> if (view.rules.bidding == BiddingMode.SCORE) DdzAction.Bid(view.allowedBids.max()) else DdzAction.Call(true)
                DdzPhase.DOUBLING -> DdzAction.Double(false)
                DdzPhase.PLAYING -> return
                DdzPhase.FINISHED -> error("The game ended before we played")
            }
            val step = ClientTables[table]!!.snapshot.step
            act(server, table, TableOp.ACT, data = DdzCodecs.ACTION.encode(action))
            context.waitFor({ (ClientTables[table]?.snapshot?.step ?: step) != step }, 100)
        }
    }

    /**
     * Holds the left button on the first card of the hand along the bottom of the screen
     * and drags right across [count] cards; returns the cards picked.
     */
    private fun dragAcross(context: ClientGameTestContext, table: BlockPos, count: Int): Set<PlayingCard> {
        val points = context.computeOnClient<List<Pair<Double, Double>>, RuntimeException> { mc ->
            val n = (ClientTables[table]!!.view as DdzView).hand.size
            val w = mc.window.guiScaledWidth
            val h = mc.window.guiScaledHeight
            // As PlayingCardHand lays the hand out.
            val step = if (n <= 1) 0 else minOf(18, (w - 120 - 32) / (n - 1))
            val x0 = (w - (32 + step * (n - 1))) / 2
            val y = h - 44 - 8 + 30
            val scale = mc.window.screenWidth.toDouble() / w
            (0 until count).map { i -> (x0 + i * step + step / 2.0) * scale to y * scale }
        }
        context.input.setCursorPos(points.first().first, points.first().second)
        context.waitTicks(2)
        context.input.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
        context.waitTicks(2)
        for ((x, y) in points.drop(1)) {
            context.input.setCursorPos(x, y)
            context.waitTicks(2)
        }
        context.input.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
        context.waitTicks(2)
        return context.computeOnClient<Set<PlayingCard>, RuntimeException> { mc -> selected(mc) }
    }

    /** The cards picked in the Dou Dizhu panel of the open table screen. */
    @Suppress("UNCHECKED_CAST")
    private fun selected(mc: Minecraft): Set<PlayingCard> {
        val screen = mc.screen as TableScreen
        val panel = TableScreen::class.java.getDeclaredField("panel").apply { isAccessible = true }.get(screen)!!
        val picker = panel.javaClass.getDeclaredField("picker").apply { isAccessible = true }.get(panel)!!
        return (picker.javaClass.getMethod("getSelected").invoke(picker) as Collection<PlayingCard>).toSet()
    }

    /** Clicks the table screen's button whose text is [key], with the real mouse. */
    private fun click(context: ClientGameTestContext, key: String) = clickWhere(context, key) { b ->
        (b.message.contents as? TranslatableContents)?.key == key
    }

    private fun clickLiteral(context: ClientGameTestContext, text: String) = clickWhere(context, text) { it.message.string == text }

    private fun clickWhere(context: ClientGameTestContext, what: String, test: (Button) -> Boolean) {
        val (x, y) = context.computeOnClient<Pair<Double, Double>, RuntimeException> { mc ->
            val screen = mc.screen as? TableScreen ?: error("No table screen to click $what on")
            val button = screen.children().filterIsInstance<Button>().firstOrNull(test) ?: error("No button $what on the table screen")
            check(button.active) { "The button $what is off" }
            val scale = mc.window.screenWidth.toDouble() / mc.window.guiScaledWidth
            (button.x + button.width / 2.0) * scale to (button.y + button.height / 2.0) * scale
        }
        context.input.setCursorPos(x, y)
        context.waitTicks(2)
        context.input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
        context.waitTicks(2)
    }

    private fun waitStatus(context: ClientGameTestContext, server: TestServerContext, table: BlockPos, status: TableStatus) {
        var waited = 0
        while (server.computeOnServer<TableStatus, RuntimeException> { s -> (s.overworld().getBlockEntity(table) as CardTableBlockEntity).status } != status) {
            check(waited < 6000) { "The table never got to $status" }
            context.waitTicks(20)
            waited += 20
        }
        context.waitFor({ ClientTables[table]?.snapshot?.status == status }, 100)
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

    /** A 3x3 table north of the player with a stool on each seat side, on a wooden floor in daylight. */
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
        for (side in listOf(Direction.SOUTH, Direction.EAST, Direction.NORTH)) {
            level.setBlockAndUpdate(table.relative(side, StoolBlock.TABLE_DISTANCE), TuKuaiContent.STOOL.defaultBlockState())
        }
        return table
    }

    private fun act(server: TestServerContext, pos: BlockPos, op: TableOp, a: Int = 0, b: Int = 0, text: String = "", data: ByteArray = ByteArray(0)) {
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            val table = s.overworld().getBlockEntity(pos) as CardTableBlockEntity
            table.handle(player, TableActionPayload(pos, op, a, b, text, data))
        }
    }

    private fun moveTo(server: TestServerContext, pos: BlockPos) {
        server.runOnServer<RuntimeException> { s -> s.playerList.players.first().teleportTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5) }
    }

    private fun botDelay(server: TestServerContext, seconds: Double) {
        server.runOnServer<RuntimeException> {
            val field = ServerSettings::class.java.getDeclaredField("botDelaySeconds")
            field.isAccessible = true
            field.set(null, seconds)
        }
    }

    private fun shot(context: ClientGameTestContext, name: String) {
        val path = context.takeScreenshot("tukuai-$name")
        TuKuai.LOGGER.info("SCREENSHOT {}", path.toAbsolutePath())
    }
}
