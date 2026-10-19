package io.github.konyaco.tukuai.neoforge

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.client.TuKuaiClient
import io.github.konyaco.tukuai.client.render.CardTableRenderer
import io.github.konyaco.tukuai.client.render.SeatRenderer
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.platform.ClientPlatform
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.neoforged.api.distmarker.Dist
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.client.event.ClientTickEvent
import net.neoforged.neoforge.client.event.EntityRenderersEvent
import net.neoforged.neoforge.client.network.ClientPacketDistributor
import net.neoforged.neoforge.common.NeoForge

@Mod(value = TuKuai.MOD_ID, dist = [Dist.CLIENT])
class TuKuaiNeoForgeClient(bus: IEventBus) {
    init {
        TuKuaiClient.init(object : ClientPlatform {
            override fun sendToServer(payload: CustomPacketPayload) {
                ClientPacketDistributor.sendToServer(payload)
            }
        })
        bus.addListener(EntityRenderersEvent.RegisterRenderers::class.java) { event ->
            event.registerBlockEntityRenderer(TuKuaiContent.CARD_TABLE_ENTITY, ::CardTableRenderer)
            event.registerEntityRenderer(TuKuaiContent.SEAT, ::SeatRenderer)
        }
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post::class.java) { TuKuaiClient.tick() }
    }
}
