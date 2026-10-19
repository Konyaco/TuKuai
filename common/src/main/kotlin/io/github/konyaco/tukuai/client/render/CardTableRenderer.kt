package io.github.konyaco.tukuai.client.render

import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.math.Axis
import io.github.konyaco.tukuai.client.CardArt
import io.github.konyaco.tukuai.client.ClientTables
import io.github.konyaco.tukuai.client.TableCard
import io.github.konyaco.tukuai.client.TableScreen
import io.github.konyaco.tukuai.content.CardTableBlock
import io.github.konyaco.tukuai.table.CardTableBlockEntity
import io.github.konyaco.tukuai.table.TableStatus
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.Font
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider
import net.minecraft.client.renderer.blockentity.state.BlockEntityRenderState
import net.minecraft.client.renderer.feature.ModelFeatureRenderer
import net.minecraft.client.renderer.rendertype.RenderTypes
import net.minecraft.client.renderer.state.level.CameraRenderState
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.core.Direction
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import net.minecraft.world.item.DyeColor
import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws the dyed cloth, the cards and the display in the middle of a card table. It is
 * attached to the table's middle block and draws across all nine.
 */
open class CardTableRenderer(@Suppress("UNUSED_PARAMETER") context: BlockEntityRendererProvider.Context) :
    BlockEntityRenderer<CardTableBlockEntity, CardTableRenderer.State> {

    class State : BlockEntityRenderState() {
        var feltColor: Int = DyeColor.GREEN.textureDiffuseColor
        var cards: List<TableCard> = emptyList()
        var back: Int = 0
        var lines: List<FormattedCharSequence> = emptyList()
        var turnSide: Direction? = null
    }

    override fun createRenderState(): State = State()

    override fun extractRenderState(
        table: CardTableBlockEntity,
        state: State,
        partialTick: Float,
        cameraPos: Vec3,
        crumbling: ModelFeatureRenderer.CrumblingOverlay?,
    ) {
        BlockEntityRenderState.extractBase(table, state, crumbling)
        val client = ClientTables[table.blockPos]
        state.back = CardArt.back()
        state.feltColor = DyeColor.byId(client?.snapshot?.felt ?: DyeColor.GREEN.id).textureDiffuseColor
        state.cards = emptyList()
        state.lines = emptyList()
        state.turnSide = null
        if (client == null) return
        val facing = table.blockState.getValue(CardTableBlock.FACING)
        val sides = List(client.snapshot.seats.size) { seat ->
            var side: Direction = facing
            repeat(seat) { side = side.counterClockWise }
            side
        }
        state.cards = client.scene(sides)
        val snapshot = client.snapshot
        val lines = if (snapshot.status == TableStatus.LOBBY || client.view == null) {
            listOf(Component.translatable("tukuai.game.${snapshot.gameId}"), TableScreen.presetName(snapshot.gameId, snapshot.presetId))
        } else {
            client.ui?.displayLines(client) ?: emptyList()
        }
        state.lines = lines.map { it.visualOrderText }
        state.turnSide = client.ui?.turnSeat(client)?.let { sides.getOrNull(it) }
    }

    override fun submit(state: State, poseStack: PoseStack, collector: SubmitNodeCollector, camera: CameraRenderState) {
        val light = state.lightCoords
        collector.submitCustomGeometry(poseStack, RenderTypes.entityCutout(CardArt.ATLAS)) { pose, buffer ->
            drawFelt(buffer, pose, light, ARGB_OPAQUE or lighten(state.feltColor))
            drawDisplay(buffer, pose, light, state.turnSide)
            for (card in state.cards) drawCard(buffer, pose, light, card, state.back)
        }
        if (state.lines.isNotEmpty()) drawDisplayText(poseStack, collector, state.lines)
    }

    /** One tile of the cloth per block, cut short at the wooden rim. */
    private fun drawFelt(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, color: Int) {
        val tile = CardArt.FELT_SIZE.toFloat()
        for (bx in -1..1) for (bz in -1..1) {
            val x0 = if (bx == -1) RIM else 0f
            val x1 = if (bx == 1) 1f - RIM else 1f
            val z0 = if (bz == -1) RIM else 0f
            val z1 = if (bz == 1) 1f - RIM else 1f
            val uv = floatArrayOf(
                (CardArt.FELT_U + x0 * tile) / CardArt.TEXTURE_WIDTH,
                (CardArt.FELT_V + z0 * tile) / CardArt.TEXTURE_HEIGHT,
                (CardArt.FELT_U + x1 * tile) / CardArt.TEXTURE_WIDTH,
                (CardArt.FELT_V + z1 * tile) / CardArt.TEXTURE_HEIGHT,
            )
            val c = floatArrayOf(0.5f + bx + (x0 + x1) / 2, 1.0005f, 0.5f + bz + (z0 + z1) / 2)
            side(buffer, pose, light, c, UP, 0f, EAST, (x1 - x0) / 2, NORTH, (z1 - z0) / 2, uv, color)
        }
    }

    /** A dark box in the middle of the table, its top edge lit on the side whose turn it is. */
    private fun drawDisplay(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, turn: Direction?) {
        val c = floatArrayOf(0.5f, 1f + DISPLAY_HEIGHT / 2, 0.5f)
        val w = DISPLAY_HALF_WIDTH
        val h = DISPLAY_HEIGHT / 2
        side(buffer, pose, light, c, UP, h, EAST, w, NORTH, w, EDGE, DISPLAY_COLOR)
        side(buffer, pose, light, c, SOUTH, w, EAST, w, UP, h, EDGE, DISPLAY_COLOR)
        side(buffer, pose, light, c, NORTH, w, WEST, w, UP, h, EDGE, DISPLAY_COLOR)
        side(buffer, pose, light, c, EAST, w, NORTH, w, UP, h, EDGE, DISPLAY_COLOR)
        side(buffer, pose, light, c, WEST, w, SOUTH, w, UP, h, EDGE, DISPLAY_COLOR)
        if (turn == null) return
        // A lamp strip along the top edge facing the player to act.
        val strip = floatArrayOf(
            0.5f + turn.stepX * (w - TURN_STRIP / 2),
            1f + DISPLAY_HEIGHT + 0.001f,
            0.5f + turn.stepZ * (w - TURN_STRIP / 2),
        )
        val halfX = if (turn.stepX == 0) w else TURN_STRIP / 2
        val halfZ = if (turn.stepZ == 0) w else TURN_STRIP / 2
        side(buffer, pose, FULL_BRIGHT, strip, UP, 0f, EAST, halfX, NORTH, halfZ, EDGE, TURN_COLOR)
    }

    /** The display's lines on each of its four sides, facing outwards, so every seat can read one. */
    private fun drawDisplayText(poseStack: PoseStack, collector: SubmitNodeCollector, lines: List<FormattedCharSequence>) {
        val font = Minecraft.getInstance().font
        val widest = lines.maxOf { font.width(it) }.coerceAtLeast(1)
        val scale = minOf(TEXT_SCALE, (DISPLAY_HALF_WIDTH * 2 - 0.04f) / widest)
        val top = -(lines.size * LINE_HEIGHT - 1) / 2f
        for (side in Direction.Plane.HORIZONTAL) {
            poseStack.pushPose()
            poseStack.translate(0.5f, 1f + DISPLAY_HEIGHT / 2, 0.5f)
            poseStack.mulPose(Axis.YP.rotation(atan2(side.stepX.toFloat(), side.stepZ.toFloat())))
            poseStack.translate(0f, 0f, DISPLAY_HALF_WIDTH + 0.002f)
            poseStack.scale(scale, -scale, scale)
            lines.forEachIndexed { i, line ->
                collector.submitText(
                    poseStack, -font.width(line) / 2f, top + i * LINE_HEIGHT, line, false,
                    Font.DisplayMode.POLYGON_OFFSET, FULL_BRIGHT, TEXT_COLOR, 0, 0,
                )
            }
            poseStack.popPose()
        }
    }

    /**
     * A card is a thin box: its face on the front, the chosen back behind, paper on the
     * four edges. (r, u, n) are the card's right, up and outward directions.
     */
    private fun drawCard(buffer: VertexConsumer, pose: PoseStack.Pose, light: Int, card: TableCard, back: Int) {
        val halfW = CARD_WIDTH * card.scale / 2
        val halfH = CARD_WIDTH * card.scale * CardArt.HEIGHT / CardArt.WIDTH / 2
        val halfT = CARD_THICKNESS * card.scale / 2
        val tilt = Math.toRadians(card.tilt.toDouble())
        val sin = sin(tilt).toFloat()
        val cos = cos(tilt).toFloat()
        // Right of the card as seen by someone reading it: the "up" direction turned clockwise.
        val r = floatArrayOf(-card.upZ, 0f, card.upX)
        val u = floatArrayOf(card.upX * cos, sin, card.upZ * cos)
        val n = floatArrayOf(-card.upX * sin, cos, -card.upZ * sin)
        // Card positions are measured from the middle of the table. A standing card rests on
        // its bottom edge; flat ones lift a little over the cards before them in their row.
        val c = floatArrayOf(
            card.x + 0.5f,
            1f + sin * halfH + cos * halfT + if (card.tilt == 0f) card.layer * LAYER_LIFT else 0f,
            card.z + 0.5f,
        )
        val neg = { v: FloatArray -> floatArrayOf(-v[0], -v[1], -v[2]) }
        val face = sprite(card.sprite)
        val backUv = sprite(back)
        side(buffer, pose, light, c, n, halfT, r, halfW, u, halfH, face)
        side(buffer, pose, light, c, neg(n), halfT, neg(r), halfW, u, halfH, backUv)
        side(buffer, pose, light, c, r, halfW, neg(n), halfT, u, halfH, EDGE)
        side(buffer, pose, light, c, neg(r), halfW, n, halfT, u, halfH, EDGE)
        side(buffer, pose, light, c, u, halfH, r, halfW, neg(n), halfT, EDGE)
        side(buffer, pose, light, c, neg(u), halfH, r, halfW, n, halfT, EDGE)
    }

    /** u0, v0, u1, v1 of a card in the atlas. */
    private fun sprite(sprite: Int): FloatArray {
        val u0 = CardArt.u(sprite).toFloat() / CardArt.TEXTURE_WIDTH
        val v0 = CardArt.v(sprite).toFloat() / CardArt.TEXTURE_HEIGHT
        return floatArrayOf(u0, v0, u0 + CardArt.WIDTH.toFloat() / CardArt.TEXTURE_WIDTH, v0 + CardArt.HEIGHT.toFloat() / CardArt.TEXTURE_HEIGHT)
    }

    /**
     * One face of a box centred on [c]: it lies [d] out along [normal] and spans [ha] along
     * [a] and [hb] along [b], where a × b = normal so it faces outwards. The texture's top
     * is towards +b.
     */
    private fun side(
        buffer: VertexConsumer,
        pose: PoseStack.Pose,
        light: Int,
        c: FloatArray,
        normal: FloatArray,
        d: Float,
        a: FloatArray,
        ha: Float,
        b: FloatArray,
        hb: Float,
        uv: FloatArray,
        color: Int = -1,
    ) {
        fun corner(sa: Float, sb: Float, tu: Float, tv: Float) {
            buffer.addVertex(
                pose,
                c[0] + normal[0] * d + a[0] * ha * sa + b[0] * hb * sb,
                c[1] + normal[1] * d + a[1] * ha * sa + b[1] * hb * sb,
                c[2] + normal[2] * d + a[2] * ha * sa + b[2] * hb * sb,
            )
                .setColor(color)
                .setUv(tu, tv)
                .setOverlay(OverlayTexture.NO_OVERLAY)
                .setLight(light)
                .setNormal(pose, normal[0], normal[1], normal[2])
        }
        corner(-1f, -1f, uv[0], uv[3])
        corner(1f, -1f, uv[2], uv[3])
        corner(1f, 1f, uv[2], uv[1])
        corner(-1f, 1f, uv[0], uv[1])
    }

    companion object {
        /** Dye colours are dark for cloth seen from above, so mix in a quarter of white. */
        private fun lighten(rgb: Int): Int {
            fun channel(shift: Int) = ((rgb shr shift and 0xFF) * 3 + 255) / 4 shl shift
            return channel(16) or channel(8) or channel(0)
        }

        /** Width of a card on the table at scale 1, in blocks. */
        private const val CARD_WIDTH = 0.3f

        /** Thickness of a card at scale 1, in blocks. */
        private const val CARD_THICKNESS = 0.008f

        /** How much higher each flat card in a row sits than the one before it. */
        private const val LAYER_LIFT = 0.002f

        /** The paper patch: one spot of plain colour, for card edges and anything tinted. */
        private val EDGE = floatArrayOf(
            CardArt.EDGE_U.toFloat() / CardArt.TEXTURE_WIDTH,
            CardArt.EDGE_V.toFloat() / CardArt.TEXTURE_HEIGHT,
            CardArt.EDGE_U.toFloat() / CardArt.TEXTURE_WIDTH,
            CardArt.EDGE_V.toFloat() / CardArt.TEXTURE_HEIGHT,
        )

        private val UP = floatArrayOf(0f, 1f, 0f)
        private val NORTH = floatArrayOf(0f, 0f, -1f)
        private val SOUTH = floatArrayOf(0f, 0f, 1f)
        private val EAST = floatArrayOf(1f, 0f, 0f)
        private val WEST = floatArrayOf(-1f, 0f, 0f)

        private const val ARGB_OPAQUE = 0xFF000000.toInt()
        private const val FULL_BRIGHT = 0xF000F0

        private const val DISPLAY_HALF_WIDTH = 0.35f
        private const val DISPLAY_HEIGHT = 0.2f
        private const val DISPLAY_COLOR = 0xFF33373D.toInt()
        private const val TURN_STRIP = 0.05f
        private const val TURN_COLOR = 0xFFFFC23A.toInt()
        private const val TEXT_COLOR = 0xFFFFE6A0.toInt()

        /** Blocks per font pixel at most; long lines shrink to fit the display. */
        private const val TEXT_SCALE = 0.0085f
        private const val LINE_HEIGHT = 10f

        /** Width of the bare wooden rim around the cloth, in blocks. */
        private const val RIM = 2f / 16f
    }
}
