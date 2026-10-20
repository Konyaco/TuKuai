package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientTable
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.content.SeatEntity
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.doudizhu.DdzPhase
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.ReplayStore
import io.github.konyaco.tukuai.table.StatsStore
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import java.util.UUID
import java.util.concurrent.TimeUnit
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext
import net.minecraft.core.BlockPos
import net.minecraft.core.UUIDUtil
import net.minecraft.core.Direction
import net.minecraft.server.MinecraftServer
import net.minecraft.world.level.block.Blocks

/**
 * Two people at one table over a real network: a dedicated server, this test's client
 * and a second client in a process of its own ([PeerClient]). Both sit down, a computer
 * takes the third seat, and they play two rounds of Dou Dizhu, each sending their own
 * moves and each seeing only their own hand.
 *
 * In the first round this client drops off partway: the computer plays its turns until
 * it is back, and then it plays them again. In the second, the other client's process
 * is killed outright and a new one joins as the same player and picks up its seat.
 *
 * It opens a second game window, so it only runs when asked: `./gradlew :fabric:runMultiplayerTest`.
 */
class MultiplayerGameTest : FabricClientGameTest {
    private companion object {
        const val PEER_NAME = "Bob"
        const val PEER_SEAT = 1
        const val MY_SEAT = 0

        /** The server runs in offline mode, so the other player's id follows from the name. */
        val PEER_ID: UUID = UUIDUtil.createOfflinePlayerUUID(PEER_NAME)
    }

