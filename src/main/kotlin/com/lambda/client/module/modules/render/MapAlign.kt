package com.lambda.client.module.modules.render

import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.mixin.extension.renderPosX
import com.lambda.client.mixin.extension.renderPosY
import com.lambda.client.mixin.extension.renderPosZ
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.Wrapper
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.GlStateUtils
import com.lambda.client.util.graphics.LambdaTessellator
import com.lambda.client.util.threads.safeListener
import net.minecraft.client.renderer.GlStateManager.glLineWidth
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import org.lwjgl.opengl.GL11.GL_LINE_LOOP
import kotlin.math.floor

object MapAlign : Module(
    name = "MapAlign",
    description = "Renders where maps will be created",
    category = Category.RENDER,
) {
    private val yLevel by setting("Y Level", 0, 0..256, 1)
    private val color by setting("Color", ColorHolder(0, 0, 255, 200), true)

    init {
        safeListener<RenderWorldEvent> {
            glLineWidth(1.0f)
            GlStateUtils.depth(false)
            val buffer = LambdaTessellator.buffer
            buffer.setTranslation(
                -Wrapper.minecraft.renderManager.renderPosX,
                -Wrapper.minecraft.renderManager.renderPosY,
                -Wrapper.minecraft.renderManager.renderPosZ
            )
            val minX = (floor(player.posX + if (player.posX > 0) 64 else -64).toInt() / 128) * 128 - 64
            val minZ = (floor(player.posZ + if (player.posZ > 0) 64 else -64).toInt() / 128) * 128 - 64
            buffer.begin(GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR)
            buffer.pos(minX.toDouble(), yLevel.toDouble(), minZ.toDouble()).color(color.r, color.g, color.b, color.a).endVertex()
            buffer.pos(minX.toDouble(), yLevel.toDouble(), (minZ + 128).toDouble()).color(color.r, color.g, color.b, color.a).endVertex()
            buffer.pos((minX + 128).toDouble(), yLevel.toDouble(), (minZ + 128).toDouble()).color(color.r, color.g, color.b, color.a).endVertex()
            buffer.pos((minX + 128).toDouble(), yLevel.toDouble(), minZ.toDouble()).color(color.r, color.g, color.b, color.a).endVertex()
            LambdaTessellator.render()
            GlStateUtils.depth(true)
        }
    }
}