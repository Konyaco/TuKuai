package io.github.konyaco.tukuai.network

import io.github.konyaco.tukuai.TuKuai
import io.github.konyaco.tukuai.table.TableOp
import net.minecraft.core.BlockPos
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.codec.StreamCodec
import net.minecraft.network.codec.StreamDecoder
import net.minecraft.network.codec.StreamMemberEncoder
import net.minecraft.network.protocol.common.custom.CustomPacketPayload

/** Server to client: the table at [pos] as this player sees it, an encoded TableSnapshot. */
class TableSyncPayload(val pos: BlockPos, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TableSyncPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<TableSyncPayload>(TuKuai.id("table_sync"))
        val CODEC: StreamCodec<FriendlyByteBuf, TableSyncPayload> = CustomPacketPayload.codec(
            StreamMemberEncoder { p, buf ->
                buf.writeBlockPos(p.pos)
                buf.writeByteArray(p.data)
            },
            StreamDecoder { buf -> TableSyncPayload(buf.readBlockPos(), buf.readByteArray()) },
        )
    }
}

/** Server to client: a saved game to watch, its file [name] and the encoded replay. */
class ReplayPayload(val name: String, val data: ByteArray) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<ReplayPayload> = TYPE

    companion object {
        val TYPE = CustomPacketPayload.Type<ReplayPayload>(TuKuai.id("replay"))
        val CODEC: StreamCodec<FriendlyByteBuf, ReplayPayload> = CustomPacketPayload.codec(
            StreamMemberEncoder { p, buf ->
                buf.writeUtf(p.name, 64)
                buf.writeByteArray(p.data)
            },
            StreamDecoder { buf -> ReplayPayload(buf.readUtf(64), buf.readByteArray()) },
        )
    }
}

/** Client to server: one request to the table at [pos]; see [TableOp] for what the fields mean. */
class TableActionPayload(
    val pos: BlockPos,
    val op: TableOp,
    val a: Int = 0,
    val b: Int = 0,
    val text: String = "",
    val data: ByteArray = ByteArray(0),
) : CustomPacketPayload {
    override fun type(): CustomPacketPayload.Type<TableActionPayload> = TYPE

    companion object {
        private const val MAX_DATA = 4096

        val TYPE = CustomPacketPayload.Type<TableActionPayload>(TuKuai.id("table_action"))
        val CODEC: StreamCodec<FriendlyByteBuf, TableActionPayload> = CustomPacketPayload.codec(
            StreamMemberEncoder { p, buf ->
                buf.writeBlockPos(p.pos)
                buf.writeVarInt(p.op.ordinal)
                buf.writeVarInt(p.a)
                buf.writeVarInt(p.b)
                buf.writeUtf(p.text, 64)
                buf.writeByteArray(p.data)
            },
            StreamDecoder { buf ->
                val pos = buf.readBlockPos()
                val op = TableOp.entries.getOrNull(buf.readVarInt()) ?: TableOp.START
                TableActionPayload(pos, op, buf.readVarInt(), buf.readVarInt(), buf.readUtf(64), buf.readByteArray(MAX_DATA))
            },
        )
    }
}
