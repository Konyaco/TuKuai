package io.github.konyaco.tukuai.fabric.test

import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.engine.core.BotLevel
import io.github.konyaco.tukuai.engine.doudizhu.DdzCodecs
import io.github.konyaco.tukuai.engine.doudizhu.DdzView
import io.github.konyaco.tukuai.engine.doudizhu.bot.DdzBot
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.TableOp
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.core.BlockPos

/**
 * Plays a Dou Dizhu seat from a client the way a person at the table would: it only
 * knows what this client was sent, and on its turn it sends a move over the network.
 * Call [tick] on the client thread.
 */
class SeatPlayer(private val table: BlockPos) {
    /** Moves sent, one per turn. */
    var sent = 0
        private set
    private var sentAt = -1

    fun tick() {
        val t = ClientTables[table] ?: return
        val view = t.view as? DdzView ?: return
        if (t.snapshot.status != TableStatus.PLAYING || !view.isMyTurn || t.snapshot.step == sentAt) return
        val action = DdzBot(BotLevel.EASY, t.snapshot.step.toLong()).act(view)
        send(table, TableOp.ACT, data = DdzCodecs.ACTION.encode(action))
        sentAt = t.snapshot.step
        sent++
    }

    companion object {
        /** Sends a request to the table the same way the table screen does. */
        fun send(table: BlockPos, op: TableOp, a: Int = 0, b: Int = 0, data: ByteArray = ByteArray(0)) {
            Services.client.sendToServer(TableActionPayload(table, op, a, b, "", data))
        }
    }
}
