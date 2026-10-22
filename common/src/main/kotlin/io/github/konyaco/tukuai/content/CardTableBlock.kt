package io.github.konyaco.tukuai.content

import com.mojang.serialization.MapCodec
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
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
 * A 3x3 table for card games, made of nine blocks, or with [BigCardTableBlock] a 5x5 one
 * for up to eight players. [partProperty] says which of the blocks a block is; the middle
 * one holds the [CardTableBlockEntity] and every other part passes clicks on to it.
 * Breaking any part breaks the whole table.
 *
 * Right-click to sit down and open the table; right-click with a dye to recolour the
 * cloth. [FACING] points at seat 0, the side the placer stood on. [WOOD] is the wood of
 * the frame and legs; the item keeps it, and placing the item gives it to all the parts.
 */
open class CardTableBlock(properties: BlockBehaviour.Properties) : CompatBaseEntityBlock(properties) {
    /**
     * Blocks from the middle to the edge: 1 for 3x3. These are getters with no field behind
     * them, since the block's states are made in the superclass constructor, which reads them.
     */
    open val radius: Int get() = 1

    open val partProperty: IntegerProperty get() = PART

    /** Blocks along a side. */
    val width: Int get() = 2 * radius + 1

    val parts: Int get() = width * width

    val centerPart: Int get() = radius * width + radius

    /** Half the table's width in blocks, measured from the middle block's centre. */
    val halfWidth: Double get() = radius + 0.5

    /** The most seats around this table, see [TableSeats]. */
    val maxSeats: Int get() = TableSeats.maxSeats(radius)

    fun dxOf(part: Int): Int = part % width - radius
    fun dzOf(part: Int): Int = part / width - radius
    fun partAt(dx: Int, dz: Int): Int = (dz + radius) * width + (dx + radius)
    fun posOf(center: BlockPos, part: Int): BlockPos = center.offset(dxOf(part), 0, dzOf(part))

