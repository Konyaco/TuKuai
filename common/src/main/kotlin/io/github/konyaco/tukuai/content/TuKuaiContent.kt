package io.github.konyaco.tukuai.content

import io.github.konyaco.tukuai.TuKuai
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
        blockProperties("card_table").noOcclusion().pushReaction(PushReaction.BLOCK),
    )

    val CARD_TABLE_ITEM: Item = BlockItem(CARD_TABLE, itemProperties("card_table").useBlockDescriptionPrefix())

    val STOOL: StoolBlock = StoolBlock(blockProperties("stool").noOcclusion().strength(1.5f))

    val STOOL_ITEM: Item = BlockItem(STOOL, itemProperties("stool").useBlockDescriptionPrefix())

    val CARD_TABLE_ENTITY: BlockEntityType<CardTableBlockEntity> by lazy {
        Services.platform.blockEntityType(::CardTableBlockEntity, CARD_TABLE)
    }

    /** The invisible thing a player rides while sitting at a table. */
    val SEAT: EntityType<SeatEntity> = EntityType.Builder.of<SeatEntity>(::SeatEntity, MobCategory.MISC)
        .sized(0.5f, SeatEntity.HEIGHT.toFloat())
        .noSummon()
        .clientTrackingRange(8)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, TuKuai.id("seat")))

    /** The creative tab entries: the table, then a stool in every colour. */
    fun creativeStacks(): List<ItemStack> = listOf(ItemStack(CARD_TABLE_ITEM)) + DyeColor.entries.map { color ->
        ItemStack(STOOL_ITEM).apply { set(DataComponents.BLOCK_STATE, BlockItemStateProperties.EMPTY.with(StoolBlock.COLOR, color)) }
    }

    private fun blockProperties(name: String): BlockBehaviour.Properties =
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.5f)
            .sound(SoundType.WOOD)
            .setId(ResourceKey.create(Registries.BLOCK, TuKuai.id(name)))

    private fun itemProperties(name: String): Item.Properties =
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, TuKuai.id(name)))
}
