package com.lambda.client.gui.hudgui.elements.player

import com.lambda.client.commons.utils.MathUtils
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.gui.hudgui.LabelHud
import com.lambda.client.util.math.Direction
import com.lambda.client.util.math.RotationUtils

internal object Rotation : LabelHud(
    name = "Rotation",
    category = Category.PLAYER,
    description = "Player rotation"
) {
    private val yaw by setting("Yaw", true)
    private val pitch by setting("Pitch", true)
    private val direction by setting("Direction", true)
    private val directionDisplayName by setting("Direction Name", true, visibility = { direction })
    private val directionXZ by setting("Direction XZ", true, visibility = { direction })

    override fun SafeClientEvent.updateText() {
        if (yaw) {
            val yawVal = MathUtils.round(RotationUtils.normalizeAngle(mc.player?.rotationYaw ?: 0.0f), 1)
            displayText.add("Yaw", secondaryColor)
            displayText.add(yawVal.toString(), primaryColor)
        }
        if (pitch) {
            val pitchVal = MathUtils.round(mc.player?.rotationPitch ?: 0.0f, 1)
            displayText.add("Pitch", secondaryColor)
            displayText.add(pitchVal.toString(), primaryColor)
        }
        if (direction) {
            val entity = mc.renderViewEntity ?: player
            val direction = Direction.fromEntity(entity)
            if (directionDisplayName) {
                displayText.add(direction.displayName, secondaryColor)
            }
            if (directionXZ) {
                displayText.add("(${direction.displayNameXY})", primaryColor)
            }
        }
    }

}