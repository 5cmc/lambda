package com.lambda.client.gui.hudgui.elements.world

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.gui.hudgui.HudElement
import com.lambda.client.util.graphics.VertexHelper
import com.lambda.client.util.graphics.font.HAlign
import com.lambda.client.util.graphics.font.TextComponent
import com.lambda.client.util.threads.runSafe
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.util.math.MathHelper
import kotlin.math.cos
import kotlin.math.sin

internal object Compass : HudElement(
    name = "Compass",
    category = Category.WORLD,
    description = "Forgehax compass"
) {

    private val axis by setting("Axis", false)
    private val shadow by setting("Text Shadow", true)

    private enum class Direction(val axis: String) {
        N("-Z"),
        W("-X"),
        S("+Z"),
        E("+X")
    }

    override fun renderHud(vertexHelper: VertexHelper) {
        super.renderHud(vertexHelper)
        runSafe {
            drawAxes()
        }
    }

    private fun SafeClientEvent.drawAxes() {
        for (dir in Direction.values()) {
            val rad = getPosOnCompass(dir)
            GlStateManager.pushMatrix()
            GlStateManager.translate((renderWidth / 2) + getX(rad), getY(rad), 0.0)
            val textComponent = TextComponent()
            textComponent.add(
                text = if (axis) dir.axis else dir.name,
                color = if (dir == Direction.N) secondaryColor else primaryColor)
            textComponent.draw(horizontalAlign = HAlign.CENTER, drawShadow = shadow)
            GlStateManager.popMatrix()
        }
    }

    private fun SafeClientEvent.getX(rad: Double): Double {
        return sin(rad) * (scale.toDouble() * 10)
    }

    private fun SafeClientEvent.getY(rad: Double): Double {
        val pitchRadians = Math.toRadians(
            MathHelper.clamp(
                mc.renderViewEntity!!.rotationPitch + 30f,
                -90f,
                90f)
                .toDouble())
        return cos(rad) * sin(pitchRadians) * (scale.toDouble() * 10)
    }

    private fun SafeClientEvent.getPosOnCompass(dir: Direction): Double {
        val yaw = Math.toRadians(MathHelper.wrapDegrees(mc.renderViewEntity!!.rotationYaw).toDouble())
        val index = dir.ordinal
        return yaw + (index * Math.PI / 2)
    }
}