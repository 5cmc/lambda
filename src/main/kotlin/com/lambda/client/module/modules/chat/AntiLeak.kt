package com.lambda.client.module.modules.chat

import com.lambda.client.manager.managers.MessageManager.newMessageModifier
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.text.MessageDetection
import kotlin.math.abs

object AntiLeak : Module(
    name = "AntiLeak",
    description = "Prevents you from leaking your coords in chat",
    category = Category.CHAT
) {
    private val rangeCheck by setting("Coord Range Check", false, description = "Checks if numbers in chats are within a range from your coords")
    private val rangeFactor by setting("Range Factor", 10.0, 1.0..100.0, 1.0, { rangeCheck }, description = "The factor to multiply/divide your coords by to determine the range")

    private val chatModifier = newMessageModifier(
        filter = { MessageDetection.Message.SELF detectNot it.packet.message },
        modifier = {
            val message = it.packet.message
            val numbers = message
                // replace all non-numbers with spaces
                .replace(Regex("[^0-9]"), " ")
                .split(" ")
                .filter { it.toDoubleOrNull() != null }
                .map { it.toDouble() }
            if (numbers.size < 2) return@newMessageModifier message
            if (rangeCheck) {
                val playerPos = mc.player?.positionVector ?: return@newMessageModifier message
                for (number in numbers) {
                    val n = abs(number)
                    if (n in abs(playerPos.x) / rangeFactor..abs(playerPos.x) * rangeFactor
                        || n in abs(playerPos.y) / rangeFactor..abs(playerPos.y) * rangeFactor
                        || n in abs(playerPos.z) / rangeFactor..abs(playerPos.z) * rangeFactor) {
                        return@newMessageModifier ""
                    }
                }
            } else {
                return@newMessageModifier ""
            }
            return@newMessageModifier message
        })

    init {
        onEnable {
            chatModifier.enable()
        }

        onDisable {
            chatModifier.disable()
        }
    }
}