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
    private var cameraYaw: Float = 0f
    private var cameraPitch: Float = 0f
    private var thirdPersonBefore: Int = 0

    init {
        onEnable {
            thirdPersonBefore = mc.gameSettings.thirdPersonView
            mc.gameSettings.thirdPersonView = 1;
            cameraYaw = mc.player.rotationYaw + 180.0f
            cameraPitch = mc.player.rotationPitch
        }

        onDisable {
            mc.gameSettings.thirdPersonView = thirdPersonBefore
        }

        safeListener<EntityViewRenderEvent.CameraSetup> {
            if (mc.gameSettings.thirdPersonView > 0) {
                it.yaw = cameraYaw
                it.pitch = cameraPitch
            }
        }
    }

    @JvmStatic
    fun handleTurn(entity: Entity, yaw: Float, pitch: Float, ci: CallbackInfo): Boolean {
        if (isDisabled) return false
        val player = mc.player ?: return false
        return if (entity == player) {
            this.cameraYaw += (yaw * 0.15).toFloat()
            this.cameraPitch -= (pitch * 0.15).toFloat()
            this.cameraPitch = MathHelper.clamp(this.cameraPitch, -180.0f, 180.0f)
            ci.cancel()
            true
        } else {
            false
        }
    }
}