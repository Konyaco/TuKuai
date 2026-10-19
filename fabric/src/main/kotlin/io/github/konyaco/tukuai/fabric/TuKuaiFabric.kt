package io.github.konyaco.tukuai.fabric

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.content.TuKuaiContent
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.CreativeModeTabs

class TuKuaiFabric : ModInitializer {
    override fun onInitialize() {
        Registry.register(BuiltInRegistries.BLOCK, TuKuai.id("card_table"), TuKuaiContent.CARD_TABLE)
        Registry.register(BuiltInRegistries.ITEM, TuKuai.id("card_table"), TuKuaiContent.CARD_TABLE_ITEM)
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register { entries ->
            entries.accept(TuKuaiContent.CARD_TABLE_ITEM)
        }
        TuKuai.LOGGER.info("TuKuai loaded on Fabric")
    }
}
