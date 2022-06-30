package com.lambda.client.module.modules.render

import com.google.common.collect.EvictingQueue
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.manager.managers.TimerManager
import com.lambda.client.mixin.extension.renderPosX
import com.lambda.client.mixin.extension.renderPosY
import com.lambda.client.mixin.extension.renderPosZ
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.InfoCalculator
import com.lambda.client.util.Wrapper
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.GlStateUtils
import com.lambda.client.util.graphics.LambdaTessellator
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.threads.onMainThread
import com.lambda.client.util.threads.safeAsyncListener
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.runBlocking
import net.minecraft.client.renderer.vertex.DefaultVertexFormats
import net.minecraft.network.play.server.SPacketChunkData
import net.minecraft.network.play.server.SPacketUnloadChunk
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import org.lwjgl.opengl.GL11.GL_LINE_LOOP
import org.lwjgl.opengl.GL11.glLineWidth
import java.time.Instant
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToLong

object NewChunksPlus : Module(
    name = "NewChunksPlus",
    description = "NewChunks...but better",
    category = Category.RENDER,
    alias = arrayOf("NCP"),
    modulePriority = 10,
) {

    private enum class DetectionMode {
        PACKET, TIMER, BOTH
    }

    private val mode by setting("New Chunks Mode", DetectionMode.BOTH)
    private val range by setting("Render Range", 1500, 64..2048, 32, description = "Maximum range for chunks to be highlighted")
    private val packetChunkYOffset by setting("Packet Chunk Y Offset", 40, -10..256, 4, fineStep = 1, description = "Packet chunk render offset in Y axis")
    private val timerChunkYOffset by setting("Timer Chunk Y Offset", 50, -10..256, 4, fineStep = 1, description = "Timer chunk render offset in Y axis")
    private val maxNumber by setting("Max Number", 5000, 1000..10000, 500, description = "Maximum number of chunks to keep")
    private val packetChunkColor by setting("Packet Chunk Color", ColorHolder(255, 64, 64, 200), description = "Packet Chunks Highlighting color")
    private val timeChunkColor by setting("Timer Chunk Color", ColorHolder(64, 255, 64, 200), description = "Timer Chunks Highlighting color")
    private val thickness by setting("Thickness", 1.5f, 0.1f..4.0f, 0.1f, description = "Thickness of the highlighting square")
    private val timer by setting("Chunk timer constant in ms", -200, -300..300, 1, description = "A lower timer means chunks have less time to load before being marked")
    private val distanceFactor by setting("Chunk distance timer factor", 35, 0..100, 1, description = "Apply a multiplier to timer, closer chunks are loaded first")
    private val distanceExponent by setting("Chunk distance timer exponent", 1.6, 1.0..3.0, 0.1, description = "Apply an exponent to player distance from chunks")

    private val packetChunks = LinkedHashSet<ChunkPos>()
    private val timeChunks = LinkedHashSet<ChunkPos>()
    private val unloadChunkTimes = EvictingQueue.create<Long>(30)

    // todo: make time settings configurations update timechunks so you don't have to keep loading new chunks while messing with them
    //  store state variables along with all chunks loaded and recalculate on every render
    //  this can be a configurable state as might impact perf signficantly but it'll help while dialing in settings
    init {
        onDisable {
            runBlocking {
                onMainThread {
                    packetChunks.clear()
                    timeChunks.clear()
                    unloadChunkTimes.clear()
                }
            }
        }

        safeListener<RenderWorldEvent> {
            glLineWidth(thickness)
            GlStateUtils.depth(false)
            val buffer = LambdaTessellator.buffer
            buffer.setTranslation(
                -Wrapper.minecraft.renderManager.renderPosX,
                -Wrapper.minecraft.renderManager.renderPosY,
                -Wrapper.minecraft.renderManager.renderPosZ
            )

            if (mode == DetectionMode.PACKET || mode == DetectionMode.BOTH) {
                val y = packetChunkYOffset.toDouble()
                for (chunkPos in packetChunks) {
                    if (player.distanceTo(chunkPos) > range) continue

                    buffer.begin(GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR)
                    buffer.pos(chunkPos.xStart.toDouble(), y, chunkPos.zStart.toDouble()).color(packetChunkColor.r, packetChunkColor.g, packetChunkColor.b, packetChunkColor.a).endVertex()
                    buffer.pos(chunkPos.xEnd + 1.0, y, chunkPos.zStart.toDouble()).color(packetChunkColor.r, packetChunkColor.g, packetChunkColor.b, packetChunkColor.a).endVertex()
                    buffer.pos(chunkPos.xEnd + 1.0, y, chunkPos.zEnd + 1.0).color(packetChunkColor.r, packetChunkColor.g, packetChunkColor.b, packetChunkColor.a).endVertex()
                    buffer.pos(chunkPos.xStart.toDouble(), y, chunkPos.zEnd + 1.0).color(packetChunkColor.r, packetChunkColor.g, packetChunkColor.b, packetChunkColor.a).endVertex()
                    LambdaTessellator.render()
                }
            }

            if (mode == DetectionMode.TIMER || mode == DetectionMode.BOTH) {
                val y = timerChunkYOffset.toDouble()
                for (chunkPos in timeChunks) {
                    if (player.distanceTo(chunkPos) > range) continue

                    buffer.begin(GL_LINE_LOOP, DefaultVertexFormats.POSITION_COLOR)
                    buffer.pos(chunkPos.xStart.toDouble(), y, chunkPos.zStart.toDouble()).color(timeChunkColor.r, timeChunkColor.g, timeChunkColor.b, timeChunkColor.a).endVertex()
                    buffer.pos(chunkPos.xEnd + 1.0, y, chunkPos.zStart.toDouble()).color(timeChunkColor.r, timeChunkColor.g, timeChunkColor.b, timeChunkColor.a).endVertex()
                    buffer.pos(chunkPos.xEnd + 1.0, y, chunkPos.zEnd + 1.0).color(timeChunkColor.r, timeChunkColor.g, timeChunkColor.b, timeChunkColor.a).endVertex()
                    buffer.pos(chunkPos.xStart.toDouble(), y, chunkPos.zEnd + 1.0).color(timeChunkColor.r, timeChunkColor.g, timeChunkColor.b, timeChunkColor.a).endVertex()
                    LambdaTessellator.render()
                }
            }

            glLineWidth(1.0f)
            GlStateUtils.depth(true)
        }

        safeAsyncListener<PacketEvent.PostReceive> { event ->
            if (event.packet !is SPacketChunkData) return@safeAsyncListener
            val packet = event.packet
            if ((mode == DetectionMode.TIMER || mode == DetectionMode.BOTH) && packet.isFullChunk) {
                val receivedTime = Instant.now().toEpochMilli()
                val chunkPos = ChunkPos(packet.chunkX, packet.chunkZ)
                onMainThread {
                    val unloadChunkTime = unloadChunkTimes.poll() ?: return@onMainThread
                    if (receivedTime > unloadChunkTime + timerCalculate(chunkPos)) {
                        timeChunks.add(chunkPos)
                        if (timeChunks.size > maxNumber) {
                            timeChunks.maxByOrNull { player.distanceTo(it) }?.let {
                                timeChunks.remove(it)
                            }
                        }
                    }
                }
            }

            if ((mode == DetectionMode.PACKET || mode == DetectionMode.BOTH) && !packet.isFullChunk) {
                val chunk = world.getChunk(event.packet.chunkX, event.packet.chunkZ)
                onMainThread {
                    if (packetChunks.add(chunk.pos)) {
                        if (packetChunks.size > maxNumber) {
                            packetChunks.maxByOrNull { player.distanceTo(it) }?.let {
                                packetChunks.remove(it)
                            }
                        }
                    }
                }
            }
        }

        safeAsyncListener<PacketEvent.PostReceive> { event ->
            if (event.packet !is SPacketUnloadChunk) return@safeAsyncListener
            if (mode == DetectionMode.TIMER || mode == DetectionMode.BOTH) {
                onMainThread {
                    val now = Instant.now().toEpochMilli()
                    unloadChunkTimes.offer(now)
                }
            }
        }
    }

    private fun timerCalculate(chunk: ChunkPos) : Long {
        // using factor to apply the following parameters:
        // small distance to player -> lower timer
        // large distance to player -> higher timer
        // chunks closest to player are loaded first
        val chunkDistanceToPlayer = chunkDistanceToPlayer(chunk)
        return (timer.toLong() + (chunkDistanceToPlayer.pow(distanceExponent) * distanceFactor)).roundToLong()
    }

    private fun chunkDistanceToPlayer(chunk: ChunkPos): Double {
        val ping = InfoCalculator.ping() * 0.001 // seconds
        val xDiff = mc.player.posX - mc.player.prevPosX
        val zDiff = mc.player.posZ - mc.player.prevPosZ
        val tps = 1000.0 / TimerManager.tickLength
        val xSpeed = xDiff * tps
        val zSpeed = zDiff * tps
        val correctedChunkPos = ChunkPos(
            BlockPos(mc.player.posX - (xSpeed * ping), 0.0, mc.player.posZ - (zSpeed * ping)))

        return hypot((correctedChunkPos.x - (chunk.x)).toDouble(), (correctedChunkPos.z - (chunk.z)).toDouble())
    }
}
