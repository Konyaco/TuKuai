package io.github.konyaco.tukuai.compat

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.Options
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import org.joml.Matrix4f

// Client-only shims; nothing on a dedicated server touches this file.

typealias GuiGraphicsExtractor = net.minecraft.client.gui.GuiGraphicsExtractor

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
abstract class CompatScreen(title: Component) : Screen(title) {
    protected val mc: Minecraft get() = minecraft
}

/** Hides and holds the cursor ([org.lwjgl.glfw.GLFW.GLFW_CURSOR_DISABLED]) or shows it again at ([x], [y]). */
fun grabMouse(minecraft: Minecraft, mode: Int, x: Double, y: Double) {
    InputConstants.grabOrReleaseMouse(minecraft.window, mode, x, y)
}

fun invertMouseX(options: Options): Boolean = options.invertMouseX().get()

fun invertMouseY(options: Options): Boolean = options.invertMouseY().get()

/** The camera's rotation and projection, with the camera at the origin. */
fun Camera.viewRotationProjection(dest: Matrix4f): Matrix4f = getViewRotationProjectionMatrix(dest)
