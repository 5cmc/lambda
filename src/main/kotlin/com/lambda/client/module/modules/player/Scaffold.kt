package com.lambda.client.module.modules.player

import com.lambda.client.LambdaMod
import com.lambda.client.commons.interfaces.DisplayEnum
import com.lambda.client.event.Phase
import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.*
import com.lambda.client.event.listener.listener
import com.lambda.client.manager.managers.HotbarManager.serverSideItem
import com.lambda.client.manager.managers.HotbarManager.spoofHotbar
import com.lambda.client.manager.managers.PlayerPacketManager.sendPlayerPacket
import com.lambda.client.mixin.extension.syncCurrentPlayItem
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.setting.settings.impl.collection.CollectionSetting
import com.lambda.client.util.EntityUtils.flooredPosition
import com.lambda.client.util.MovementUtils.speed
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.ESPRenderer
import com.lambda.client.util.items.*
import com.lambda.client.util.math.RotationUtils.getRotationTo
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.safeListener
import com.lambda.client.util.world.PlaceInfo
import com.lambda.client.util.world.getNeighbour
import com.lambda.client.util.world.isFullBox
import com.lambda.client.util.world.placeBlock
import com.lambda.schematic.LambdaSchematicaHelper
import com.lambda.schematic.Schematic
import net.minecraft.block.Block
import net.minecraft.block.BlockStainedGlass
import net.minecraft.block.state.IBlockState
import net.minecraft.init.Blocks
import net.minecraft.init.Blocks.AIR
import net.minecraft.item.EnumDyeColor
import net.minecraft.item.Item
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.network.play.client.CPacketEntityAction
import net.minecraft.network.play.server.SPacketBlockChange
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.util.Tuple
import net.minecraft.util.math.BlockPos
import net.minecraftforge.client.event.InputUpdateEvent
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent
import java.util.*
import java.util.concurrent.ConcurrentHashMap

