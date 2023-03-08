package com.lambda.client.module.modules.player

import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.listener.listener
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.threads.runSafe
import com.lambda.client.util.threads.safeListener
import net.minecraft.client.gui.GuiGameOver
import net.minecraft.network.play.client.CPacketClientStatus
import net.minecraftforge.client.event.GuiOpenEvent

object Undead : Module(
    name = "Undead",
    description = "Allows you to move after dying",
    category = Category.PLAYER
) {

    private var isDead = false

    init {
        onDisable {
            runSafe {
                if (isDead) {
                    connection.sendPacket(CPacketClientStatus(CPacketClientStatus.State.PERFORM_RESPAWN))
                    isDead = false
                }
            }
        }

        listener<ConnectionEvent.Disconnect> {
            isDead = false
        }

        safeListener<GuiOpenEvent> {
            if (it.gui is GuiGameOver) {
                it.gui = null
                isDead = true
                player.health = 20.0f
            }
        }
    }
}