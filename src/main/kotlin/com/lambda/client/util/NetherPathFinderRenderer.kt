package com.lambda.client.util

import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.GLAllocation
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.OpenGlHelper
import net.minecraft.entity.Entity
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.Vec3d
import net.minecraftforge.client.event.RenderWorldLastEvent
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder

class NetherPathFinderRenderer(path: List<BlockPos>){

    private var path: List<BlockPos>? = null

    private var bufferId = 0
    private var numVertices = 0

    init {
        this.path = path
        val floatSize = 4
        val vertexSize = floatSize * 3
        val buffer = GLAllocation.createDirectByteBuffer(path.size * vertexSize)
        for (pos in path) {
            buffer.putFloat(pos.x.toFloat())
            buffer.putFloat(pos.y.toFloat())
            buffer.putFloat(pos.z.toFloat())
        }
        (buffer as Buffer).rewind() // stupid no method error
        bufferId = uploadBuffer(buffer)
        numVertices = path.size
    }


    private fun uploadBuffer(buffer: ByteBuffer): Int {
        val ints = ByteBuffer.allocateDirect(4).order(ByteOrder.nativeOrder()).asIntBuffer()
        GL15.glGenBuffers(ints)
        val id = ints[0]
        OpenGlHelper.glBindBuffer(GL15.GL_ARRAY_BUFFER, id)
        OpenGlHelper.glBufferData(GL15.GL_ARRAY_BUFFER, buffer, GL15.GL_STATIC_DRAW)
        OpenGlHelper.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        return id
    }

    fun getInterpolatedAmount(entity: Entity?, ticks: Double): Vec3d? {
        return Vec3d(
            (entity!!.posX - entity.lastTickPosX) * ticks,
            (entity.posY - entity.lastTickPosY) * ticks,
            (entity.posZ - entity.lastTickPosZ) * ticks)
    }

    private fun interpolatedPos(entity: Entity?, partialTicks: Float): Vec3d {
        return Vec3d(entity!!.lastTickPosX, entity.lastTickPosY, entity.lastTickPosZ)
            .add(getInterpolatedAmount(entity, partialTicks.toDouble()))
    }

    private fun isInNether(): Boolean {
        return Minecraft.getMinecraft().player.dimension == -1
    }


    private fun getTranslation(partialTicks: Float): Vec3d {
        val renderEntity = Minecraft.getMinecraft().renderViewEntity
        return interpolatedPos(renderEntity, partialTicks)
    }

    fun preRender() {
        GlStateManager.pushMatrix()
        GlStateManager.disableTexture2D()
        GlStateManager.enableBlend()
        GlStateManager.disableAlpha()
        GlStateManager.tryBlendFuncSeparate(GL11.GL_SRC_ALPHA, GL11.GL_ONE_MINUS_SRC_ALPHA, 1, 0)
        GlStateManager.shadeModel(GL11.GL_SMOOTH)
        GlStateManager.disableDepth()
    }

    fun postRender() {
        GlStateManager.shadeModel(GL11.GL_FLAT)
        GlStateManager.disableBlend()
        GlStateManager.enableAlpha()
        GlStateManager.enableTexture2D()
        GlStateManager.enableDepth()
        GlStateManager.enableCull()
        GlStateManager.popMatrix()
    }

    private fun drawLine(bufferId: Int, numVertices: Int, partialTicks: Float) {
        GlStateManager.color(0f, 0f, 1f)
        val translation = getTranslation(partialTicks)
        GlStateManager.translate(-translation.x, -translation.y, -translation.z) // TODO: this probably doesnt have to be done in 1.13+
        OpenGlHelper.glBindBuffer(GL15.GL_ARRAY_BUFFER, bufferId)
        GlStateManager.glEnableClientState(GL11.GL_VERTEX_ARRAY)
        GlStateManager.glVertexPointer(3, GL11.GL_FLOAT, 0, 0)
        GlStateManager.glDrawArrays(GL11.GL_LINE_STRIP, 0, numVertices)

        // post draw
        OpenGlHelper.glBindBuffer(GL15.GL_ARRAY_BUFFER, 0)
        GlStateManager.glDisableClientState(GL11.GL_VERTEX_ARRAY)
        GlStateManager.resetColor() // probably not needed
    }

    @SubscribeEvent
    fun onRender(event: RenderWorldLastEvent) {
        if (!isInNether()) return
        preRender()
        GlStateManager.glLineWidth(1f)
        drawLine(bufferId, numVertices, event.partialTicks)
        postRender()
    }

    // Must be called before throwing away this renderer
    fun deleteBuffer() {
        OpenGlHelper.glDeleteBuffers(bufferId)
    }
}