package io.github.konyaco.tukuai.content

import io.github.konyaco.tukuai.TuKuai
import net.minecraft.core.registries.Registries
import net.minecraft.resources.ResourceKey
import net.minecraft.world.item.BlockItem
import net.minecraft.world.item.Item
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.SoundType
import net.minecraft.world.level.block.state.BlockBehaviour
import net.minecraft.world.level.material.MapColor

/**
 * Blocks and items shared by both loaders; each loader registers these exact instances.
 * Nothing may read this object before the block registry opens: NeoForge rejects
 * blocks and items created outside its registration events.
 */
object TuKuaiContent {
    val CARD_TABLE: Block = Block(blockProperties("card_table"))

    val CARD_TABLE_ITEM: Item = BlockItem(CARD_TABLE, itemProperties("card_table").useBlockDescriptionPrefix())

    private fun blockProperties(name: String): BlockBehaviour.Properties =
        BlockBehaviour.Properties.of()
            .mapColor(MapColor.WOOD)
            .strength(2.5f)
            .sound(SoundType.WOOD)
            .setId(ResourceKey.create(Registries.BLOCK, TuKuai.id(name)))

    private fun itemProperties(name: String): Item.Properties =
        Item.Properties().setId(ResourceKey.create(Registries.ITEM, TuKuai.id(name)))
}