    override fun runTest(context: ClientGameTestContext) {
        if (System.getenv("TUKUAI_MULTIPLAYER") == null) return
        val properties = Properties()
        properties.setProperty("max-players", "4")
        context.worldBuilder().createServer(properties).use { server ->
            var connection = server.connect()
            connection.clientLevel.waitForChunksRender()
            val table = server.computeOnServer<BlockPos, RuntimeException> { s -> buildScene(s) }
            val me = context.computeOnClient<UUID, RuntimeException> { it.player!!.uuid }
            val myName = context.computeOnClient<String, RuntimeException> { it.user.name }
            val port = server.computeOnServer<Int, RuntimeException> { it.port }
            var peer = Peer.start("peer-1", port, table, PEER_SEAT)
            try {
                waitOnServer(context, server, peer, 6000, "The second client did not join") { s -> s.playerList.getPlayerByName(PEER_NAME) != null }
                server.runOnServer<RuntimeException> { s ->
                    s.playerList.getPlayerByName(PEER_NAME)!!.teleportTo(table.x + 3.5, table.y.toDouble(), table.z + 0.5)
                }

                // Everything from here is sent from the clients, as the table screen would.
                // With no turn timer, the computer never plays for someone who is here.
                context.runOnClient<RuntimeException> {
                    SeatPlayer.send(table, TableOp.SIT, MY_SEAT)
                    SeatPlayer.send(table, TableOp.TIMER, 0)
                    SeatPlayer.send(table, TableOp.ADD_BOT, 2, BotLevel.NORMAL.ordinal)
                }
                waitPeer(context, peer, 1200, "The second client did not sit down") { it["you"] == "$PEER_SEAT" }
                context.waitFor({ ClientTables[table]?.snapshot?.seats?.all { it.occupant != null } == true }, 100)
                context.runOnClient<RuntimeException> { SeatPlayer.send(table, TableOp.START) }
                context.waitFor({ ClientTables[table]?.snapshot?.status == TableStatus.PLAYING }, 200)
                waitPeer(context, peer, 200, "The second client did not see the game start") { it["status"] == "PLAYING" }

                // Play until the bidding is over and a few cards are down.
                val player = SeatPlayer(table)
                play(context, player, table, peer) { t -> (t.view as DdzView).let { it.phase == DdzPhase.PLAYING && it.handCounts.sum() <= 48 } }
                context.setScreen { TableScreen(table) }
                context.waitTicks(10)
                shot(context, "mp-1-player-screen")
                context.setScreen { null }

                // Drop off. The seat stays ours, and the computer plays for us meanwhile.
                val leftAt = peer.status()!!["step"].toString().toInt()
                connection.close()
                waitPeer(context, peer, 200, "The other player was not told we left") { it["seats"].toString().startsWith("$myName/away") }
                waitPeer(context, peer, 1200, "Nobody played our turns while we were away") { it["step"].toString().toInt() >= leftAt + 4 || it["status"] == "FINISHED" }
                check(server.computeOnServer<Boolean, RuntimeException> { s -> tableAt(s, table).seatOf(me) == MY_SEAT }) { "Leaving gave our seat away" }
                check(peer.status()!!["status"] == "PLAYING") { "The round ended while we were away, so getting back in was not tested" }

                // Back in: the seat and the hand are ours again, and we play our own turns.
                connection = server.connect()
                connection.clientLevel.waitForChunksRender()
                context.waitFor({ (ClientTables[table]?.view as? DdzView)?.seat == MY_SEAT }, 200)
                context.waitTicks(20)
                check(server.computeOnServer<Boolean, RuntimeException> { s -> s.playerList.getPlayer(me)?.vehicle is SeatEntity }) { "We were not back in our seat on the server" }
                check(context.computeOnClient<Boolean, RuntimeException> { it.player?.vehicle is SeatEntity }) { "We were not back in our seat on our own screen" }
                waitPeer(context, peer, 200, "The other player did not see us come back") { it["seats"].toString().startsWith("$myName/here/self") }
                val sentBefore = player.sent
                play(context, player, table, peer) { false }
                check(player.sent > sentBefore) { "We never played a turn after coming back" }

                // The same result on both clients, and in both players' records.
                checkEnd(context, server, table, peer, me, 1)
                context.runOnClient<RuntimeException> { it.toastManager.clear() }
                context.setScreen { TableScreen(table) }
                context.waitTicks(10)
                shot(context, "mp-2-result")
                context.setScreen { null }

                // The other player watches the round again; the replay comes over the network too.
                peer.command(context, "REPLAY")
                waitPeer(context, peer, 200, "The other client could not open the replay") { it["screen"] == "ReplayScreen" }
                peer.command(context, "CLOSE")

                // Round two, started by the other player.
                peer.command(context, "START")
                context.waitFor({ ClientTables[table]?.snapshot?.let { it.status == TableStatus.PLAYING && it.rounds == 1 } == true }, 200)
                play(context, player, table, peer) { t -> (t.view as DdzView).let { it.phase == DdzPhase.PLAYING && it.handCounts.sum() <= 50 } }

                // The other client is killed outright. Its seat stays, and the computer plays it,
                // while we take our turns until it is our turn again and then wait for them.
                peer.kill()
                context.waitFor({ ClientTables[table]?.snapshot?.seats?.get(PEER_SEAT)?.present == false }, 200)
                val killedAt = ClientTables[table]!!.snapshot.step
                play(context, player, table, peer) { t -> t.snapshot.step >= killedAt + 3 && (t.view as DdzView).isMyTurn }
                check(ClientTables[table]!!.snapshot.status == TableStatus.PLAYING) { "The round ended while the other player was away" }
                check(server.computeOnServer<Boolean, RuntimeException> { s -> tableAt(s, table).seatOf(PEER_ID) == PEER_SEAT }) { "Dropping out gave their seat away" }

                // A new client joins as the same player and is dealt back in.
                peer = Peer.start("peer-2", port, table, PEER_SEAT)
                waitPeer(context, peer, 6000, "The other player did not get their seat back") { it["viewSeat"] == "$PEER_SEAT" && it["you"] == "$PEER_SEAT" }
                context.waitFor({ ClientTables[table]?.snapshot?.seats?.get(PEER_SEAT)?.let { it.present && !it.auto } == true }, 200)
                play(context, player, table, peer) { false }
                val theirs = checkEnd(context, server, table, peer, me, 2)
                check(theirs["sent"].toString().toInt() > 0) { "The other player never played a turn after coming back" }
                check(theirs["tally"] == ClientTables[table]!!.snapshot.tally.joinToString(";")) { "The two clients disagree on the points so far" }
                context.setScreen { TableScreen(table) }
                context.waitTicks(10)
                shot(context, "mp-3-second-result")
                context.setScreen { null }
                connection.close()
                TuKuai.LOGGER.info("Two players finished two rounds")
            } finally {
                peer.stop()
            }
        }
    }

