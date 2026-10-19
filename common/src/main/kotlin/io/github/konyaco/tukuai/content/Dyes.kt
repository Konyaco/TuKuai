package io.github.konyaco.tukuai.content

import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.DyeColor
import net.minecraft.world.item.ItemStack

object Dyes {
    /** The colour of a vanilla dye item, or null for anything else. */
    fun colorOf(stack: ItemStack): DyeColor? {
        if (stack.isEmpty) return null
        val id = BuiltInRegistries.ITEM.getKey(stack.item)
        if (id.namespace != "minecraft") return null
        return DyeColor.entries.firstOrNull { id.path == "${it.serializedName}_dye" }
    }
}
