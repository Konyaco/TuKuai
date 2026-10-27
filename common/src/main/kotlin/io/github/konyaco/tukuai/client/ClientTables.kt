package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.table.TableSnapshot
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.core.BlockPos
import net.minecraft.core.Holder
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
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
    private var sceneKey: Triple<List<SeatPlace>, Int, Int>? = null

    /** Cards on the table top, worked out once per update rather than every frame. */
    fun scene(seats: List<SeatPlace>): List<TableCard> {
        val key = Triple(seats, ClientConfig.cardBack, ClientConfig.cardFace)
        if (key != sceneKey) {
            sceneKey = key
            scene = if (view == null) emptyList() else ui?.scene(this, seats) ?: emptyList()
        }
        return scene ?: emptyList()
    }

    /** The most seats the table block in the world has room for. */
    val maxSeats: Int
        get() = (Minecraft.getInstance().level?.getBlockState(pos)?.block as? CardTableBlock)?.maxSeats ?: 4

    /** Where each seat is at this table, from the table block in the world; empty if it is not there. */
    fun seatPlaces(): List<SeatPlace> {
        val state = Minecraft.getInstance().level?.getBlockState(pos) ?: return emptyList()
        val block = state.block as? CardTableBlock ?: return emptyList()
        return SeatPlace.of(state.getValue(CardTableBlock.FACING), block.radius, snapshot.seats.size)
    }

    /** True for a player who is not in the game on this table, only looking on. */
    val watching: Boolean get() = snapshot.you == null && snapshot.status != TableStatus.LOBBY

    /** For a player [watching] a game: whether they can see every hand, or why not. */
    fun watchingLine(): Component? = when {
        !watching -> null
        snapshot.status == TableStatus.FINISHED -> Component.translatable("tukuai.table.watching")
        snapshot.spectators -> Component.translatable("tukuai.table.watching_open")
        else -> Component.translatable("tukuai.table.watching_closed")
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
        forgetOldLevel()
        val snapshot = try {
            TableSnapshot.decode(data)
        } catch (e: Exception) {
            TuKuai.LOGGER.warn("Could not read the table at {}", pos, e)
            return
        }
        val table = ClientTable(pos.immutable(), snapshot, ticks)
        val previous = tables.put(table.pos, table)
        table.ui?.onUpdate(previous?.takeIf { it.snapshot.gameId == snapshot.gameId }, table)
        val screen = Minecraft.getInstance().screen
        if (screen is TableScreen && screen.pos == table.pos) screen.onTableChanged(table) else remindTurn(previous, table)
    }

    /**
     * A player who closed the table screen mid-game would not notice their turn come round:
     * they get the chime the screen plays and a line over the hotbar saying how to get back.
     */
    private fun remindTurn(previous: ClientTable?, table: ClientTable) {
        val s = table.snapshot
        val me = s.you ?: return
        if (s.status != TableStatus.PLAYING || s.paused || s.seats.getOrNull(me)?.auto == true) return
        if (!table.isTurnOf(me) || previous?.isTurnOf(me) == true) return
        val mc = Minecraft.getInstance()
        mc.soundManager.play(SimpleSoundInstance.forUI(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.4f))
        mc.player?.sendOverlayMessage(Component.translatable("tukuai.table.your_turn"))
        reminded = true
    }

    /** Set while the line over the hotbar may still say it is the player's turn. */
    private var reminded = false

    /** Clears the reminder once the player is back at the table, so it does not linger over their cards. */
    fun forgetReminder() {
        if (!reminded) return
        reminded = false
        Minecraft.getInstance().player?.sendOverlayMessage(Component.empty())
    }

    private fun ClientTable.isTurnOf(seat: Int) = snapshot.status == TableStatus.PLAYING && ui?.turnSeat(this) == seat

    /**
     * Tables belong to the level they were sent in, so a new level (joining a server,
     * changing dimension) starts with none. Checked as tables arrive too: the server sends
     * them straight after a player joins, often before the client's next tick.
     */
    private fun forgetOldLevel() {
        val current = Minecraft.getInstance().level
        if (current !== level) {
            level = current
            tables.clear()
        }
    }

    /** Plays a sound at a table for this player only; everyone near it gets the same update and plays it too. */
    fun playAt(pos: BlockPos, sound: SoundEvent, volume: Float, pitch: Float) {
        Minecraft.getInstance().level?.playLocalSound(pos, sound, SoundSource.BLOCKS, volume, pitch, false)
    }

    fun playAt(pos: BlockPos, sound: Holder<SoundEvent>, volume: Float, pitch: Float) = playAt(pos, sound.value(), volume, pitch)

    fun tick() {
        ticks++
        forgetOldLevel()
        val player = Minecraft.getInstance().player ?: return
        for (table in tables.values) table.ui?.tick(table)
        if (ticks % 40 == 0L) {
            tables.keys.removeIf { net.minecraft.world.phys.Vec3.atCenterOf(it).distanceToSqr(player.position()) > FORGET_RANGE * FORGET_RANGE }
        }
    }
}
