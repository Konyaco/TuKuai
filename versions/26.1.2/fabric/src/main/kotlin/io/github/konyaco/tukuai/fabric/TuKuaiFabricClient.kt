package io.github.konyaco.tukuai.fabric

import io.github.konyaco.tukuai.client.TuKuaiClient
import io.github.konyaco.tukuai.client.render.CardTableRenderer
import io.github.konyaco.tukuai.client.render.SeatRenderer
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.network.ReplayPayload
import io.github.konyaco.tukuai.network.TableSyncPayload
import io.github.konyaco.tukuai.platform.ClientPlatform
import io.github.konyaco.tukuai.platform.Services
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking
import net.fabricmc.fabric.api.client.rendering.v1.BlockEntityRendererRegistry
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

class TuKuaiFabricClient : ClientModInitializer {
    override fun onInitializeClient() {
        TuKuaiClient.init(object : ClientPlatform {
            override fun sendToServer(payload: CustomPacketPayload) {
                ClientPlayNetworking.send(payload)
            }
        })
        ClientPlayNetworking.registerGlobalReceiver(TableSyncPayload.TYPE) { payload, _ ->
            Services.receiveTable(payload.pos, payload.data)
        }
        ClientPlayNetworking.registerGlobalReceiver(ReplayPayload.TYPE) { payload, _ ->
            Services.openReplay(payload.name, payload.data)
        }
        BlockEntityRendererRegistry.register(TuKuaiContent.CARD_TABLE_ENTITY, ::CardTableRenderer)
        EntityRendererRegistry.register(TuKuaiContent.SEAT, ::SeatRenderer)
        ClientTickEvents.END_CLIENT_TICK.register { TuKuaiClient.tick() }
    }
}
