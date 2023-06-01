package com.lambda.client.manager.managers

import com.lambda.client.LambdaMod
import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.events.RightClickBlockEvent
import com.lambda.client.event.listener.listener
import com.lambda.client.manager.Manager
import com.lambda.client.module.modules.player.PacketLogger
import com.lambda.client.module.modules.render.ContainerPreview.cacheContainers
import com.lambda.client.module.modules.render.ContainerPreview.cacheEnderChests
import com.lambda.client.util.FolderUtils
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.inventory.ContainerChest
import net.minecraft.inventory.IInventory
import net.minecraft.inventory.InventoryBasic
import net.minecraft.inventory.ItemStackHelper
import net.minecraft.item.ItemStack
import net.minecraft.nbt.CompressedStreamTools
import net.minecraft.nbt.NBTTagByte
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.tileentity.*
import net.minecraft.util.EnumFacing
import net.minecraft.util.NonNullList
import net.minecraft.util.math.BlockPos
import net.minecraftforge.event.entity.player.PlayerContainerEvent
import java.io.File
import java.io.IOException
import java.nio.file.Paths

object CachedContainerManager : Manager {
    private val directory = Paths.get(FolderUtils.lambdaFolder, "cached-containers").toFile()
    val containers: MutableMap<BlockPos, NBTTagCompound> = HashMap()
    private var echestFile: File? = null
    private var currentFile: File? = null
    private var currentTileEntity: TileEntity? = null
    private var currentEnderChest: NonNullList<ItemStack>? = null

    init {
        listener<ConnectionEvent.Connect> {
            val serverDirectory = if (mc.integratedServer != null && mc.integratedServer?.isServerRunning == true) {
                mc.integratedServer?.folderName ?: run {
                    LambdaMod.LOG.info("Failed to get SP directory")
                    return@listener
                }
            } else {
                mc.currentServerData?.serverIP?.replace(":", "_")
                    ?: run {
                        LambdaMod.LOG.info("Failed to get server directory")
                        return@listener
                    }
            }

            val folder = File(directory, serverDirectory)
            echestFile = folder.toPath().resolve(mc.session.profile.id.toString()).resolve("echest.nbt").toFile()
            try {
                if (!echestFile!!.exists()) {
                    if (!echestFile!!.parentFile.exists()) echestFile!!.parentFile.mkdirs()
                    echestFile!!.createNewFile()
                }
            } catch (e: IOException) {
                LambdaMod.LOG.error("Failed to create ender chest file", e)
            }
        }

        listener<ConnectionEvent.Disconnect> {
            echestFile = null
            currentEnderChest = null
            containers.clear()
        }

        safeListener<RightClickBlockEvent> {
            if (!cacheContainers) return@safeListener
            world.getTileEntity(it.pos)?.let { tileEntity ->
                if (tileEntity is TileEntityLockableLoot) {
                    currentTileEntity = tileEntity
                }
            }
        }

        safeListener<PlayerContainerEvent.Close> { event ->
            if (!cacheContainers) return@safeListener

            val tileEntity = currentTileEntity ?: return@safeListener
            currentTileEntity = null

            val tileEntityTag = tileEntity.serializeNBT()

            val matrix = getContainerMatrix(tileEntity)

            if (tileEntity is TileEntityChest && event.container.inventory.size == 90) {
                var otherChest: TileEntityChest? = null
                var facing: EnumFacing? = null

                tileEntity.adjacentChestXNeg?.let {
                    otherChest = it
                    facing = EnumFacing.WEST
                }

                tileEntity.adjacentChestXPos?.let {
                    otherChest = it
                    facing = EnumFacing.EAST
                }

                tileEntity.adjacentChestZNeg?.let {
                    otherChest = it
                    facing = EnumFacing.NORTH
                }

                tileEntity.adjacentChestZPos?.let {
                    otherChest = it
                    facing = EnumFacing.SOUTH
                }

                otherChest?.let { other ->
                    facing?.let { face ->
                        val slotCount = matrix.first * matrix.second * 2
                        val inventory = event.container.inventory.take(slotCount)

                        safeInventoryToDB(inventory, tileEntityTag, tileEntity.pos, face)
                        safeInventoryToDB(inventory, other.serializeNBT(), other.pos, face.opposite)
                    }
                }

            } else {
                val slotCount = matrix.first * matrix.second
                val inventory = event.container.inventory.take(slotCount)

                safeInventoryToDB(inventory, tileEntityTag, tileEntity.pos, null)
            }
        }
    }

