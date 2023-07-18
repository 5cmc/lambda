package com.lambda.client.manager.managers


import com.lambda.client.manager.Manager


object ProxyManager : Manager {

    fun isProxy(): Boolean {
        val serverBrand = mc.player.serverBrand ?: "Unknown Server Type"
        return serverBrand == "ZenithProxy";
    }
}