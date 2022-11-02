package com.lambda.client.gui.hudgui.elements.misc

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.gui.hudgui.LabelHud
import com.lambda.client.module.modules.misc.UptimeResolver

internal object ServerUptime : LabelHud(
    name = "Server Uptime",
    category = Category.MISC,
    description = "Displays the server's current uptime"
) {
    override fun SafeClientEvent.updateText() {
        if (UptimeResolver.isDisabled) UptimeResolver.enable()
        val uptime = UptimeResolver.uptime
        val uptimeStr = "Server Uptime: " + if (uptime == null) {
            "Unknown"
        } else {
            "${uptime.days}d ${uptime.hours}h ${uptime.minutes}m"
        }
        displayText.addLine(uptimeStr)
    }
}