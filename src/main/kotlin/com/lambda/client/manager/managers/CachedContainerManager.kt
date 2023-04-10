package com.lambda.client.manager.managers

import com.lambda.client.LambdaMod
import com.lambda.client.event.listener.listener
import com.lambda.client.manager.Manager
import com.lambda.client.module.modules.player.PacketLogger
import com.lambda.client.module.modules.render.ContainerPreview.cacheContainers
import com.lambda.client.util.FolderUtils
import com.lambda.client.util.threads.defaultScope
import com.lambda.client.util.threads.safeListener
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.inventory.ItemStackHelper
import net.minecraft.item.ItemStack
import net.minecraft.nbt.CompressedStreamTools
import net.minecraft.nbt.NBTTagByte
import net.minecraft.nbt.NBTTagCompound
import net.minecraft.nbt.NBTTagList
import net.minecraft.tileentity.*
import net.minecraft.util.EnumFacing
import net.minecraft.util.NonNullList
import net.minecraft.util.math.BlockPos
import net.minecraftforge.event.entity.player.PlayerContainerEvent
import net.minecraftforge.event.entity.player.PlayerInteractEvent
import net.minecraftforge.event.world.WorldEvent
import java.io.File
import java.io.IOException
import java.nio.file.Paths

object CachedContainerManager : Manager {
    private val directory = Paths.get(FolderUtils.lambdaFolder, "cached-containers").toFile()
    private val containerWorlds = HashMap<File, NBTTagCompound>()
    private var currentFile: File? = null
    private var currentTileEntity: TileEntity? = null

    init {
        listener<WorldEvent.Load> {
            if (!cacheContainers) return@listener

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
            currentFile = File(folder, "${it.world.provider.dimension}.nbt")

            currentFile?.let { file ->
                if (containerWorlds[file] != null) return@listener

                defaultScope.launch(Dispatchers.IO) {
                    try {
                        CompressedStreamTools.read(file)?.let { compound ->
                            containerWorlds[file] = compound
                            LambdaMod.LOG.info("Container DB loaded from $file")
                        } ?: run {
                            if (!folder.exists()) folder.mkdirs()
                            val containerDB = NBTTagCompound().apply {
                                setTag("Containers", NBTTagList())
                            }
                            containerWorlds[file] = containerDB
                            CompressedStreamTools.write(containerDB, file)
                            LambdaMod.LOG.info("New container DB created in $file")
                        }
                    } catch (e: IOException) {
                        LambdaMod.LOG.error("Failed to load container DB from $file", e)
                    }
                }
            }
        }

        safeListener<PlayerInteractEvent.RightClickBlock> {
            if (!cacheContainers) return@safeListener
            world.getTileEntity(it.pos)?.let { tileEntity ->
                if (tileEntity is TileEntityLockableLoot || tileEntity is TileEntityEnderChest) {
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
        val file = currentFile ?: return
        val containerDB = containerWorlds[file] ?: return
        val containerList = containerDB.getContainerList() ?: return

        val nonNullList = NonNullList.withSize(inventory.size, ItemStack.EMPTY)

        inventory.forEachIndexed { index, itemStack ->
            nonNullList[index] = itemStack
        }

        ItemStackHelper.saveAllItems(tileEntityTag, nonNullList)

        facing?.let {
            tileEntityTag.setTag("adjacentChest", NBTTagByte(it.index.toByte()))
        }

        findContainer(pos)?.let { containerTag ->
            containerList.removeAll { containerTag == it }
            containerList.appendTag(tileEntityTag)
        } ?: run {
            containerList.appendTag(tileEntityTag)
        }

        defaultScope.launch(Dispatchers.IO) {
            try {
                CompressedStreamTools.write(containerDB, file)
            } catch (e: IOException) {
                LambdaMod.LOG.warn("${PacketLogger.chatName} Failed saving containers!", e)
            }
        }
    }

    fun findContainer(pos: BlockPos) = containerWorlds[currentFile]
        ?.getContainerList()
        ?.filterIsInstance<NBTTagCompound>()
        ?.firstOrNull {
            pos.x == it.getInteger("x")
                && pos.y == it.getInteger("y")
                && pos.z == it.getInteger("z")
        }

    fun getInventoryOfContainer(tag: NBTTagCompound): NonNullList<ItemStack>? {
        val inventory = NonNullList.withSize(54, ItemStack.EMPTY)
        ItemStackHelper.loadAllItems(tag, inventory)
        return inventory
    }

    fun getAllContainers() = containerWorlds[currentFile]?.getContainerList()?.filterIsInstance<NBTTagCompound>()

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

    private fun NBTTagCompound.getContainerList() = getTag("Containers") as? NBTTagList
}