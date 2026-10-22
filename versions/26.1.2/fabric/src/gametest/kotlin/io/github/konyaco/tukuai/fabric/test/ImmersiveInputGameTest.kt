package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.client.gd.GdUi
import io.github.konyaco.tukuai.client.render.CardFrame
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.StoolBlock
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.engine.card.PlayingCard
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.doudizhu.BiddingMode
import io.github.konyaco.tukuai.engine.doudizhu.DdzAction
import io.github.konyaco.tukuai.engine.doudizhu.DdzCodecs
import io.github.konyaco.tukuai.engine.doudizhu.DdzPhase
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.doudizhu.Patterns
import io.github.konyaco.tukuai.engine.guandan.GdAction
import io.github.konyaco.tukuai.engine.guandan.GdCodecs
import io.github.konyaco.tukuai.engine.guandan.GdPhase
import io.github.konyaco.tukuai.engine.guandan.GdView
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.table.CardTableBlockEntity
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
import kotlin.math.abs

/**
 * The immersive view with a real mouse: dragging with the right button turns the head,
 * and Dou Dizhu cards are picked by clicking them on the table, then played with the
 * play button. Then a Guandan turn, whose cards are picked by sweeping over them on the
 * table, two copies of a card being told apart.
 */
class ImmersiveInputGameTest : FabricClientGameTest {
    override fun runTest(context: ClientGameTestContext) {
        if (System.getenv("TUKUAI_SHOWCASE") != null || System.getenv("TUKUAI_MULTIPLAYER") != null) return
        // TUKUAI_ONLY=<name> runs just one of the tests, to try it quickly.
        if (System.getenv("TUKUAI_ONLY")?.let { it != "immersive" } == true) return
        playDouDizhu(context)
        playGuandan(context)
    }

    private fun playDouDizhu(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            val table = server.computeOnServer<BlockPos, RuntimeException> { s -> buildScene(s) }
            context.waitTicks(40)
            world.clientLevel.waitForChunksRender()

            act(server, table, TableOp.TIMER, 0)
            act(server, table, TableOp.SIT, 0)
            act(server, table, TableOp.ADD_BOT, -1, BotLevel.EASY.ordinal)
            context.waitFor({ ClientTables[table]?.snapshot?.seats?.all { it.occupant != null } == true }, 100)
            context.runOnClient<RuntimeException> { ClientConfig.immersive = true }
            act(server, table, TableOp.START)
            context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
            context.setScreen { TableScreen(table) }

            // Bid and double through the server until it is our turn to play cards.
            var tries = 0
            while (true) {
                check(++tries < 40) { "Never got a turn to play cards" }
                context.waitFor({ (ClientTables[table]?.view as? DdzView)?.isMyTurn == true }, 2400)
                val view = ClientTables[table]!!.view as DdzView
                val action: DdzAction = when (view.phase) {
                    DdzPhase.BIDDING -> if (view.rules.bidding == BiddingMode.SCORE) DdzAction.Bid(view.allowedBids.max()) else DdzAction.Call(true)
                    DdzPhase.DOUBLING -> DdzAction.Double(false)
                    DdzPhase.PLAYING -> if (Patterns.options(view.hand, view.toBeat, view.rules).isEmpty()) DdzAction.Pass else break
                    DdzPhase.FINISHED -> error("The game ended before we played")
                }
                val step = ClientTables[table]!!.snapshot.step
                act(server, table, TableOp.ACT, data = DdzCodecs.ACTION.encode(action))
                context.waitFor({ (ClientTables[table]?.snapshot?.step ?: step) != step }, 100)
            }

            // Look down at our own hand, which stands at the near edge of the table.
            val side = context.computeOnClient<Direction, RuntimeException> { sides(table).first() }
            val hand = Vec3(table.x + 0.5 + side.stepX * 1.05, table.y + CardTableBlock.TOP_HEIGHT + 0.2, table.z + 0.5 + side.stepZ * 1.05)
            context.runOnClient<RuntimeException> { it.player!!.lookAt(EntityAnchorArgument.Anchor.EYES, hand) }
            context.waitTicks(10)
            shot(context, "immersive-hand")

            // Dragging with the right button turns the head.
            val (yaw, pitch) = context.computeOnClient<Pair<Float, Float>, RuntimeException> { it.player!!.yRot to it.player!!.xRot }
            context.input.setCursorPos(400.0, 300.0)
            context.waitTicks(2)
            context.input.holdMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
            context.waitTicks(2)
            context.input.moveCursor(150.0, -60.0)
            context.waitTicks(2)
            context.input.moveCursor(150.0, -60.0)
            context.waitTicks(2)
            shot(context, "immersive-turned")
            context.input.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_RIGHT)
            context.waitTicks(2)
            val (turnedYaw, turnedPitch) = context.computeOnClient<Pair<Float, Float>, RuntimeException> { it.player!!.yRot to it.player!!.xRot }
            TuKuai.LOGGER.info("Turned from {}/{} to {}/{}", yaw, pitch, turnedYaw, turnedPitch)
            check(turnedYaw - yaw > 10f) { "Dragging right with the right button did not turn the head right: $yaw -> $turnedYaw" }
            check(pitch - turnedPitch > 3f) { "Dragging up with the right button did not raise the head: $pitch -> $turnedPitch" }
            // The test harness keeps the cursor to itself, so whether it hides is not checked here.
            check(context.computeOnClient<Boolean, RuntimeException> { it.screen is TableScreen }) { "Turning closed the table screen" }

