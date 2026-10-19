package io.github.konyaco.tukuai.content

import com.mojang.serialization.MapCodec
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.context.BlockPlaceContext
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.HorizontalDirectionalBlock
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityTicker
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.StateDefinition
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.shapes.CollisionContext
import net.minecraft.world.phys.shapes.Shapes
import net.minecraft.world.phys.shapes.VoxelShape

/**
 * A table for card games. Right-click to sit down and open the table; right-click
 * with a dye to recolour the cloth. [FACING] points at seat 0, the side the placer stood on.
 */
class CardTableBlock(properties: BlockBehaviour.Properties) : BaseEntityBlock(properties) {
    init {
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH))
    }

    override fun codec(): MapCodec<out BaseEntityBlock> = CODEC

    override fun createBlockStateDefinition(builder: StateDefinition.Builder<Block, BlockState>) {
        builder.add(FACING)
    }

    override fun getStateForPlacement(context: BlockPlaceContext): BlockState =
        defaultBlockState().setValue(FACING, context.horizontalDirection.opposite)

    override fun getShape(state: BlockState, level: BlockGetter, pos: BlockPos, context: CollisionContext): VoxelShape = SHAPE

    override fun newBlockEntity(pos: BlockPos, state: BlockState): BlockEntity = CardTableBlockEntity(pos, state)

    override fun <T : BlockEntity> getTicker(level: Level, state: BlockState, type: BlockEntityType<T>): BlockEntityTicker<T>? =
        if (level.isClientSide()) {
            null
        } else {
            createTickerHelper(type, TuKuaiContent.CARD_TABLE_ENTITY, BlockEntityTicker { tickLevel, _, _, table ->
                table.serverTick(tickLevel as ServerLevel)
            })
        }

    override fun useWithoutItem(state: BlockState, level: Level, pos: BlockPos, player: Player, hit: BlockHitResult): InteractionResult {
        if (level.isClientSide()) {
            Services.openTable(pos)
        } else {
            (level.getBlockEntity(pos) as? CardTableBlockEntity)?.onUse(player as ServerPlayer)
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
        val color: DyeColor = DyeColor.getColor(stack) ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        val table = level.getBlockEntity(pos) as? CardTableBlockEntity ?: return InteractionResult.TRY_WITH_EMPTY_HAND
        if (table.felt == color) return InteractionResult.TRY_WITH_EMPTY_HAND
        if (!level.isClientSide()) {
            table.dye(color)
            if (!player.abilities.instabuild) stack.shrink(1)
        }
        return InteractionResult.SUCCESS
    }

    companion object {
        val FACING = HorizontalDirectionalBlock.FACING

        val CODEC: MapCodec<CardTableBlock> = Block.simpleCodec(::CardTableBlock)

        private val SHAPE: VoxelShape = Shapes.or(
            Block.box(0.0, 12.0, 0.0, 16.0, 16.0, 16.0),
            Block.box(1.0, 0.0, 1.0, 4.0, 12.0, 4.0),
            Block.box(12.0, 0.0, 1.0, 15.0, 12.0, 4.0),
            Block.box(1.0, 0.0, 12.0, 4.0, 12.0, 15.0),
            Block.box(12.0, 0.0, 12.0, 15.0, 12.0, 15.0),
        )
    }
}
