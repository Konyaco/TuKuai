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
 * An invisible seat next to a card table. It exists only while someone sits on it
 * and is never saved; the table remembers who sits where.
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

    override fun readAdditionalSaveData(input: ValueInput) {}

    override fun addAdditionalSaveData(output: ValueOutput) {}

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