    /** Both clients saw the round end with the same score, and it went into both records. */
    private fun checkEnd(context: ClientGameTestContext, server: TestServerContext, table: BlockPos, peer: Peer, me: UUID, games: Int): Properties {
        val mine = ClientTables[table]!!.view as DdzView
        val theirs = waitPeer(context, peer, 200, "The other client did not see the end") { it["status"] == "FINISHED" && it["scores"] != "" }
        check(mine.settlement!!.scores.joinToString(";") == theirs["scores"]) { "The two clients disagree on the score: ${mine.settlement} and ${theirs["scores"]}" }
        check(mine.revealedHands.size == 3 && theirs["revealed"] == "3") { "The hands were not shown at the end" }
        check(theirs["sent"].toString().toInt() > 0) { "The other client never played a turn" }
        server.runOnServer<RuntimeException> { s ->
            check(StatsStore.of(s, me)?.total?.games == games && StatsStore.of(s, PEER_ID)?.total?.games == games) { "Round $games was not added to both records" }
            check(ReplayStore.list(s).size == games) { "Round $games was not saved" }
        }
        return theirs
    }

    /**
     * Plays our turns until [until] holds or the round is over, checking at every step
     * that each client has only its own hand and that the two hands never share a card.
     */
    private fun play(context: ClientGameTestContext, player: SeatPlayer, table: BlockPos, peer: Peer, until: (ClientTable) -> Boolean) {
        var waited = 0
        while (true) {
            context.runOnClient<RuntimeException> { player.tick() }
            val t = ClientTables[table] ?: error("We lost the table")
            val view = t.view as? DdzView ?: error("No game at the table")
            if (t.snapshot.status == TableStatus.FINISHED || until(t)) return
            check(t.snapshot.you == MY_SEAT && view.seat == MY_SEAT) { "We are not in our seat: ${t.snapshot.you}" }
            check(view.revealedHands.isEmpty()) { "We saw other hands during the game" }
            check(view.hand.size == view.handCounts[MY_SEAT]) { "Our hand has ${view.hand.size} cards but the table says ${view.handCounts[MY_SEAT]}" }
            if (waited % 20 == 0) {
                val theirs = peer.status()
                if (theirs != null && theirs["step"] == t.snapshot.step.toString() && theirs["phase"] == view.phase.name) {
                    val hand = theirs["hand"].toString().split(';').filter { it.isNotEmpty() }
                    check(theirs["viewSeat"] == "$PEER_SEAT" && theirs["revealed"] == "0") { "The other client saw more than its own hand: $theirs" }
                    check(hand.size == view.handCounts[PEER_SEAT]) { "The other hand has ${hand.size} cards but the table says ${view.handCounts[PEER_SEAT]}" }
                    check(hand.none { it in view.hand.map(Any::toString) }) { "Both clients have the same card" }
                }
            }
            check(waited < 12000) { "The round did not finish" }
            context.waitTicks(1)
            waited++
        }
    }

    private fun waitOnServer(context: ClientGameTestContext, server: TestServerContext, peer: Peer, ticks: Int, failure: String, test: (MinecraftServer) -> Boolean) {
        var waited = 0
        while (!server.computeOnServer<Boolean, RuntimeException> { test(it) }) {
            check(peer.alive) { "The second client quit; see ${peer.dir.resolve("output.log")}" }
            check(waited < ticks) { failure }
            context.waitTicks(20)
            waited += 20
        }
    }

    private fun waitPeer(context: ClientGameTestContext, peer: Peer, ticks: Int, failure: String, test: (Properties) -> Boolean): Properties {
        var waited = 0
        while (true) {
            val status = peer.status()
            if (status != null && test(status)) return status
            check(peer.alive) { "The second client quit; see ${peer.dir.resolve("output.log")}" }
            check(waited < ticks) { "$failure: $status" }
            context.waitTicks(10)
            waited += 10
        }
    }

    private fun tableAt(s: MinecraftServer, pos: BlockPos) = s.overworld().getBlockEntity(pos) as CardTableBlockEntity

