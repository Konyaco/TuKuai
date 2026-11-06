package io.github.konyaco.tukuai.content

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
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.Mirror
import net.minecraft.world.level.block.Rotation
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
 * A wooden chair with a wool cushion, in the wood of the slab it was made with. Right-click
 * to sit on it. A chair just outside the middle of a card table's edge is that side's seat,
 * and at a big table also one a block either side of the middle: sitting on it takes the
 * seat and opens the table. Right-click with a dye to change the cushion.
 *
 * [FACING] is the way whoever sits on it looks. A chair at a table faces the table, and the
 * table turns any of its chairs that do not, which also turns the stools of older versions
 * (now chairs facing north) the right way round.
 */
class ChairBlock(properties: BlockBehaviour.Properties) : CompatBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(COLOR, DyeColor.RED).setValue(WOOD, Wood.OAK))
    }

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, COLOR, WOOD)
    }

    /** Facing the table it is a seat of, or else facing whoever placed it. */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = tableNextTo(context.level, context.clickedPos)?.second?.side?.opposite ?: context.horizontalDirection.opposite
        return defaultBlockState().setValue(FACING, facing)
    }

    override fun rotate(state: BlockState, rotation: Rotation): BlockState = state.setValue(FACING, rotation.rotate(state.getValue(FACING)))

    override fun mirror(state: BlockState, mirror: Mirror): BlockState = state.rotate(mirror.getRotation(state.getValue(FACING)))

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES.getValue(state.getValue(FACING))

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

    /** Sits on a chair that is not a table's seat, unless someone is already on it. */
    private fun sit(level: ServerLevel, pos: BlockPos, player: ServerPlayer) {
        val vehicle = player.vehicle
        if (vehicle is SeatEntity && vehicle.blockPosition() == pos) return
        val taken = level.getEntitiesOfClass(SeatEntity::class.java, AABB(pos)) { it.passengers.isNotEmpty() }
        if (taken.isNotEmpty()) return
        if (player.isPassenger) player.stopRiding()
        val seat = SeatEntity(TuKuaiContent.SEAT, level)
        seat.setPos(pos.x + 0.5, pos.y + SEAT_HEIGHT - SeatEntity.HEIGHT, pos.z + 0.5)
        seat.setYRot(level.getBlockState(pos).getValue(FACING).toYRot())
        if (level.addFreshEntity(seat) && !player.startRiding(seat)) seat.discard()
    }

    companion object {
        val COLOR: EnumProperty<DyeColor> = EnumProperty.create("color", DyeColor::class.java)

        val WOOD = Wood.PROPERTY

        val FACING = HorizontalDirectionalBlock.FACING

        /** Height of the top of the cushion, in blocks; see tools/gen_models.py. */
        const val SEAT_HEIGHT = 12.0 / 16.0

        /** How far a chair that serves as a 3x3 table's seat is from the table's middle block. */
        const val TABLE_DISTANCE = 2

        /** The seat, the legs and the back of a chair facing north, as in tools/gen_models.py. */
        private val NORTH_BOXES = listOf(
            doubleArrayOf(1.0, 8.0, 1.0, 15.0, 12.0, 15.0),
            doubleArrayOf(2.0, 0.0, 2.0, 4.0, 8.0, 4.0),
            doubleArrayOf(12.0, 0.0, 2.0, 14.0, 8.0, 4.0),
            doubleArrayOf(2.0, 0.0, 12.0, 4.0, 8.0, 14.0),
            doubleArrayOf(12.0, 0.0, 12.0, 14.0, 8.0, 14.0),
            doubleArrayOf(2.0, 12.0, 12.0, 14.0, 23.0, 14.0),
        )

        private val SHAPES: Map<Direction, VoxelShape> = Direction.Plane.HORIZONTAL.associateWith { facing ->
            Shapes.or(Shapes.empty(), *NORTH_BOXES.map { turned(it, facing) }.toTypedArray())
        }

        /** A box of a north-facing chair, turned to [facing] like the block state turns the model. */
        private fun turned(b: DoubleArray, facing: Direction): VoxelShape {
            val x0 = b[0]
            val y0 = b[1]
            val z0 = b[2]
            val x1 = b[3]
            val y1 = b[4]
            val z1 = b[5]
            return when (facing) {
                Direction.EAST -> Block.box(16 - z1, y0, x0, 16 - z0, y1, x1)
                Direction.SOUTH -> Block.box(16 - x1, y0, 16 - z1, 16 - x0, y1, 16 - z0)
                Direction.WEST -> Block.box(z0, y0, 16 - x1, z1, y1, 16 - x0)
                else -> Block.box(x0, y0, z0, x1, y1, z1)
            }
        }

        /**
         * The card table this chair is a seat of, and where along which side of it the chair
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
