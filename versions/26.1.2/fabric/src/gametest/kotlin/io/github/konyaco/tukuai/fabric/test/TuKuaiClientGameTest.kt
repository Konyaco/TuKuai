package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientConfig
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.ReplayScreen
import io.github.konyaco.tukuai.client.RulesScreen
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.SeatEntity
import io.github.konyaco.tukuai.content.StoolBlock
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.content.Wood
import io.github.konyaco.tukuai.engine.bluff.BluffAction
import io.github.konyaco.tukuai.engine.bluff.BluffCodecs
import io.github.konyaco.tukuai.engine.bluff.BluffEventKind
import io.github.konyaco.tukuai.engine.bluff.BluffPhase
import io.github.konyaco.tukuai.engine.bluff.BluffView
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.guandan.GdPhase
import io.github.konyaco.tukuai.engine.guandan.GdView
import io.github.konyaco.tukuai.engine.paodekuai.PdkAction
import io.github.konyaco.tukuai.engine.paodekuai.PdkCodecs
import io.github.konyaco.tukuai.engine.paodekuai.PdkView
import io.github.konyaco.tukuai.engine.tutu.TutuView
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.ReplayStore
import io.github.konyaco.tukuai.table.ServerSettings
import io.github.konyaco.tukuai.table.StatsStore
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.BlockItemStateProperties
import net.minecraft.world.item.context.UseOnContext
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3
import org.lwjgl.glfw.GLFW

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
        // Taking README screenshots with ShowcaseGameTest; no need to play the whole test round too.
        if (System.getenv("TUKUAI_SHOWCASE") != null || System.getenv("TUKUAI_MULTIPLAYER") != null) return
        // TUKUAI_ONLY=<name> runs just one of the tests, to try it quickly; "tutu" plays only Tutu cards, "bluff" only creeper bluff.
        val only = System.getenv("TUKUAI_ONLY")
        if (only == "tutu") return onlyGame(context) { server, table -> playTutu(context, server, table) }
        if (only == "bluff") return onlyGame(context) { server, table -> playBluff(context, server, table) }
        if (only != null && only != "game") return
        context.worldBuilder().create().use { world ->
            world.clientLevel.waitForChunksRender()
            val server = world.server
            val table = server.computeOnServer<BlockPos, RuntimeException> { s -> buildScene(s) }
            check(server.computeOnServer<Boolean, RuntimeException> { s ->
                listOf("card_table/oak", "card_table/cherry", "stool/birch_red").all {
                    s.recipeManager.byKey(ResourceKey.create(Registries.RECIPE, TuKuai.id(it))).isPresent
                }
            }) { "The card table and stool recipes did not load" }
            context.waitTicks(40)
            world.clientLevel.waitForChunksRender()
            server.runOnServer<RuntimeException> { s -> checkOldTables(s) }

            // The table and a stool from the side, to show how they are built.
            moveTo(server, table.offset(-4, 0, 3))
            context.waitTicks(5)
            context.input.lookAt(table)
            context.waitTicks(5)
            shot(context, "0-world-table-side")
            val stool = table.east(StoolBlock.TABLE_DISTANCE)
            moveTo(server, stool.offset(2, 0, 2))
            context.waitTicks(5)
            context.input.lookAt(stool)
            context.waitTicks(5)
            shot(context, "0-world-stool")
            moveTo(server, oldBase)
            context.waitTicks(5)

            woodenTables(context, server)

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

            // A newcomer's lobby points to the rule book until it has been opened once.
            context.runOnClient<RuntimeException> { ClientConfig.guideSeen = false }
            context.setScreen { TableScreen(table) }
            context.waitTicks(10)
            shot(context, "2-screen-lobby")
            readRules(context, "doudizhu", "patterns")

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

            // The same hand in each of the other card face styles, on the screen and on the table.
            for ((i, style) in CardArt.FACE_STYLES.withIndex().drop(1)) {
                for (immersive in listOf(false, true)) {
                    context.runOnClient<RuntimeException> {
                        ClientConfig.cardFace = i
                        ClientConfig.immersive = immersive
                        (it.screen as? TableScreen)?.refresh()
                    }
                    context.waitTicks(5)
                    shot(context, "4-face-$style-${if (immersive) "world" else "screen"}")
                }
            }
            context.runOnClient<RuntimeException> { ClientConfig.cardFace = 0 }

            checkPause(context, server, table)
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
            // Back in the lobby, the player's seat shows the game just played.
            context.waitFor({ ClientTables[table]?.snapshot?.seats?.firstOrNull()?.games == 1 }, 100)
            context.runOnClient<RuntimeException> { (it.screen as TableScreen).openLobby() }
            context.waitTicks(5)
            shot(context, "7-screen-lobby-record")
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
        readRules(context, "uno", "actions")

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

        checkPause(context, server, table)
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

        playTutu(context, server, table)
        playBluff(context, server, table)
        playPaoDeKuai(context, server, table)
    }

    /** A table with only us and computers, straight into [play], such as a hand of Tutu cards; `TUKUAI_LANG` picks the language. */
    private fun onlyGame(context: ClientGameTestContext, play: (TestServerContext, BlockPos) -> Unit) {
        val lang = System.getenv("TUKUAI_LANG") ?: "en_us"
        if (lang != "en_us") {
            context.runOnClient<RuntimeException> { mc ->
                mc.options.languageCode = lang
                mc.languageManager.setSelected(lang)
                mc.reloadResourcePacks()
            }
            context.waitTicks(2)
            context.waitFor({ it.overlay == null }, 1200)
        }
        context.worldBuilder().create().use { world ->
            world.clientLevel.waitForChunksRender()
            val server = world.server
            val table = server.computeOnServer<BlockPos, RuntimeException> { s -> buildScene(s) }
            context.waitTicks(40)
            world.clientLevel.waitForChunksRender()
            act(server, table, TableOp.SIT, 0)
            context.waitTicks(10)
            play(server, table)
        }
    }

    /**
     * Switches the table to creeper bluff for four and plays a game of it: the lobby, the rule
     * book on the creeper, our turn with a play on the pile to answer, a call turned over, a
     * fuse hissing, the end and the replay.
     */
    private fun playBluff(context: ClientGameTestContext, server: TestServerContext, table: BlockPos) {
        context.setScreen { null }
        act(server, table, TableOp.GAME, text = "bluff")
        repeat(3) { act(server, table, TableOp.ADD_BOT, -1, BotLevel.entries[it].ordinal) }
        context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == "bluff" && it.seats.size == 4 && it.seats.all { s -> s.occupant != null } } == true }, 100)
        context.setScreen { TableScreen(table) }
        context.waitTicks(10)
        shot(context, "bluff-1-lobby")
        readRules(context, "bluff", "penalty")

        context.setScreen { null }
        act(server, table, TableOp.START)
        context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == "bluff" && it.status == TableStatus.PLAYING } == true }, 200)
        context.input.lookAt(table)
        context.waitTicks(12)
        shot(context, "bluff-2-world-dealing")

        // Our turn with someone's play to answer; when we lead, we lay down a card and wait again.
        context.setScreen { TableScreen(table) }
        val bluff = { ClientTables[table]?.view as? BluffView }
        var turns = 0
        while (true) {
            context.waitFor({ bluff()?.let { it.isMyTurn || it.finished } == true }, 2400)
            val view = bluff()!!
            if (view.finished) {
                act(server, table, TableOp.START)
                context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
                continue
            }
            if (view.phase == BluffPhase.PLAY && view.canCall && !view.mustCall) break
            check(++turns < 40) { "Never had a play to answer" }
            val action = if (view.phase == BluffPhase.PENALTY) BluffAction.Light else if (view.mustCall) BluffAction.Call else BluffAction.Play(listOf(view.hand.first()))
            act(server, table, TableOp.ACT, data = BluffCodecs.ACTION.encode(action))
            context.waitFor({ bluff()?.let { v -> v.log.size > view.log.size } == true }, 100)
        }
        context.waitTicks(10)
        shot(context, "bluff-3-screen-turn")
        context.setScreen { null }
        context.waitTicks(5)
        shot(context, "bluff-4-world-turn")

        // We call it: the play is turned over, and whoever lost the call has to light their fuse.
        context.setScreen { TableScreen(table) }
        act(server, table, TableOp.ACT, data = BluffCodecs.ACTION.encode(BluffAction.Call))
        context.waitFor({ bluff()?.phase == BluffPhase.PENALTY }, 100)
        context.waitTicks(10)
        shot(context, "bluff-5-screen-called")
        context.setScreen { null }
        context.waitTicks(5)
        shot(context, "bluff-6-world-called")

        checkPause(context, server, table)
        context.setScreen { TableScreen(table) }
        act(server, table, TableOp.AUTO, 1)
        context.waitFor({ bluff()?.log?.lastOrNull()?.kind == BluffEventKind.DEAL && bluff()?.log?.any { it.kind == BluffEventKind.LIGHT } == true }, 600)
        context.waitTicks(8)
        shot(context, "bluff-7-screen-hiss")

        var waited = 0
        while (status(server, table) != TableStatus.FINISHED) {
            check(waited < 12000) { "The creeper bluff game did not finish" }
            context.waitTicks(100)
            waited += 100
        }
        context.waitFor({ bluff()?.finished == true }, 200)
        val view = bluff()!!
        check(view.out.count { !it } == 1 && view.winner != null) { "The creeper bluff game ended oddly: $view" }
        context.waitTicks(20)
        context.setScreen { TableScreen(table) }
        context.waitTicks(10)
        shot(context, "bluff-8-screen-result")
        context.setScreen { null }
        context.waitTicks(10)
        shot(context, "bluff-9-world-result")

        act(server, table, TableOp.REPLAY)
        context.waitFor({ it.screen is ReplayScreen }, 100)
        context.runOnClient<RuntimeException> {
            val replay = it.screen as ReplayScreen
            replay.pause()
            replay.seek(replay.total / 2)
        }
        context.waitTicks(5)
        shot(context, "bluff-10-replay")
        context.setScreen { null }
        act(server, table, TableOp.AUTO, 0)
    }

    /**
     * Switches the table to Tutu cards, with four seats, and plays a hand of it: the lobby,
     * the rule book's wild cards, our turn, a cactus in the pile or TNT burning, the end and
     * the replay.
     */
    private fun playTutu(context: ClientGameTestContext, server: TestServerContext, table: BlockPos) {
        context.setScreen { null }
        act(server, table, TableOp.GAME, text = "tutu")
        repeat(3) { act(server, table, TableOp.ADD_BOT, -1, BotLevel.entries[it].ordinal) }
        context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == "tutu" && it.seats.size == 4 && it.seats.all { s -> s.occupant != null } } == true }, 100)
        context.setScreen { TableScreen(table) }
        context.waitTicks(10)
        shot(context, "tutu-1-lobby")
        readRules(context, "tutu", "wilds")

        context.setScreen { null }
        act(server, table, TableOp.START)
        context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == "tutu" && it.status == TableStatus.PLAYING } == true }, 200)
        context.input.lookAt(table)
        context.waitTicks(12)
        shot(context, "tutu-2-world-dealing")

        context.setScreen { TableScreen(table) }
        context.waitFor({ (ClientTables[table]?.view as? TutuView)?.isMyTurn == true }, 1200)
        context.waitTicks(10)
        shot(context, "tutu-3-screen-turn")

        checkPause(context, server, table)
        // Let the computer play for us until TNT is lit, or failing that a cactus is planted, or the hand ends.
        act(server, table, TableOp.AUTO, 1)
        context.waitFor({ (ClientTables[table]?.view as? TutuView)?.let { it.finished || it.bombs > 0 || it.cacti.isNotEmpty() } == true }, 6000)
        context.waitFor({ (ClientTables[table]?.view as? TutuView)?.let { it.finished || it.bombs > 0 || it.drawCount < 40 } == true }, 6000)
        context.waitTicks(5)
        shot(context, "tutu-4-screen-specials")
        context.setScreen { null }
        context.waitTicks(10)
        shot(context, "tutu-5-world-specials")

        var waited = 0
        while (status(server, table) != TableStatus.FINISHED) {
            check(waited < 12000) { "The Tutu hand did not finish" }
            context.waitTicks(100)
            waited += 100
        }
        context.waitFor({ (ClientTables[table]?.view as? TutuView)?.finished == true }, 200)
        val view = ClientTables[table]!!.view as TutuView
        check(view.settlement!!.scores.sum() == 0) { "The Tutu hand ended oddly: $view" }
        context.waitTicks(20)
        context.setScreen { TableScreen(table) }
        context.waitTicks(10)
        shot(context, "tutu-6-screen-result")
        context.setScreen { null }
        context.waitTicks(10)
        shot(context, "tutu-7-world-result")

        act(server, table, TableOp.REPLAY)
        context.waitFor({ it.screen is ReplayScreen }, 100)
        context.runOnClient<RuntimeException> {
            val replay = it.screen as ReplayScreen
            replay.pause()
            replay.seek(replay.total / 2)
        }
        context.waitTicks(5)
        shot(context, "tutu-8-replay")
    }

    /**
     * Switches the table to Pao De Kuai, which drops back to three seats, and plays a hand of
     * it: the lobby, the rule book, our turn with a hint picked, the world view, and the end.
     */
    private fun playPaoDeKuai(context: ClientGameTestContext, server: TestServerContext, table: BlockPos) {
        context.setScreen { null }
        act(server, table, TableOp.GAME, text = "paodekuai")
        context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == "paodekuai" && it.seats.size == 3 && it.seats.all { s -> s.occupant != null } } == true }, 100)
        context.setScreen { TableScreen(table) }
        context.waitTicks(10)
        shot(context, "20-pdk-lobby")
        readRules(context, "paodekuai", "patterns")
        context.runOnClient<RuntimeException> { (it.screen as TableScreen).openRules() }
        context.waitFor({ it.screen is RulesScreen }, 20)
        context.runOnClient<RuntimeException> { (it.screen as RulesScreen).showSection("last") }
        context.waitTicks(5)
        shot(context, "rules-paodekuai-last")
        context.input.pressKey(GLFW.GLFW_KEY_ESCAPE)
        context.waitFor({ it.screen is TableScreen }, 20)

        context.setScreen { null }
        act(server, table, TableOp.START)
        context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == "paodekuai" && it.status == TableStatus.PLAYING } == true }, 200)
        context.input.lookAt(table)
        context.waitTicks(12)
        shot(context, "21-pdk-world-dealing")

        // Our turn, with the hint's cards picked out. Turns where nothing beats the table are passed.
        context.setScreen { TableScreen(table) }
        var passes = 0
        while (true) {
            context.waitFor({ ClientTables[table]?.let { (it.view as? PdkView)?.isMyTurn == true || it.snapshot.status == TableStatus.FINISHED } == true }, 1200)
            // Now and then someone goes out while we pass: deal again.
            if (ClientTables[table]!!.snapshot.status == TableStatus.FINISHED) {
                act(server, table, TableOp.START)
                context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
                continue
            }
            val canPlay = context.computeOnClient<Boolean, RuntimeException> {
                (ClientTables[table]?.view as? PdkView)?.turnRules()?.plays?.isNotEmpty() == true
            }
            if (canPlay) break
            check(++passes < 30) { "Never had anything to play" }
            act(server, table, TableOp.ACT, data = PdkCodecs.ACTION.encode(PdkAction.Pass))
            context.waitFor({ (ClientTables[table]?.view as? PdkView)?.isMyTurn != true }, 100)
        }
        context.waitTicks(10)
        context.input.pressKey(GLFW.GLFW_KEY_H)
        context.waitTicks(5)
        shot(context, "22-pdk-screen-turn")

        context.runOnClient<RuntimeException> {
            ClientConfig.immersive = true
            (it.screen as? TableScreen)?.refresh()
        }
        context.waitTicks(5)
        shot(context, "23-pdk-screen-immersive")
        context.runOnClient<RuntimeException> {
            ClientConfig.immersive = false
            (it.screen as? TableScreen)?.refresh()
        }

        checkPause(context, server, table)
        act(server, table, TableOp.AUTO, 1)
        context.waitTicks(200)
        shot(context, "24-pdk-screen-midgame")
        context.setScreen { null }
        context.waitTicks(5)
        shot(context, "25-pdk-world-midgame")

        var waited = 0
        while (status(server, table) != TableStatus.FINISHED) {
            check(waited < 6000) { "The Pao De Kuai hand did not finish" }
            context.waitTicks(100)
            waited += 100
        }
        context.waitTicks(20)
        context.setScreen { TableScreen(table) }
        context.waitTicks(10)
        shot(context, "26-pdk-screen-result")
        context.setScreen { null }
        context.waitTicks(10)
        shot(context, "27-pdk-world-result")

        act(server, table, TableOp.REPLAY)
        context.waitFor({ it.screen is ReplayScreen }, 100)
        context.runOnClient<RuntimeException> {
            val replay = it.screen as ReplayScreen
            replay.pause()
            replay.seek(replay.total / 2)
        }
        context.waitTicks(5)
        shot(context, "28-pdk-replay")

        playGuandan(context, server, table)
    }

    /**
     * Switches the table to Guandan, four seats in two teams, and plays a round of it with
     * the computers: the lobby, the rule book, our turn with a hint picked, both views, the
     * result and its replay. Then a few rounds of the long game, for the screen between
     * rounds and the tribute.
     */
    private fun playGuandan(context: ClientGameTestContext, server: TestServerContext, table: BlockPos) {
        context.setScreen { null }
        act(server, table, TableOp.GAME, text = "guandan")
        act(server, table, TableOp.ADD_BOT, -1, BotLevel.NORMAL.ordinal)
        context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == "guandan" && it.seats.size == 4 && it.seats.all { s -> s.occupant != null } } == true }, 100)
        context.setScreen { TableScreen(table) }
        context.waitTicks(10)
        shot(context, "29-gd-lobby")
        readRules(context, "guandan", "patterns")
        for (section in listOf("wild", "bombs", "tribute")) {
            context.runOnClient<RuntimeException> { (it.screen as TableScreen).openRules() }
            context.waitFor({ it.screen is RulesScreen }, 20)
            context.runOnClient<RuntimeException> { (it.screen as RulesScreen).showSection(section) }
            context.waitTicks(5)
            shot(context, "rules-guandan-$section")
            context.input.pressKey(GLFW.GLFW_KEY_ESCAPE)
            context.waitFor({ it.screen is TableScreen }, 20)
        }

        context.setScreen { null }
        act(server, table, TableOp.START)
        context.waitFor({ ClientTables[table]?.snapshot?.let { it.gameId == "guandan" && it.status == TableStatus.PLAYING } == true }, 200)
        context.input.lookAt(table)
        context.waitTicks(12)
        shot(context, "30-gd-world-dealing")
        context.waitTicks(40)
        shot(context, "31-gd-world")

        // Our turn, with the hint's cards picked out.
        context.setScreen { TableScreen(table) }
        context.waitFor({ (ClientTables[table]?.view as? GdView)?.let { v -> v.isMyTurn && v.phase == GdPhase.PLAYING } == true }, 2400)
        context.waitTicks(10)
        context.input.pressKey(GLFW.GLFW_KEY_H)
        context.waitTicks(5)
        shot(context, "32-gd-screen-turn")
        context.runOnClient<RuntimeException> {
            ClientConfig.immersive = true
            (it.screen as? TableScreen)?.refresh()
        }
        context.waitTicks(5)
        shot(context, "33-gd-screen-immersive")
        context.runOnClient<RuntimeException> {
            ClientConfig.immersive = false
            (it.screen as? TableScreen)?.refresh()
        }

        checkPause(context, server, table)
        act(server, table, TableOp.AUTO, 1)
        context.waitTicks(400)
        shot(context, "34-gd-screen-midgame")
        context.setScreen { null }
        context.waitTicks(5)
        shot(context, "35-gd-world-midgame")

        var waited = 0
        while (status(server, table) != TableStatus.FINISHED) {
            check(waited < 12000) { "The Guandan round did not finish" }
            context.waitTicks(100)
            waited += 100
        }
        context.waitFor({ (ClientTables[table]?.view as? GdView)?.finished == true }, 200)
        val view = ClientTables[table]!!.view as GdView
        check(view.finished && view.rounds.size == 1 && view.scores.sum() == 0) { "The Guandan round ended oddly: $view" }
        context.waitTicks(20)
        context.setScreen { TableScreen(table) }
        context.waitTicks(10)
        shot(context, "36-gd-screen-result")
        context.setScreen { null }
        context.waitTicks(10)
        shot(context, "37-gd-world-result")

        act(server, table, TableOp.REPLAY)
        context.waitFor({ it.screen is ReplayScreen }, 100)
        context.runOnClient<RuntimeException> {
            val replay = it.screen as ReplayScreen
            replay.pause()
            replay.seek(replay.total / 2)
        }
        context.waitTicks(5)
        shot(context, "38-gd-replay")

        // The long game, with the computers quick: the screen between rounds, then the tribute.
        context.setScreen { null }
        act(server, table, TableOp.PRESET, text = "to_ace")
        botDelay(server, 0.15)
        act(server, table, TableOp.START)
        context.waitFor({ ClientTables[table]?.snapshot?.let { it.presetId == "to_ace" && it.status == TableStatus.PLAYING } == true }, 200)
        act(server, table, TableOp.AUTO, 1)
        context.setScreen { TableScreen(table) }
        // Slow down again near the end of the round, so the screens in between stay up a while.
        // Until the new match's first view arrives, the finished round above is still on the
        // client, with its order filled in; only a to_ace view in play counts.
        context.waitFor({ (ClientTables[table]?.view as? GdView)?.let { v -> v.rules.toAce && v.round == 0 && v.phase == GdPhase.PLAYING && (v.order.isNotEmpty() || v.handCounts.any { it <= 4 }) } == true }, 6000)
        botDelay(server, 2.5)
        context.waitFor({ (ClientTables[table]?.view as? GdView)?.let { it.rules.toAce && it.phase == GdPhase.ROUND_OVER } == true }, 4800)
        context.waitTicks(5)
        shot(context, "39-gd-screen-round-over")
        context.waitFor({ (ClientTables[table]?.view as? GdView)?.let { it.rules.toAce && it.round == 1 && it.phase != GdPhase.ROUND_OVER } == true }, 400)
        context.waitTicks(5)
        shot(context, "40-gd-screen-tribute")
        botDelay(server, 1.1)
        context.setScreen { null }
    }

    /**
     * Opens the rule book from the table screen and takes pictures of the table's own rules
     * and of [section], scrolled to the top and to the end, and closes it again. The test
     * window is small enough that this is already the largest GUI scale it allows.
     */
    private fun readRules(context: ClientGameTestContext, game: String, section: String) {
        context.runOnClient<RuntimeException> { (it.screen as TableScreen).openRules() }
        context.waitFor({ it.screen is RulesScreen }, 20)
        context.waitTicks(5)
        check(context.computeOnClient<Boolean, RuntimeException> { ClientConfig.guideSeen }) { "Opening the rule book was not remembered" }
        shot(context, "rules-$game-table")
        context.runOnClient<RuntimeException> { (it.screen as RulesScreen).showSection(section) }
        context.waitTicks(5)
        shot(context, "rules-$game-$section")
        context.input.pressKey(GLFW.GLFW_KEY_END)
        context.waitTicks(5)
        shot(context, "rules-$game-$section-end")
        context.input.pressKey(GLFW.GLFW_KEY_ESCAPE)
        context.waitFor({ it.screen is TableScreen }, 20)
        context.waitTicks(5)
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
        // A birch table: all nine parts are birch, and so is the table it drops.
        val stack = ItemStack(TuKuaiContent.CARD_TABLE_ITEM)
        stack.set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(CardTableBlock.WOOD, Wood.BIRCH))
        player.setItemInHand(InteractionHand.MAIN_HAND, stack)
        val hit = BlockHitResult(Vec3.atCenterOf(clicked.below()).add(0.0, 0.5, 0.0), Direction.UP, clicked.below(), false)
        stack.useOn(UseOnContext(player, InteractionHand.MAIN_HAND, hit))
        val placed = clicked.west()
        for (part in 0 until CardTableBlock.PARTS) {
            val state = level.getBlockState(CardTableBlock.posOf(placed, part))
            check(
                state.block is CardTableBlock && state.getValue(CardTableBlock.PART) == part &&
                    state.getValue(CardTableBlock.FACING) == Direction.EAST && state.getValue(CardTableBlock.WOOD) == Wood.BIRCH,
            ) {
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
        val dropped = level.getEntitiesOfClass(ItemEntity::class.java, AABB(placed).inflate(2.5)) { it.item.`is`(TuKuaiContent.CARD_TABLE_ITEM) }
            .first().item.get(DataComponents.BLOCK_STATE)?.get(CardTableBlock.WOOD)
        check(dropped == Wood.BIRCH) { "A birch table dropped a $dropped table" }

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

    /**
     * A row of tables in different woods, each with a stool of the same wood, seen from
     * the side in daylight. Away from the rest of the test, south of the player.
     */
    private fun woodenTables(context: ClientGameTestContext, server: TestServerContext) {
        val woods = listOf(Wood.SPRUCE, Wood.BIRCH, Wood.CHERRY, Wood.DARK_OAK, Wood.MANGROVE, Wood.CRIMSON, Wood.WARPED, Wood.BAMBOO)
        val colors = listOf(DyeColor.BLUE, DyeColor.RED, DyeColor.PINK, DyeColor.ORANGE, DyeColor.LIME, DyeColor.CYAN, DyeColor.YELLOW, DyeColor.PURPLE)
        val row = server.computeOnServer<BlockPos, RuntimeException> { s ->
            val level = s.overworld()
            s.commands.performPrefixedCommand(s.createCommandSourceStack(), "time set noon")
            s.commands.performPrefixedCommand(s.createCommandSourceStack(), "weather clear")
            val start = oldBase.offset(-14, 0, 12)
            for (x in -2..32) for (z in -3..8) {
                for (y in 0..4) level.setBlockAndUpdate(start.offset(x, y, z), Blocks.AIR.defaultBlockState())
                level.setBlockAndUpdate(start.offset(x, -1, z), Blocks.STONE_BRICKS.defaultBlockState())
            }
            woods.forEachIndexed { i, wood ->
                val center = start.offset(i * 4, 0, 0)
                for (part in 0 until CardTableBlock.PARTS) {
                    level.setBlockAndUpdate(
                        CardTableBlock.posOf(center, part),
                        TuKuaiContent.CARD_TABLE.defaultBlockState().setValue(CardTableBlock.FACING, Direction.SOUTH)
                            .setValue(CardTableBlock.PART, part).setValue(CardTableBlock.WOOD, wood),
                    )
                }
                (level.getBlockEntity(center) as CardTableBlockEntity).dye(colors[i])
                level.setBlockAndUpdate(
                    center.south(StoolBlock.TABLE_DISTANCE),
                    TuKuaiContent.STOOL.defaultBlockState().setValue(StoolBlock.WOOD, wood).setValue(StoolBlock.COLOR, colors[i]),
                )
            }
            start
        }
        context.waitTicks(20)
        val middle = row.offset(14, 0, 0)
        moveTo(server, middle.offset(0, 0, 13))
        context.waitTicks(10)
        context.input.lookAt(middle)
        context.waitTicks(20)
        shot(context, "0-world-wood-tables")
        moveTo(server, row.offset(6, 0, 5))
        context.waitTicks(5)
        context.input.lookAt(row.offset(4, 0, 1))
        context.waitTicks(10)
        shot(context, "0-world-wood-close")
        moveTo(server, oldBase)
        context.waitTicks(5)
    }

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

    private fun act(server: TestServerContext, pos: BlockPos, op: TableOp, a: Int = 0, b: Int = 0, text: String = "", data: ByteArray = ByteArray(0)) {
        server.runOnServer<RuntimeException> { s ->
            val player = s.playerList.players.first()
            val table = s.overworld().getBlockEntity(pos) as CardTableBlockEntity
            table.handle(player, TableActionPayload(pos, op, a, b, text, data))
        }
    }

    /**
     * Stands up mid-game: with only computers left in it, the game waits instead of them
     * playing it out. Sitting back down picks it up again.
     */
    private fun checkPause(context: ClientGameTestContext, server: TestServerContext, table: BlockPos) {
        val seat = server.computeOnServer<Int, RuntimeException> { s ->
            (s.overworld().getBlockEntity(table) as CardTableBlockEntity).seatOf(s.playerList.players.first().uuid) ?: -1
        }
        check(status(server, table) == TableStatus.PLAYING && seat >= 0) { "No game of ours to pause" }
        act(server, table, TableOp.STAND)
        context.waitFor({ ClientTables[table]?.snapshot?.paused == true }, 100)
        val step = ClientTables[table]!!.snapshot.step
        context.waitTicks(60)
        val now = ClientTables[table]!!.snapshot
        check(now.paused && now.step == step && now.status == TableStatus.PLAYING) { "The ${now.gameId} game went on with nobody at the table" }
        act(server, table, TableOp.SIT, seat)
        context.waitFor({ ClientTables[table]?.snapshot?.paused == false }, 100)
    }

    /** Stands the player in the middle of the block at [pos] and waits for the client to follow. */
    private fun moveTo(server: TestServerContext, pos: BlockPos) {
        server.runOnServer<RuntimeException> { s ->
            s.playerList.players.first().teleportTo(pos.x + 0.5, pos.y.toDouble(), pos.z + 0.5)
        }
    }

    /** Sets how long computers wait before they play; the setting is only read from the config file otherwise. */
    private fun botDelay(server: TestServerContext, seconds: Double) {
        server.runOnServer<RuntimeException> {
            val field = ServerSettings::class.java.getDeclaredField("botDelaySeconds")
            field.isAccessible = true
            field.set(null, seconds)
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
