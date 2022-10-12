package com.lambda.client.module.modules.misc

import com.lambda.client.module.Category
import com.lambda.client.module.Module

object Focus: Module(
    name = "Focus",
    description = "Toggles in-game focus off",
    category = Category.MISC
) {

    init {
        this.disable() // make ourselves disabled always even if config has us enabled for some reason
        onEnable {
            mc.gameSettings.pauseOnLostFocus = false
            mc.setIngameNotInFocus()
            this.disable()
        }
    }
}