            // Back to the hand, then pick a play by clicking its cards on the table.
            context.runOnClient<RuntimeException> { it.player!!.lookAt(EntityAnchorArgument.Anchor.EYES, hand) }
            context.waitTicks(10)
            val view = ClientTables[table]!!.view as DdzView
            // A pair or three of a kind if there is one, to pick more than one card.
            val options = Patterns.options(view.hand, view.toBeat, view.rules)
            val play = (options.filter { it.cards.size in 2..3 && !it.pattern.isBomb }.ifEmpty { options }).minBy { it.cards.size }.cards
            TuKuai.LOGGER.info("Picking {} on the table", play)
            for (card in play) {
                val (x, y) = context.computeOnClient<Pair<Double, Double>, RuntimeException> { screenPoint(it, table, card) }
                context.input.setCursorPos(x, y)
                context.waitTicks(3)
                context.input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
                context.waitTicks(3)
            }
            context.waitTicks(10)
            val raised = context.computeOnClient<List<PlayingCard>, RuntimeException> {
                val screen = it.screen as TableScreen
                val client = ClientTables[table]!!
                (client.view as DdzView).hand.filter { card -> handCard(table, card)?.let { c -> screen.isRaised(client, c) } == true }
            }
            check(raised.toSet() == play.toSet()) { "Clicking $play on the table picked $raised" }
            shot(context, "immersive-picked")

