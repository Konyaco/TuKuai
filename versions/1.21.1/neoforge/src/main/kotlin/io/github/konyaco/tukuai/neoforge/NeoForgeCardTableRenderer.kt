package io.github.konyaco.tukuai.neoforge

import io.github.konyaco.tukuai.client.render.CardTableRenderer
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.world.phys.AABB

/**
 * NeoForge skips block entities whose box is off screen, and the box is one block by
 * default. The table draws across all of its blocks, so widen it to match.
 */
class NeoForgeCardTableRenderer(context: BlockEntityRendererProvider.Context) : CardTableRenderer(context) {
    override fun getRenderBoundingBox(blockEntity: CardTableBlockEntity): AABB {
        val half = (blockEntity.blockState.block as? CardTableBlock)?.halfWidth ?: CardTableBlock.HALF_WIDTH
        return AABB(blockEntity.blockPos).inflate(half - 0.5, 0.5, half - 0.5)
    }
}
