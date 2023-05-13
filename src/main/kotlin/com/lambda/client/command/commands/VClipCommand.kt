package com.lambda.client.command.commands

import com.lambda.client.command.ClientCommand

object VClipCommand : ClientCommand(
    name = "vclip",
    description = "Teleport vertically"
) {
    init {
        double("blocks") { distance ->
            executeSafe("Teleport vertically") {
                player.setPositionAndUpdate(player.posX, player.posY + distance.value, player.posZ)
            }
        }
    }
}