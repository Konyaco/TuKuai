package io.github.konyaco.tukuai.engine.core

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * A small binary format for everything that leaves the engine: actions sent by
 * clients, views sent to clients, rules and replays saved to disk. Keeping it here
 * means the engine needs no serialization library and no Minecraft classes.
 */
class WireWriter {
    private val bytes = ByteArrayOutputStream()
    private val out = DataOutputStream(bytes)

    fun int(value: Int) = apply { out.writeInt(value) }

    fun long(value: Long) = apply { out.writeLong(value) }

    fun bool(value: Boolean) = apply { out.writeBoolean(value) }

    fun string(value: String) = apply { out.writeUTF(value) }

    fun bytes(value: ByteArray) = apply {
        out.writeInt(value.size)
        out.write(value)
    }

    fun ints(values: List<Int>) = apply {
        out.writeInt(values.size)
        values.forEach(out::writeInt)
    }

    fun <T> list(values: List<T>, write: WireWriter.(T) -> Unit) = apply {
        out.writeInt(values.size)
        values.forEach { write(it) }
    }

    fun <T : Any> nullable(value: T?, write: WireWriter.(T) -> Unit) = apply {
        out.writeBoolean(value != null)
        if (value != null) write(value)
    }

    fun toByteArray(): ByteArray {
        out.flush()
        return bytes.toByteArray()
    }
}

class WireReader(data: ByteArray) {
    private val input = DataInputStream(ByteArrayInputStream(data))

    fun int(): Int = input.readInt()

    fun long(): Long = input.readLong()

    fun bool(): Boolean = input.readBoolean()

    fun string(): String = input.readUTF()

    fun bytes(): ByteArray = ByteArray(checkedSize()).also(input::readFully)

    fun ints(): List<Int> = List(checkedSize()) { input.readInt() }

    fun <T> list(read: WireReader.() -> T): List<T> = List(checkedSize()) { read() }

    fun <T : Any> nullable(read: WireReader.() -> T): T? = if (input.readBoolean()) read() else null

    private fun checkedSize(): Int {
        val size = input.readInt()
        // Data comes from the network, so a corrupt size must not allocate gigabytes.
        if (size < 0 || size > MAX_ELEMENTS) throw WireFormatException("Bad length $size")
        return size
    }

    private companion object {
        const val MAX_ELEMENTS = 1 shl 20
    }
}

class WireFormatException(message: String) : RuntimeException(message)

/** Turns one kind of value into bytes and back. */
interface WireCodec<T> {
    fun write(writer: WireWriter, value: T)

    fun read(reader: WireReader): T

    fun encode(value: T): ByteArray = WireWriter().also { write(it, value) }.toByteArray()

    fun decode(data: ByteArray): T =
        try {
            read(WireReader(data))
        } catch (e: java.io.IOException) {
            throw WireFormatException("Truncated data: ${e.message}")
        } catch (e: IllegalArgumentException) {
            throw WireFormatException("Bad data: ${e.message}")
        } catch (e: IndexOutOfBoundsException) {
            throw WireFormatException("Bad data: ${e.message}")
        }
}
