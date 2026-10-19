package io.github.konyaco.tukuai.content

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.platform.Services
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.entity.EntityType
import net.minecraft.world.entity.MobCategory
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor

/**
 * Blocks, items and types shared by both loaders; each loader registers these exact instances.
 * Nothing may read this object before the block registry opens: NeoForge rejects
 * blocks and items created outside its registration events.
 */
object TuKuaiContent {
    val CARD_TABLE: CardTableBlock = CardTableBlock(
        blockProperties("card_table").noOcclusion(),
    )

    val CARD_TABLE_ITEM: Item = BlockItem(CARD_TABLE, itemProperties("card_table").useBlockDescriptionPrefix())

    val CARD_TABLE_ENTITY: BlockEntityType<CardTableBlockEntity> by lazy {
        Services.platform.blockEntityType(::CardTableBlockEntity, CARD_TABLE)
    }

    /** The invisible thing a player rides while sitting at a table. */
    val SEAT: EntityType<SeatEntity> = EntityType.Builder.of<SeatEntity>(::SeatEntity, MobCategory.MISC)
        .sized(0.5f, 0.25f)
        .noSummon()
        .clientTrackingRange(8)
        .build(ResourceKey.create(Registries.ENTITY_TYPE, TuKuai.id("seat")))

    private fun blockProperties(name: String): BlockBehaviour.Properties =
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.5f)
            .sound(SoundType.WOOD)
            .setId(ResourceKey.create(Registries.BLOCK, TuKuai.id(name)))

    private fun itemProperties(name: String): Item.Properties =
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, TuKuai.id(name)))
}
