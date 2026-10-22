package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.table.Occupant
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.ConnectScreen
import net.minecraft.client.gui.screens.TitleScreen
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.multiplayer.resolver.ServerAddress
import net.minecraft.core.BlockPos

/**
 * The second player in [MultiplayerGameTest]: an ordinary client, started by the test as
 * a process of its own, that joins the test's server, sits in its seat and plays its own
 * turns. It does nothing unless the test started it, which sets `tukuai.peer.server`.
 *
 * It writes what it sees to `peer-status.properties` in its game folder every half
 * second, and quits when the test leaves a file named `stop` there. A file named
 * `command` holds one thing for it to do: a [TableOp] to send, or CLOSE to close the screen.
 */
class PeerClient : ClientModInitializer {
    override fun onInitializeClient() {
        val server = System.getProperty("tukuai.peer.server") ?: return
        val (x, y, z) = System.getProperty("tukuai.peer.table").split(',').map { it.toInt() }
        val table = BlockPos(x, y, z)
        val seat = System.getProperty("tukuai.peer.seat").toInt()
        val dir = Path.of("").toAbsolutePath()
        val player = SeatPlayer(table)
        var ticks = 0
        var connectedAt = -1
        TuKuai.LOGGER.info("Peer client for {} at {}, seat {}", server, table, seat)
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            ticks++
            if (Files.exists(dir.resolve("stop"))) {
                mc.stop()
                return@register
            }
            val command = dir.resolve("command")
            if (mc.level != null && Files.exists(command)) {
                when (val text = Files.readString(command).trim()) {
                    "CLOSE" -> mc.setScreen(null)
                    else -> SeatPlayer.send(table, TableOp.valueOf(text))
                }
                Files.delete(command)
            }
            if (mc.level == null) {
                // Join once the title screen is up, and again only if the first try failed.
                if (mc.screen is TitleScreen && (connectedAt < 0 || ticks - connectedAt > 200)) {
                    connectedAt = ticks
                    ConnectScreen.startConnecting(mc.screen!!, mc, ServerAddress.parseString(server), ServerData("TuKuai test", server, ServerData.Type.OTHER), false, null)
                }
            } else {
                val t = ClientTables[table]
                if (t != null && t.snapshot.status == TableStatus.LOBBY && t.snapshot.you != seat && ticks % 20 == 0) {
                    SeatPlayer.send(table, TableOp.SIT, seat)
                }
                player.tick()
            }
            if (ticks % 10 == 0) writeStatus(mc, dir, table, player)
        }
    }

    private fun writeStatus(mc: Minecraft, dir: Path, table: BlockPos, player: SeatPlayer) {
        val p = Properties()
        p["connected"] = (mc.level != null).toString()
        p["name"] = mc.user.name
        p["sent"] = player.sent.toString()
        p["screen"] = mc.screen?.javaClass?.simpleName ?: ""
        val t = ClientTables[table]
        if (t != null) {
            val s = t.snapshot
            p["status"] = s.status.name
            p["paused"] = s.paused.toString()
            p["step"] = s.step.toString()
            p["rounds"] = s.rounds.toString()
            p["tally"] = s.tally.joinToString(";")
            p["you"] = (s.you ?: -1).toString()
            p["seats"] = s.seats.joinToString(";") { seat ->
                when (val o = seat.occupant) {
                    is Occupant.Human -> "${o.name}/${if (seat.present) "here" else "away"}/${if (seat.auto) "auto" else "self"}"
                    is Occupant.Computer -> "computer"
                    null -> "empty"
                }
            }
            (t.view as? DdzView)?.let { v ->
                p["phase"] = v.phase.name
                p["viewSeat"] = (v.seat ?: -1).toString()
                p["hand"] = v.hand.joinToString(";")
                p["handCounts"] = v.handCounts.joinToString(";")
                p["revealed"] = v.revealedHands.size.toString()
                p["scores"] = v.settlement?.scores?.joinToString(";") ?: ""
            }
        }
        val tmp = dir.resolve("peer-status.tmp")
        Files.newBufferedWriter(tmp).use { p.store(it, null) }
        Files.move(tmp, dir.resolve("peer-status.properties"), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