            // The play button stays on the screen.
            val (bx, by) = context.computeOnClient<Pair<Double, Double>, RuntimeException> { mc ->
                val button = (mc.screen as TableScreen).children().filterIsInstance<Button>().first { b ->
                    (b.message.contents as? TranslatableContents)?.key == "tukuai.ddz.play"
                }
                check(button.active) { "The play button is off with $play picked" }
                val window = mc.window
                (button.x + button.width / 2.0) * window.screenWidth / window.guiScaledWidth to
                    (button.y + button.height / 2.0) * window.screenHeight / window.guiScaledHeight
            }
            context.input.setCursorPos(bx, by)
            context.waitTicks(2)
            context.input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
            context.waitFor({ (ClientTables[table]?.view as? DdzView)?.hand?.none { it in play } == true }, 100)
            context.runOnClient<RuntimeException> { it.player!!.lookAt(EntityAnchorArgument.Anchor.EYES, hand.add(-side.stepX * 0.5, 0.0, -side.stepZ * 0.5)) }
            context.waitTicks(15)
            shot(context, "immersive-played")
            context.setScreen { null }
            context.runOnClient<RuntimeException> { ClientConfig.immersive = false }
        }
    }

    /** Where on the screen, in window pixels, the visible part of [card] in our standing hand is. */
    private fun screenPoint(mc: Minecraft, table: BlockPos, card: PlayingCard): Pair<Double, Double> =
        screenPoint(mc, table, handCard(table, card) ?: error("$card is not in our hand on the table"))

    private fun screenPoint(mc: Minecraft, table: BlockPos, shown: TableCard): Pair<Double, Double> {
        val card = shown.sprite
        val f = CardFrame.of(shown, 0f)
        // Each card covers the one on its left but for a strip along that one's left edge.
        val along = -f.halfW + 0.03f
        val up = f.halfH * 0.3f
        val point = Vec3(
            table.x + f.c[0] + f.r[0] * along + f.u[0] * up.toDouble(),
            table.y + f.c[1] + f.r[1] * along + f.u[1] * up.toDouble(),
            table.z + f.c[2] + f.r[2] * along + f.u[2] * up.toDouble(),
        )
        val ndc = mc.gameRenderer.projectPointToScreen(point)
        check(abs(ndc.x) < 1 && abs(ndc.y) < 1) { "$card is off the screen at $ndc" }
        val window = mc.window
        return (ndc.x + 1) / 2 * window.screenWidth to (1 - ndc.y) / 2 * window.screenHeight
    }

    private fun handCard(table: BlockPos, card: PlayingCard): TableCard? =
        ClientTables[table]!!.let { it.scene(it.seatPlaces()) }.firstOrNull { it.mine && it.sprite == CardArt.face(card) }

    /** The side of the table each seat sits at, as the renderer has it. */
    private fun sides(table: BlockPos): List<Direction> = ClientTables[table]!!.seatPlaces().map { it.side }

    /**
     * Guandan in the immersive view: a play of two or three cards picked by pressing on the
     * first of them on the table and sweeping to the last, then played with the play button.
     */
    private fun playGuandan(context: ClientGameTestContext) {
        context.worldBuilder().create().use { world ->
            val server = world.server
            val table = server.computeOnServer<BlockPos, RuntimeException> { s -> buildScene(s, seats = 4) }
            context.waitTicks(40)
            world.clientLevel.waitForChunksRender()

            act(server, table, TableOp.TIMER, 0)
            act(server, table, TableOp.GAME, text = "guandan")
            act(server, table, TableOp.SIT, 0)
            act(server, table, TableOp.ADD_BOT, -1, BotLevel.EASY.ordinal)
            context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == "guandan" && it.seats.size == 4 && it.seats.all { s -> s.occupant != null } } == true }, 100)
            context.runOnClient<RuntimeException> { ClientConfig.immersive = true }
            act(server, table, TableOp.START)
            context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
            context.setScreen { TableScreen(table) }

            // Pass until there is a pair or three of a kind to play, lying side by side in the hand.
            var tries = 0
            var run: List<Int>
            while (true) {
                check(++tries < 40) { "Never got a Guandan turn with a pair to play" }
                context.waitFor({ (ClientTables[table]?.view as? GdView)?.let { it.isMyTurn && it.phase == GdPhase.PLAYING } == true }, 2400)
                val view = ClientTables[table]!!.view as GdView
                val turn = view.turnRules()!!
                val hand = GdUi.sorted(view, view.hand)
                run = turn.plays.filter { it.cards.size in 2..3 && !it.pattern.isBomb }
                    .map { play -> places(hand, play.cards) }
                    .filter { it.last() - it.first() == it.size - 1 }
                    .minByOrNull { it.size } ?: emptyList()
                if (run.isNotEmpty()) break
                // Without a pair to lead, lead the smallest single instead.
                val action = if (turn.canPass) GdAction.Pass else GdAction.PlayCards(turn.plays.minBy { it.cards.size }.cards)
                val step = ClientTables[table]!!.snapshot.step
                act(server, table, TableOp.ACT, data = GdCodecs.ACTION.encode(action))
                context.waitFor({ (ClientTables[table]?.snapshot?.step ?: step) != step }, 100)
            }

            val side = context.computeOnClient<Direction, RuntimeException> { sides(table).first() }
            val look = Vec3(table.x + 0.5 + side.stepX * 1.05, table.y + CardTableBlock.TOP_HEIGHT + 0.2, table.z + 0.5 + side.stepZ * 1.05)
            context.runOnClient<RuntimeException> { it.player!!.lookAt(EntityAnchorArgument.Anchor.EYES, look) }
            context.waitTicks(10)
            shot(context, "gd-immersive-hand")

            val view = ClientTables[table]!!.view as GdView
            val hand = GdUi.sorted(view, view.hand)
            val keys = GdUi.keys(hand)
            val play = run.map { hand[it] }
            TuKuai.LOGGER.info("Sweeping over {} on the table", play)
            fun point(i: Int) = context.computeOnClient<Pair<Double, Double>, RuntimeException> { mc ->
                screenPoint(mc, table, myCards(table).first { it.key == keys[i] })
            }
            val (x0, y0) = point(run.first())
            val (x1, y1) = point(run.last())
            context.input.setCursorPos(x0, y0)
            context.waitTicks(3)
            context.input.holdMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
            context.waitTicks(2)
            for (k in 1..4) {
                context.input.setCursorPos(x0 + (x1 - x0) * k / 4, y0 + (y1 - y0) * k / 4)
                context.waitTicks(2)
            }
            context.input.releaseMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
            context.waitTicks(10)
            val raised = context.computeOnClient<List<Int>, RuntimeException> {
                val screen = it.screen as TableScreen
                val client = ClientTables[table]!!
                hand.indices.filter { i -> myCards(table).firstOrNull { c -> c.key == keys[i] }?.let { c -> screen.isRaised(client, c) } == true }
            }
            check(raised == run) { "Sweeping over $run on the table picked $raised" }
            shot(context, "gd-immersive-picked")

            pressButton(context, "tukuai.ddz.play")
            context.waitFor({ (ClientTables[table]?.view as? GdView)?.hand?.size == hand.size - play.size }, 100)
            context.waitTicks(15)
            shot(context, "gd-immersive-played")
            context.setScreen { null }
            context.runOnClient<RuntimeException> { ClientConfig.immersive = false }
        }
    }

    /** Where in [hand] the [cards] are, each copy of a card at its own place, in hand order. */
    private fun places(hand: List<PlayingCard>, cards: List<PlayingCard>): List<Int> {
        val used = HashSet<Int>()
        return cards.map { card -> hand.indices.first { it !in used && hand[it] == card }.also { used += it } }.sorted()
    }

    /** Clicks the table screen's button with the label [key]; it must be on. */
    private fun pressButton(context: ClientGameTestContext, key: String) {
        val (bx, by) = context.computeOnClient<Pair<Double, Double>, RuntimeException> { mc ->
            val button = (mc.screen as TableScreen).children().filterIsInstance<Button>().first { b ->
                (b.message.contents as? TranslatableContents)?.key == key
            }
            check(button.active) { "The $key button is off" }
            val window = mc.window
            (button.x + button.width / 2.0) * window.screenWidth / window.guiScaledWidth to
                (button.y + button.height / 2.0) * window.screenHeight / window.guiScaledHeight
        }
        context.input.setCursorPos(bx, by)
        context.waitTicks(2)
        context.input.pressMouse(GLFW.GLFW_MOUSE_BUTTON_LEFT)
    }

    private fun myCards(table: BlockPos): List<TableCard> = ClientTables[table]!!.let { it.scene(it.seatPlaces()) }.filter { it.mine }

    /** A 3x3 table north of the player with a stool on each seat side, in daylight. */
    private fun buildScene(server: MinecraftServer, seats: Int = 3): BlockPos {
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
        for (side in listOf(Direction.SOUTH, Direction.EAST, Direction.NORTH, Direction.WEST).take(seats)) {
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

    private fun shot(context: ClientGameTestContext, name: String) {
        val path = context.takeScreenshot("tukuai-$name")
        TuKuai.LOGGER.info("SCREENSHOT {}", path.toAbsolutePath())
    }
}
