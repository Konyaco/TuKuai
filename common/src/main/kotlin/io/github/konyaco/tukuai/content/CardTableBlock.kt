package io.github.konyaco.tukuai.content

import com.mojang.serialization.MapCodec
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.LevelEvent
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.level.block.state.properties.IntegerProperty
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A 3x3 table for card games, made of nine blocks. [PART] says which of the nine a
 * block is; the middle one holds the [CardTableBlockEntity] and every other part
 * passes clicks on to it. Breaking any part breaks the whole table.
 *
 * Right-click to sit down and open the table; right-click with a dye to recolour the
 * cloth. [FACING] points at seat 0, the side the placer stood on. [WOOD] is the wood of
 * the frame and legs; the item keeps it, and placing the item gives it to all nine parts.
 */
class CardTableBlock(properties: BlockBehaviour.Properties) : BaseEntityBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(PART, CENTER).setValue(WOOD, Wood.OAK))
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, PART, WOOD)
    }

    /**
     * The clicked block becomes the middle of the table's near edge, so the table
     * reaches away from the player. Fails if any of the nine blocks is taken.
     */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = context.horizontalDirection.opposite
        val center = context.clickedPos.relative(facing.opposite)
        val level = context.level
        for (part in 0 until PARTS) {
            val pos = posOf(center, part)
            if (pos == context.clickedPos) continue
            if (level.isOutsideBuildHeight(pos) || !level.worldBorder.isWithinBounds(pos)) return null
            if (!level.getBlockState(pos).canBeReplaced(context)) return null
        }
        return defaultBlockState().setValue(FACING, facing).setValue(PART, partAt(facing.stepX, facing.stepZ))
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide()) return
        val center = centerOf(pos, state)
        for (part in 0 until PARTS) {
            val partPos = posOf(center, part)
            if (partPos != pos) level.setBlock(partPos, state.setValue(PART, part), UPDATE_ALL)
        }
    }

    /** A part whose neighbour inside the table is gone goes too, so the whole table comes down together. */
    override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        direction: Direction,
        neighborPos: BlockPos,
        neighborState: BlockState,
        random: RandomSource,
    ): BlockState {
        if (direction.axis.isHorizontal) {
            val part = state.getValue(PART)
            val dx = dxOf(part) + direction.stepX
            val dz = dzOf(part) + direction.stepZ
            if (dx in -1..1 && dz in -1..1 && !sameTable(neighborState, state, partAt(dx, dz))) {
                return Blocks.AIR.defaultBlockState()
            }
        }
        return super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random)
    }

    /** Only the middle part drops the table, so in creative mode take it away first without a drop. */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (!level.isClientSide() && player.abilities.instabuild && state.getValue(PART) != CENTER) {
            val center = centerOf(pos, state)
            val centerState = level.getBlockState(center)
            if (sameTable(centerState, state, CENTER)) {
                level.setBlock(center, Blocks.AIR.defaultBlockState(), UPDATE_ALL or UPDATE_SUPPRESS_DROPS)
                level.levelEvent(player, LevelEvent.PARTICLES_DESTROY_BLOCK, center, getId(centerState))
            }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        SHAPES[state.getValue(PART)]

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state.getValue(PART) == CENTER) CardTableBlockEntity(pos, state) else null

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide() || state.getValue(PART) != CENTER) {
            null
        } else {
            createTickerHelper(type, TuKuaiContent.CARD_TABLE_ENTITY, BlockEntityTicker { tickLevel, _, _, table ->
                table.serverTick(tickLevel as ServerLevel)
            })
        }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        val center = centerOf(pos, state)
        if (level.isClientSide()) {
            Services.openTable(center)
        } else {
            (level.getBlockEntity(center) as? CardTableBlockEntity)?.onUse(player as ServerPlayer)
        }
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
        val color: DyeColor = Dyes.colorOf(stack) ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val table = level.getBlockEntity(centerOf(pos, state)) as? CardTableBlockEntity ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        if (table.felt == color) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (!level.isClientSide()) {
            table.dye(color)
            if (!player.abilities.instabuild) stack.shrink(1)
        }
        return InteractionResult.SUCCESS
    }

    companion object {
        val FACING = HorizontalDirectionalBlock.FACING

        /** Which of the nine blocks this is: `(dz + 1) * 3 + (dx + 1)` for its offset from the middle. */
        val PART: IntegerProperty = IntegerProperty.create("part", 0, 8)

        const val PARTS = 9

        val WOOD = Wood.PROPERTY

        /**
         * The middle part. It is also the default, so a table saved as one block, before
         * tables were 3x3, loads as a middle part and grows its other parts on its first tick.
         */
        const val CENTER = 4

        /** Half the table's width in blocks, measured from the middle block's centre. */
        const val HALF_WIDTH = 1.5

        val CODEC: MapCodec<CardTableBlock> = Block.simpleCodec(::CardTableBlock)

        fun dxOf(part: Int): Int = part % 3 - 1
        fun dzOf(part: Int): Int = part / 3 - 1
        fun partAt(dx: Int, dz: Int): Int = (dz + 1) * 3 + (dx + 1)

        fun posOf(center: BlockPos, part: Int): BlockPos = center.offset(dxOf(part), 0, dzOf(part))

        /** Where the middle block of the table that [state] at [pos] belongs to is. */
        fun centerOf(pos: BlockPos, state: BlockState): BlockPos {
            if (state.block !is CardTableBlock) return pos
            val part = state.getValue(PART)
            return pos.offset(-dxOf(part), 0, -dzOf(part))
        }

        /** Whether [other] is part [part] of the same table as [state]. */
        fun sameTable(other: BlockState, state: BlockState, part: Int): Boolean =
            other.block is CardTableBlock && other.getValue(PART) == part && other.getValue(FACING) == state.getValue(FACING)

        private val TOP: VoxelShape = Block.box(0.0, 12.0, 0.0, 16.0, 16.0, 16.0)

        /** The top everywhere, with a leg under each outer corner. */
        private val SHAPES: List<VoxelShape> = List(PARTS) { part ->
            val dx = dxOf(part)
            val dz = dzOf(part)
            if (dx == 0 || dz == 0) {
                TOP
            } else {
                val x = if (dx < 0) 1.0 else 12.0
                val z = if (dz < 0) 1.0 else 12.0
                Shapes.or(TOP, Block.box(x, 0.0, z, x + 3.0, 12.0, z + 3.0))
            }
        }
    }
}
