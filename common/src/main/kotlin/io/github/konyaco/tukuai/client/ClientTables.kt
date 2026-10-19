package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.table.TableSnapshot
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.world.level.Level

/** A table as this client last heard about it. */
class ClientTable(val pos: BlockPos, val snapshot: TableSnapshot, private val receivedAt: Long) {
    val ui: GameUi? = GameUis[snapshot.gameId]

    /** The decoded game view, or null in the lobby or if it could not be read. */
    val view: Any? = snapshot.view?.let { data ->
        try {
            ui?.decodeView(data)
        } catch (e: Exception) {
            TuKuai.LOGGER.warn("Could not read the table view at {}", pos, e)
            null
        }
    }

    private var scene: List<TableCard>? = null
    private var sceneKey: Pair<List<Direction>, Int>? = null

    /** Cards on the table top, worked out once per update rather than every frame. */
    fun scene(sides: List<Direction>): List<TableCard> {
        val key = sides to ClientConfig.cardBack
        if (key != sceneKey) {
            sceneKey = key
            scene = if (view == null) emptyList() else ui?.scene(this, sides) ?: emptyList()
        }
        return scene ?: emptyList()
    }

    /** Ticks left on the turn timer, counted down locally between updates; -1 when there is none. */
    fun timerTicks(): Int =
        if (snapshot.timerTicks < 0) -1 else (snapshot.timerTicks - (ClientTables.ticks - receivedAt)).toInt().coerceAtLeast(0)
}

object ClientTables {
    private const val FORGET_RANGE = 64.0

    var ticks = 0L
        private set
    private val tables = HashMap<BlockPos, ClientTable>()
    private var level: Level? = null

    operator fun get(pos: BlockPos): ClientTable? = tables[pos]

    fun all(): Collection<ClientTable> = tables.values

    fun receive(pos: BlockPos, data: ByteArray) {
        val snapshot = try {
            TableSnapshot.decode(data)
        } catch (e: Exception) {
            TuKuai.LOGGER.warn("Could not read the table at {}", pos, e)
            return
        }
        val table = ClientTable(pos.immutable(), snapshot, ticks)
        tables[table.pos] = table
        val screen = Minecraft.getInstance().screen
        if (screen is TableScreen && screen.pos == table.pos) screen.onTableChanged(table)
    }

    fun tick() {
        ticks++
        val mc = Minecraft.getInstance()
        if (mc.level !== level) {
            level = mc.level
            tables.clear()
        }
        val player = mc.player ?: return
        if (ticks % 40 == 0L) {
            tables.keys.removeIf { net.minecraft.world.phys.Vec3.atCenterOf(it).distanceToSqr(player.position()) > FORGET_RANGE * FORGET_RANGE }
        }
    }
}
