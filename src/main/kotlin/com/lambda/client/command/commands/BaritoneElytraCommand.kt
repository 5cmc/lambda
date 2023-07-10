package com.lambda.client.command.commands

import com.lambda.client.command.ClientCommand
import com.lambda.client.command.CommandManager
import com.lambda.client.util.text.MessageSendHelper

object BaritoneElytraCommand : ClientCommand(
    name = "bpath",
    alias = arrayOf("bp")
) {

    init {
        literal("thisway") {
            int("blocks") { blocksArg ->
                executeSafe("Elytra in the current direction for X blocks.") {
                    exec("thisway", blocksArg.value.toString())
                    exec("elytra")
                }
            }
        }

        literal("goto") {
            int("x") {x ->
                int("z") {z ->
                    executeSafe {
                        exec("goto", x.value.toString(), z.value.toString())
                        exec("elytra")
                    }
                }
            }
        }

        literal("stop", "cancel") {
            executeSafe("Stop the current Baritone process.") {
                exec("stop")
            }
        }
    }

    private fun exec(vararg args: String) {
        val safeArgs = CommandManager.tryParseArgument(args.joinToString(" ")) ?: return
        MessageSendHelper.sendBaritoneCommand(*safeArgs)
    }
}