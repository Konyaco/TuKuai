package io.github.konyaco.tukuai.compat

import com.mojang.blaze3d.platform.InputConstants
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.Font
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.util.FormattedCharSequence
import org.joml.Matrix4f
import org.joml.Quaternionf

// Client-only shims; nothing on a dedicated server touches this file.

//? if >=26.1 {
typealias GuiGraphicsExtractor = net.minecraft.client.gui.GuiGraphicsExtractor
//?} else {
/*/** 26.1 renamed GuiGraphics, and its text methods, as it moved to extracting what to draw. */
typealias GuiGraphicsExtractor = net.minecraft.client.gui.GuiGraphics

fun GuiGraphicsExtractor.text(font: Font, text: Component, x: Int, y: Int, color: Int, shadow: Boolean = true) =
    drawString(font, text, x, y, color, shadow)

fun GuiGraphicsExtractor.text(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int, shadow: Boolean = true) =
    drawString(font, text, x, y, color, shadow)

fun GuiGraphicsExtractor.text(font: Font, text: String, x: Int, y: Int, color: Int, shadow: Boolean = true) =
    drawString(font, text, x, y, color, shadow)

fun GuiGraphicsExtractor.centeredText(font: Font, text: Component, x: Int, y: Int, color: Int) = drawCenteredString(font, text, x, y, color)

fun GuiGraphicsExtractor.centeredText(font: Font, text: FormattedCharSequence, x: Int, y: Int, color: Int) = drawCenteredString(font, text, x, y, color)

fun GuiGraphicsExtractor.centeredText(font: Font, text: String, x: Int, y: Int, color: Int) = drawCenteredString(font, text, x, y, color)
*///?}

typealias KeyEvent = net.minecraft.client.input.KeyEvent

typealias MouseButtonEvent = net.minecraft.client.input.MouseButtonEvent

/**
 * Draws the [regionWidth] by [regionHeight] pixels at ([u], [v]) of a [textureWidth] by
 * [textureHeight] texture as a [width] by [height] rectangle at ([x], [y]).
 */
fun GuiGraphicsExtractor.blitTexture(
    texture: Identifier,
    x: Int,
    y: Int,
    u: Float,
    v: Float,
    width: Int,
    height: Int,
    regionWidth: Int,
    regionHeight: Int,
    textureWidth: Int,
    textureHeight: Int,
) = blit(RenderPipelines.GUI_TEXTURED, texture, x, y, u, v, width, height, regionWidth, regionHeight, textureWidth, textureHeight)

/** A screen; older Minecraft versions give theirs 26.1's drawing and input methods. */
//? if >=26.1 {
abstract class CompatScreen(title: Component) : Screen(title) {
    protected val mc: Minecraft get() = minecraft
}
//?} else {
/*abstract class CompatScreen(title: Component) : Screen(title) {
    protected val mc: Minecraft get() = minecraft

    /** 26.1's renderBackground: draws what lies behind the screen. */
    open fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) =
        super.renderBackground(g, mouseX, mouseY, partialTick)

    /** 26.1's render: draws the screen's widgets; screens draw their own things first and then call this. */
    open fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) =
        super.render(g, mouseX, mouseY, partialTick)

    final override fun renderBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) =
        extractBackground(g, mouseX, mouseY, partialTick)

    final override fun render(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) =
        extractRenderState(g, mouseX, mouseY, partialTick)
}
*///?}

/** Hides and holds the cursor when [grab], or shows it again at ([x], [y]). */
fun grabMouse(minecraft: Minecraft, grab: Boolean, x: Double, y: Double) {
    //? if >=26.3 {
    if (grab) InputConstants.grabMouse(minecraft.window, x, y) else InputConstants.releaseMouse(minecraft.window, x, y)
    //?} else
    //InputConstants.grabOrReleaseMouse(minecraft.window, if (grab) InputConstants.CURSOR_DISABLED else InputConstants.CURSOR_NORMAL, x, y)
}

fun invertMouseX(options: Options): Boolean = options.invertMouseX().get()

fun invertMouseY(options: Options): Boolean = options.invertMouseY().get()

/** Draws with [draw] over everything drawn so far on the screen, its widgets too. */
fun GuiGraphicsExtractor.drawOnTop(draw: () -> Unit) {
    nextStratum()
    draw()
}

/** The camera's rotation and projection, with the camera at the origin. */
//? if >=26.1 {
fun Camera.viewRotationProjection(dest: Matrix4f): Matrix4f = getViewRotationProjectionMatrix(dest)
//?} else {
/*fun Camera.viewRotationProjection(dest: Matrix4f): Matrix4f {
    // Before 26.1 the camera keeps no projection, so work it out as the game does: the field
    // of view setting scaled by the player's own modifier, such as for sprinting.
    val minecraft = Minecraft.getInstance()
    val options = minecraft.options
    val modifier = minecraft.player?.getFieldOfViewModifier(options.cameraType.isFirstPerson, options.fovEffectScale().get().toFloat()) ?: 1f
    val fov = options.fov().get() * modifier
    return dest.set(minecraft.gameRenderer.getProjectionMatrix(fov)).rotate(Quaternionf(rotation()).conjugate())
}
*///?}

// 26.2 moved the open screen and the loading overlay from Minecraft to its Gui.
//? if >=26.2 {
/** The screen open now, or null in the world. */
val Minecraft.currentScreen: Screen? get() = gui.screen()

/** Opens [screen], or goes back to the world for null. */
fun Minecraft.openScreen(screen: Screen?) = gui.setScreen(screen)

/** Whether the loading overlay still covers the game. */
val Minecraft.isLoading: Boolean get() = gui.overlay() != null

/** The camera the world is drawn from. */
val Minecraft.worldCamera: Camera get() = gameRenderer.mainCamera()
//?} else {
/*/** The screen open now, or null in the world. */
val Minecraft.currentScreen: Screen? get() = screen

/** Opens [screen], or goes back to the world for null. */
fun Minecraft.openScreen(screen: Screen?) = setScreen(screen)

/** Whether the loading overlay still covers the game. */
val Minecraft.isLoading: Boolean get() = overlay != null

/** The camera the world is drawn from. */
val Minecraft.worldCamera: Camera get() = gameRenderer.mainCamera
*///?}

//? if <26.3 {
/*/** Turns the pose by [rotation]; 26.3 has this as PoseStack's own rotate. */
fun PoseStack.rotate(rotation: Quaternionf) = mulPose(rotation)
*///?}