    private fun safeInventoryToDB(inventory: List<ItemStack>, tileEntityTag: NBTTagCompound, pos: BlockPos, facing: EnumFacing?) {

        val nonNullList = NonNullList.withSize(inventory.size, ItemStack.EMPTY)

        inventory.forEachIndexed { index, itemStack ->
            nonNullList[index] = itemStack
        }

        ItemStackHelper.saveAllItems(tileEntityTag, nonNullList)

        facing?.let {
            tileEntityTag.setTag("adjacentChest", NBTTagByte(it.index.toByte()))
        }

        containers[pos] = tileEntityTag
    }

    private fun saveEchest(inventory: NonNullList<ItemStack>) {
        if (!cacheEnderChests) return
        val currentEchestFile = echestFile ?: return
        val nonNullList = NonNullList.withSize(inventory.size, ItemStack.EMPTY)
        inventory.forEachIndexed { index, itemStack ->
            nonNullList[index] = itemStack
        }
        val nbt = NBTTagCompound()
        ItemStackHelper.saveAllItems(nbt, nonNullList)

        defaultScope.launch(Dispatchers.IO) {
            try {
                CompressedStreamTools.write(nbt, currentEchestFile)
            } catch (e: Throwable) {
                LambdaMod.LOG.warn("${PacketLogger.chatName} Failed saving echest!", e)
            }
        }
    }

    @JvmStatic
    fun setEnderChestInventory(inv: IInventory) {
        val inventory = NonNullList.withSize(inv.sizeInventory, ItemStack.EMPTY)
        for (i in 0 until inv.sizeInventory) {
            inventory[i] = inv.getStackInSlot(i)
        }
        currentEnderChest = inventory
        saveEchest(inventory)
    }

    fun getInventoryOfContainer(tag: NBTTagCompound): NonNullList<ItemStack>? {
        val inventory = NonNullList.withSize(54, ItemStack.EMPTY)
        ItemStackHelper.loadAllItems(tag, inventory)
        return inventory
    }

    fun getContainerMatrix(type: TileEntity): Pair<Int, Int> {
        return when (type) {
            is TileEntityChest -> Pair(9, 3)
            is TileEntityDispenser -> Pair(3, 3)
            is TileEntityHopper -> Pair(5, 1)
            is TileEntityShulkerBox -> Pair(9, 3)
            is TileEntityEnderChest -> Pair(9, 3)
            else -> Pair(0, 0) // Should never happen
        }
    }

    fun getEnderChestInventory(): NonNullList<ItemStack> {
        echestFile?.let { eFile ->
            currentEnderChest?.let { return it }
            if (cacheEnderChests) {
                try {
                    CompressedStreamTools.read(eFile)?.let { nbt ->
                        val inventory = NonNullList.withSize(27, ItemStack.EMPTY)
                        ItemStackHelper.loadAllItems(nbt, inventory)
                        currentEnderChest = inventory
                        return inventory
                    }
                } catch (e: IOException) {
                    currentEnderChest = NonNullList.withSize(27, ItemStack.EMPTY)
                    LambdaMod.LOG.warn("${PacketLogger.chatName} Failed loading echest!", e)
                }
            }
        }
        return NonNullList.withSize(27, ItemStack.EMPTY)
    }

    @JvmStatic
    fun updateContainerInventory(windowId: Int) {
        val container = mc.player.openContainer
        if (container.windowId == windowId) {
            if (container is ContainerChest) {
                val chest = container.lowerChestInventory
                if (chest is InventoryBasic) {
                    if (chest.name.contains("Ender Chest")) {
                        setEnderChestInventory(chest)
                    } else {
                        // todo: save other inventories but we also need the position
                    }
                }
            }
        }
    }

    @JvmStatic
    fun onGuiChestClosed() {
        val container = mc.player.openContainer
        if (container is ContainerChest) {
            val chest = container.lowerChestInventory
            if (chest is InventoryBasic) {
                if (chest.name.contains("Ender Chest")) {
                    setEnderChestInventory(chest)
                } else {
                    // todo: save other inventories but we also need the position
                }
            }
        }
    }
}