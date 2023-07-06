package com.lambda.client.command.commands

import com.lambda.client.command.ClientCommand
import net.minecraft.util.math.MathHelper

object HClipCommand : ClientCommand(
    name = "hclip",
    description = "Teleport horizontally"
) {
    init {
        double("blocks") { distance ->
            executeSafe("Teleport horizontally") {
                val theta: Float = Math.toRadians(player.rotationYawHead.toDouble()).toFloat()
                val destX = mc.player.posX - MathHelper.sin(theta) * distance.value
                val destZ = mc.player.posZ + MathHelper.cos(theta) * distance.value
                player.setPositionAndUpdate(destX, player.posY, destZ)
            }
        }
    }
}