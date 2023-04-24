package com.lambda.client.gui.hudgui.elements.world

import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.gui.hudgui.LabelHud
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import net.minecraft.tileentity.TileEntityChest
import net.minecraft.tileentity.TileEntityShulkerBox
import net.minecraft.util.math.ChunkPos
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent

internal object ChestCounter : LabelHud(
    name = "ChestCounter",
    category = Category.WORLD,
    description = "Counts the number of chests in the world"
) {
    private val dubs by setting("Count Dubs", true, description = "Counts double chests instead of individual chests")
    private val shulkers by setting("Count Shulkers", true, description = "Counts shulkers in the world")
    private val textColor by setting("Text Color", primaryColor, description = "Color of the main text")
    private val numberColor by setting("Number Color", secondaryColor, description = "Color of the number")
    private val searchDelayTicks by setting("Search Delay", 20, 1..100, 1, description = "How many ticks to wait before searching for chests again")

    private val delayTimer: TickTimer = TickTimer(TimeUnit.TICKS)
    private var chestCount = 0
    private var shulkerCount = 0
    private var blockSearchJob: Job? = null

    override fun SafeClientEvent.updateText() {
        displayText.add( if (dubs) "Dubs: " else "Chests: ", textColor)
        displayText.add("$chestCount", numberColor)
        if (shulkers) {
            displayText.add(" Shulkers: ", textColor)
            displayText.add("$shulkerCount", numberColor)
        }
    }

    init {
        safeListener<ClientTickEvent> {
            if (it.phase != TickEvent.Phase.START) return@safeListener
            if (blockSearchJob?.isActive == true) return@safeListener
            if (delayTimer.tick(searchDelayTicks.toLong())) {
                blockSearchJob = defaultScope.launch {
                    searchChunks()
                }
            }
        }
    }

    private fun SafeClientEvent.searchChunks() {
        try {
            val renderDist = mc.gameSettings.renderDistanceChunks
            val playerChunkPos = ChunkPos(player.position)
            val chunkPos1 = ChunkPos(playerChunkPos.x - renderDist, playerChunkPos.z - renderDist)
            val chunkPos2 = ChunkPos(playerChunkPos.x + renderDist, playerChunkPos.z + renderDist)
            var chestC = 0
            var shulkC = 0
            for (x in chunkPos1.x..chunkPos2.x) for (z in chunkPos1.z..chunkPos2.z) {
                try {
                    val chunk = world.getChunk(x, z)
                    if (chunk == null || chunk.isEmpty || !chunk.isLoaded) continue
                    for (tileEntity in chunk.tileEntityMap.values) {
                        if (tileEntity is TileEntityChest) {
                            if (dubs) {
                                if (tileEntity.adjacentChestXPos != null || tileEntity.adjacentChestZPos != null) chestC++
                            } else {
                                chestC++
                            }
                        }
                        if (shulkers && tileEntity is TileEntityShulkerBox) shulkC++
                    }
                } catch (e: Exception) {
                    LambdaMod.LOG.error("ChestCounter: Error searching chunk $x $z", e)
                }
            }
            chestCount = chestC
            shulkerCount = shulkC

        } catch (e: Exception) {
            LambdaMod.LOG.error("ChestCounter: Error searching chunks", e)
        }
    }
}

