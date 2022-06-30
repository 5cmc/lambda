package com.lambda.client.module.modules.player

import com.lambda.client.event.Phase
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.OnUpdateWalkingPlayerEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.events.PlayerTravelEvent
import com.lambda.client.event.listener.listener
import com.lambda.client.manager.managers.HotbarManager.resetHotbar
import com.lambda.client.manager.managers.HotbarManager.serverSideItem
import com.lambda.client.manager.managers.HotbarManager.spoofHotbar
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.mixin.extension.syncCurrentPlayItem
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.EntityUtils.prevPosVector
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.items.*
import com.lambda.client.util.math.RotationUtils.getRotationTo
import com.lambda.client.util.math.VectorUtils.toBlockPos
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.safeListener
import com.lambda.client.util.world.PlaceInfo
import com.lambda.client.util.world.getNeighbour
import com.lambda.client.util.world.placeBlock
import com.lambda.mixin.entity.MixinEntity
import com.lambda.schematic.LambdaSchematicaHelper
import com.lambda.schematic.Schematic
import net.minecraft.block.BlockStainedGlass.COLOR
import net.minecraft.block.state.IBlockState
import net.minecraft.init.Blocks.AIR
import net.minecraft.init.Blocks.STAINED_GLASS
import net.minecraft.item.EnumDyeColor
import net.minecraft.item.ItemBlock
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.util.EnumFacing
import net.minecraft.util.Tuple
import net.minecraft.util.math.BlockPos
import net.minecraft.util.math.RayTraceResult
import net.minecraft.util.math.Vec3d
import java.util.*
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * @see MixinEntity.moveInvokeIsSneakingPre
 * @see MixinEntity.moveInvokeIsSneakingPost
 */