    /** A floor around the first player with a table just north of them. */
    private fun buildScene(server: MinecraftServer): BlockPos {
        val player = server.playerList.players.first()
        val level = server.overworld()
        val base = player.blockPosition()
        for (x in -6..6) for (z in -6..6) {
            for (y in 0..4) level.setBlockAndUpdate(base.offset(x, y, z), Blocks.AIR.defaultBlockState())
            level.setBlockAndUpdate(base.offset(x, -1, z), Blocks.OAK_PLANKS.defaultBlockState())
        }
        val table = base.north(3)
        for (part in 0 until CardTableBlock.PARTS) {
            level.setBlockAndUpdate(
                CardTableBlock.posOf(table, part),
                TuKuaiContent.CARD_TABLE.defaultBlockState().setValue(CardTableBlock.FACING, Direction.SOUTH).setValue(CardTableBlock.PART, part),
            )
        }
        player.teleportTo(base.x + 0.5, base.y.toDouble(), base.z + 0.5)
        return table
    }

    private fun shot(context: ClientGameTestContext, name: String) {
        val path = context.takeScreenshot("tukuai-$name")
        TuKuai.LOGGER.info("SCREENSHOT {}", path.toAbsolutePath())
    }

    /** The second client: this same game, started again as its own process with [PeerClient] switched on. */
    private class Peer(val dir: Path, private val process: Process) {
        val alive: Boolean get() = process.isAlive

        /** What the second client last wrote down, or null before it first did. */
        fun status(): Properties? {
            val file = dir.resolve("peer-status.properties")
            if (!Files.exists(file)) return null
            return Properties().also { p -> Files.newBufferedReader(file).use(p::load) }
        }

        /** Leaves [text] in the `command` file and waits for the client to take it. */
        fun command(context: ClientGameTestContext, text: String) {
            val file = dir.resolve("command")
            Files.writeString(file, text)
            var waited = 0
            while (Files.exists(file)) {
                check(alive && waited < 200) { "The second client did not take the command $text" }
                context.waitTicks(5)
                waited += 5
            }
        }

        /** Ends the process at once, as if the game crashed or the network went. */
        fun kill() {
            process.destroyForcibly()
            process.waitFor(30, TimeUnit.SECONDS)
        }

        fun stop() {
            runCatching { Files.createFile(dir.resolve("stop")) }
            if (!process.waitFor(60, TimeUnit.SECONDS)) process.destroyForcibly()
        }

        companion object {
            fun start(name: String, port: Int, table: BlockPos, seat: Int): Peer {
                val dir = Path.of(name).toAbsolutePath()
                if (Files.exists(dir)) dir.toFile().deleteRecursively()
                Files.createDirectories(dir)
                // Quiet and small, and straight into the game without first-launch screens.
                Files.writeString(
                    dir.resolve("options.txt"),
                    listOf(
                        "onboardAccessibility:false", "skipMultiplayerWarning:true", "joinedFirstServer:true",
                        "pauseOnLostFocus:false", "soundCategory_master:0.0", "renderDistance:4", "simulationDistance:5",
                        "maxFps:30", "tutorialStep:none", "narrator:0",
                    ).joinToString("\n"),
                )
                val runtime = ManagementFactory.getRuntimeMXBean()
                val command = ArrayList<String>()
                command += Path.of(System.getProperty("java.home"), "bin", "java").toString()
                command += runtime.inputArguments.filterNot { it.startsWith("-Dfabric.client.gametest") || it.startsWith("-agentlib:jdwp") }
                // The window has to be made on the first thread on macOS; the game test runs it elsewhere.
                if (System.getProperty("os.name").startsWith("Mac") && "-XstartOnFirstThread" !in command) command += "-XstartOnFirstThread"
                command += "-Dtukuai.peer.server=localhost:$port"
                command += "-Dtukuai.peer.table=${table.x},${table.y},${table.z}"
                command += "-Dtukuai.peer.seat=$seat"
                command += listOf("-cp", runtime.classPath)
                command += System.getProperty("sun.java.command").split(' ').filter { it.isNotEmpty() }
                command += listOf("--username", PEER_NAME)
                TuKuai.LOGGER.info("Starting the second client in {}", dir)
                val process = ProcessBuilder(command)
                    .directory(dir.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(dir.resolve("output.log").toFile())
                    .start()
                return Peer(dir, process)
            }
        }
    }
}
