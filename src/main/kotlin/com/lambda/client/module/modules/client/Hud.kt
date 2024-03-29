package com.lambda.client.module.modules.client

import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.color.ColorHolder

object Hud : Module(
    name = "Hud",
    description = "Toggles Hud displaying and settings",
    category = Category.CLIENT,
    showOnArray = false,
    enabledByDefault = true
) {
    val hudFrame by setting("Hud Frame", false)
    val primaryColor by setting("Primary Color", ColorHolder(255, 240, 246), false)
    val secondaryColor by setting("Secondary Color", ColorHolder(108, 0, 43), false)
    val textShadow by setting("Text Shadow", true)
    val chatSnap by setting("Chat Snap", true)
    val collisionSnapping by setting("Collision Snapping", true)
    val f3Hide by setting("Hide on F3", false)
}