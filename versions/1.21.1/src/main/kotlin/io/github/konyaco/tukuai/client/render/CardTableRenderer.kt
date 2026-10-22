package io.github.konyaco.tukuai.client.render

import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.compat.ViewMatrices
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider

/** Draws a card table's cloth, cards and display with [TableArt]. */
open class CardTableRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CardTableBlockEntity> {

    private val art = TableArt()
    private val look = TableLook()

    override fun render(table: CardTableBlockEntity, partialTick: Float, poseStack: PoseStack, buffers: MultiBufferSource, light: Int, overlay: Int) {
        // The world's projection and view rotation, for finding the card under the mouse.
        ViewMatrices.viewRotationProjection.set(RenderSystem.getProjectionMatrix()).mul(RenderSystem.getModelViewMatrix())
        art.look(table, look, partialTick)
        art.draw(buffers.getBuffer(RenderType.entityCutout(CardArt.ATLAS)), poseStack.last(), light, look)
        val font = Minecraft.getInstance().font
        art.drawText(poseStack, look) { pose, x, y, line ->
            font.drawInBatch(line, x, y, TableArt.TEXT_COLOR, false, pose.last().pose(), buffers, Font.DisplayMode.POLYGON_OFFSET, 0, TableArt.FULL_BRIGHT)
        }
    }

    /** The table reaches over the blocks around its middle, which may be in chunks the game is not drawing. */
    override fun shouldRenderOffScreen(table: CardTableBlockEntity): Boolean = true
}
