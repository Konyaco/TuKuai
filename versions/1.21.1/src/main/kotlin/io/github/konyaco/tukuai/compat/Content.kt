package io.github.konyaco.tukuai.compat

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style
import net.minecraft.resources.ResourceKey
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.InteractionHand
import net.minecraft.world.ItemInteractionResult
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.level.Level
import net.minecraft.world.level.LevelAccessor
import net.minecraft.world.level.LevelReader
import net.minecraft.world.level.block.BaseEntityBlock
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.RenderShape
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.BlockHitResult

/** Whether this build has pale oak, which came in 1.21.4. */
const val HAS_PALE_OAK = false

// 1.21.1 takes registry ids from the registration itself, so blocks and items have no use for them.
@Suppress("UNUSED_PARAMETER")
fun BlockBehaviour.Properties.setId(key: ResourceKey<Block>): BlockBehaviour.Properties = this

@Suppress("UNUSED_PARAMETER")
fun Item.Properties.setId(key: ResourceKey<Item>): Item.Properties = this

/** A block item is named after its block in 1.21.1 already. */
fun Item.Properties.useBlockDescriptionPrefix(): Item.Properties = this

fun <T : Entity> EntityType.Builder<T>.build(key: ResourceKey<EntityType<*>>): EntityType<T> = build(key.location().toString())

/** A line over the hotbar. */
fun Player.sendOverlayMessage(message: Component) = displayClientMessage(message, true)

val Player.plainTextName: String get() = gameProfile.name

fun Style.withRunCommand(command: String): Style = withClickEvent(ClickEvent(ClickEvent.Action.RUN_COMMAND, command))

fun Style.withShowText(text: Component): Style = withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, text))

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

    /** 1.21.1 leaves blocks with a block entity to their renderer alone; this one has a model too. */
    override fun getRenderShape(state: BlockState): RenderShape = RenderShape.MODEL

    final override fun useItemOn(
        stack: ItemStack,
        state: BlockState,
        level: Level,
        pos: BlockPos,
        player: Player,
        hand: InteractionHand,
        hit: BlockHitResult,
    ): ItemInteractionResult =
        if (useItem(stack, state, level, pos, player, hand, hit)) {
            ItemInteractionResult.sidedSuccess(level.isClientSide)
        } else {
            ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        }

    final override fun updateShape(
        state: BlockState,
        direction: Direction,
        neighborState: BlockState,
        level: LevelAccessor,
        pos: BlockPos,
        neighborPos: BlockPos,
    ): BlockState = shapeAfterNeighbor(state, level, pos, direction, neighborPos, neighborState)
        ?: super.updateShape(state, direction, neighborState, level, pos, neighborPos)
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
    ): ItemInteractionResult =
        if (useItem(stack, state, level, pos, player, hand, hit)) {
            ItemInteractionResult.sidedSuccess(level.isClientSide)
        } else {
            ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION
        }
}

/** A block entity saved through 26.1's [ValueInput] and [ValueOutput]. */
abstract class CompatBlockEntity(type: BlockEntityType<*>, pos: BlockPos, state: BlockState) : BlockEntity(type, pos, state) {
    protected open fun saveAdditional(output: ValueOutput) {}

    protected open fun loadAdditional(input: ValueInput) {}

    final override fun saveAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.saveAdditional(tag, registries)
        saveAdditional(ValueOutput(tag))
    }

    final override fun loadAdditional(tag: CompoundTag, registries: HolderLookup.Provider) {
        super.loadAdditional(tag, registries)
        loadAdditional(ValueInput(tag, registries))
    }
}

/** An entity with 26.1's server-side hurt and its [ValueInput] and [ValueOutput] saving. */
abstract class CompatEntity(type: EntityType<*>, level: Level) : Entity(type, level) {
    open fun hurtServer(level: ServerLevel, source: DamageSource, amount: Float): Boolean = super.hurt(source, amount)

    final override fun hurt(source: DamageSource, amount: Float): Boolean {
        val level = level()
        return if (level is ServerLevel) hurtServer(level, source, amount) else super.hurt(source, amount)
    }

    protected abstract fun readAdditionalSaveData(input: ValueInput)

    protected abstract fun addAdditionalSaveData(output: ValueOutput)

    final override fun readAdditionalSaveData(tag: CompoundTag) = readAdditionalSaveData(ValueInput(tag, null))

    final override fun addAdditionalSaveData(tag: CompoundTag) = addAdditionalSaveData(ValueOutput(tag))
}