object Scaffold : Module(
    name = "Scaffold",
    description = "Places blocks under you",
    category = Category.PLAYER,
    modulePriority = 500
) {
    private val page by setting("Page", Page.GENERAL)

    private val blockSelectionMode by setting("Block Selection Mode", ScaffoldBlockSelectionMode.ANY, { page == Page.GENERAL })
    private val tower by setting("Tower", true, { page == Page.GENERAL })
    private val spoofHotbar by setting("Spoof Hotbar", true, { page == Page.GENERAL })
    val safeWalk by setting("Safe Walk", true, { page == Page.GENERAL })
    private val useNoFall by setting("No Fall", false, { page == Page.GENERAL })
    private val descendOnSneak by setting("Descend on sneak", true, { page == Page.GENERAL })
    private val visibleSideCheck by setting("Visible side check", true, { page == Page.GENERAL })
    private val delay by setting("Delay", 0, 0..10, 1, { page == Page.GENERAL }, unit = " ticks")
    private val timeout by setting("Timeout", 15, 1..40, 1, { page == Page.GENERAL }, unit = " ticks")
    private val attempts by setting("Placement Search Depth", 3, 0..7, 1, { page == Page.GENERAL })
    private val maxPending by setting("Max Pending", 3, 0..10, 1, { page == Page.GENERAL })
    private val below by setting("Max Tower Distance", 0.3, 0.0..2.0, 0.01, { page == Page.GENERAL })
    private val filled by setting("Filled", true, { page == Page.RENDER }, description = "Renders surfaces")
    private val outline by setting("Outline", true, { page == Page.RENDER }, description = "Renders outline")
    private val alphaFilled by setting("Alpha Filled", 26, 0..255, 1, { filled && page == Page.RENDER }, description = "Alpha for surfaces")
    private val alphaOutline by setting("Alpha Outline", 26, 0..255, 1, { outline && page == Page.RENDER }, description = "Alpha for outline")
    private val thickness by setting("Outline Thickness", 2f, .25f..4f, .25f, { outline && page == Page.RENDER }, description = "Changes thickness of the outline")
    private val pendingBlockColor by setting("Pending Color", ColorHolder(0, 0, 255), visibility = { page == Page.RENDER })

    val blockSelectionWhitelist = setting(CollectionSetting("BlockWhitelist", linkedSetOf("minecraft:obsidian"), String::class.java, { false }))
    val blockSelectionBlacklist = setting(CollectionSetting("BlockBlacklist", blockBlacklist.map { it.registryName.toString() }.toMutableSet(), String::class.java, { false }))

    private enum class Page {
        GENERAL, RENDER
    }

    private enum class ScaffoldBlockSelectionMode(
        override val displayName: String,
        val filter: (ItemStack, PlaceInfo) -> Boolean): DisplayEnum {
        ANY("Any", { it, _ -> it.item.isUsableItem() }),
        WHITELIST("Whitelist", { it, _ -> blockSelectionWhitelist.contains(it.item.registryName.toString()) && it.item.isUsableItem() }),
        BLACKLIST("Blacklist", { it, _ -> !blockSelectionBlacklist.contains(it.item.registryName.toString()) && it.item.isUsableItem() }),
        SCHEMATIC("Schematic", { item, placeInfo -> schematicFilter(item, placeInfo) });
    }
    fun schematicFilter(itemStack: ItemStack, placeInfo: PlaceInfo): Boolean {
        if (loadedSchematic != null && loadedSchematicOrigin != null) {
            getSchematicBlockState(loadedSchematic!!, loadedSchematicOrigin!!, placeInfo.placedPos)?.let {
                if (it.block == AIR) return false
                return itemStack.item.block.getStateFromMeta(itemStack.metadata).equals(it)
            }
        }
        return false
    }

    private var placeInfo: PlaceInfo? = null
    private val renderer = ESPRenderer()

    private val placeTimer = TickTimer(TimeUnit.TICKS)
    private val towerTimer: TickTimer = TickTimer(TimeUnit.TICKS)
    private val waterTowerTimer: TickTimer = TickTimer(TimeUnit.TICKS)
    private val posLookTimer: TickTimer = TickTimer(TimeUnit.TICKS)
    private var oldNoFall = false
    private var oldFallMode = NoFall.Mode.CATCH
    private var goDown = false
    private var loadedSchematic: Schematic? = null
    private var loadedSchematicOrigin: BlockPos? = null

    private val pendingBlocks = ConcurrentHashMap<BlockPos, PendingBlock>()

    init {
        onEnable {
            towerTimer.reset()
            schematicToggle(true, blockSelectionMode == ScaffoldBlockSelectionMode.SCHEMATIC)

            if (!useNoFall) return@onEnable

            oldNoFall = NoFall.isEnabled
            oldFallMode = NoFall.mode

            NoFall.mode = NoFall.Mode.CATCH
            NoFall.enable()
        }

        onDisable {
            placeInfo = null
            pendingBlocks.clear()

            if (!useNoFall) return@onDisable
            if (!oldNoFall) NoFall.disable()

            NoFall.mode = oldFallMode
            oldNoFall = false
        }

        safeListener<PacketEvent.Receive> { event ->
            when (val packet = event.packet) {
                is SPacketPlayerPosLook -> {
                    pendingBlocks.forEach {
                        world.setBlockState(it.key, it.value.blockState)
                    }
                    pendingBlocks.clear()
                    posLookTimer.reset()
                }
                is SPacketBlockChange -> {
                    pendingBlocks.remove(packet.blockPosition)
                }
            }
        }

        safeListener<PlayerTravelEvent> {
            if (!tower || !mc.gameSettings.keyBindJump.isKeyDown || !isHoldingBlock || !posLookTimer.tick(15, false)) {
                towerTimer.reset()
                return@safeListener
            }
            if (player.isInWater || world.getBlockState(player.flooredPosition).material.isLiquid) {
                player.motionY = .11
                towerTimer.reset()
                waterTowerTimer.reset()
            } else if (shouldTower) {
                if (waterTowerTimer.tick(5, false)) {
                    player.jump()
                    if (towerTimer.tick(30)) {
                        // reset pos back onto top block
                        player.motionY = -0.3
                    }
                } else {
                    towerTimer.reset()
                }
            }
        }

        listener<RenderWorldEvent> {
            renderer.aFilled = if (filled) alphaFilled else 0
            renderer.aOutline = if (outline) alphaOutline else 0
            renderer.thickness = thickness

            pendingBlocks.keys.forEach {
                renderer.add(it, pendingBlockColor)
            }

            renderer.render(clear = true)
        }

        safeListener<PushOutOfBlocksEvent> {
            if (tower) it.cancel()
        }
    }

    private val SafeClientEvent.isHoldingBlock: Boolean
        get() = player.serverSideItem.item is ItemBlock

    private val SafeClientEvent.shouldTower: Boolean
        get() = !player.onGround
            && world.getCollisionBoxes(player, player.entityBoundingBox.offset(0.0, -below, 0.0)).isNotEmpty()
            && world.getCollisionBoxes(player, player.entityBoundingBox).isEmpty()
            && !player.capabilities.isFlying
            && player.speed < 0.1
//            && (getHeldScaffoldBlock(placeInfo) != null || getBlockSlot(placeInfo) != null)

    init {
        safeListener<ClientTickEvent> { event ->
            if (event.phase != TickEvent.Phase.START) return@safeListener

            pendingBlocks.values
                .filter { it.age > timeout * 50L }
                .forEach { pendingBlock ->
                    LambdaMod.LOG.warn("$chatName Timeout: ${pendingBlock.blockPos}")
                    pendingBlocks.remove(pendingBlock.blockPos)
                    world.setBlockState(pendingBlock.blockPos, pendingBlock.blockState)
                }

            placeInfo?.let { placeInfo ->
                pendingBlocks[placeInfo.placedPos]?.let {
                    if (it.age < timeout * 50L) {
                        return@safeListener
                    }
                }
                swap(placeInfo)?.let { block ->
                    place(placeInfo, block)
                    sendPlayerPacket {
                        rotate(getRotationTo(placeInfo.hitVec))
                    }
                }
            }
        }

        safeListener<OnUpdateWalkingPlayerEvent> { event ->
            if (event.phase != Phase.PRE) return@safeListener

            val origin = if (goDown && descendOnSneak) {
                goDown = false
                player.flooredPosition.down(2)
            } else {
                player.flooredPosition.down()
            }

            placeInfo = getNeighbour(
                BlockPos(origin.x, origin.y.coerceIn(0..256), origin.z),
                attempts,
                visibleSideCheck = visibleSideCheck
            )
        }

        safeListener<InputUpdateEvent> {
            if (!descendOnSneak
                || !it.movementInput.sneak
                || player.capabilities.isFlying
            ) return@safeListener

            goDown = true
            it.movementInput.sneak = false
            it.movementInput.moveStrafe *= 5f
            it.movementInput.moveForward *= 5f
        }
    }

    private fun SafeClientEvent.swap(placeInfo: PlaceInfo): Block? {
        getHeldScaffoldBlock(placeInfo)?.let { return it }
        getBlockSlot(placeInfo)?.let { slot ->
            if (spoofHotbar) spoofHotbar(slot)
            else swapToSlot(slot)
            return slot.stack.item.block
        }
        if (swapToBlockOrMove<Block>(this@Scaffold, { blockSelectionMode.filter(it, placeInfo) } )) {
            getBlockSlot(placeInfo)?.let { slot ->
                if (spoofHotbar) spoofHotbar(slot)
                else swapToSlot(slot)
                return slot.stack.item.block
            }
        } else if (blockSelectionMode == ScaffoldBlockSelectionMode.SCHEMATIC) {
            if (loadedSchematic != null && loadedSchematicOrigin != null) {
                getSchematicBlockState(loadedSchematic!!, loadedSchematicOrigin!!, placeInfo.placedPos)?.let {
                    if (it.block != AIR) {
                        if (it.block == Blocks.STAINED_GLASS) {
                            val color: EnumDyeColor = it.properties.get(BlockStainedGlass.COLOR) as EnumDyeColor
                            MessageSendHelper.sendChatMessage("$chatName No ${color.dyeColorName} ${it.block.localizedName} was found in inventory.")
                        } else {
                            MessageSendHelper.sendChatMessage("$chatName No ${it.block.localizedName} was found in inventory.")
                        }
                    }
                }
            }
        }
        return null
    }

    private fun SafeClientEvent.place(placeInfo: PlaceInfo, blockToPlace: Block) {
        if (placeTimer.tick(delay.toLong())
            && pendingBlocks.size < maxPending
        ) {
            val isBlacklisted = world.getBlockState(placeInfo.pos).block in blockBlacklist || blockToPlace in blockBlacklist

            if (isBlacklisted) connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.START_SNEAKING))

            placeBlock(placeInfo)

            if (isBlacklisted) connection.sendPacket(CPacketEntityAction(player, CPacketEntityAction.Action.STOP_SNEAKING))

            pendingBlocks[placeInfo.placedPos] = PendingBlock(placeInfo.placedPos, world.getBlockState(placeInfo.placedPos), blockToPlace)
            world.setBlockState(placeInfo.placedPos, Blocks.BARRIER.defaultState)
        }
    }

    private fun SafeClientEvent.getHeldScaffoldBlock(placeInfo: PlaceInfo): Block? {
        playerController.syncCurrentPlayItem()
        if (blockSelectionMode.filter(player.heldItemMainhand, placeInfo)) {
            return player.heldItemMainhand.item.block
        }
        if (blockSelectionMode.filter(player.heldItemOffhand, placeInfo)) {
            return player.heldItemOffhand.item.block
        }
        return null
    }

    private fun SafeClientEvent.getBlockSlot(placeInfo: PlaceInfo): HotbarSlot? {
        playerController.syncCurrentPlayItem()
        return player.hotbarSlots.firstItem<ItemBlock, HotbarSlot> { blockSelectionMode.filter(it, placeInfo) }
    }

    private fun schematicToggle(@Suppress("UNUSED_PARAMETER") prev: Boolean, input: Boolean): Boolean {
        if (input) {
            val schematic = loadSchematic()
            if (schematic.isPresent) {
                MessageSendHelper.sendChatMessage("Loaded schematic")
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

    private data class PendingBlock(
        val blockPos: BlockPos,
        val blockState: IBlockState,
        val block: Block,
        val timestamp: Long = System.currentTimeMillis()
    ) {
        val age get() = System.currentTimeMillis() - timestamp
    }

    private fun Item.isUsableItem() = this is ItemBlock && block.defaultState.isFullBox
}