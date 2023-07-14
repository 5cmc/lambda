package com.lambda.client.module.modules.misc

import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.safeListener
import net.minecraft.network.play.client.CPacketPlayer
import net.minecraftforge.fml.common.gameevent.TickEvent

object TPSpam : Module(
    name = "TPSpam",
    description = "odpay debug module, do not use",
    category = Category.MISC
) {
    private val delay by setting("Delay", 5, 0..20, 1, unit = " ticks")

    private val timer = TickTimer(TimeUnit.TICKS)

    init {
        safeListener<TickEvent.ClientTickEvent> {
            if (timer.tick(delay.toLong())) {
                connection.sendPacket(CPacketPlayer.Position(player.posX, player.posY + 1000, player.posZ, false))
                MessageSendHelper.sendChatMessage("asd")

            }
        }
    }
}