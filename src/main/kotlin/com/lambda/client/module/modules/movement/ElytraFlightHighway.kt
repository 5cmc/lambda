package com.lambda.client.module.modules.movement

import baritone.api.pathing.goals.GoalXZ
import com.lambda.client.LambdaMod
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.module.modules.player.ViewLock
import com.lambda.client.util.BaritoneUtils
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.threads.safeAsyncListener
import com.lambda.client.util.threads.safeListener
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.util.math.BlockPos
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.time.Instant

object ElytraFlightHighway : Module(
    name = "ElytraFlightHighway",
    description = "efly on the highway",
    category = Category.MOVEMENT,
    modulePriority = 1000
) {
    private val baritonePathForwardBlocks by setting("Rubberband Path Distance", 20, 1..50, 1)

    private var currentState = State.WALKING
    private var timer = TickTimer(TimeUnit.TICKS)
    private var lastSPacketPlayerPosLook: Long = Instant.now().toEpochMilli()
    private var flyTickCount = 0
    private var flyPlayerLastPos: BlockPos? = null
    private var flyBlockedTickCount = 0

    enum class State {
        FLYING, TAKEOFF, WALKING
    }

    init {

        onEnable {
            currentState = State.TAKEOFF
            toggleAllOn()
        }

        onDisable {
            currentState = State.WALKING
            toggleAllOff()
            stopPathing()
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.END) return@safeListener

            when (currentState) {
                State.WALKING -> {
                    while (isPathing()) return@safeListener
                    currentState = State.TAKEOFF
                    toggleAllOn()
                }
                State.TAKEOFF -> {
                    if (mc.player.isElytraFlying) {
                        currentState = State.FLYING
                    }
                }
                State.FLYING -> {
                    if (!mc.player.isElytraFlying) {
                        if (flyTickCount++ > 30) {
                            toggleAllOff()
                            pathForward()
                            timer.reset()
                            currentState = State.WALKING
                        }
                    } else {
                        flyTickCount = 0
                    }
                    val playerCurrentPos = mc.player.position
                    if (playerCurrentPos.equals(flyPlayerLastPos)) {
                        if (flyBlockedTickCount++ > 20) {
                            toggleAllOff()
                            pathForward()
                            timer.reset()
                            currentState = State.WALKING
                        }
                    } else {
                        flyBlockedTickCount = 0
                    }
                    flyPlayerLastPos = playerCurrentPos
                }
            }
        }

        safeAsyncListener<PacketEvent.Receive> {
            if ((currentState != State.FLYING && currentState != State.TAKEOFF) || it.packet !is SPacketPlayerPosLook) return@safeAsyncListener
            val now = Instant.now().toEpochMilli()
            if (now - lastSPacketPlayerPosLook < 1000L) {
                LambdaMod.LOG.info("Rubberband detected")
                toggleAllOff()
                pathForward()
                currentState = State.WALKING
            }
            lastSPacketPlayerPosLook = now
        }
    }

    private fun pathForward() {
        val playerContext = BaritoneUtils.primary?.playerContext!!
        BaritoneUtils.primary?.customGoalProcess?.setGoalAndPath(GoalXZ.fromDirection(
            playerContext.playerFeetAsVec(),
            playerContext.player().rotationYawHead,
            baritonePathForwardBlocks.toDouble()
        ))
    }

    private fun stopPathing() {
        BaritoneUtils.cancelEverything()
    }

    private fun isPathing(): Boolean {
        return BaritoneUtils.isActive
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