package io.github.konyaco.tukuai.neoforge

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.command.TuKuaiCommands
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.network.ReplayPayload
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.network.TableNetwork
import io.github.konyaco.tukuai.network.TableSyncPayload
import io.github.konyaco.tukuai.platform.Platform
import io.github.konyaco.tukuai.platform.Services
import java.util.function.Supplier
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.Registries
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState
import net.neoforged.bus.api.IEventBus
import net.neoforged.fml.common.Mod
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent
import net.neoforged.neoforge.event.RegisterCommandsEvent
import net.neoforged.neoforge.network.PacketDistributor
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent
import net.neoforged.neoforge.network.handling.IPayloadHandler
import net.neoforged.neoforge.registries.DeferredRegister

@Mod(TuKuai.MOD_ID)
class TuKuaiNeoForge(bus: IEventBus) {
    init {
        Services.platform = NeoForgePlatform
        TuKuai.init()

        // The suppliers run inside the registry events, which is when TuKuaiContent may first be read.
        val blocks = DeferredRegister.create(Registries.BLOCK, TuKuai.MOD_ID)
        blocks.register("card_table", Supplier { TuKuaiContent.CARD_TABLE })
        blocks.register(bus)

        val items = DeferredRegister.create(Registries.ITEM, TuKuai.MOD_ID)
        items.register("card_table", Supplier { TuKuaiContent.CARD_TABLE_ITEM })
        items.register(bus)

        val blockEntities = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, TuKuai.MOD_ID)
        blockEntities.register("card_table", Supplier { TuKuaiContent.CARD_TABLE_ENTITY })
        blockEntities.register(bus)

        val entities = DeferredRegister.create(Registries.ENTITY_TYPE, TuKuai.MOD_ID)
        entities.register("seat", Supplier { TuKuaiContent.SEAT })
        entities.register(bus)

        bus.addListener(BuildCreativeModeTabContentsEvent::class.java) { event ->
            if (event.tabKey == CreativeModeTabs.FUNCTIONAL_BLOCKS) event.accept(TuKuaiContent.CARD_TABLE_ITEM)
        }
        bus.addListener(RegisterPayloadHandlersEvent::class.java, ::registerPayloads)
        NeoForge.EVENT_BUS.addListener(RegisterCommandsEvent::class.java) { event -> TuKuaiCommands.register(event.dispatcher) }
        TuKuai.LOGGER.info("TuKuai loaded on NeoForge")
    }

    private fun registerPayloads(event: RegisterPayloadHandlersEvent) {
        event.registrar("1")
            .playToClient(TableSyncPayload.TYPE, TableSyncPayload.CODEC, IPayloadHandler { payload, _ ->
                Services.receiveTable(payload.pos, payload.data)
            })
            .playToClient(ReplayPayload.TYPE, ReplayPayload.CODEC, IPayloadHandler { payload, _ ->
                Services.openReplay(payload.name, payload.data)
            })
            .playToServer(TableActionPayload.TYPE, TableActionPayload.CODEC, IPayloadHandler { payload, context ->
                (context.player() as? ServerPlayer)?.let { TableNetwork.onAction(it, payload) }
            })
    }
}

object NeoForgePlatform : Platform {
    override fun <T : BlockEntity> blockEntityType(factory: (BlockPos, BlockState) -> T, block: Block): BlockEntityType<T> =
        BlockEntityType(BlockEntityType.BlockEntitySupplier { pos, state -> factory(pos, state) }, block)

    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) {
        PacketDistributor.sendToPlayer(player, payload)
    }
}
