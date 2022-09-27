package com.lambda.client.module.modules.render

import com.lambda.client.command.CommandManager
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.setting.settings.impl.collection.CollectionSetting
import com.lambda.client.util.TickTimer
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.graphics.GeometryMasks
import com.lambda.client.util.graphics.ShaderHelper
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.text.formatValue
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeAsyncListener
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.block.state.IBlockState
import net.minecraft.init.Blocks
import net.minecraft.network.play.server.SPacketBlockChange
import net.minecraft.network.play.server.SPacketMultiBlockChange
import net.minecraft.util.math.AxisAlignedBB
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.Chunk
import net.minecraftforge.event.world.ChunkEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import kotlin.collections.set
import kotlin.math.max

object Search : Module(
    name = "Search",
    description = "Highlights blocks in the world",
    category = Category.RENDER
) {
    private val defaultSearchList = linkedSetOf("minecraft:portal", "minecraft:end_portal_frame", "minecraft:bed")

    private val updateDelay by setting("Update Delay", 1000, 500..3000, 50)
    private val range by setting("Search Range", 1000, 0..4096, 8)
    private val yRangeBottom by setting("Top Y", 256, 0..256, 1)
    private val yRangeTop by setting("Bottom Y", 0, 0..256, 1)
    private val maximumBlocks by setting("Maximum Blocks", 256, 16..4096, 128)
    private val filled by setting("Filled", true)
    private val outline by setting("Outline", true)
    private val tracer by setting("Tracer", true)
    private val customColors by setting("Custom Colors", false)
    private val customColor by setting("Custom Color", ColorHolder(155, 144, 255), visibility = { customColors })
    private val aFilled by setting("Filled Alpha", 31, 0..255, 1, { filled })
    private val aOutline by setting("Outline Alpha", 127, 0..255, 1, { outline })
    private val aTracer by setting("Tracer Alpha", 200, 0..255, 1, { tracer })
    private val thickness by setting("Line Thickness", 2.0f, 0.25f..5.0f, 0.25f)

    var overrideWarning by setting("Override Warning", false, { false })
    val searchList = setting(CollectionSetting("Search List", defaultSearchList, { false }))

    private val renderer = ESPRenderer()
    private val updateTimer = TickTimer()
    private val foundBlockMap: ConcurrentMap<BlockPos, IBlockState> = ConcurrentHashMap()

    override fun getHudInfo(): String {
        return renderer.size.toString()
    }

    init {
        searchList.editListeners.add {
            foundBlockMap.entries
                .filterNot { searchList.contains(it.value.block.registryName.toString()) }
                .forEach { foundBlockMap.remove(it.key) }
            if (isEnabled) searchAllLoadedChunks()
        }

        onEnable {
            if (!overrideWarning && ShaderHelper.isIntegratedGraphics) {
                MessageSendHelper.sendErrorMessage("$chatName Warning: Running Search with an Intel Integrated GPU is not recommended, as it has a &llarge&r impact on performance.")
                MessageSendHelper.sendWarningMessage("$chatName If you're sure you want to try, run the ${formatValue("${CommandManager.prefix}search override")} command")
                disable()
                return@onEnable
            }
            searchAllLoadedChunks()
        }

        onDisable {
            renderer.clear()
            foundBlockMap.clear()
        }

        safeListener<RenderWorldEvent> {
            renderer.render(false)

            if (updateTimer.tick(updateDelay.toLong())) {
                updateRenderer()
            }
        }

        safeAsyncListener<ChunkEvent.Load> {
            val foundBlocksInChunk = findBlocksInChunk(it.chunk)
            foundBlocksInChunk.forEach { block -> foundBlockMap[block.first] = block.second }
        }

        safeAsyncListener<PacketEvent.Receive> {
            if (it.packet is SPacketMultiBlockChange) {
                it.packet.changedBlocks.forEach { changedBlock -> handleBlockChange(changedBlock.pos, changedBlock.blockState) }
            }
            if (it.packet is SPacketBlockChange) {
                handleBlockChange(it.packet.blockPosition, it.packet.getBlockState())
            }
        }
    }

    private fun searchAllLoadedChunks() {
        val renderDist = mc.gameSettings.renderDistanceChunks
        val playerChunkPos = ChunkPos(mc.player.position)
        val chunkPos1 = ChunkPos(playerChunkPos.x - renderDist, playerChunkPos.z - renderDist)
        val chunkPos2 = ChunkPos(playerChunkPos.x + renderDist, playerChunkPos.z + renderDist)

        defaultScope.launch {
            coroutineScope {
                for (x in chunkPos1.x..chunkPos2.x) for (z in chunkPos1.z..chunkPos2.z) {
                    val chunk = mc.world.getChunk(x, z)
                    if (!chunk.isLoaded) continue

                    launch {
                        findBlocksInChunk(chunk).forEach { pair -> foundBlockMap[pair.first] = pair.second }
                    }
                    delay(1L)
                }
            }
        }
    }

    private fun handleBlockChange(pos: BlockPos, state: IBlockState) {
        if (searchQuery(state)) {
            foundBlockMap[pos] = state
        }
    }

    private fun SafeClientEvent.updateRenderer() {
        defaultScope.launch {
            val playerPos = mc.player.position
            // unload rendering on block pos > range
            foundBlockMap
                .filter { entry -> playerPos.distanceTo(entry.key) > max(mc.gameSettings.renderDistanceChunks * 16, range) }
                .map { entry -> entry.key }
                .forEach { pos -> foundBlockMap.remove(pos) }
            val eyePos = player.getPositionEyes(1f)
            val sortedFoundBlocks = foundBlockMap
                .map { entry -> (eyePos.distanceTo(entry.key) to entry.key) }
                .filter { pair -> pair.first < range }
                .toList()

            updateAlpha()

            val renderList = ArrayList<Triple<AxisAlignedBB, ColorHolder, Int>>()
            val sides = GeometryMasks.Quad.ALL

            sortedFoundBlocks.forEachIndexed { index, pair ->
                if (index >= maximumBlocks) return@forEachIndexed
                val bb = foundBlockMap[pair.second]!!.getSelectedBoundingBox(world, pair.second)
                val color = getBlockColor(pair.second, foundBlockMap[pair.second]!!)

                renderList.add(Triple(bb, color, sides))
            }

            renderer.replaceAll(renderList)
        }
    }

    private fun updateAlpha() {
        renderer.aFilled = if (filled) aFilled else 0
        renderer.aOutline = if (outline) aOutline else 0
        renderer.aTracer = if (tracer) aTracer else 0
        renderer.thickness = thickness
    }

    private fun findBlocksInChunk(chunk: Chunk): ArrayList<Pair<BlockPos, IBlockState>> {
        val yRange = yRangeTop..yRangeBottom
        val xRange = (chunk.x shl 4)..(chunk.x shl 4) + 15
        val zRange = (chunk.z shl 4)..(chunk.z shl 4) + 15

        val blocks: ArrayList<Pair<BlockPos, IBlockState>> = ArrayList()
        for (y in yRange) for (x in xRange) for (z in zRange) {
            val pos = BlockPos(x, y, z)
            val blockState = chunk.getBlockState(pos)
            if (searchQuery(blockState)) blocks.add((pos to blockState))
        }
        return blocks
    }

    private fun searchQuery(state: IBlockState): Boolean {
        val block = state.block
        if (block == Blocks.AIR) return false
        return searchList.contains(block.registryName.toString())
    }

    private fun SafeClientEvent.getBlockColor(pos: BlockPos, blockState: IBlockState): ColorHolder {
        val block = blockState.block

        return if (!customColors) {
            if (block == Blocks.PORTAL) {
                ColorHolder(82, 49, 153)
            } else {
                val colorInt = blockState.getMapColor(world, pos).colorValue
                ColorHolder((colorInt shr 16), (colorInt shr 8 and 255), (colorInt and 255))
            }
        } else {
            customColor
        }
    }

}