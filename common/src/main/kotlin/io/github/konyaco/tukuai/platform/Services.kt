package io.github.konyaco.tukuai.platform

import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.common.custom.CustomPacketPayload
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.entity.BlockEntityType
import net.minecraft.world.level.block.state.BlockState

/** The few things the two loaders do differently. Each loader sets [Services.platform] first thing. */
interface Platform {
    fun <T : BlockEntity> blockEntityType(factory: (BlockPos, BlockState) -> T, block: Block): BlockEntityType<T>

    fun sendToPlayer(player: ServerPlayer, payload: CustomPacketPayload)
}

/** Client-only counterpart of [Platform], set by each loader's client setup. */
interface ClientPlatform {
    fun sendToServer(payload: CustomPacketPayload)
}

object Services {
    lateinit var platform: Platform
    lateinit var client: ClientPlatform

    /**
     * Calls from shared block code into the client. They default to doing nothing, so a
     * dedicated server never loads a client class.
     */
    var openTable: (BlockPos) -> Unit = {}

    /** Hands a table update from the server to the client side. */
    var receiveTable: (BlockPos, ByteArray) -> Unit = { _, _ -> }
}
