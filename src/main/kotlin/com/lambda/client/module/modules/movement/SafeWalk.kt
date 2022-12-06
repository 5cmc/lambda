package com.lambda.client.module.modules.movement

import com.lambda.client.event.events.PlayerMoveEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.module.modules.player.Scaffold
import com.lambda.client.util.BaritoneUtils
import com.lambda.client.util.threads.safeListener

object SafeWalk : Module(
    name = "SafeWalk",
    description = "Keeps you from walking off edges",
    category = Category.MOVEMENT,
    alwaysListening = true
) {

    init {
        safeListener<PlayerMoveEvent> { event ->
            if ((isEnabled || (Scaffold.isEnabled && Scaffold.safeWalk)) && player.onGround && !BaritoneUtils.isPathing) {
                var x = event.x
                var z = event.z
                while (x != 0.0 && world.getCollisionBoxes(player, player.entityBoundingBox.offset(x, (-player.stepHeight).toDouble(), 0.0)).isEmpty()) {
                    if (x < 0.05 && x >= -0.05) {
                        x = 0.0
                    } else if (x > 0.0) {
                        x -= 0.05
                    } else {
                        x += 0.05
                    }
                }
                while (z != 0.0 && world.getCollisionBoxes(player, player.entityBoundingBox.offset(0.0, (-player.stepHeight).toDouble(), z)).isEmpty()) {
                    if (z < 0.05 && z >= -0.05) {
                        z = 0.0
                    } else if (z > 0.0) {
                        z -= 0.05
                    } else {
                        z += 0.05
                    }
                }
                while (x != 0.0 && z != 0.0 && world.getCollisionBoxes(player, player.entityBoundingBox.offset(x, (-player.stepHeight).toDouble(), z)).isEmpty()) {
                    if (x < 0.05 && x >= -0.05) {
                        x = 0.0
                    } else if (x > 0.0) {
                        x -= 0.05
                    } else {
                        x += 0.05
                    }
                    if (z < 0.05 && z >= -0.05) {
                        z = 0.0
                    } else if (z > 0.0) {
                        z -= 0.05
                    } else {
                        z += 0.05
                    }
                }
                event.x = x
                event.z = z
            }
        }
    }
}