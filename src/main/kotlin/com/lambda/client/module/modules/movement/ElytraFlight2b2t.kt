package com.lambda.client.module.modules.movement

import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.PlayerMoveEvent
import com.lambda.client.event.events.PlayerTravelEvent
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.manager.managers.TimerManager.resetTimer
import com.lambda.client.mixin.extension.isInWeb
import com.lambda.client.mixin.extension.tickLength
import com.lambda.client.mixin.extension.timer
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.module.modules.player.ViewLock
import com.lambda.client.util.BaritoneUtils
import com.lambda.client.util.EntityUtils.isInOrAboveLiquid
import com.lambda.client.util.MovementUtils
import com.lambda.client.util.MovementUtils.applySpeedPotionEffects
import com.lambda.client.util.MovementUtils.calcMoveYaw
import com.lambda.client.util.MovementUtils.setSpeed
import com.lambda.client.util.MovementUtils.speed
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.math.Vec2f
import com.lambda.client.util.threads.safeAsyncListener
import com.lambda.client.util.threads.safeListener
import com.lambda.client.util.world.getGroundPos
import net.minecraft.client.settings.KeyBinding
import net.minecraft.init.Items
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraftforge.client.event.InputUpdateEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.time.Instant
import kotlin.math.cos
import kotlin.math.sin

