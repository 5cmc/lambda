package com.lambda.client.util

import net.minecraft.client.gui.GuiMultiplayer
import net.minecraft.client.multiplayer.ServerData
import net.minecraft.client.resources.I18n
import net.minecraft.util.text.TextFormatting
import java.net.UnknownHostException
import java.util.concurrent.ThreadPoolExecutor

class RetryingServerPingRunner(val owner: GuiMultiplayer, val server: ServerData, val executor: ThreadPoolExecutor) {

    private var tries = 0

    fun run() {
        executor.submit { pingRunnable() }
    }

    private fun pingRunnable() {
        try {
            owner.oldServerPinger.ping(this.server)
        } catch (e: UnknownHostException) {
            server.pingToServer = -1L
            server.serverMOTD = TextFormatting.DARK_RED.toString() + I18n.format("multiplayer.status.cannot_resolve", *arrayOfNulls<Any>(0))
        } catch (e: Exception) {
            if (tries++ < 3) {
                try {
                    Thread.sleep(500)
                } catch (e: Exception) {
                }
                if (tries < 3) {
                    executor.submit { pingRunnable() }
                }
            } else {
                server.pingToServer = -1L
                server.serverMOTD = TextFormatting.DARK_RED.toString() + I18n.format("multiplayer.status.cannot_connect", *arrayOfNulls<Any>(0))
            }
        }
    }
}