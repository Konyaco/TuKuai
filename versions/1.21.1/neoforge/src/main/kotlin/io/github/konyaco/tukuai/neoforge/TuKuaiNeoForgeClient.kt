package io.github.konyaco.tukuai.neoforge

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.ItemModels
import io.github.konyaco.tukuai.client.TuKuaiClient
import io.github.konyaco.tukuai.client.render.SeatRenderer
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.platform.ClientPlatform
import net.minecraft.client.renderer.item.ItemProperties
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.network.PacketDistributor

@Mod(value = TuKuai.MOD_ID, dist = [Dist.CLIENT])
class TuKuaiNeoForgeClient(bus: IEventBus) {
    init {
        TuKuaiClient.init(object : ClientPlatform {
            override fun sendToServer(payload: CustomPacketPayload) {
                PacketDistributor.sendToServer(payload)
            }
        })
        bus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerBlockEntityRenderer(TuKuaiContent.CARD_TABLE_ENTITY, ::NeoForgeCardTableRenderer)
            event.registerEntityRenderer(TuKuaiContent.SEAT, ::SeatRenderer)
        }
        bus.addListener(FMLClientSetupEvent::class.java) { event ->
            event.enqueueWork { ItemModels.register(ItemProperties::register) }
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { TuKuaiClient.tick() }
    }
}
