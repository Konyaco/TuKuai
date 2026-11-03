package io.github.konyaco.tukuai.client.render

import com.mojang.blaze3d.vertex.PoseStack
import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.world.phys.Vec3

/** Draws a card table's cloth, cards and display with [TableArt]. */
open class CardTableRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CardTableBlockEntity, CardTableRenderer.State> {

    class State : BlockEntityRenderState() {
        val look = TableLook()
    }

    private val art = TableArt()

    override fun createRenderState(): State = State()

    override fun extractRenderState(
        table: CardTableBlockEntity,
        state: State,
        partialTick: Float,
        cameraPos: Vec3,
        crumbling: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(table, state, crumbling)
        art.look(table, state.look, partialTick)
    }

    override fun submit(state: State, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val light = state.lightCoords
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(CardArt.ATLAS)) { pose, buffer -> art.draw(buffer, pose, light, state.look) }
        art.drawText(poseStack, state.look) { pose, x, y, line ->
            collector.submitText(pose, x, y, line, false, Font.DisplayMode.POLYGON_OFFSET, TableArt.FULL_BRIGHT, TableArt.TEXT_COLOR, 0, 0)
        }
    }
}
