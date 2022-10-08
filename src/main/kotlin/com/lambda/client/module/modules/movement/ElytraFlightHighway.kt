package com.lambda.client.module.modules.movement

import baritone.api.pathing.goals.GoalXZ
import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.module.modules.player.LagNotifier
import com.lambda.client.module.modules.player.ViewLock
import com.lambda.client.util.BaritoneUtils
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.util.MovementInputFromOptions
import net.minecraft.util.math.Vec2f
import net.minecraft.util.math.Vec3d
import net.minecraftforge.client.event.InputUpdateEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.time.Instant
import com.lambda.client.util.MovementUtils.centerPlayer

object ElytraFlightHighway : Module(
    name = "ElytraFlightHighway",
    description = "efly on the highway",
    category = Category.MOVEMENT,
    modulePriority = 1000
) {
    private val baritonePathForwardBlocks by setting("Rubberband Path Distance", 20, 1..50, 1)
    private val baritoneEndDelayMs by setting("Baritone End Pathing Delay Ms", 500, 0..2000, 50)
    private val baritoneStartDelayMs by setting("Baritone Start Delay Ms", 500, 0..2000, 50)
    private val centerPlayer by setting("Center", true)
    private const val jumpDelay: Int = 10

    private var currentState = State.WALKING
    private var timer = TickTimer(TimeUnit.TICKS)
    private var lastSPacketPlayerPosLook: Long = Instant.now().toEpochMilli()
    private var flyTickCount = 0
    private var flyPlayerLastPos: Vec3d = Vec3d.ZERO
    private var flyBlockedTickCount = 0
    private var isBaritoning: Boolean = false
    private var baritoneStartTime: Long = 0L
    private var baritoneEndPathingTime: Long = 0L
    private var beforePathingPlayerPitchYaw: Vec2f = Vec2f.ZERO
    private var scheduleBaritoneJob: Job? = null

    enum class State {
        FLYING, TAKEOFF, WALKING
    }

    override fun getHudInfo(): String {
        return currentState.name
    }

    init {

        onEnable {
            currentState = State.TAKEOFF
            toggleAllOn()
        }

        onDisable {
            currentState = State.WALKING
            toggleAllOff()
            scheduleBaritoneJob?.cancel()
            stopPathing()
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.END) return@safeListener

            when (currentState) {
                State.WALKING -> {
                    if (scheduleBaritoneJob?.isActive == true) {
                        return@safeListener
                    }
                    while (isPathing()) {
                        isBaritoning = true
                        return@safeListener
                    }
                    // delay takeoff if we were pathing
                    if (isBaritoning) {
                        if (centerPlayer) if (!player.centerPlayer()) return@safeListener
                        baritoneEndPathingTime = Instant.now().toEpochMilli()
                        mc.player.rotationPitch = beforePathingPlayerPitchYaw.x
                        mc.player.rotationYaw = beforePathingPlayerPitchYaw.y
                        isBaritoning = false
                        return@safeListener
                    }
                    if (Instant.now().toEpochMilli() - baritoneEndPathingTime < baritoneEndDelayMs) {
                        return@safeListener
                    }
                    currentState = State.TAKEOFF
                    toggleAllOn()
                }
                State.TAKEOFF -> {
                    if (player.onGround && timer.tick(jumpDelay.toLong())) player.jump()
                    if (mc.player.isElytraFlying) {
                        currentState = State.FLYING
                    }
                }
                State.FLYING -> {
                    if (!mc.player.isElytraFlying) {
                        if (flyTickCount++ > 30) {
                            toggleAllOff()
                            pathForward()
                            currentState = State.WALKING
                        }
                    } else {
                        flyTickCount = 0
                    }
                    val playerCurrentPos = mc.player.positionVector
                    if (playerCurrentPos.distanceTo(flyPlayerLastPos) < 2.0) {
                        if (flyBlockedTickCount++ > 20) {
                            toggleAllOff()
                            pathForward()
                            currentState = State.WALKING
                        }
                    } else {
                        flyBlockedTickCount = 0
                    }
                    flyPlayerLastPos = playerCurrentPos
                }
            }
        }

        safeListener<PacketEvent.Receive> {
            if ((currentState != State.FLYING && currentState != State.TAKEOFF && !isPathing()) || it.packet !is SPacketPlayerPosLook) return@safeListener
            val now = Instant.now().toEpochMilli()
            if (now - lastSPacketPlayerPosLook < 1000L) {
                LambdaMod.LOG.info("Rubberband detected")
                toggleAllOff()
                pathForward()
                currentState = State.WALKING
            }
            lastSPacketPlayerPosLook = now
        }

        safeListener<InputUpdateEvent>(6969) {
            if (currentState != State.TAKEOFF) return@safeListener
            if (LagNotifier.isBaritonePaused && LagNotifier.pauseAutoWalk) return@safeListener
            if (it.movementInput !is MovementInputFromOptions) return@safeListener
            it.movementInput.moveForward = 1.0f
        }
    }

    private fun SafeClientEvent.pathForward() {
        beforePathingPlayerPitchYaw = mc.player.pitchYaw
        if (scheduleBaritoneJob?.isActive == true) return
        baritoneStartTime = Instant.now().toEpochMilli()
        scheduleBaritoneJob = defaultScope.launch {
            delay(baritoneStartDelayMs.toLong())
            val playerContext = BaritoneUtils.primary?.playerContext!!
            BaritoneUtils.primary?.customGoalProcess?.setGoalAndPath(GoalXZ.fromDirection(
                playerContext.playerFeetAsVec(),
                playerContext.player().rotationYawHead,
                baritonePathForwardBlocks.toDouble()
            ))
        }
    }

    private fun stopPathing() {
        BaritoneUtils.cancelEverything()
    }

    private fun isPathing(): Boolean {
        return BaritoneUtils.isActive
    }

    private fun toggleAllOff() {
        ElytraFlight2b2t.disable()
        ViewLock.disable()
    }

    private fun toggleAllOn() {
        ViewLock.enable()
        ElytraFlight2b2t.enable()
    }
}