package com.lambda.client.module.modules.render

import com.lambda.client.command.CommandManager
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.ChunkDataEvent
import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.setting.settings.impl.collection.CollectionSetting
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
import com.lambda.client.util.world.isWater
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.block.BlockEnderChest
import net.minecraft.block.BlockShulkerBox
import net.minecraft.block.state.IBlockState
import net.minecraft.entity.EntityList
import net.minecraft.entity.item.EntityItem
import net.minecraft.entity.item.EntityItemFrame
import net.minecraft.init.Blocks
import net.minecraft.network.play.server.SPacketBlockChange
import net.minecraft.network.play.server.SPacketMultiBlockChange
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.chunk.Chunk
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentMap
import java.util.concurrent.locks.Lock
import java.util.concurrent.locks.ReentrantLock
import kotlin.collections.set
import kotlin.math.max

object Search : Module(
    name = "Search",
    description = "Highlights blocks in the world",
    category = Category.RENDER
) {
    private val defaultSearchList = linkedSetOf("minecraft:portal", "minecraft:end_portal_frame", "minecraft:bed")

    private val entitySearch by setting("Entity Search", true)
    private val blockSearch by setting("Block Search", true)
    private val illegalBedrock = setting("Illegal Bedrock", false)
    private val illegalNetherWater = setting("Illegal Nether Water", false)
    private val range by setting("Search Range", 512, 0..4096, 8)
    private val yRangeBottom by setting("Top Y", 256, 0..256, 1)
    private val yRangeTop by setting("Bottom Y", 0, 0..256, 1)
    private val maximumBlocks by setting("Maximum Blocks", 256, 1..4096, 128, visibility = { blockSearch })
    private val maximumEntities by setting("Maximum Entities", 256, 1..4096, 128, visibility = { entitySearch })
    private val filled by setting("Filled", true)
    private val outline by setting("Outline", true)
    private val tracer by setting("Tracer", true)
    private val entitySearchColor by setting("Entity Search Color", ColorHolder(155, 144, 255), visibility = { entitySearch })
    private val autoBlockColor by setting("Block Search Auto Color", true)
    private val customBlockColor by setting("Block Search Custom Color", ColorHolder(155, 144, 255), visibility = { !autoBlockColor })
    private val aFilled by setting("Filled Alpha", 31, 0..255, 1, { filled })
    private val aOutline by setting("Outline Alpha", 127, 0..255, 1, { outline })
    private val aTracer by setting("Tracer Alpha", 200, 0..255, 1, { tracer })
    private val thickness by setting("Line Thickness", 2.0f, 0.25f..5.0f, 0.25f)
    private val hideF1 by setting("Hide on F1", true)

    var overrideWarning by setting("Override Warning", false, { false })
    val blockSearchList = setting(CollectionSetting("Search List", defaultSearchList, { false }))
    val entitySearchList = setting(CollectionSetting("Entity Search List", linkedSetOf(EntityList.getKey((EntityItemFrame::class.java))!!.path), { false }))
    val blockSearchDimensionFilter = setting(CollectionSetting("Block Dimension Filter", linkedSetOf(DimensionFilter(Blocks.OBSIDIAN.registryName.toString(), linkedSetOf(-1))), { false }))
    val entitySearchDimensionFilter = setting(CollectionSetting("Entity Dimension Filter", linkedSetOf(DimensionFilter(EntityList.getKey((EntityItem::class.java))!!.path, linkedSetOf(-1))), { false }))

    private val blockRenderer = ESPRenderer()
    private val entityRenderer = ESPRenderer()
    private val foundBlockMap: ConcurrentMap<BlockPos, IBlockState> = ConcurrentHashMap()
    private var blockSearchJob: Job? = null
    private var entitySearchJob: Job? = null
    private var prevDimension = -2

    override fun getHudInfo(): String {
        return (blockRenderer.size + entityRenderer.size).toString()
    }

    init {
        blockSearchList.editListeners.add { blockSearchListUpdateListener(isEnabled) }
        illegalBedrock.listeners.add { blockSearchListUpdateListener(illegalBedrock.value) }
        illegalNetherWater.listeners.add { blockSearchListUpdateListener(illegalNetherWater.value) }

        onEnable {
            if (!overrideWarning && ShaderHelper.isIntegratedGraphics) {
                MessageSendHelper.sendErrorMessage("$chatName Warning: Running Search with an Intel Integrated GPU is not recommended, as it has a &llarge&r impact on performance.")
                MessageSendHelper.sendWarningMessage("$chatName If you're sure you want to try, run the ${formatValue("${CommandManager.prefix}search override")} command")
                disable()
            } else {
                searchAllLoadedChunks()
            }
        }

        onDisable {
            blockRenderer.clear()
            foundBlockMap.clear()
        }

        safeListener<RenderWorldEvent> {
            if (player.dimension != prevDimension) {
                prevDimension = player.dimension
                foundBlockMap.clear()
            }
            if (blockSearch) {
                if (!(hideF1 && mc.gameSettings.hideGUI)) {
                    blockRenderer.render(false)
                }
            }
            if (entitySearch) {
                if (!(hideF1 && mc.gameSettings.hideGUI)) {
                    entityRenderer.render(false)
                }
            }
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (blockSearchJob == null || blockSearchJob?.isCompleted == true) {
                blockSearchJob = defaultScope.launch {
                    blockRenderUpdate()
                }
            }
            if (entitySearchJob == null || entitySearchJob?.isCompleted == true) {
                entitySearchJob = defaultScope.launch {
                    searchLoadedEntities()
                }
            }
        }

        safeAsyncListener<ChunkDataEvent> {
            val foundBlocksInChunk = findBlocksInChunk(it.chunk)
            foundBlocksInChunk.forEach { block -> foundBlockMap[block.first] = block.second }
        }

        safeListener<PacketEvent.Receive> {
            if (it.packet is SPacketMultiBlockChange) {
                it.packet.changedBlocks.forEach { changedBlock -> handleBlockChange(changedBlock.pos, changedBlock.blockState) }
            }
            if (it.packet is SPacketBlockChange) {
                handleBlockChange(it.packet.blockPosition, it.packet.getBlockState())
            }
        }

        safeAsyncListener<ConnectionEvent.Disconnect> {
            if (isEnabled) {
                blockRenderer.clear()
                entityRenderer.clear()
                foundBlockMap.clear()
            }
        }
    }

    private fun blockSearchListUpdateListener(newBool: Boolean) {
        foundBlockMap.entries
            .filterNot { blockSearchList.contains(it.value.block.registryName.toString()) }
            .forEach { foundBlockMap.remove(it.key) }
        if (newBool) searchAllLoadedChunks()
    }

    private fun searchLoadedEntities() {
        val renderList = mc.world.getLoadedEntityList()
            .filter {
                val entityName: String? = EntityList.getKey(it)?.path
                return@filter if (entityName != null) entitySearchList.contains(entityName) else false
            }
            .filter {
                val entityName: String = EntityList.getKey(it)?.path!!
                val dims = entitySearchDimensionFilter.value.find { dimFilter -> dimFilter.searchKey == entityName }?.dim
                return@filter dims?.contains(mc.player.dimension) ?: true
            }
            .sortedBy { it.distanceTo(mc.player.getPositionEyes(1f)) }
            .take(maximumEntities)
            .filter { it.distanceTo(mc.player.getPositionEyes(1f)) < range }
            .toMutableList()
        entityRenderer.clear()
        renderList.forEach { entityRenderer.add(it, entitySearchColor) }
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
                    delay(500L)
                }
            }
        }
    }

    private fun handleBlockChange(pos: BlockPos, state: IBlockState) {
        if (searchQuery(state, pos)) {
            foundBlockMap[pos] = state
        } else {
            foundBlockMap.remove(pos)
        }
    }

    private fun SafeClientEvent.blockRenderUpdate() {
        val playerPos = mc.player.position
        // unload rendering on block pos > range
        foundBlockMap
            .filter { entry -> playerPos.distanceTo(entry.key) > max(mc.gameSettings.renderDistanceChunks * 16, range) }
            .map { entry -> entry.key }
            .forEach { pos -> foundBlockMap.remove(pos) }
        val eyePos = player.getPositionEyes(1f)
        val sortedFoundBlocks = foundBlockMap
            .filter {
                val filterEntry = blockSearchDimensionFilter.value
                    .find { dimFilter -> dimFilter.searchKey == it.value.block.registryName.toString() }?.dim
                return@filter filterEntry?.contains(mc.player.dimension) ?: true
            }
            .map { entry -> (eyePos.distanceTo(entry.key) to entry.key) }
            .filter { pair -> pair.first < range }
            .toList()

        updateAlpha()

        val renderList = sortedFoundBlocks
            .take(maximumBlocks)
            .map { pair ->
                try {
                    // concurrency could cause foundBlockMap value to no longer be in map when we get here
                    // todo: create a lock on this method?
                    val bb = foundBlockMap[pair.second]!!.getSelectedBoundingBox(world, pair.second)
                    val color = getBlockColor(pair.second, foundBlockMap[pair.second]!!)

                    return@map Triple(bb, color, GeometryMasks.Quad.ALL)
                } catch (ex: Exception) {
                    // fall through
                    return@map null
                }
            }
            .filterNotNull()
            .toMutableList()
        blockRenderer.replaceAll(renderList)
    }

    private fun updateAlpha() {
        blockRenderer.aFilled = if (filled) aFilled else 0
        blockRenderer.aOutline = if (outline) aOutline else 0
        blockRenderer.aTracer = if (tracer) aTracer else 0
        blockRenderer.thickness = thickness
        entityRenderer.aFilled = if (filled) aFilled else 0
        entityRenderer.aOutline = if (outline) aOutline else 0
        entityRenderer.aTracer = if (tracer) aTracer else 0
        entityRenderer.thickness = thickness
    }

    private fun findBlocksInChunk(chunk: Chunk): ArrayList<Pair<BlockPos, IBlockState>> {
        val yRange = yRangeTop..yRangeBottom
        val xRange = (chunk.x shl 4)..(chunk.x shl 4) + 15
        val zRange = (chunk.z shl 4)..(chunk.z shl 4) + 15

        val blocks: ArrayList<Pair<BlockPos, IBlockState>> = ArrayList()
        for (y in yRange) for (x in xRange) for (z in zRange) {
            val pos = BlockPos(x, y, z)
            val blockState = chunk.getBlockState(pos)
            if (searchQuery(blockState, pos)) blocks.add((pos to blockState))
        }
        return blocks
    }

    private fun searchQuery(state: IBlockState, pos: BlockPos): Boolean {
        val block = state.block
        if (block == Blocks.AIR) return false
        return blockSearchList.contains(block.registryName.toString())
            || isIllegalBedrock(state, pos)
            || isIllegalWater(state, pos)
    }

    private fun isIllegalBedrock(state: IBlockState, pos: BlockPos): Boolean {
        if (!illegalBedrock.value) return false
        val block = state.block
        if (block != Blocks.BEDROCK) return false
        return when (mc.player.dimension) {
            0 -> {
                pos.y >= 5
            }
            -1 -> {
                pos.y in 5..122
            }
            else -> {
                false
            }
        }
    }

    private fun isIllegalWater(state: IBlockState, pos: BlockPos): Boolean {
        if (!illegalNetherWater.value) return false
        return (mc.player.dimension == -1 && state.isWater)
    }

    private fun SafeClientEvent.getBlockColor(pos: BlockPos, blockState: IBlockState): ColorHolder {
        val block = blockState.block
        return if (autoBlockColor) {
            when (block) {
                Blocks.PORTAL -> {
                    ColorHolder(82, 49, 153)
                }
                is BlockShulkerBox -> {
                    val colorInt = block.color.colorValue
                    ColorHolder((colorInt shr 16), (colorInt shr 8 and 255), (colorInt and 255))
                }
                is BlockEnderChest -> {
                    ColorHolder(64, 49, 114)
                }
                else -> {
                    val colorInt = blockState.getMapColor(world, pos).colorValue
                    ColorHolder((colorInt shr 16), (colorInt shr 8 and 255), (colorInt and 255))
                }
            }
        } else {
            customBlockColor
        }
    }

    data class DimensionFilter(val searchKey: String, val dim: LinkedHashSet<Int>) {
        override fun toString(): String {
            return "$searchKey -> $dim"
        }
    }

}