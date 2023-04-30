package com.lambda.client.module.modules.render

import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.graphics.GeometryMasks
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.runSafe
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import net.minecraft.block.material.Material
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.Chunk
import net.minecraftforge.fml.common.gameevent.TickEvent

object TunnelESP : Module(
    name = "TunnelESP",
    description = "Finds tunnels around you",
    category = Category.RENDER) {

    private val yMin by setting("Y Min", 0, 0..255, 1)
    private val yMax by setting("Y Max", 128, 0..255, 1)
    private val delay by setting("Scan Delay (ticks)", 60, 0..200, 10)
    private val color by setting("Color", ColorHolder(0, 255, 0, 255))

    private val renderer: ESPRenderer = ESPRenderer()
    private val delayTimer: TickTimer = TickTimer(TimeUnit.TICKS)
    private var scanJob: Job? = null

    init {

        onEnable {
            renderer.clear()
            runSafe {
                scan()
            }
        }

        onDisable {
            renderer.clear()
        }

        safeListener<RenderWorldEvent> {
            renderer.aOutline = color.a
            renderer.render(false)
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.START) return@safeListener
            if (scanJob?.isActive == true) return@safeListener
            if (delayTimer.tick(delay.toLong())) {
                scan()
            }
        }
    }

    private fun SafeClientEvent.scan() {
        scanJob = defaultScope.launch {
            try {
                val renderDist = mc.gameSettings.renderDistanceChunks
                val playerChunkPos = ChunkPos(player.position)
                val chunkPos1 = ChunkPos(playerChunkPos.x - renderDist, playerChunkPos.z - renderDist)
                val chunkPos2 = ChunkPos(playerChunkPos.x + renderDist, playerChunkPos.z + renderDist)
                val result: ArrayList<BlockPos> = ArrayList()
                for (x in chunkPos1.x..chunkPos2.x) for (z in chunkPos1.z..chunkPos2.z) {
                    if (!isActive) return@launch
                    runSafe {
                        val chunk = world.getChunk(x, z)
                        if (!chunk.isLoaded) return@runSafe
                        result.addAll(scanChunk(chunk))
                    }
                }
                result.map { Triple(AxisAlignedBB(it), color, GeometryMasks.Quad.ALL) }.toMutableList().let {
                    renderer.replaceAll(it)
                }
                delayTimer.reset()
            } catch (e: Exception) {
                LambdaMod.LOG.error("Error while scanning for tunnels", e)
            }
        }
    }

    private fun SafeClientEvent.scanChunk(chunk: Chunk): ArrayList<BlockPos> {
        val yRange = yMin..yMax
        val xRange = (chunk.x shl 4)..(chunk.x shl 4) + 15
        val zRange = (chunk.z shl 4)..(chunk.z shl 4) + 15
        val blocks: ArrayList<BlockPos> = ArrayList()
        for (y in yRange) for (x in xRange) for (z in zRange) {
            val pos = BlockPos(x, y, z)
            if (isTunnelBlock(pos)) {
                blocks.add(pos)
                blocks.add(pos.up())
            }
        }
        return blocks
    }

    private fun SafeClientEvent.isTunnelBlock(pos: BlockPos): Boolean {
        if (!isAir(pos) || !isAir(pos.up())) return false;
        if (isAir(pos.down()) || isAir(pos.up().up())) return false;
        if (isAir(pos.north())
            && isAir(pos.south())
            && isAir(pos.up().north())
            && isAir(pos.up().south())) {
            return !(isAir(pos.east())
                || isAir(pos.west())
                || isAir(pos.up().east())
                || isAir(pos.up().west()))
        }
        if (isAir(pos.east())
            && isAir(pos.west())
            && isAir(pos.up().east())
            && isAir(pos.up().west())) {
            return !(isAir(pos.north())
                || isAir(pos.south())
                || isAir(pos.up().north())
                || isAir(pos.up().south()))
        }
        return false
    }

    private fun SafeClientEvent.isAir(pos: BlockPos): Boolean {
        return world.getBlockState(pos).material == Material.AIR
    }
}