object ElytraFlight2b2t : Module(
    name = "ElytraFlight2b2t",
    description = "Go very fast on 2b2t",
    category = Category.MOVEMENT,
    modulePriority = 1000
) {
    private val ticksBetweenBoosts by setting("Ticks between boost", 11, 1..500, 1)
    private val boostDelayTicks by setting("Boost delay ticks", 16, 1..200, 1)
    private val boostAcceleration by setting("Boost speed acceleration", 1.02, 1.00..2.0, 0.001)
    private val takeoffTimerSpeed by setting("Takeoff Timer Tick Length", 250.0f, 100.0f..1000.0f, 1.0f)

    private val strafeAirSpeedBoost = 0.016f
    private val baseFlightSpeed: Double = 40.2
    private var currentState = State.PAUSED
    private var timer = TickTimer(TimeUnit.TICKS)
    private var flightHeight: Double = 0.0
    private var currentFlightSpeed: Double = 40.2
    private var shouldStartBoosting: Boolean = false;
    private val strafeTimer = TickTimer(TimeUnit.TICKS)
    private var jumpTicks = 0
    private var elytraIsEquipped = false
    private var elytraDurability = 0
    private var wasInLiquid: Boolean = false
    private var isFlying: Boolean = false
    private var isStandingStill = false
    private var isStandingStillH: Boolean = false
    private var lastSPacketPlayerPosLook: Long = Instant.now().toEpochMilli()

    enum class State {
        FLYING, WALKING, PAUSED
    }

    init {

        onEnable {
            currentState = State.PAUSED
            timer.reset()
            shouldStartBoosting = false
        }

        onDisable {
            currentState = State.PAUSED
            resetFlightSpeed()
            shouldStartBoosting = false
            mc.timer.tickLength = 50.0f
            wasInLiquid = false
            isFlying = false
        }

        safeListener<ConnectionEvent.Disconnect> {
            mc.timer.tickLength = 50.0f
            disable()
            ViewLock.disable()
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (it.phase != TickEvent.Phase.END) return@safeListener

            when (currentState) {
                State.PAUSED -> {
                    flightHeight = mc.player.posY + 1.0
                    currentState = State.WALKING
                }
                State.WALKING -> {
                    val currentY = mc.player.posY
                    // need to add some leniency here for actual coord being a long decimal
                    if (
                        (currentY >= flightHeight - 0.05 && currentY <= flightHeight + 0.05 || currentY > flightHeight + 1)
                            || mc.player.isElytraFlying) {
                        currentState = State.FLYING
                        timer.reset()
                    }
                }
                State.FLYING -> {
                    if (shouldStartBoosting) {
                        if (timer.tick(ticksBetweenBoosts, true)) {
                            setFlightSpeed(currentFlightSpeed * boostAcceleration)
                        }
                    } else {
                        if (timer.tick(boostDelayTicks, true)) {
                            shouldStartBoosting = true;
                        }
                    }
                }
            }
        }

        safeAsyncListener<PacketEvent.Receive> {
            if (currentState != State.FLYING || it.packet !is SPacketPlayerPosLook) return@safeAsyncListener
            timer.reset()
            resetFlightSpeed()
            if (Instant.now().toEpochMilli() - lastSPacketPlayerPosLook < 500L) {
                LambdaMod.LOG.info("Rubberband detected")
                mc.player.capabilities.isFlying = false
            }
            lastSPacketPlayerPosLook = Instant.now().toEpochMilli()
        }

        safeListener<PlayerTravelEvent> {
            if (shouldStrafe()) strafe()
            stateUpdate(it)
            if (currentState == State.FLYING) {
                if (elytraIsEquipped && elytraDurability > 1) {
                    if (!isFlying) {
                        takeoff(it)
                    } else {
                        mc.timer.tickLength = 50.0f
                        player.isSprinting = false
                    }
                    spoofRotation()
                }
            }
        }

        safeListener<PlayerMoveEvent> {
            if (shouldStrafe()) setSpeed(java.lang.Double.max(player.speed, applySpeedPotionEffects(0.2873)))
            else {
                reset()
            }
            if (currentState == State.FLYING) {
                setSpeed(currentFlightSpeed / 10.0)
                player.motionY = 0.0
            }
        }

        safeListener<InputUpdateEvent> {
            if (currentState == State.PAUSED) {
                it.movementInput.moveForward = 0.0f
                it.movementInput.moveStrafe = 0.0f
            }
            if (currentState != State.PAUSED) {
                it.movementInput.moveForward = 1.0f
            }
        }
    }

    private fun resetFlightSpeed() {
        setFlightSpeed(baseFlightSpeed)
    }

    private fun setFlightSpeed(speed: Double) {
        currentFlightSpeed = speed
    }

    private fun SafeClientEvent.shouldStrafe(): Boolean =
        (!player.capabilities.isFlying
            && !player.isElytraFlying
            && !mc.gameSettings.keyBindSneak.isKeyDown
            && !BaritoneUtils.isPathing
            && MovementUtils.isInputting
            && !(player.isInOrAboveLiquid || player.isInWeb))

    private fun SafeClientEvent.strafe() {
        player.jumpMovementFactor = strafeAirSpeedBoost
        jump()

        strafeTimer.reset()
    }

    private fun SafeClientEvent.jump() {
        if (player.onGround && jumpTicks <= 0) {
            if (player.isSprinting) {
                val yaw = calcMoveYaw()
                player.motionX -= sin(yaw) * 0.2
                player.motionZ += cos(yaw) * 0.2
            }

            KeyBinding.setKeyBindState(mc.gameSettings.keyBindJump.keyCode, false)
            player.motionY = 0.4
            player.isAirBorne = true
            jumpTicks = 5
        }

        jumpTicks--
    }

    private fun SafeClientEvent.reset() {
        player.jumpMovementFactor = 0.02f
        resetTimer()
        jumpTicks = 0
    }

    private fun SafeClientEvent.stateUpdate(event: PlayerTravelEvent) {
        /* Elytra Check */
        val armorSlot = player.inventory.armorInventory[2]
        elytraIsEquipped = armorSlot.item == Items.ELYTRA

        /* Elytra Durability Check */
        if (elytraIsEquipped) {
            val oldDurability = elytraDurability
            elytraDurability = armorSlot.maxDamage - armorSlot.itemDamage
        } else elytraDurability = 0

        /* wasInLiquid check */
        if (player.isInWater || player.isInLava) {
            wasInLiquid = true
        } else if (player.onGround || isFlying) {
            wasInLiquid = false
        }

        /* Elytra flying status check */
        isFlying = player.isElytraFlying

        /* Movement input check */
        isStandingStillH = player.movementInput.moveForward == 0f && player.movementInput.moveStrafe == 0f
        isStandingStill = isStandingStillH && !player.movementInput.jump && !player.movementInput.sneak
    }

    /* The best takeoff method <3 */
    private fun SafeClientEvent.takeoff(event: PlayerTravelEvent) {
        /* Pause Takeoff if server is lagging, player is in water/lava, or player is on ground */
        val timerSpeed = takeoffTimerSpeed
        val height = 0.1
        val closeToGround = player.posY <= world.getGroundPos(player).y + height && !wasInLiquid && !mc.isSingleplayer

        if (player.motionY < -0.02) {
            if (closeToGround) {
                mc.timer.tickLength = 25.0f
                return
            }

            if (!wasInLiquid && !mc.isSingleplayer) { /* Cringe moment when you use elytra flight in single player world */
                event.cancel()
                player.setVelocity(0.0, -0.02, 0.0)
            }

            if (!mc.isSingleplayer) mc.timer.tickLength = timerSpeed * 2.0f
            connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_FALL_FLYING))
        }
    }

    private fun SafeClientEvent.spoofRotation() {
        if (player.isSpectator || !elytraIsEquipped || elytraDurability <= 1 || !isFlying) return

        var cancelRotation = false
        var rotation = Vec2f(player)

        if (!isStandingStill) rotation = Vec2f(rotation.x, -2.02f)

        /* Cancels rotation packets if player is not moving and not clicking */
        cancelRotation = isStandingStill && ((!mc.gameSettings.keyBindUseItem.isKeyDown && !mc.gameSettings.keyBindAttack.isKeyDown))

        sendPlayerPacket {
            if (cancelRotation) {
                cancelRotate()
            } else {
                rotate(rotation)
            }
        }
    }
}