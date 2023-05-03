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
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent

internal object ChestCounter : LabelHud(
    name = "ChestCounter",
    category = Category.WORLD,
    description = "Counts the number of chests in the world"
) {
    private val dubs by setting("Count Dubs", true, description = "Counts double chests instead of individual chests")
    private val shulkers by setting("Count Shulkers", true, description = "Counts shulkers in the world")
    private val searchDelayTicks by setting("Search Delay", 5, 1..100, 1, description = "How many ticks to wait before searching for chests again")

    private val delayTimer: TickTimer = TickTimer(TimeUnit.TICKS)
    private var chestCount = 0
    private var shulkerCount = 0
    private var blockSearchJob: Job? = null

    override fun SafeClientEvent.updateText() {
        displayText.add( if (dubs) "Dubs: " else "Chests: ", primaryColor)
        displayText.add("$chestCount", secondaryColor)
        if (shulkers) {
            displayText.add(" Shulkers: ", primaryColor)
            displayText.add("$shulkerCount", secondaryColor)
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
            var chestC = 0
            var shulkC = 0
            for (it in world.loadedTileEntityList) {
                if (it is TileEntityChest) {
                    if (dubs) {
                        if (it.adjacentChestXPos != null || it.adjacentChestZPos != null) chestC++
                    } else {
                        chestC++
                    }
                } else if (shulkers && it is TileEntityShulkerBox) shulkC++
            }
            chestCount = chestC
            shulkerCount = shulkC

        } catch (e: Exception) {
            LambdaMod.LOG.error("ChestCounter: Error searching chunks", e)
        }
    }
}

