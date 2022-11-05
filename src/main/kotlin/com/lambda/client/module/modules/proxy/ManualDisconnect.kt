package com.lambda.client.module.modules.proxy

import com.lambda.client.manager.managers.MessageManager.sendMessageDirect
import com.lambda.client.module.Category
import com.lambda.client.module.Module

object ManualDisconnect : Module(
    name = "ManualDisconnect",
    description = "Adds a menu button for manual proxy disconnect",
    category = Category.PROXY
) {

    fun dc() {
        sendMessageDirect("!dc")
    }
}
