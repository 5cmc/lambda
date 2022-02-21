package com.lambda.client.module.modules.movement

import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.module.modules.player.ViewLock
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.threads.safeListener
import net.minecraftforge.fml.common.gameevent.TickEvent

object ElytraFlight2b2t : Module(
    name = "ElytraFlight2b2t",
    description = "go fast",
    category = Category.MOVEMENT,
    modulePriority = 1000
) {
    // todo: might add this later
//    private val autoConfigure by setting("Auto-Configure Dependent Module Settings", true)

    private var currentState = State.PAUSED
    private var timer = TickTimer(TimeUnit.TICKS)
    private var flightHeight: Double = 0.0

    enum class State {
        FLYING, WALKING, PAUSED
    }

    init {

        onEnable {
            currentState = State.PAUSED
            toggleAllOff()
            timer.reset()
        }

        onDisable {
            currentState = State.PAUSED
            toggleAllOff()
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.END) return@safeListener

            when (currentState) {
                State.PAUSED -> {
                    flightHeight = mc.player.posY + 1.0
                    currentState = State.WALKING
                    toggleWalkOn()
                }
                State.WALKING -> {
                    val currentY = mc.player.posY
                    // need to add some leniency here for actual coord being a long decimal
                    if (
                        (currentY >= flightHeight - 0.05 && currentY <= flightHeight + 0.05 || currentY > flightHeight + 1)
                            || mc.player.isElytraFlying) {
                        currentState = State.FLYING
                        toggleEflyOn()
                        toggleFlightOn()
                        timer.reset()
                    }
                }
                State.FLYING -> {
                }
            }
        }
    }

    private fun toggleAllOff() {
        mc.player.sendChatMessage(".toggle elytrafly off")
        mc.player.sendChatMessage(".toggle speed off")
        mc.player.sendChatMessage(".toggle autowalk off")
        mc.player.sendChatMessage(".toggle flight off")
        ElytraFlight.disable()
        ViewLock.disable()
    }

    private fun toggleWalkOn() {
        mc.player.sendChatMessage(".toggle elytrafly on")
        mc.player.sendChatMessage(".toggle speed on")
        mc.player.sendChatMessage(".toggle autowalk on")
        ViewLock.enable()
    }

    private fun toggleFlightOn() {
        mc.player.sendChatMessage(".toggle flight on")
    }

    private fun toggleEflyOn() {
        ElytraFlight.enable()
    }
}