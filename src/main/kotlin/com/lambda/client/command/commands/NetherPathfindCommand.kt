package com.lambda.client.command.commands

import com.lambda.client.command.ClientCommand
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.module.modules.movement.NetherPathfinder
import com.lambda.client.util.text.MessageSendHelper

object NetherPathfindCommand : ClientCommand(
    name = "npath",
    alias = arrayOf("p")
) {
    init {
        literal("goto") {
            int("x") {x ->
                int("z") {z ->
                    executeSafe {
                        goto(x.value, z.value)
                    }
                }
            }
        }
        literal("cancel", "stop") {
            executeSafe {
                cancel()
            }
        }
        literal("thisway") {
            int("dist") { dist ->
                executeSafe {
                    thisWay(dist.value)
                }
            }
        }
        literal("seed") {
            long("s") { newSeed ->
                executeSafe { setSeed(newSeed.value) }
            }
        }
    }

    private fun SafeClientEvent.goto(x: Int, z: Int) {
        if (NetherPathfinder.isInNether()) {
            if (NetherPathfinder.isDisabled) {
                NetherPathfinder.enable()
            }
            NetherPathfinder.goto(x, z)
        } else {
            MessageSendHelper.sendChatMessage("Player is not in the nether!")
        }

    }

    private fun SafeClientEvent.cancel() {
        NetherPathfinder.disable()
        MessageSendHelper.sendChatMessage("Cancelled pathing")
    }

    private fun SafeClientEvent.thisWay(dist: Int) {
        if (NetherPathfinder.isInNether()) {
            if (NetherPathfinder.isDisabled) {
                NetherPathfinder.enable()
            }
            NetherPathfinder.thisWay(dist)
        } else {
            MessageSendHelper.sendChatMessage("Player is not in the nether!")
        }
    }

    private fun SafeClientEvent.setSeed(newSeed: Long) {
        NetherPathfinder.setSeed(newSeed)
    }
}