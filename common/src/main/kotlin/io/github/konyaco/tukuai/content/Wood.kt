package io.github.konyaco.tukuai.content

import net.minecraft.util.StringRepresentable
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.block.state.properties.EnumProperty
import net.minecraft.world.level.material.MapColor

/**
 * The wood a card table or stool is made of, taken from the planks or slab in its recipe.
 * Oak comes first: it is the default, so tables and stools from before they had a wood
 * keep the look they always had. The models and recipes come from `tools/gen_wood.py`.
 */
enum class Wood(private val id: String, val mapColor: MapColor) : StringRepresentable {
    OAK("oak", MapColor.WOOD),
    SPRUCE("spruce", MapColor.PODZOL),
    BIRCH("birch", MapColor.SAND),
    JUNGLE("jungle", MapColor.DIRT),
    ACACIA("acacia", MapColor.COLOR_ORANGE),
    DARK_OAK("dark_oak", MapColor.COLOR_BROWN),
    MANGROVE("mangrove", MapColor.COLOR_RED),
    CHERRY("cherry", MapColor.TERRACOTTA_WHITE),
    PALE_OAK("pale_oak", MapColor.QUARTZ),
    BAMBOO("bamboo", MapColor.COLOR_YELLOW),
    CRIMSON("crimson", MapColor.CRIMSON_STEM),
    WARPED("warped", MapColor.WARPED_STEM),
    ;

    override fun getSerializedName(): String = id

    companion object {
        val PROPERTY: EnumProperty<Wood> = EnumProperty.create("wood", Wood::class.java)

        /** The map colour of a block with a [PROPERTY], which follows its wood like planks do. */
        fun mapColorOf(state: BlockState): MapColor = state.getValue(PROPERTY).mapColor
    }
}
