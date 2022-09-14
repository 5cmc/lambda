package com.lambda.client.module.modules.render

import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.threads.safeListener
import net.minecraft.entity.Entity
import net.minecraft.util.math.MathHelper
import net.minecraftforge.client.event.EntityViewRenderEvent
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

object FreeLook : Module(
    name = "FreeLook",
    description = "Look Freely",
    category = Category.RENDER
) {
    private var dYaw: Float = 0f
    private var dPitch: Float = 0f
    private var thirdPersonBefore: Int = 0

    init {
        onEnable {
            dYaw = 0.0f
            dPitch = 0.0f
            thirdPersonBefore = mc.gameSettings.thirdPersonView
            mc.gameSettings.thirdPersonView = 1;
        }

        onDisable {
            mc.gameSettings.thirdPersonView = thirdPersonBefore
        }

        safeListener<EntityViewRenderEvent.CameraSetup> {
            if (mc.gameSettings.thirdPersonView > 0) {
                it.yaw += dYaw
                it.pitch += dPitch
            }
        }
    }

    @JvmStatic
    fun handleTurn(entity: Entity, yaw: Float, pitch: Float, ci: CallbackInfo): Boolean {
        if (isDisabled) return false
        val player = mc.player ?: return false
        return if (entity == player) {
            this.dYaw += (yaw * 0.15).toFloat()
            this.dPitch -= (pitch * 0.15).toFloat()
            this.dPitch = MathHelper.clamp(this.dPitch, -180.0f, 180.0f)
            ci.cancel()
            true
        } else {
            false
        }
    }
}