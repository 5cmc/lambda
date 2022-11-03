package com.lambda.client.gui.hudgui.elements.misc

import com.lambda.client.event.LambdaEventBus
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.gui.hudgui.LabelHud
import com.lambda.client.util.InfoCalculator
import com.lambda.client.util.PingCalculator

internal object Ping : LabelHud(
    name = "Ping",
    category = Category.MISC,
    description = "Delay between client and server"
) {

    private val pingBypass by setting("Bypass", true,
        description = "Bypass when server does not report client ping in tablist",
        consumer = { _, input ->
        if (input) {
            LambdaEventBus.subscribe(PingCalculator)
        } else {
            LambdaEventBus.unsubscribe(PingCalculator)
        }
        return@setting input
    })

    override fun onGuiInit() {
        super.onGuiInit()
        if (pingBypass) {
            LambdaEventBus.subscribe(PingCalculator)
        }
    }

    override fun onClosed() {
        super.onClosed()
        LambdaEventBus.unsubscribe(PingCalculator)
    }

    override fun SafeClientEvent.updateText() {
        if (pingBypass) {
            displayText.add(PingCalculator.ping.toString(), primaryColor)
        } else {
            displayText.add(InfoCalculator.ping().toString(), primaryColor)
        }
        displayText.add("ms", secondaryColor)
    }

}