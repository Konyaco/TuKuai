package io.github.konyaco.tukuai.fabric

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.command.TuKuaiCommands
import io.github.konyaco.tukuai.content.TuKuaiContent
import io.github.konyaco.tukuai.network.ReplayPayload
import io.github.konyaco.tukuai.network.TableActionPayload
import io.github.konyaco.tukuai.network.TableNetwork
import io.github.konyaco.tukuai.network.TableSyncPayload
import io.github.konyaco.tukuai.platform.Platform
import io.github.konyaco.tukuai.platform.Services
import net.fabricmc.api.ModInitializer
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking
import net.fabricmc.fabric.api.`object`.builder.v1.block.entity.FabricBlockEntityTypeBuilder
import net.minecraft.core.BlockPos
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.item.CreativeModeTabs
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

class TuKuaiFabric : ModInitializer {
    override fun onInitialize() {
        Services.platform = FabricPlatform
        TuKuai.init()

        Registry.register(BuiltInRegistries.BLOCK, TuKuai.id("card_table"), TuKuaiContent.CARD_TABLE)
        Registry.register(BuiltInRegistries.ITEM, TuKuai.id("card_table"), TuKuaiContent.CARD_TABLE_ITEM)
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, TuKuai.id("card_table"), TuKuaiContent.CARD_TABLE_ENTITY)
        Registry.register(BuiltInRegistries.ENTITY_TYPE, TuKuai.id("seat"), TuKuaiContent.SEAT)
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register { entries ->
            entries.accept(TuKuaiContent.CARD_TABLE_ITEM)
        }

        PayloadTypeRegistry.clientboundPlay().register(TableSyncPayload.TYPE, TableSyncPayload.CODEC)
        PayloadTypeRegistry.clientboundPlay().register(ReplayPayload.TYPE, ReplayPayload.CODEC)
        PayloadTypeRegistry.serverboundPlay().register(TableActionPayload.TYPE, TableActionPayload.CODEC)
        ServerPlayNetworking.registerGlobalReceiver(TableActionPayload.TYPE) { payload, context ->
            TableNetwork.onAction(context.player(), payload)
        }
        CommandRegistrationCallback.EVENT.register { dispatcher, _, _ -> TuKuaiCommands.register(dispatcher) }
        TuKuai.LOGGER.info("TuKuai loaded on Fabric")
    }
}

object FabricPlatform : Platform {
    override fun <T : BlockEntity> blockEntityType(factory: (BlockPos, BlockState) -> T, block: Block): BlockEntityType<T> =
        FabricBlockEntityTypeBuilder.create(FabricBlockEntityTypeBuilder.Factory { pos, state -> factory(pos, state) }, block).build()

    override fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload) {
        ServerPlayNetworking.send(player, payload)
    }
}
