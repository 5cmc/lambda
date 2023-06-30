package com.lambda.client.module.modules.render

import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.SeedOverlayBlockMap
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import com.lambda.worldgen.WorldGenerator
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.block.BlockFalling
import net.minecraft.block.state.IBlockState
import net.minecraft.init.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.ChunkPos
import net.minecraft.world.World
import net.minecraft.world.WorldSettings
import net.minecraftforge.common.ForgeModContainer
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.lang.Integer.min
import java.time.Duration
import java.time.Instant

object SeedOverlay: Module(
    name = "SeedOverlay",
    category = Category.RENDER,
    description = "Render an overlay of blocks changed based on the current seed compared to the vanilla world"
) {
    /**
     * todo:
     *  increase efficiency similar to Search module - only need to compare on enable, new chunk packets, or changed block packets
     *  add more overworld biome block mappings to handle feature differences
     */
    private val overworldEnabled by setting("Overworld Enabled", true)
    private val overworldSeed by setting("Overworld Seed", "-4172144997902289642", consumer = this::seedSettingConsumer)
    private val netherEnabled by setting("Nether Enabled", true)
    private val netherSeed by setting("Nether Seed", "146008555100680", consumer = this::seedSettingConsumer)
    private val endEnabled by setting("End Enabled", true)
    private val endSeed by setting("End Seed", "146008555100680", consumer = this::seedSettingConsumer)
    private val renderNewBlocks by setting("Render New Blocks", true)
    private val renderNewBlocksColor by setting("New Blocks Color", ColorHolder(255, 0, 0))
    private val renderMissingBlocks by setting("Render Missing Blocks", true)
    private val renderMissingBlocksColor by setting("Missing Blocks Color", ColorHolder(0, 255, 0))
    private val renderDifferentBlocks by setting("Render Different Blocks", true)
    private val renderDifferentBlocksColor by setting("Different Blocks Color", ColorHolder(0, 0, 255))
    private val renderFilled by setting("Render Filled", true)
    private val renderFilledAlpha by setting("Render Fill Alpha", 100, 0..255, 1)
    private val renderOutline by setting("Render Outline", true)
    private val renderTracer by setting("Render Tracer", false)
    private val renderTracerAlpha by setting("Render Tracer Alpha", 150, 1..255, 1)
    private val renderThickness by setting("Render Line Thickness", 1.0, 0.1..3.0, 0.1)
    private val yMin by setting("Y Minimum", 5, 0..255, 1)
    private val yMax by setting("Y Maximum", 128, 0..255, 1)
    private val range by setting("Server View-Distance", 4, 1..16, 1)
    private val maxRenderedDifferences by setting("Max Rendered", 500, 1..5000, 1)
    private val ignoreNewChunks by setting("Ignore NewChunks", true)

    private var worldGenerator: WorldGenerator? = null
    private var generatedWorld: World? = null
    private var map: SeedOverlayBlockMap = SeedOverlayBlockMap(0)
    private var updateJob: Job? = null
    private var initializingJob: Job? = null
    private val espRenderer = ESPRenderer()

    private val differences = HashMap<BlockPos, BlockDifference>()

    private enum class BlockDifference {
        NEW, MISSING, DIFFERENT
    }

    override fun getHudInfo(): String {
        return differences.size.toString()
    }

    init {
        onEnable {
            // stop potential logspam
            // i don't think this really matters or is something we need to care about
            ForgeModContainer.logCascadingWorldGeneration = false
        }

        onDisable {
            defaultScope.launch {
                reset()
                updateJob?.cancel()
                initializingJob?.cancel()
            }
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.START) return@safeListener
            if (!isJobCompletedOrNull(updateJob) || !isJobCompletedOrNull(initializingJob)) return@safeListener
            if (mc.isSingleplayer) {
                MessageSendHelper.sendChatMessage("Single Player Not Supported")
                disable()
                return@safeListener
            }
            if (!enabledOnCurrentDimension()) {
                initializingJob = defaultScope.launch {
                    reset()
                }
                return@safeListener
            }
            if (worldGenerator == null || generatedWorld == null) {
                map = SeedOverlayBlockMap(world.provider.dimension, net.minecraft.init.Biomes.PLAINS)
                initializingJob = defaultScope.launch {
                    reset()
                    try {
                        worldGenerator = initWorldGenerator(mc.world, getSeedForCurrentWorld()!!)
                        generatedWorld = worldGenerator?.getWorld(mc.world.provider.dimension)
                    } catch (ex: Exception) {
                        disable()
                        MessageSendHelper.sendChatMessage("Error starting SeedOverlay ${ex.message}")
                    }
                }
            }

            generatedWorld?.let { genWorld ->
                if (genWorld.provider.dimension != world.provider.dimension) {
                    reset()
                    return@safeListener
                }
            }

            updateJob = defaultScope.launch {
                try {
                    searchLoadedChunks()
                } catch (ex: Exception) {
                    LambdaMod.LOG.warn("Error updating SeedOverlay", ex)
                }
            }
        }

        safeListener<RenderWorldEvent> {
            espRenderer.thickness = renderThickness.toFloat()
            if (renderFilled) {
                espRenderer.aFilled = renderFilledAlpha
            } else {
                espRenderer.aFilled = 0
            }
            if (renderOutline) {
                espRenderer.aOutline = 255
            } else {
                espRenderer.aOutline = 0
            }
            if (renderTracer) {
                espRenderer.aTracer = renderTracerAlpha
            } else {
                espRenderer.aTracer = 0
            }
            synchronized(differences) {
                differences.entries.take(maxRenderedDifferences).forEach {
                    when (it.value) {
                        BlockDifference.NEW -> if (renderNewBlocks) espRenderer.add(it.key, renderNewBlocksColor)
                        BlockDifference.DIFFERENT -> if (renderDifferentBlocks) espRenderer.add(it.key, renderDifferentBlocksColor)
                        BlockDifference.MISSING -> if (renderMissingBlocks) espRenderer.add(it.key, renderMissingBlocksColor)
                    }
                }
            }
            espRenderer.render(true)
        }

        safeListener<ConnectionEvent.Disconnect> {
            disable()
        }
    }

    private fun isJobCompletedOrNull(job: Job?): Boolean {
        return job?.isCompleted ?: true
    }

    private fun SafeClientEvent.getSeedForCurrentWorld(): Long? {
        return when(mc.world.provider.dimension) {
            0 -> overworldSeed.toLongOrNull()
            -1 -> netherSeed.toLongOrNull()
            1 -> endSeed.toLongOrNull()
            else -> null
        }
    }

    private fun SafeClientEvent.enabledOnCurrentDimension(): Boolean {
        return when(mc.world.provider.dimension) {
            0 -> overworldEnabled
            -1 -> netherEnabled
            1 -> endEnabled
            else -> false
        }
    }

    private fun clearToRender() {
        synchronized(differences) {
            differences.clear()
        }
    }

    private fun reset() {
        worldGenerator?.let { generator ->
            try {
                generator.stopServer()
            } catch (e: Exception) {

            }
        }
        worldGenerator = null
        generatedWorld = null
        clearToRender()
    }

    private fun SafeClientEvent.searchLoadedChunks() {
        if (yMin >= yMax) return
        val newDifferences: MutableMap<BlockPos, BlockDifference> = HashMap()
        try {
            worldGenerator?.let {
                generatedWorld = worldGenerator!!.getWorld(world.provider.dimension)
                generatedWorld?.let { genWorld ->
                    val searchRange = min((mc.gameSettings.renderDistanceChunks / 2), range)
                    for (z in -searchRange * 16 until searchRange * 16) {
                        for (x in -searchRange * 16 until searchRange * 16) {
                            val playerX = (player.posX + x).toInt()
                            val playerZ = (player.posZ + z).toInt()
                            val sampleBlockPos = BlockPos(playerX, 40, playerZ)
                            val chunkPos = ChunkPos(sampleBlockPos)
                            // need to have all surrounding chunks loaded in order for structures/features to be populated
                            // otherwise we'll get large sections of false positives
                            // unwanted side effect: we won't compare blocks in chunks at edges of view distance
                            if (ignoreNewChunks && NewChunksPlus.isNewChunk(sampleBlockPos)) continue
                            if (surroundingChunksLoaded(world, chunkPos.x, chunkPos.z)) {
                                if (!surroundingChunksLoaded(genWorld, chunkPos.x, chunkPos.z)) {
                                    // force surrounding chunks to get loaded
                                    genWorld.chunkProvider.provideChunk(chunkPos.x, chunkPos.z)
                                    genWorld.chunkProvider.provideChunk(chunkPos.x+1, chunkPos.z)
                                    genWorld.chunkProvider.provideChunk(chunkPos.x-1, chunkPos.z)
                                    genWorld.chunkProvider.provideChunk(chunkPos.x, chunkPos.z+1)
                                    genWorld.chunkProvider.provideChunk(chunkPos.x, chunkPos.z-1)
                                    genWorld.chunkProvider.provideChunk(chunkPos.x+1, chunkPos.z+1)
                                    genWorld.chunkProvider.provideChunk(chunkPos.x+1, chunkPos.z-1)
                                    genWorld.chunkProvider.provideChunk(chunkPos.x-1, chunkPos.z+1)
                                    genWorld.chunkProvider.provideChunk(chunkPos.x-1, chunkPos.z-1)
                                }
                                for (y in yMin..yMax) {
                                    val blockPos = BlockPos(playerX, y, playerZ)
                                    compare(blockPos)?.let {
                                        newDifferences[blockPos] = it
                                    }
                                }
                            }
                        }
                    }
                }
            }
            synchronized(differences) {
                differences.clear()
                differences.putAll(newDifferences)
            }
        } catch (ex: Exception) {
            LambdaMod.LOG.warn("Error comparing chunks", ex)
        }
    }

    private fun SafeClientEvent.compare(blockPos: BlockPos): BlockDifference? {
        map.let { blockMap ->
            val playerBlockState: IBlockState = world.getBlockState(blockPos)
            blockMap.setBiome(world.getBiome(blockPos))
            generatedWorld?.let {
                val generatedBlockState = it.getBlockState(blockPos)
                if (blockMap[playerBlockState.block] != blockMap[generatedBlockState.block]) {
                    if (!playerBlockState.material.isLiquid && !generatedBlockState.material.isLiquid &&
                        !BlockFalling::class.java.isAssignableFrom(playerBlockState.block.javaClass) && !BlockFalling::class.java.isAssignableFrom(generatedBlockState.block.javaClass)) {
                        return if (playerBlockState.block == Blocks.AIR) {
                            BlockDifference.MISSING
                        } else if (generatedBlockState.block == Blocks.AIR) {
                            BlockDifference.NEW
                        } else {
                            BlockDifference.DIFFERENT
                        }
                    }
                }
            }
        }
        return null
    }

    private fun surroundingChunksLoaded(w: World, x: Int, z: Int): Boolean {
        return isChunkLoaded(w, x, z)
            && isChunkLoaded(w, x - 1, z)
            && isChunkLoaded(w, x + 1, z)
            && isChunkLoaded(w, x, z - 1)
            && isChunkLoaded(w, x, z + 1)
            && isChunkLoaded(w, x + 1, z + 1)
            && isChunkLoaded(w, x + 1, z - 1)
            && isChunkLoaded(w, x - 1, z + 1)
            && isChunkLoaded(w, x - 1, z - 1)
    }

    private fun isChunkLoaded(w: World, x: Int, z: Int): Boolean {
        return w.chunkProvider.getLoadedChunk(x, z) != null
    }

    private fun initWorldGenerator(worldIn: World, seed: Long): WorldGenerator {
        val settings = WorldSettings(seed, worldIn.worldInfo.gameType, true, false, worldIn.worldType)
        settings.generatorOptions = worldIn.worldInfo.generatorOptions

        val worldGen: WorldGenerator = WorldGenerator.create(settings)
        worldGen.startServerThread()
        val before = Instant.now()
        while (!worldGen.done) {
            MessageSendHelper.sendChatMessage("[SeedOverlay] ${worldGen.percentDone}% Generated")
            Thread.sleep(1000)
            if (Instant.now().minus(Duration.ofSeconds(30)).isAfter(before)) {
                throw RuntimeException("Timed out creating world")
            }
        }
        MessageSendHelper.sendChatMessage("Done generating world!")
        return worldGen
    }

    private fun seedSettingConsumer(prev: String, newVal: String): String {
        newVal.toLongOrNull()?.let {
            disable()
            return newVal
        } ?: run {
            return prev
        }
    }
}