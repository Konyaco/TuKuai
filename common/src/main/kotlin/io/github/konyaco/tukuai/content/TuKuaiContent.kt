package io.github.konyaco.tukuai.content

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.compat.*
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.BlockItemStateProperties
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor
import net.minecraft.world.level.material.PushReaction

/**
 * Blocks, items and types shared by both loaders; each loader registers these exact instances.
 * Nothing may read this object before the block registry opens: NeoForge rejects
 * blocks and items created outside its registration events.
 */
object TuKuaiContent {
    val CARD_TABLE: CardTableBlock = CardTableBlock(
        blockProperties("card_table").mapColor(Wood::mapColorOf).noOcclusion().pushReaction(PushReaction.BLOCK),
    )

    val CARD_TABLE_ITEM: Item = BlockItem(CARD_TABLE, itemProperties("card_table").useBlockDescriptionPrefix())

    /** The 5x5 table for up to eight players. */
    val BIG_CARD_TABLE: BigCardTableBlock = BigCardTableBlock(
        blockProperties("big_card_table").mapColor(Wood::mapColorOf).noOcclusion().pushReaction(PushReaction.BLOCK),
    )

    val BIG_CARD_TABLE_ITEM: Item = BlockItem(BIG_CARD_TABLE, itemProperties("big_card_table").useBlockDescriptionPrefix())

    val STOOL: StoolBlock = StoolBlock(blockProperties("stool").mapColor(Wood::mapColorOf).noOcclusion().strength(1.5f))

    val STOOL_ITEM: Item = BlockItem(STOOL, itemProperties("stool").useBlockDescriptionPrefix())

    val CARD_TABLE_ENTITY: BlockEntityType<CardTableBlockEntity> by lazy {
        Services.platform.blockEntityType(::CardTableBlockEntity, CARD_TABLE, BIG_CARD_TABLE)
    }

    /** The invisible thing a player rides while sitting at a table. */
    val SEAT: EntityType<SeatEntity> = EntityType.Builder.of<SeatEntity>(::SeatEntity, MobCategory.MISC)
        .sized(0.5f, SeatEntity.HEIGHT.toFloat())
        .noSummon()
        .clientTrackingRange(8)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, TuKuai.id("seat")))

    /**
     * The creative tab entries: a table in every wood, a big table in every wood, then an oak
     * stool in every colour and a red one in every other wood.
     */
    fun creativeStacks(): List<ItemStack> =
        Wood.available.map { wood -> withState(ItemStack(CARD_TABLE_ITEM), BlockItemStateProperties.EMPTY.with(CardTableBlock.WOOD, wood)) } +
            Wood.available.map { wood -> withState(ItemStack(BIG_CARD_TABLE_ITEM), BlockItemStateProperties.EMPTY.with(CardTableBlock.WOOD, wood)) } +
            DyeColor.entries.map { color -> withState(ItemStack(STOOL_ITEM), BlockItemStateProperties.EMPTY.with(StoolBlock.COLOR, color)) } +
            Wood.available.drop(1).map { wood ->
                withState(ItemStack(STOOL_ITEM), BlockItemStateProperties.EMPTY.with(StoolBlock.COLOR, DyeColor.RED).with(StoolBlock.WOOD, wood))
            }

    private fun withState(stack: ItemStack, state: BlockItemStateProperties): ItemStack = stack.apply { set(DataComponents.BLOCK_STATE, state) }

    private fun blockProperties(name: String): BlockBehaviour.Properties =
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.5f)
            .sound(SoundType.WOOD)
            .setId(ResourceKey.create(Registries.BLOCK, TuKuai.id(name)))

    private fun itemProperties(name: String): Item.Properties =
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, TuKuai.id(name)))
}
