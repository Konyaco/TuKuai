package io.github.konyaco.tukuai.content

import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.level.Level
import net.minecraft.world.level.storage.ValueInput
import net.minecraft.world.level.storage.ValueOutput

/**
 * An invisible seat next to a card table. It exists only while someone sits on it.
 * It is saved with the world (the game refuses to let players ride entities that
 * cannot be saved), so a seated player is still seated after a restart.
 */
class SeatEntity(type: EntityType<*>, level: Level) : Entity(type, level) {
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
        if (passengers.isEmpty() || pos == null || level().getBlockEntity(pos) !is CardTableBlockEntity) {
            ejectPassengers()
            discard()
        }
    }
}
