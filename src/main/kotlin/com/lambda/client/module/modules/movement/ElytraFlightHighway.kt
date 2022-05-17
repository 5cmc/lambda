package com.lambda.client.module.modules.movement

import com.lambda.client.LambdaMod
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.module.modules.player.ViewLock
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.threads.safeAsyncListener
import com.lambda.client.util.threads.safeListener
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.time.Instant

object ElytraFlightHighway : Module(
    name = "ElytraFlightHighway",
    description = "efly on the highway",
    category = Category.MOVEMENT,
    modulePriority = 1000
) {
    private val waitTicks by setting("Rubberband Wait Ticks", 80, 0..300, 1)

    private var currentState = State.PAUSED
    private var timer = TickTimer(TimeUnit.TICKS)
    private var lastSPacketPlayerPosLook: Long = Instant.now().toEpochMilli()
    private var flyTickCount = 0


    enum class State {
        FLYING, WALKING, PAUSED
    }

    init {

        onEnable {
            currentState = State.WALKING
            toggleAllOff()
            toggleAllOn()
        }

        onDisable {
            currentState = State.PAUSED
            toggleAllOff()
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.END) return@safeListener

            when (currentState) {
                State.PAUSED -> {
                    if (timer.tick(waitTicks)) {
                        currentState = State.WALKING
                        toggleAllOn()
                    }
                }
                State.WALKING -> {
                    if (mc.player.isElytraFlying) {
                        currentState = State.FLYING
                    }
                }
                State.FLYING -> {
                    if (!mc.player.isElytraFlying) {
                        if (flyTickCount++ > 30) {
                            toggleAllOff()
                            timer.reset()
                            currentState = State.PAUSED
                        }
                    } else {
                        flyTickCount = 0
                    }
                }
            }
        }

        safeAsyncListener<PacketEvent.Receive> {
            if ((currentState != State.FLYING && currentState != State.WALKING) || it.packet !is SPacketPlayerPosLook) return@safeAsyncListener
            val now = Instant.now().toEpochMilli()
            if (now - lastSPacketPlayerPosLook < 1000L) {
                LambdaMod.LOG.info("Rubberband detected")
                currentState = State.PAUSED
                toggleAllOff()
                timer.reset()
            }
            lastSPacketPlayerPosLook = now
        }
    }


    private fun toggleAllOff() {
        ElytraFlight2b2t.disable()
        Speed.disable()
        ViewLock.disable()
        AutoWalk.disable()
    }

    private fun toggleAllOn() {
        Speed.enable()
        AutoWalk.enable()
        ViewLock.enable()
        ElytraFlight2b2t.enable()
    }
}