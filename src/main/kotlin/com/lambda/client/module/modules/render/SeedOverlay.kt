package com.lambda.client.module.modules.render

import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.RenderWorldEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import com.lambda.worldgen.WorldGenerator
import kotlinx.coroutines.launch
import net.minecraft.block.BlockFalling
import net.minecraft.block.state.IBlockState
import net.minecraft.init.Blocks
import net.minecraft.util.math.BlockPos
import net.minecraft.world.World
import net.minecraft.world.WorldSettings
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.time.Duration
import java.time.Instant

object SeedOverlay: Module(
    name = "SeedOverlay",
    category = Category.RENDER,
    description = "Render an overlay of blocks changed based on the current seed compared to the vanilla world"
) {
    /**
     * todo:
     *  paper generated world comparison (is this even feasible?)
     *  increase efficiency similar to Search module - only need to compare on enable, new chunk packets, or changed block packets
     */

    private val overworldSeed by setting("Overworld Seed", "-4172144997902289642")
    private val netherSeed by setting("Nether Seed", "146008555100680")
    private val endSeed by setting("End Seed", "146008555100680")
    private val compareMode by setting("Compare Mode", CompareMode.BLOCKS, description = "Compare exact block types or compare block materials")
    private val renderNewBlocks by setting("Render New Blocks", true)
    private val renderNewBlocksColor by setting("New Blocks Color", ColorHolder(255, 0, 0))
    private val renderMissingBlocks by setting("Render Missing Blocks", true)
    private val renderMissingBlocksColor by setting("Missing Blocks Color", ColorHolder(0, 255, 0))
    private val renderDifferentBlocks by setting("Render Different Blocks", true)
    private val renderDifferentBlocksColor by setting("Different Blocks Color", ColorHolder(0, 0, 255))
    private val renderOutline by setting("Render Outline", true)
    private val renderOutlineThickness by setting("Render Outline Thickness", 1.0, 0.1..3.0, 0.1)
    private val renderFilled by setting("Render Filled", true)
    private val renderFilledAlpha by setting("Render Fill Alpha", 100, 0..255, 1)
    private val yMin by setting("Y Minimum", 30, 0..255, 1)
    private val yMax by setting("Y Maximum", 90, 0..255, 1)
    private val range by setting("Render Distance Range", 8, 1..16, 1)
    private val maxRenderedDifferences by setting("Max Rendered", 5000, 500..50000, 100)

    private var worldGenerator: WorldGenerator? = null
    private var generatedWorld: World? = null
    private var isUpdating = false
    private var isInitializing = false
    private val espRenderer = ESPRenderer()

    private val differences = HashMap<BlockPos, Int>()

    private val ignoreBlocks = listOf(Blocks.GLOWSTONE, Blocks.LOG, Blocks.LEAVES, Blocks.LOG2, Blocks.LEAVES2,
        Blocks.COAL_ORE, Blocks.IRON_ORE, Blocks.GOLD_ORE, Blocks.LAPIS_ORE, Blocks.EMERALD_ORE, Blocks.DIAMOND_ORE,
        Blocks.TALLGRASS, Blocks.DOUBLE_PLANT, Blocks.VINE, Blocks.YELLOW_FLOWER, Blocks.RED_FLOWER, Blocks.BROWN_MUSHROOM,
        Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM_BLOCK, Blocks.RED_MUSHROOM_BLOCK, Blocks.FIRE, Blocks.DEADBUSH, Blocks.QUARTZ_ORE)

    private enum class CompareMode {
        BLOCKS, MATERIAL
    }

    init {
        onEnable {

        }

        onDisable {
            try {
                worldGenerator?.stopServer()
            } catch (ex: Exception) {

            }
            worldGenerator = null
            generatedWorld = null
            isUpdating = false
            isInitializing = false
            clearToRender()
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.START) return@safeListener
            if (isUpdating || isInitializing) return@safeListener
            if (mc.isSingleplayer) {
                MessageSendHelper.sendChatMessage("Single Player Not Supported")
                disable()
                return@safeListener
            }
            if (worldGenerator == null || generatedWorld == null) {
                isInitializing = true
                defaultScope.launch {
                    worldGenerator?.let { generator ->
                        try {
                            generator.stopServer()
                        } catch (e: Exception) {

                        }
                    }
                    try {
                        worldGenerator = createFreshWorldCopy(Companion.mc.world, getSeedForCurrentWorld()!!)
                        generatedWorld = worldGenerator!!.getWorld(Companion.mc.world.provider.dimension)
                    } catch (ex: Exception) {
                        worldGenerator = null
                        generatedWorld = null
                        disable()
                        MessageSendHelper.sendChatMessage("Error starting SeedOverlay ${ex.message}")
                    }
                    isInitializing = false
                }
            }

            generatedWorld?.let { genWorld ->
                if (genWorld.provider.dimension != world.provider.dimension) {
                    worldGenerator = null
                    generatedWorld = null
                    clearToRender()
                    return@safeListener
                }
            }


            isUpdating = true
            defaultScope.launch {
                try {
                    update()
                } catch (ex: Exception) {
                    LambdaMod.LOG.warn("Error updating SeedOverlay", ex)
                }
                isUpdating = false
            }
        }

        safeListener<RenderWorldEvent> {
            if (renderFilled) {
                espRenderer.aFilled = renderFilledAlpha
            } else {
                espRenderer.aFilled = 0
            }
            if (renderOutline) {
                espRenderer.aOutline = 255
                espRenderer.thickness = renderOutlineThickness.toFloat()
            } else {
                espRenderer.aOutline = 0
            }
            synchronized(differences) {
                differences.entries.take(maxRenderedDifferences).forEach {
                    when (it.value) {
                        /**
                         * 1 = block in player world but not in generated
                         * 0 = different blocks in both worlds
                         * -1 = block in generated world but not in player
                         */
                        1 -> if (renderNewBlocks) espRenderer.add(it.key, renderNewBlocksColor)
                        0 -> if (renderDifferentBlocks) espRenderer.add(it.key, renderDifferentBlocksColor)
                        -1 -> if (renderMissingBlocks) espRenderer.add(it.key, renderMissingBlocksColor)
                    }
                }
            }
            espRenderer.render(true)
        }
    }

    private fun SafeClientEvent.getSeedForCurrentWorld(): Long? {
        return when(mc.world.provider.dimension) {
            0 -> overworldSeed.toLongOrNull()
            -1 -> netherSeed.toLongOrNull()
            1 -> endSeed.toLongOrNull()
            else -> null
        }
    }

    private fun clearToRender() {
        synchronized(differences) {
            differences.clear()
        }
    }

    private fun SafeClientEvent.update() {
        val newRender: MutableMap<BlockPos, Int> = HashMap()

        if (generatedWorld != null && worldGenerator != null) {
            generatedWorld = worldGenerator!!.getWorld(world.provider.dimension)
            for (z in -range * 16 until range * 16) {
                for (x in -range * 16 until range * 16) {
                    val theX = (player.posX + x).toInt()
                    val theZ = (player.posZ + z).toInt()
                    if (yMin >= yMax) return
                    for (y in yMin..yMax) {
                        val bp = BlockPos(theX, y, theZ)
                        if (world.isBlockLoaded(bp, false) && generatedWorld!!.getChunk(bp).isTerrainPopulated) {
                            val a: IBlockState = world.getBlockState(bp)
                            val b = generatedWorld!!.getBlockState(bp)
                            val compare = when(compareMode) {
                                CompareMode.BLOCKS -> a.block != b.block
                                CompareMode.MATERIAL -> a.material != b.material
                            }
                            if (compare) {
                                if (!a.material.isLiquid && !b.material.isLiquid &&
                                    !BlockFalling::class.java.isAssignableFrom(a.block.javaClass) && !BlockFalling::class.java.isAssignableFrom(b.block.javaClass) &&
                                    !ignoreBlocks.contains(a.block) && !ignoreBlocks.contains(b.block)) {
                                    if (a.block == Blocks.AIR) {
                                        newRender[bp] = -1 // block in generated world but not in player
                                    } else if (b.block == Blocks.AIR) {
                                        newRender[bp] = 1 // block in player world but not in generated
                                    } else {
                                        newRender[bp] = 0 // different block in both
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        synchronized(differences) {
            differences.clear()
            differences.putAll(newRender)
        }
    }

    private fun createFreshWorldCopy(worldIn: World, seed: Long): WorldGenerator {
        val i = worldIn.worldInfo

        val nbt = i.cloneNBTCompound(null)
        nbt.setLong("RandomSeed", seed)
        val settings = WorldSettings(seed, worldIn.worldInfo.gameType, true, false, worldIn.worldType)
        settings.generatorOptions = worldIn.worldInfo.generatorOptions

        val w: WorldGenerator = WorldGenerator.create(settings)
        w.startServerThread()
        val before = Instant.now()
        while (!w.done) {
            MessageSendHelper.sendChatMessage("${w.percentDone}% Generated")
            Thread.sleep(1000)
            if (Instant.now().minus(Duration.ofSeconds(30)).isAfter(before)) {
                throw RuntimeException("Timed out creating world")
            }
        }
        MessageSendHelper.sendChatMessage("Done generating world!")
        return w
    }
}