object Scaffold : Module(
    name = "Scaffold",
    description = "Places blocks under you",
    category = Category.PLAYER,
    modulePriority = 500
) {
    private val tower by setting("Tower", true)
    private val spoofHotbar by setting("Spoof Hotbar", true)
    val safeWalk by setting("Safe Walk", true)
    private val sneak by setting("Sneak", true)
    private val strictDirection by setting("Strict Direction", false)
    private val delay by setting("Delay", 2, 1..10, 1, unit = " ticks")
    private val maxRange by setting("Max Range", 1, 0..3, 1)
    private val schematicaBuild by setting("Schematic", true, consumer = this::schematicToggle)

    private var lastHitVec: Vec3d? = null
    private var placeInfo: PlaceInfo? = null
    private var inactiveTicks = 69
    private var loadedSchematic: Schematic? = null
    private var loadedSchematicOrigin: BlockPos? = null

    private val placeTimer = TickTimer(TimeUnit.TICKS)
    private val rubberBandTimer = TickTimer(TimeUnit.TICKS)

    override fun isActive(): Boolean {
        return isEnabled && inactiveTicks <= 5
    }

    init {
        onDisable {
            placeInfo = null
            inactiveTicks = 69
        }

        onEnable {
            // todo: check if this is necessary
            schematicToggle(true, schematicaBuild)
        }

        listener<PacketEvent.Receive> {
            if (it.packet !is SPacketPlayerPosLook) return@listener
            rubberBandTimer.reset()
        }

        safeListener<PlayerTravelEvent> {
            if (!tower || !mc.gameSettings.keyBindJump.isKeyDown || inactiveTicks > 5 || !isHoldingBlock) return@safeListener
            if (rubberBandTimer.tick(10, false)) {
                if (shouldTower) player.motionY = 0.41999998688697815
            } else if (player.fallDistance <= 2.0f) {
                player.motionY = -0.169
            }
        }
    }

    private fun schematicToggle(prev: Boolean, input: Boolean): Boolean {
        if (input) {
            val schematic = loadSchematic()
            if (schematic.isPresent) {
                this.loadedSchematic = schematic.get().first
                this.loadedSchematicOrigin = schematic.get().second
            } else {
                MessageSendHelper.sendChatMessage("No loaded schematic found")
                return false
            }
        } else {
            this.loadedSchematic = null
            this.loadedSchematicOrigin = null
        }
        return input
    }

    private val SafeClientEvent.isHoldingBlock: Boolean
        get() = player.serverSideItem.item is ItemBlock

    private val SafeClientEvent.shouldTower: Boolean
        get() = !player.onGround
            && player.posY - floor(player.posY) <= 0.1

    init {
        safeListener<OnUpdateWalkingPlayerEvent> { event ->
            if (event.phase != Phase.PRE) return@safeListener

            inactiveTicks++
            placeInfo = calcNextPos()?.let {
                getNeighbour(it, 1, visibleSideCheck = strictDirection, sides = arrayOf(EnumFacing.DOWN))
                    ?: getNeighbour(it, 3, visibleSideCheck = strictDirection, sides = EnumFacing.HORIZONTALS)
            }

            placeInfo?.let {
                lastHitVec = it.hitVec
                swapAndPlace(it)
            }

            if (inactiveTicks > 5) {
                resetHotbar()
            } else if (isHoldingBlock) {
                lastHitVec?.let {
                    sendPlayerPacket {
                        rotate(getRotationTo(it))
                    }
                }
            }
        }
    }

    private fun SafeClientEvent.calcNextPos(): BlockPos? {
        val posVec = player.positionVector
        val blockPos = posVec.toBlockPos()
        return checkPos(blockPos)
            ?: run {
                val realMotion = posVec.subtract(player.prevPosVector)
                val nextPos = blockPos.add(roundToRange(realMotion.x), 0, roundToRange(realMotion.z))
                checkPos(nextPos)
            }
    }

    private fun SafeClientEvent.checkPos(blockPos: BlockPos): BlockPos? {
        val center = Vec3d(blockPos.x + 0.5, blockPos.y.toDouble(), blockPos.z + 0.5)
        val rayTraceResult = world.rayTraceBlocks(
            center,
            center.subtract(0.0, 0.5, 0.0),
            false,
            true,
            false
        )
        return blockPos.down().takeIf { rayTraceResult?.typeOfHit != RayTraceResult.Type.BLOCK }
    }

    private fun roundToRange(value: Double) =
        (value * 2.5 * maxRange).roundToInt().coerceAtMost(maxRange)

    private fun SafeClientEvent.swapAndPlace(placeInfo: PlaceInfo) {
        if (schematicaBuild && loadedSchematic != null && loadedSchematicOrigin != null) {
            val blockTypeForSchematicBlockPos: IBlockState? = getSchematicBlockState(loadedSchematic!!, loadedSchematicOrigin!!, placeInfo.placedPos)
            if (blockTypeForSchematicBlockPos != null) {
                if (blockTypeForSchematicBlockPos.block == AIR) return
                if (!swapToBlockOrMove(this@Scaffold, blockTypeForSchematicBlockPos.block, predicateItem = { it.item.block.getStateFromMeta(it.metadata).equals(blockTypeForSchematicBlockPos)})) {
                    if (blockTypeForSchematicBlockPos.block == STAINED_GLASS) {
                        val color: EnumDyeColor = blockTypeForSchematicBlockPos.properties.get(COLOR) as EnumDyeColor
                        MessageSendHelper.sendChatMessage("$chatName No ${color.dyeColorName} ${blockTypeForSchematicBlockPos.block.localizedName} was found in inventory.")
                    } else {
                        MessageSendHelper.sendChatMessage("$chatName No ${blockTypeForSchematicBlockPos.block.localizedName} was found in inventory.")
                    }
                    return
                }
                // todo: remove duplicated logic
                inactiveTicks = 0

                if (placeTimer.tick(delay.toLong())) {
                    val shouldSneak = sneak && !player.isSneaking
                    if (shouldSneak) connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
                    placeBlock(placeInfo)
                    if (shouldSneak) connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.STOP_SNEAKING))
                }
            }
        } else {
            getBlockSlot()?.let { slot ->
                if (spoofHotbar) spoofHotbar(slot)
                else swapToSlot(slot)

                inactiveTicks = 0

                if (placeTimer.tick(delay.toLong())) {
                    val shouldSneak = sneak && !player.isSneaking
                    if (shouldSneak) connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))
                    placeBlock(placeInfo)
                    if (shouldSneak) connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.STOP_SNEAKING))
                }
            }
        }
    }

    private fun SafeClientEvent.getBlockSlot(): HotbarSlot? {
        playerController.syncCurrentPlayItem()
        return player.hotbarSlots.firstItem<ItemBlock, HotbarSlot>()
    }

    private fun loadSchematic(): Optional<Tuple<Schematic, BlockPos>> {
        return if (LambdaSchematicaHelper.isSchematicaPresent()) {
            LambdaSchematicaHelper.getOpenSchematic()
        } else {
            Optional.empty()
        }
    }

    private fun getSchematicBlockState(schematic: Schematic, origin: BlockPos, blockPos: BlockPos): IBlockState? {
        return if (schematic.inSchematic(blockPos.x - origin.x, blockPos.y - origin.y, blockPos.z - origin.z)) {
            return schematic.desiredState(blockPos.x - origin.x, blockPos.y - origin.y, blockPos.z - origin.z)
        } else null
    }
}