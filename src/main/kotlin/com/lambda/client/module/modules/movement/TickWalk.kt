package com.lambda.client.module.modules.movement

import com.lambda.client.event.listener.listener
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.module.modules.player.LagNotifier
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import net.minecraft.util.MovementInputFromOptions
import net.minecraftforge.client.event.InputUpdateEvent
import net.minecraftforge.fml.common.gameevent.TickEvent

object TickWalk : Module(
    name = "TickWalk",
    description = "Tick walk for 2b2t entity fly exploit idk",
    category = Category.MOVEMENT,
    modulePriority = 200
) {
    private val waitTicks by setting("Wait Ticks", 20, 0..50, 1)
    private val walkTicks by setting("Walk ticks", 8, 0..20, 1)
    private val timer = TickTimer(TimeUnit.TICKS)

    enum class CurrentState {
        WALKING, WAITING
    }
    private var currentState = CurrentState.WALKING;

    init {
        onEnable {
            timer.reset()
        }

        onDisable {
            currentState = CurrentState.WAITING
        }

        listener<InputUpdateEvent>(9999) {
            if (LagNotifier.paused && LagNotifier.pauseAutoWalk) return@listener

            if (it.movementInput !is MovementInputFromOptions) return@listener

            when (currentState) {
                CurrentState.WALKING -> {
                    it.movementInput.forwardKeyDown = true
                    it.movementInput.moveForward = 1.0f
                }
            }
        }

        listener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.START) return@listener
            when (currentState) {
                CurrentState.WALKING -> {
//                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode, true)
                    if (timer.tick(walkTicks)) {
                        currentState = CurrentState.WAITING
                    }
                }
                CurrentState.WAITING -> {
//                    KeyBinding.setKeyBindState(mc.gameSettings.keyBindForward.keyCode, false)
                    if (timer.tick(waitTicks)) {
                        currentState = CurrentState.WALKING
                    }
                }
            }
        }
    }
}