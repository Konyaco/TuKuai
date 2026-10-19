package io.github.konyaco.tukuai.content

import com.mojang.serialization.MapCodec
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A wooden stool with a wool cushion. Right-click to sit on it. A stool just outside the
 * middle of a card table's edge is that side's seat: sitting on it takes the seat and
 * opens the table. Right-click with a dye to change the cushion.
 */
class StoolBlock(properties: BlockBehaviour.Properties) : Block(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(COLOR, DyeColor.RED))
    }

    override fun codec(): MapCodec<out Block> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(COLOR)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        val table = tableNextTo(level, pos)
        if (level.isClientSide()) {
            if (table != null) Services.openTable(table.first.blockPos)
            return InteractionResult.SUCCESS
        }
        val serverPlayer = player as ServerPlayer
        if (table != null && table.first.sitAt(serverPlayer, table.second)) return InteractionResult.SUCCESS
        sit(level as ServerLevel, pos, serverPlayer)
        return InteractionResult.SUCCESS
    }

    override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult {
        val color = Dyes.colorOf(stack) ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        if (state.getValue(COLOR) == color) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (!level.isClientSide()) {
            level.setBlock(pos, state.setValue(COLOR, color), UPDATE_ALL)
            level.playSound(null, pos, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1f, 1f)
            if (!player.abilities.instabuild) stack.shrink(1)
        }
        return InteractionResult.SUCCESS
    }

    /** Sits on a stool that is not a table's seat, unless someone is already on it. */
    private fun sit(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        val vehicle = player.vehicle
        if (vehicle is SeatEntity && vehicle.blockPosition() == pos) return
        val taken = level.getEntitiesOfClass(SeatEntity::class.java, AABB(pos)) { it.passengers.isNotEmpty() }
        if (taken.isNotEmpty()) return
        if (player.isPassenger) player.stopRiding()
        val seat = SeatEntity(TuKuaiContent.SEAT, level)
        seat.setPos(pos.x + 0.5, pos.y + SEAT_HEIGHT - SeatEntity.HEIGHT, pos.z + 0.5)
        seat.setYRot(player.yRot)
        if (level.addFreshEntity(seat) && !player.startRiding(seat)) seat.discard()
    }

    companion object {
        val COLOR: EnumProperty<DyeColor> = EnumProperty.create("color", DyeColor::class.java)

        val CODEC: MapCodec<StoolBlock> = Block.simpleCodec(::StoolBlock)

        /** Height of the top of the cushion, in blocks. */
        const val SEAT_HEIGHT = 10.0 / 16.0

        /** How far a stool that serves as a table's seat is from the table's middle block. */
        const val TABLE_DISTANCE = 2

        private val SHAPE: VoxelShape = Shapes.or(
            Block.box(1.0, 6.0, 1.0, 15.0, 10.0, 15.0),
            Block.box(2.0, 0.0, 2.0, 4.0, 6.0, 4.0),
            Block.box(12.0, 0.0, 2.0, 14.0, 6.0, 4.0),
            Block.box(2.0, 0.0, 12.0, 4.0, 6.0, 14.0),
            Block.box(12.0, 0.0, 12.0, 14.0, 6.0, 14.0),
        )

        /** The card table this stool is a seat of, and the side of the table it is on. */
        fun tableNextTo(level: Level, pos: BlockPos): Pair<CardTableBlockEntity, Direction>? {
            for (side in Direction.Plane.HORIZONTAL) {
                val table = level.getBlockEntity(pos.relative(side.opposite, TABLE_DISTANCE)) as? CardTableBlockEntity ?: continue
                return table to side
            }
            return null
        }
    }
}
