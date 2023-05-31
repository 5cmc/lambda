package com.lambda.client.module.modules.render

import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.threads.safeListener
import net.minecraftforge.fml.common.gameevent.TickEvent

object Zoom : Module(
    name = "Zoom",
    description = "Configures FOV",
    category = Category.RENDER,
    showOnArray = false
) {
    private var fov = 0f
    private var sensi = 0f

    private val fovChange = setting("FOV", 40.0f, 1.0f..180.0f, 0.5f)
    private val modifySensitivity = setting("Modify Sensitivity", true)
    private val sensitivityMultiplier = setting("Sensitivity Multiplier", 1.0f, 0.25f..2.0f, 0.25f, { modifySensitivity.value })
    private val smoothCamera = setting("Cinematic Camera", false)
    private val f5Only = setting("3rd Person Only", false)

    init {
        onEnable {
            fov = mc.gameSettings.fovSetting
            sensi = mc.gameSettings.mouseSensitivity

            if (modifySensitivity.value) mc.gameSettings.mouseSensitivity = sensi * sensitivityMultiplier.value
            mc.gameSettings.smoothCamera = smoothCamera.value
        }

        onDisable {
            mc.gameSettings.fovSetting = fov
            mc.gameSettings.mouseSensitivity = sensi
            mc.gameSettings.smoothCamera = false
        }

        safeListener<TickEvent.ClientTickEvent>(){
            if(f5Only.value){
                if(mc.gameSettings.thirdPersonView == 1) mc.gameSettings.fovSetting = fovChange.value else mc.gameSettings.fovSetting = fov
            }else{
                mc.gameSettings.fovSetting = fovChange.value
            }
            return@safeListener
        }

        fovChange.listeners.add {
            if (isEnabled) mc.gameSettings.fovSetting = fovChange.value
        }
        modifySensitivity.listeners.add {
            if (isEnabled) if (modifySensitivity.value) mc.gameSettings.mouseSensitivity = sensi * sensitivityMultiplier.value
            else mc.gameSettings.mouseSensitivity = sensi
        }
        sensitivityMultiplier.listeners.add {
            if (isEnabled) mc.gameSettings.mouseSensitivity = sensi * sensitivityMultiplier.value
        }
        smoothCamera.listeners.add {
            if (isEnabled) mc.gameSettings.smoothCamera = smoothCamera.value
        }
    }
}