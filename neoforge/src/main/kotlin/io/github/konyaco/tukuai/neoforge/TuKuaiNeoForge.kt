package io.github.konyaco.tukuai.neoforge

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.content.TuKuaiContent
import java.util.function.Supplier
import net.minecraft.core.registries.Registries
import net.minecraft.world.item.CreativeModeTabs
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.registries.DeferredRegister

@Mod(TuKuai.MOD_ID)
class TuKuaiNeoForge(bus: IEventBus) {
    init {
        // The suppliers run inside the registry events, which is when TuKuaiContent may first be read.
        val blocks = DeferredRegister.create(Registries.BLOCK, TuKuai.MOD_ID)
        blocks.register("card_table", Supplier { TuKuaiContent.CARD_TABLE })
        blocks.register(bus)

        val items = DeferredRegister.create(Registries.ITEM, TuKuai.MOD_ID)
        items.register("card_table", Supplier { TuKuaiContent.CARD_TABLE_ITEM })
        items.register(bus)

        bus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(TuKuaiContent.CARD_TABLE_ITEM)
        }
        TuKuai.LOGGER.info("TuKuai loaded on NeoForge")
    }
}
