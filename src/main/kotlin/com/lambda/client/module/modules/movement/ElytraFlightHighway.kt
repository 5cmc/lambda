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
    description = "280km/h elytra fly on highway",
    category = Category.MOVEMENT,
    modulePriority = 1000
) {
    private val waitTicks by setting("Rubberband Wait Ticks", 80, 0..150, 1)
    private val flightToggleY by setting("Flight Toggle Y", 121.0, 120.0..122.0, .05)
    private val glideToggleY by setting("Glide Toggle Y", 120.4, 118.0..121.0, .05)

    private var currentState = State.PAUSED
    private var timer = TickTimer(TimeUnit.TICKS)
    private var lastSPacketPlayerPosLook: Long = Instant.now().toEpochMilli()

    enum class State {
        FLYING, WALKING, PAUSED, GLIDE
    }

    init {

        onEnable {
            currentState = State.WALKING
            toggleAllOff()
            toggleWalkOn()
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
                        toggleWalkOn()
                    }
                }
                State.WALKING -> {
                    val currentY = mc.player.posY
                    // need to add some leniency here for actual coord being a long decimal
                    if (currentY >= flightToggleY - 0.05 && currentY <= flightToggleY + 0.05) {
                        currentState = State.FLYING
                        toggleFlightOn()
                        timer.reset()
                    }
                }
                State.FLYING -> {
                    val currentY = mc.player.posY
                    if (currentY <= glideToggleY) {
                        toggleFlightOff()
                        currentState = State.GLIDE
                    }
                }
                State.GLIDE -> {
                    if (!mc.player.isElytraFlying) {
                        currentState = State.WALKING
                        timer.reset()
                    }
                }
            }
        }

        safeAsyncListener<PacketEvent.Receive> {
            if (currentState != State.FLYING || it.packet !is SPacketPlayerPosLook) return@safeAsyncListener
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
        mc.player.sendChatMessage(".toggle elytrafly off")
        mc.player.sendChatMessage(".toggle speed off")
        mc.player.sendChatMessage(".toggle autowalk off")
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
        ElytraFlight.enable()
    }

    private fun toggleFlightOff() {
        ElytraFlight.disable()
    }
}