    /** Whether [other] is part [part] of the same table as [state]. */
    fun sameTable(other: BlockState, state: BlockState, part: Int): Boolean =
        other.block === this && other.getValue(partProperty) == part && other.getValue(FACING) == state.getValue(FACING)

    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH).setValue(partProperty, centerPart).setValue(WOOD, Wood.OAK))
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING, partProperty, WOOD)
    }

    /**
     * The clicked block becomes the middle of the table's near edge, so the table
     * reaches away from the player. Fails if any of its blocks is taken.
     */
    override fun getStateForPlacement(context: BlockPlaceContext): BlockState? {
        val facing = context.horizontalDirection.opposite
        val center = context.clickedPos.relative(facing.opposite, radius)
        val level = context.level
        for (part in 0 until parts) {
            val pos = posOf(center, part)
            if (pos == context.clickedPos) continue
            if (level.isOutsideBuildHeight(pos) || !level.worldBorder.isWithinBounds(pos)) return null
            if (!level.getBlockState(pos).canBeReplaced(context)) return null
        }
        return defaultBlockState().setValue(FACING, facing).setValue(partProperty, partAt(facing.stepX * radius, facing.stepZ * radius))
    }

    override fun setPlacedBy(level: Level, pos: BlockPos, state: BlockState, placer: LivingEntity?, stack: ItemStack) {
        super.setPlacedBy(level, pos, state, placer, stack)
        if (level.isClientSide()) return
        val center = centerOf(pos, state)
        for (part in 0 until parts) {
            val partPos = posOf(center, part)
            if (partPos != pos) level.setBlock(partPos, state.setValue(partProperty, part), UPDATE_ALL)
        }
    }

    /** A part whose neighbour inside the table is gone goes too, so the whole table comes down together. */
    override fun shapeAfterNeighbor(
        state: BlockState,
        level: LevelReader,
        pos: BlockPos,
        direction: Direction,
        neighborPos: BlockPos,
        neighborState: BlockState,
    ): BlockState? {
        if (direction.axis.isHorizontal) {
            val part = state.getValue(partProperty)
            val dx = dxOf(part) + direction.stepX
            val dz = dzOf(part) + direction.stepZ
            if (dx in -radius..radius && dz in -radius..radius && !sameTable(neighborState, state, partAt(dx, dz))) {
                return Blocks.AIR.defaultBlockState()
            }
        }
        return null
    }

    /** Only the middle part drops the table, so in creative mode take it away first without a drop. */
    override fun playerWillDestroy(level: Level, pos: BlockPos, state: BlockState, player: Player): BlockState {
        if (!level.isClientSide() && player.abilities.instabuild && state.getValue(partProperty) != centerPart) {
            val center = centerOf(pos, state)
            val centerState = level.getBlockState(center)
            if (sameTable(centerState, state, centerPart)) {
                level.setBlock(center, Blocks.AIR.defaultBlockState(), UPDATE_ALL or UPDATE_SUPPRESS_DROPS)
                level.levelEvent(player, LevelEvent.PARTICLES_DESTROY_BLOCK, center, getId(centerState))
            }
        }
        return super.playerWillDestroy(level, pos, state, player)
    }

    /** The top everywhere, with a leg under each outer corner. */
    private val shapes: List<VoxelShape> by lazy {
        List(parts) { part ->
            val dx = dxOf(part)
            val dz = dzOf(part)
            if (dx * dx != radius * radius || dz * dz != radius * radius) {
                TOP
            } else {
                val x = if (dx < 0) 1.0 else 11.0
                val z = if (dz < 0) 1.0 else 11.0
                Shapes.or(TOP, Block.box(x, 0.0, z, x + 4.0, 12.0, z + 4.0))
            }
        }
    }

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape =
        shapes[state.getValue(partProperty)]

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity? =
        if (state.getValue(partProperty) == centerPart) CardTableBlockEntity(pos, state) else null

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide() || state.getValue(partProperty) != centerPart) {
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

    override fun useItem(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): Boolean {
        val color: DyeColor = Dyes.colorOf(stack) ?: return false
        val table = level.getBlockEntity(centerOf(pos, state)) as? CardTableBlockEntity ?: return false
        if (table.felt == color) return false
        if (!level.isClientSide()) {
            table.dye(color)
            if (!player.abilities.instabuild) stack.shrink(1)
        }
        return true
    }

    companion object {
        val FACING = HorizontalDirectionalBlock.FACING

        /** Which of the nine blocks of a 3x3 table this is: `(dz + 1) * 3 + (dx + 1)` for its offset from the middle. */
        val PART: IntegerProperty = IntegerProperty.create("part", 0, 8)

        /** The parts of a 3x3 table. */
        const val PARTS = 9

        val WOOD = Wood.PROPERTY

        /**
         * The middle part. It is also the default, so a table saved as one block, before
         * tables were 3x3, loads as a middle part and grows its other parts on its first tick.
         */
        const val CENTER = 4

        /** Half the 3x3 table's width in blocks, measured from the middle block's centre. */
        const val HALF_WIDTH = 1.5

        /**
         * Height of the table top, where the cloth and the cards lie, in blocks. A little
         * under a full block, so someone on a stool looks down onto it; see tools/gen_models.py.
         */
        const val TOP_HEIGHT = 14f / 16f

        val CODEC: MapCodec<CardTableBlock> = Block.simpleCodec(::CardTableBlock)

        /** Parts of a 3x3 table; a [BigCardTableBlock] has its own, see [CardTableBlock.partAt]. */
        fun partAt(dx: Int, dz: Int): Int = (dz + 1) * 3 + (dx + 1)

        fun posOf(center: BlockPos, part: Int): BlockPos = center.offset(part % 3 - 1, 0, part / 3 - 1)

        /** Where the middle block of the table that [state] at [pos] belongs to is. */
        fun centerOf(pos: BlockPos, state: BlockState): BlockPos {
            val block = state.block as? CardTableBlock ?: return pos
            val part = state.getValue(block.partProperty)
            return pos.offset(-block.dxOf(part), 0, -block.dzOf(part))
        }

        /** The table whose middle block is at [pos], if there is one. */
        fun at(level: BlockGetter, pos: BlockPos): CardTableBlock? = level.getBlockState(pos).block as? CardTableBlock

        private val TOP: VoxelShape = Block.box(0.0, 12.0, 0.0, 16.0, 14.0, 16.0)
    }
}

/** A 5x5 card table with room for eight. Everything else works as at a [CardTableBlock]. */
class BigCardTableBlock(properties: BlockBehaviour.Properties) : CardTableBlock(properties) {
    override val radius: Int get() = 2

    override val partProperty: IntegerProperty get() = BIG_PART

    override fun codec(): MapCodec<out BaseEntityBlock> = BIG_CODEC

    companion object {
        /** Which of the 25 blocks this is: `(dz + 2) * 5 + (dx + 2)` for its offset from the middle. */
        val BIG_PART: IntegerProperty = IntegerProperty.create("part", 0, 24)

        val BIG_CODEC: MapCodec<BigCardTableBlock> = Block.simpleCodec(::BigCardTableBlock)
    }
}
