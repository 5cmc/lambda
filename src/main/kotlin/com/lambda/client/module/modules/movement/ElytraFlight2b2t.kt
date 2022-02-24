package com.lambda.client.module.modules.movement

import com.lambda.client.LambdaMod
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.PlayerMoveEvent
import com.lambda.client.event.events.PlayerTravelEvent
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.mixin.extension.tickLength
import com.lambda.client.mixin.extension.timer
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.module.modules.player.ViewLock
import com.lambda.client.util.MovementUtils.setSpeed
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.math.Vec2f
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.safeAsyncListener
import com.lambda.client.util.threads.safeListener
import com.lambda.client.util.world.getGroundPos
import net.minecraft.init.Items
import net.minecraft.inventory.ClickType
import net.minecraft.item.ItemElytra
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.CPacketConfirmTeleport
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraftforge.client.event.InputUpdateEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import java.time.Instant

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
    private val minHoverTakeoffHeight by setting("Min Hover Takeoff Height", 0.5, 0.0..1.0, 0.01)

    private val baseFlightSpeed: Double = 40.2
    private var currentState = State.PAUSED
    private var timer = TickTimer(TimeUnit.TICKS)
    private var currentFlightSpeed: Double = 40.2
    private var shouldStartBoosting: Boolean = false;
    private var elytraIsEquipped = false
    private var elytraDurability = 0
    private var wasInLiquid: Boolean = false
    private var isFlying: Boolean = false
    private var isStandingStill = false
    private var isStandingStillH: Boolean = false
    private var lastSPacketPlayerPosLook: Long = Instant.now().toEpochMilli()
    private var unequipedElytra: Boolean = false
    private var shouldHover: Boolean = false

    enum class State {
        FLYING, PRETAKEOFF, PAUSED, HOVER
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
            shouldHover = false
            unequipedElytra = false
        }

        safeListener<ConnectionEvent.Disconnect> {
            mc.timer.tickLength = 50.0f
            disable()
            ViewLock.disable()
        }

        safeListener<TickEvent.ClientTickEvent>(priority = 9999) {
            if (it.phase != TickEvent.Phase.END) return@safeListener

            when (currentState) {
                State.PAUSED -> {
                    val armorSlot = player.inventory.armorInventory[2]
                    elytraIsEquipped = armorSlot.item == Items.ELYTRA
                    if (!elytraIsEquipped) {
                        MessageSendHelper.sendChatMessage("No Elytra equipped")
                        disable()
                    }
                    if (armorSlot.maxDamage <= 5) {
                        MessageSendHelper.sendChatMessage("Equipped Elytra broken or almost broken")
                        disable()
                    }
                    currentState = State.PRETAKEOFF
                }
                State.PRETAKEOFF -> {
                    val expectedMotionY = -0.16976 // magic number, do not question
                    val notCloseToGround = player.posY >= world.getGroundPos(player).y + minHoverTakeoffHeight && !wasInLiquid && !mc.isSingleplayer

                    if ((withinRange(mc.player.motionY, expectedMotionY, 0.05)) && !mc.player.isElytraFlying) {
                        currentState = State.FLYING
                        timer.reset()
                    } else if (!unequipedElytra && (mc.player.isElytraFlying || notCloseToGround)) {
                        currentState = State.HOVER
                    }
                }
                State.HOVER -> {
                    val armorSlot = player.inventory.armorInventory[2]
                    elytraIsEquipped = armorSlot.item == Items.ELYTRA
                    if (elytraIsEquipped && !unequipedElytra) {
                        mc.playerController.windowClick(0, 6, 0,
                            ClickType.QUICK_MOVE, mc.player)
                        unequipedElytra = true
                        shouldHover = true
                    } else if (!elytraIsEquipped && unequipedElytra) {
                        var slot: Int = getSlotOfNextElytra()
                        mc.playerController.windowClick(0, slot, 0, ClickType.QUICK_MOVE, mc.player)
                        shouldHover = true
                    } else if (elytraIsEquipped && unequipedElytra) {
                        // ok, ready to fall
                        shouldHover = false
                        currentState = State.PRETAKEOFF
                    }
                }
                State.FLYING -> {
                    unequipedElytra = false
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
            if (it.packet is SPacketPlayerPosLook) {
                if (currentState == State.FLYING) {
                    timer.reset()
                    resetFlightSpeed()
                    if (Instant.now().toEpochMilli() - lastSPacketPlayerPosLook < 1000L) {
                        LambdaMod.LOG.info("Rubberband detected")
                        currentState = State.PRETAKEOFF
                    }
                    lastSPacketPlayerPosLook = Instant.now().toEpochMilli()
                } else if (shouldHover) {
                    it.cancel()
                    connection.sendPacket(CPacketConfirmTeleport(it.packet.teleportId))
                }
            }
        }

        safeListener<PlayerTravelEvent> {
            stateUpdate(it)
            if (currentState == State.FLYING) {
                if (elytraIsEquipped && elytraDurability > 1) {
                    if (!isFlying) {
                        takeoff(it)
                    } else {
                        mc.timer.tickLength = 50.0f
                        player.isSprinting = false
                    }
                }
            }
            spoofRotation()
        }

        safeListener<PlayerMoveEvent> {
            if (currentState == State.FLYING && mc.player.isElytraFlying) {
                setSpeed(currentFlightSpeed / 10.0)
                player.motionY = 0.0
            } else if (shouldHover) {
                setSpeed(0.0)
                player.motionY = 0.0
            }
        }

        safeListener<InputUpdateEvent> {
            if (currentState == State.PAUSED) {
                it.movementInput.moveForward = 0.0f
                it.movementInput.moveStrafe = 0.0f
            }
            if (currentState != State.PAUSED && !mc.player.onGround) {
                it.movementInput.moveForward = 1.0f
            }
        }
    }

    private fun withinRange(num: Double, target: Double, range: Double): Boolean {
        return (num >= target - range && num <= target + range)
    }

    private fun resetFlightSpeed() {
        setFlightSpeed(baseFlightSpeed)
    }

    private fun getSlotOfNextElytra(): Int {
        (0..44).forEach { slot ->
            val stack = mc.player.inventory.getStackInSlot(slot)
            if (stack.item !is ItemElytra) return@forEach

            if (stack.count > 1) return@forEach

            if (!isItemBroken(stack)) {
                return slot
            }
        }
        return -1
    }

    private fun isItemBroken(itemStack: ItemStack): Boolean { // (100 * damage / max damage) >= (100 - 70)
        return if (itemStack.maxDamage == 0) {
            false
        } else {
            itemStack.maxDamage - itemStack.itemDamage <= 5
        }
    }

    private fun setFlightSpeed(speed: Double) {
        currentFlightSpeed = speed
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

        var rotation = Vec2f(player)

        if (!isStandingStill) rotation = Vec2f(rotation.x, -2.02f)

        /* Cancels rotation packets if player is not moving and not clicking */
        var cancelRotation = isStandingStill && ((!mc.gameSettings.keyBindUseItem.isKeyDown && !mc.gameSettings.keyBindAttack.isKeyDown))

        sendPlayerPacket {
            if (cancelRotation) {
                cancelRotate()
            } else {
                rotate(rotation)
            }
        }
    }
}