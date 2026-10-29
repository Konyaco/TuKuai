package io.github.konyaco.tukuai.compat

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.RandomSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.ScheduledTickAccess
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/** Whether this build has pale oak, which came in 1.21.4. */
const val HAS_PALE_OAK = true

fun Style.withRunCommand(command: String): Style = withClickEvent(ClickEvent.RunCommand(command))

fun Style.withShowText(text: Component): Style = withHoverEvent(HoverEvent.ShowText(text))

/**
 * Block hooks under names that are the same on every Minecraft version, where the
 * game's own methods differ: [useItem] for using an item on the block, and
 * [shapeAfterNeighbor] for a neighbour changing.
 */
abstract class CompatBaseEntityBlock(properties: BlockBehaviour.Properties) : BaseEntityBlock(properties) {
    /** Right-click holding [stack]: true when the block used it, false to go on as with an empty hand. */
    protected open fun useItem(stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult): Boolean =
        false

    /** The state after the neighbour at [neighborPos] changed, or null to leave it to the game. */
    protected open fun shapeAfterNeighbor(
        state: BlockState,
        level: LevelReader,
        pos: BlockPos,
        direction: Direction,
        neighborPos: BlockPos,
        neighborState: BlockState,
    ): BlockState? = null

    final override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult =
        if (useItem(stack, state, level, pos, player, hand, hit)) InteractionResult.SUCCESS else InteractionResult.TRY_WITH_EMPTY_HAND

    final override fun updateShape(
        state: BlockState,
        level: LevelReader,
        ticks: ScheduledTickAccess,
        pos: BlockPos,
        direction: Direction,
        neighborPos: BlockPos,
        neighborState: BlockState,
        random: RandomSource,
    ): BlockState = shapeAfterNeighbor(state, level, pos, direction, neighborPos, neighborState)
        ?: super.updateShape(state, level, ticks, pos, direction, neighborPos, neighborState, random)
}

/** [CompatBaseEntityBlock] for a block without a block entity. */
abstract class CompatBlock(properties: BlockBehaviour.Properties) : Block(properties) {
    protected open fun useItem(stack: ItemStack, state: BlockState, level: Level, pos: BlockPos, player: Player, hand: InteractionHand, hit: BlockHitResult): Boolean =
        false

    final override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): InteractionResult =
        if (useItem(stack, state, level, pos, player, hand, hit)) InteractionResult.SUCCESS else InteractionResult.TRY_WITH_EMPTY_HAND
}

/** Hurts a player on the server. */
fun Player.hurtOnServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean = hurtServer(level, source, amount)
