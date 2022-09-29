package com.lambda.client.command.commands

import com.lambda.client.command.ClientCommand
import com.lambda.client.module.modules.render.Search
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.text.formatValue

// TODO: Remove once GUI has List
object SearchCommand : ClientCommand(
    name = "search",
    description = "Manage search blocks"
) {
    private val warningBlocks = arrayOf("minecraft:grass", "minecraft:end_stone", "minecraft:lava", "minecraft:bedrock", "minecraft:netherrack", "minecraft:dirt", "minecraft:water", "minecraft:stone")

    init {
        literal("add", "+") {
            block("block") { blockArg ->
                literal("force") {
                    execute("Force add a block to search list") {
                        val blockName = blockArg.value.registryName.toString()
                        addBlock(blockName)
                    }
                }

                execute("Add a block to search list") {
                    val blockName = blockArg.value.registryName.toString()

                    if (warningBlocks.contains(blockName)) {
                        MessageSendHelper.sendWarningMessage("Your world contains lots of ${formatValue(blockName)}, " +
                            "it might cause extreme lag to add it. " +
                            "If you are sure you want to add it run ${formatValue("$prefixName add $blockName force")}"
                        )
                    } else {
                        addBlock(blockName)
                    }
                }
            }
            entity("entity") { entityArg ->
                execute("Add an entity to search list") {
                    val entityName = entityArg.value
                    if (Search.entitySearchList.contains(entityName)) {
                        MessageSendHelper.sendChatMessage("$entityName is already added to search list")
                        return@execute
                    }
                    Search.entitySearchList.editValue { it.add(entityName) }
                    MessageSendHelper.sendChatMessage("$entityName has been added to search list")
                }
            }
        }

        literal("remove", "-") {
            block("block") { blockArg ->
                execute("Remove a block from search list") {
                    val blockName = blockArg.value.registryName.toString()

                    if (!Search.blockSearchList.contains(blockName)) {
                        MessageSendHelper.sendErrorMessage("You do not have ${formatValue(blockName)} added to search block list")
                    } else {
                        Search.blockSearchList.editValue { it.remove(blockName) }
                        MessageSendHelper.sendChatMessage("Removed ${formatValue(blockName)} from search block list")
                    }
                }
            }
            entity("entity") { entityArg ->
                execute("Remove an entity from search list") {
                    val entityName = entityArg.value
                    Search.entitySearchList.editValue { it.remove(entityName) }
                    MessageSendHelper.sendChatMessage("Removed $entityName from search list")
                }
            }
        }

        literal("set", "=") {
            block("block") { blockArg ->
                execute("Set the search list to one block") {
                    val blockName = blockArg.value.registryName.toString()

                    Search.blockSearchList.editValue {
                        it.clear()
                        it.add(blockName)
                    }
                    MessageSendHelper.sendChatMessage("Set the search block list to ${formatValue(blockName)}")
                }
            }
            entity("entity") { entityArg ->
                execute("Sets the search list to one entity") {
                    val entityName = entityArg.value
                    Search.entitySearchList.editValue {
                        it.clear()
                        it.add(entityName)
                    }
                    MessageSendHelper.sendChatMessage("Set the entity search list to $entityName")
                }
            }
        }

        literal("reset", "default") {
            execute("Reset the search list to defaults") {
                Search.blockSearchList.resetValue()
                Search.entitySearchList.resetValue()
                MessageSendHelper.sendChatMessage("Reset the search list to defaults")
            }
        }

        literal("list") {
            execute("Print search list") {
                MessageSendHelper.sendChatMessage("Blocks: ${Search.blockSearchList.joinToString()}")
                MessageSendHelper.sendChatMessage("Entities ${Search.entitySearchList.joinToString()}")
            }
        }

        literal("clear") {
            execute("Set the search list to nothing") {
                Search.blockSearchList.editValue { it.clear() }
                Search.entitySearchList.editValue { it.clear() }
                MessageSendHelper.sendChatMessage("Cleared the search block list")
            }
        }

        literal("override") {
            execute("Override the Intel Integrated GPU check") {
                Search.overrideWarning = true
                MessageSendHelper.sendWarningMessage("Override for Intel Integrated GPUs enabled!")
            }
        }
    }

    private fun addBlock(blockName: String) {
        if (blockName == "minecraft:air") {
            MessageSendHelper.sendChatMessage("You can't add ${formatValue(blockName)} to the search block list")
            return
        }

        if (Search.blockSearchList.contains(blockName)) {
            MessageSendHelper.sendErrorMessage("${formatValue(blockName)} is already added to the search block list")
        } else {
            Search.blockSearchList.editValue { it.add(blockName) }
            MessageSendHelper.sendChatMessage("${formatValue(blockName)} has been added to the search block list")
        }
    }
}