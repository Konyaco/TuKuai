package io.github.konyaco.tukuai.content

import com.mojang.serialization.MapCodec
import io.github.konyaco.tukuai.compat.*
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
 * A wooden stool with a wool cushion, in the wood of the slab it was made with. Right-click
 * to sit on it. A stool just outside the middle of a card table's edge is that side's seat,
 * and at a big table also one a block either side of the middle: sitting on it takes the
 * seat and opens the table. Right-click with a dye to change the cushion.
 */
class StoolBlock(properties: BlockBehaviour.Properties) : CompatBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(COLOR, DyeColor.RED).setValue(WOOD, Wood.OAK))
    }

    override fun codec(): MapCodec<out Block> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(COLOR, WOOD)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        val table = tableNextTo(level, pos)
        if (level.isClientSide()) {
            if (table != null) Services.openTable(table.first.blockPos)
            return InteractionResult.SUCCESS
        }
        val serverPlayer = player as ServerPlayer
        if (table != null && table.first.sitAt(serverPlayer, table.second.side, table.second.along)) return InteractionResult.SUCCESS
        sit(level as ServerLevel, pos, serverPlayer)
        return InteractionResult.SUCCESS
    }

    override fun useItem(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): Boolean {
        val color = Dyes.colorOf(stack) ?: return false
        if (state.getValue(COLOR) == color) return false
        if (!level.isClientSide()) {
            level.setBlock(pos, state.setValue(COLOR, color), UPDATE_ALL)
            level.playSound(null, pos, SoundEvents.WOOL_PLACE, SoundSource.BLOCKS, 1f, 1f)
            if (!player.abilities.instabuild) stack.shrink(1)
        }
        return true
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

        val WOOD = Wood.PROPERTY

        val CODEC: MapCodec<StoolBlock> = Block.simpleCodec(::StoolBlock)

        /** Height of the top of the cushion, in blocks; see tools/gen_models.py. */
        const val SEAT_HEIGHT = 12.0 / 16.0

        /** How far a stool that serves as a 3x3 table's seat is from the table's middle block. */
        const val TABLE_DISTANCE = 2

        private val SHAPE: VoxelShape = Shapes.or(
            Block.box(1.0, 8.0, 1.0, 15.0, 12.0, 15.0),
            Block.box(2.0, 0.0, 2.0, 4.0, 8.0, 4.0),
            Block.box(12.0, 0.0, 2.0, 14.0, 8.0, 4.0),
            Block.box(2.0, 0.0, 12.0, 4.0, 8.0, 14.0),
            Block.box(12.0, 0.0, 12.0, 14.0, 8.0, 14.0),
        )

        /**
         * The card table this stool is a seat of, and where along which side of it the stool
         * stands: just outside the edge, facing the middle block or, at a big table, one
         * either side of it.
         */
        fun tableNextTo(level: Level, pos: BlockPos): Pair<CardTableBlockEntity, SeatSpot>? {
            for (radius in 1..2) {
                for (side in Direction.Plane.HORIZONTAL) {
                    for (along in -(radius - 1)..(radius - 1)) {
                        val spot = SeatSpot(side, along)
                        val center = pos.relative(side.opposite, radius + 1).relative(spot.right, -along)
                        val table = level.getBlockEntity(center) as? CardTableBlockEntity ?: continue
                        if (table.table.radius == radius) return table to spot
                    }
                }
            }
            return null
        }
    }
}
