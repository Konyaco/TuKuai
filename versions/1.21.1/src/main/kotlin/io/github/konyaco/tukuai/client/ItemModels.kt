package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.content.StoolBlock
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.content.Wood
import net.minecraft.client.renderer.item.ClampedItemPropertyFunction
import net.minecraft.core.component.DataComponents
import net.minecraft.resources.ResourceLocation
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.Item

/**
 * 1.21.1 picks an item's model by number, not by block state as 26.1 does: `tukuai:wood`
 * and `tukuai:color` are the item's wood and cushion colour as sixteenths, which the item
 * models in this version's resources choose between (see tools/gen_wood.py).
 */
object ItemModels {
    val WOOD: ResourceLocation = TuKuai.id("wood")
    val COLOR: ResourceLocation = TuKuai.id("color")

    fun register(register: (Item, ResourceLocation, ClampedItemPropertyFunction) -> Unit) {
        val wood = ClampedItemPropertyFunction { stack, _, _, _ ->
            (stack.get(DataComponents.BLOCK_STATE)?.get(Wood.PROPERTY) ?: Wood.OAK).ordinal / 16f
        }
        val color = ClampedItemPropertyFunction { stack, _, _, _ ->
            (stack.get(DataComponents.BLOCK_STATE)?.get(StoolBlock.COLOR) ?: DyeColor.RED).ordinal / 16f
        }
        register(TuKuaiContent.CARD_TABLE_ITEM, WOOD, wood)
        register(TuKuaiContent.BIG_CARD_TABLE_ITEM, WOOD, wood)
        register(TuKuaiContent.STOOL_ITEM, WOOD, wood)
        register(TuKuaiContent.STOOL_ITEM, COLOR, color)
    }
}
