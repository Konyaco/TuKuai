package io.github.konyaco.tukuai.client

import io.github.konyaco.tukuai.content.CardTableBlock
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.particles.ParticleOptions

/** Particles over a card table, for what happens in a game: smoke, explosions, sparks. */
object TableParticles {
    /**
     * A few particles [height] blocks above the table top at [pos], [x] and [z] blocks from
     * its middle and spread a little around there, drifting up at [rise].
     */
    fun burst(pos: BlockPos, particle: ParticleOptions, x: Float, z: Float, count: Int, height: Double = 0.2, rise: Double = 0.05) {
        val level = Minecraft.getInstance().level ?: return
        repeat(count) {
            level.addParticle(
                particle,
                pos.x + 0.5 + x + (Math.random() - 0.5) * 0.3, pos.y + CardTableBlock.TOP_HEIGHT + height, pos.z + 0.5 + z + (Math.random() - 0.5) * 0.3,
                0.0, rise, 0.0,
            )
        }
    }
}
