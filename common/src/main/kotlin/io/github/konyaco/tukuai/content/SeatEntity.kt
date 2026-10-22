package io.github.konyaco.tukuai.content

import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level

/**
 * An invisible seat at a card table, or on a stool that is no table's seat ([table] is
 * null then). It exists only while someone sits on it.
 * It is saved with the world (the game refuses to let players ride entities that
 * cannot be saved), so a seated player is still seated after a restart.
 */
class SeatEntity(type: EntityType<*>, level: Level) : CompatEntity(type, level) {
    var table: BlockPos? = null

    init {
        noPhysics = true
        setNoGravity(true)
        setInvisible(true)
    }

    override fun defineSynchedData(builder: SynchedEntityData.Builder) {}

    override fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean = false

    override fun readAdditionalSaveData(input: ValueInput) {
        val y = input.getIntOr("table_y", Int.MIN_VALUE)
        table = if (y == Int.MIN_VALUE) null else BlockPos(input.getIntOr("table_x", 0), y, input.getIntOr("table_z", 0))
    }

    override fun addAdditionalSaveData(output: ValueOutput) {
        val pos = table ?: return
        output.putInt("table_x", pos.x)
        output.putInt("table_y", pos.y)
        output.putInt("table_z", pos.z)
    }

    override fun tick() {
        super.tick()
        if (level().isClientSide()) return
        val pos = table
        val holds = if (pos == null) {
            level().getBlockState(blockPosition()).block is StoolBlock
        } else {
            level().getBlockEntity(pos) is CardTableBlockEntity
        }
        if (passengers.isEmpty() || !holds) {
            ejectPassengers()
            discard()
        }
    }

    companion object {
        /** The seat's height; whoever sits on it rides this far above its position. */
        const val HEIGHT = 0.25
    }
}
