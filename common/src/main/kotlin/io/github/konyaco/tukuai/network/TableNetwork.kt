package io.github.konyaco.tukuai.network

import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.server.level.ServerPlayer

/** Server-side handling of what clients send; the loaders call this on the server thread. */
object TableNetwork {
    fun onAction(player: ServerPlayer, payload: TableActionPayload) {
        val level = player.level()
        if (!level.isLoaded(payload.pos)) return
        (level.getBlockEntity(payload.pos) as? CardTableBlockEntity)?.handle(player, payload)
    }
}
