package com.lambda.client.module.modules.misc

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.safeListener
import net.minecraft.network.play.server.SPacketKeepAlive
import java.time.Duration

object UptimeResolver : Module(
    name = "UptimeResolver",
    description = "Gets the server's uptime",
    category = Category.MISC
) {

    private val shouldPrintToChat by setting("Print to chat", false)

    var uptime: Uptime? = null
    private var shouldPrint = false

    init {

        onEnable {
            shouldPrint = true
            uptime = null
        }

        onDisable {
            uptime = null
        }

        safeListener<PacketEvent.Receive>() {
            if (it.packet is SPacketKeepAlive) {
                uptime = Uptime(it.packet.id)
                if (shouldPrintToChat && shouldPrint) {
                    printUptime(uptime!!)
                    shouldPrint = false
                }
            }
        }

        safeListener<ConnectionEvent.Disconnect> {
            shouldPrint = true
            uptime = null
        }
    }

    private fun SafeClientEvent.printUptime(uptime: Uptime) {
        MessageSendHelper.sendChatMessage("[UptimeResolver] ${uptime.days}d ${uptime.hours}h ${uptime.minutes}m")
    }

    class Uptime(uptimeMs: Long) {
        val days: Long
        val hours: Long
        val minutes: Long
        init {
            val duration = Duration.ofMillis(uptimeMs)
            days = duration.toDays()
            hours = duration.minusDays(days).toHours()
            minutes = duration.minusDays(days).minusHours(hours).toMinutes()
        }
    }
}