package io.github.konyaco.tukuai.compat

import java.util.Optional
import net.minecraft.core.HolderLookup
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.Tag

/** What 26.1 reads saved block entities and entities from, over the compound tag 1.21.1 gives. */
class ValueInput(private val tag: CompoundTag, @Suppress("unused") val registries: HolderLookup.Provider?) {
    fun getIntOr(key: String, fallback: Int): Int = if (tag.contains(key, Tag.TAG_INT.toInt())) tag.getInt(key) else fallback

    fun getStringOr(key: String, fallback: String): String = if (tag.contains(key, Tag.TAG_STRING.toInt())) tag.getString(key) else fallback

    fun getString(key: String): Optional<String> =
        if (tag.contains(key, Tag.TAG_STRING.toInt())) Optional.of(tag.getString(key)) else Optional.empty()

    fun getIntArray(key: String): Optional<IntArray> =
        if (tag.contains(key, Tag.TAG_INT_ARRAY.toInt())) Optional.of(tag.getIntArray(key)) else Optional.empty()
}

/** What 26.1 saves block entities and entities to, over the compound tag 1.21.1 gives. */
class ValueOutput(private val tag: CompoundTag) {
    fun putInt(key: String, value: Int) = tag.putInt(key, value)

    fun putString(key: String, value: String) = tag.putString(key, value)

    fun putIntArray(key: String, value: IntArray) = tag.putIntArray(key, value)
}
