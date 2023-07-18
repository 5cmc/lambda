package com.lambda.client.manager.managers


import com.lambda.client.manager.Manager
import com.lambda.client.util.Wrapper.player
import com.lambda.client.event.SafeClientEvent


object ProxyManager : Manager {

    fun isProxy(): Boolean {
        val serverBrand = mc.player.serverBrand ?: "Unknown Server Type"
        return serverBrand.startsWith("ZenithProxy");
    }
}