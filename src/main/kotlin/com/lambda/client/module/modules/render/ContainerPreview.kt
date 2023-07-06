package com.lambda.client.module.modules.render

import com.lambda.client.commons.extension.floorToInt
import com.lambda.client.event.events.RenderOverlayEvent
import com.lambda.client.manager.managers.CachedContainerManager
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.Bind
import com.lambda.client.util.color.ColorHolder
import com.lambda.client.util.graphics.GlStateUtils
import com.lambda.client.util.graphics.ProjectionUtils
import com.lambda.client.util.graphics.RenderUtils2D
import com.lambda.client.util.graphics.VertexHelper
import com.lambda.client.util.graphics.font.FontRenderAdapter
import com.lambda.client.util.items.block
import com.lambda.client.util.math.Vec2d
import com.lambda.client.util.math.VectorUtils.distanceTo
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.threads.safeListener
import com.lambda.client.util.world.getHitVec
import net.minecraft.block.BlockEnderChest
import net.minecraft.block.BlockShulkerBox
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.inventory.GuiContainer
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.client.renderer.RenderHelper
import net.minecraft.entity.item.EntityItemFrame
import net.minecraft.entity.player.EntityPlayer
import net.minecraft.init.Blocks
import net.minecraft.inventory.Container
import net.minecraft.inventory.IInventory
import net.minecraft.inventory.ItemStackHelper
import net.minecraft.inventory.Slot
import net.minecraft.item.ItemShulkerBox
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NBTTagByte
import net.minecraft.tileentity.TileEntity
import net.minecraft.tileentity.TileEntityEnderChest
import net.minecraft.tileentity.TileEntityLockableLoot
import net.minecraft.util.EnumFacing
import net.minecraft.util.NonNullList
import net.minecraft.util.math.Vec3d
import net.minecraft.util.text.ITextComponent
import net.minecraft.util.text.TextComponentString
import net.minecraftforge.client.event.GuiOpenEvent
import net.minecraftforge.client.event.GuiScreenEvent
import net.minecraftforge.client.event.RenderTooltipEvent
import org.lwjgl.input.Keyboard
import org.lwjgl.opengl.GL11.GL_LINE_LOOP
import org.lwjgl.opengl.GL11.glLineWidth

object ContainerPreview : Module(
    name = "ContainerPreview",
    description = "Previews shulkers and ender chests in the game GUI",
    category = Category.RENDER
) {

    val cacheContainers = false // by setting("Cache Containers", false)
    private val renderCachedContainers = false // by setting("Render Cached Containers", false, { cacheContainers })
    val cacheEnderChests by setting("Cache Ender Chest", true)
    private val useCustomFont by setting("Use Custom Font", false)
    private val backgroundColor by setting("Background Color", ColorHolder(16, 0, 16, 255))
    private val borderTopColor by setting("Top Border Color", ColorHolder(144, 101, 237, 54))
    private val borderBottomColor by setting("Bottom Border Color", ColorHolder(40, 0, 127, 80))
    private val previewLock by setting("Preview Lock Bind", Bind())
    private val itemFrames by setting("Item Frames", true)
    private const val CONTAINER_GUI_SIZE = 16 + 54 + 6
    private const val x_offset = 8
    private const val y_offset = 0
    var locked = false
    private var isKeySet = false
    private var isMouseInPreviewGui = false
    private var isModGeneratedToolTip = false
    private var lastX = -1
    private var lastY = -1

    private var stackContainer: GuiPreview? = null

    init {

        onEnable {
            reset()
        }

        onDisable {
            reset()
        }

        safeListener<GuiScreenEvent.KeyboardInputEvent.Pre> {
            if (Keyboard.getEventKey() == previewLock.key) {
                locked = Keyboard.getEventKeyState()
            }
        }


        safeListener<GuiScreenEvent.DrawScreenEvent.Post> (priority = -5) {
            if (mc.currentScreen !is GuiContainer) return@safeListener
            val gui = it.gui as GuiContainer
            if (!isLocked()) {
                val slotUnder = gui.slotUnderMouse
                if (slotUnder != null && slotUnder.hasStack && !slotUnder.stack.isEmpty && (slotUnder.stack.item is ItemShulkerBox || slotUnder.stack.item.block == Blocks.ENDER_CHEST)) {
                    if (stackContainer == null || stackContainer?.parentContainer != slotUnder.stack) {
                        stackContainer = createPreviewGui(slotUnder.stack, getContainerContents(slotUnder.stack))
                    }
                } else {
                    stackContainer = null
                }
            }
            val renderX: Int
            var renderY: Int
            if (!isLocked() || lastX == -1 && (lastY == -1)) {
                lastX = it.mouseX + x_offset
                renderX = lastX
                lastY = it.mouseY + y_offset
                renderY = lastY
            } else {
                renderX = lastX
                renderY = lastY
            }
            isMouseInPreviewGui = false // recheck

            if (stackContainer != null) {
                stackContainer!!.posX = renderX
                stackContainer!!.posY = renderY
                stackContainer!!.drawScreen(it.mouseX, it.mouseY, it.renderPartialTicks)
            }

            GlStateManager.enableLighting()
            GlStateManager.color(1f, 1f, 1f, 1.0f)
        }

        safeListener<RenderTooltipEvent.Pre> {
            if (mc.currentScreen !is GuiContainer || isModGeneratedToolTip) return@safeListener
            if (isMouseInPreviewGui) {
                it.isCanceled = true
            } else if (it.stack.item is ItemShulkerBox || it.stack.item.block == Blocks.ENDER_CHEST) {
                it.isCanceled = true
            }
        }

        safeListener<GuiOpenEvent> {
            if (it.gui == null) {
                reset()
            }
        }

        safeListener<RenderOverlayEvent> {
            if (!renderCachedContainers) return@safeListener

            var indexH = 0

            // todo: handle echest rendering

            // todo: handle container breaking here and in manager

            // Preprocessing needs to be done in the manager to reduce strain on the render thread
            CachedContainerManager.containers
                .filter { it.key.distanceTo(player.position) < 6 }
                .forEach { entry ->
                    CachedContainerManager.getInventoryOfContainer(entry.value)?.let { container ->
                        val mockTileEntity = TileEntity.create(world, entry.value)
                        var matrix = if (mockTileEntity is TileEntityLockableLoot || mockTileEntity is TileEntityEnderChest) {
                            CachedContainerManager.getContainerMatrix(mockTileEntity)
                        } else {
                            return@safeListener
                        }

                        var renderPos = entry.key.toVec3dCenter()

                        (entry.value.getTag("adjacentChest") as? NBTTagByte)?.byte?.toInt()?.let { index ->
                            renderPos = getHitVec(entry.key, EnumFacing.byIndex(index))
                            matrix = Pair(9, 6)
                        }

                        // todo: make this rendering less retarded
                        //   we should draw a standard rect and items in a matrix. then translate those to the position we want
                        //   should be able to increase the distance over which we render these

                        val screenPos = ProjectionUtils.toScaledScreenPos(renderPos)

                        val width = matrix.first * 16
                        val height = matrix.second * 16

                        val vertexHelper = VertexHelper(GlStateUtils.useVbo())

                        val color = backgroundColor.clone().apply { a = 50 }

                        val newX = screenPos.x - width / 2
                        val newY = screenPos.y - height / 2

                        RenderUtils2D.drawRoundedRectFilled(
                            vertexHelper,
                            Vec2d(newX, newY),
                            Vec2d(newX + width, newY + height),
                            1.0,
                            color = color
                        )

                        container.forEachIndexed { index, itemStack ->
                            val x = newX + (index % matrix.first) * 16
                            val y = newY + (index / matrix.first) * 16
                            RenderUtils2D.drawItem(itemStack, x.floorToInt(), y.floorToInt())
                        }
                    }
                    indexH += 60
                }
        }

        safeListener<RenderOverlayEvent> {
            if (!itemFrames) return@safeListener
            mc.renderManager.pointedEntity?.let { pe ->
                if (pe !is EntityItemFrame) return@safeListener
                if (!(pe.displayedItem.item.block is BlockShulkerBox || pe.displayedItem.item.block is BlockEnderChest)) return@safeListener
                val posX = pe.posX + (pe.facingDirection?.xOffset ?: 0) * 0.5
                val posY = pe.posY + (pe.facingDirection?.yOffset ?: 0) * 0.5
                val posZ = pe.posZ + (pe.facingDirection?.zOffset ?: 0) * 0.5
                val screenPos = ProjectionUtils.toScaledScreenPos(Vec3d(posX, posY, posZ))

                val width = 9 * 16
                val height = 3 * 16

                val newX = screenPos.x - width / 2
                val newY = screenPos.y - height / 2

                GlStateManager.pushMatrix()
                val vertexHelper = VertexHelper(GlStateUtils.useVbo())

                RenderUtils2D.drawRoundedRectFilled(
                    vertexHelper,
                    Vec2d(newX, newY),
                    Vec2d(newX + width, newY + height),
                    1.0,
                    color = backgroundColor
                )

                drawRectOutline(vertexHelper, newX + 1, newY + 1, (width - 2).toDouble(), (height - 2).toDouble())
                GlStateManager.enableDepth()

                RenderHelper.enableGUIStandardItemLighting()
                GlStateManager.enableRescaleNormal()
                GlStateManager.enableColorMaterial()
                GlStateManager.enableLighting()
                val contents = if (pe.displayedItem.item.block is BlockShulkerBox && pe.displayedItem.hasTagCompound()) {
                    getContainerContents(pe.displayedItem)
                } else if (pe.displayedItem.item.block is BlockEnderChest) {
                    getEnderChestData()
                } else {
                    return@safeListener
                }
                contents.forEachIndexed { index, itemStack ->
                    val x = newX + (index % 9) * 16
                    val y = newY + (index / 9) * 16
                    RenderUtils2D.drawItem(itemStack, x.floorToInt(), y.floorToInt())
                }

                GlStateManager.popMatrix()
            }
        }
    }

    private fun reset() {
        isModGeneratedToolTip = false
        isMouseInPreviewGui = false
        isKeySet = false
        locked = false
        lastY = -1
        lastX = lastY
    }

    private fun createPreviewGui(parentContainer: ItemStack, containerContents: MutableList<ItemStack>): GuiPreview? {
        return GuiPreview(
            PreviewContainer(PreviewInventory(containerContents), 27),
            parentContainer
        )
    }

    private fun getContainerContents(stack: ItemStack): MutableList<ItemStack> { // TODO: move somewhere else
        if (stack.item.block == Blocks.ENDER_CHEST) {
            return getEnderChestData()
        } else {
            val contents = NonNullList.withSize(27, ItemStack.EMPTY)
            val compound = stack.tagCompound
            if (compound != null && compound.hasKey("BlockEntityTag", 10)) {
                val tags = compound.getCompoundTag("BlockEntityTag")
                if (tags.hasKey("Items", 9)) {
                    // load in the items
                    ItemStackHelper.loadAllItems(tags, contents)
                }
            }
            return contents
        }
    }

    private fun drawRectOutline(vertexHelper: VertexHelper, x: Double, y: Double, width: Double, height: Double) {
        RenderUtils2D.prepareGl()
        glLineWidth(5.0f)

        vertexHelper.begin(GL_LINE_LOOP)
        vertexHelper.put(Vec2d(x, y), borderTopColor)
        vertexHelper.put(Vec2d(x, y + height), borderBottomColor)
        vertexHelper.put(Vec2d(x + width, y + height), borderBottomColor)
        vertexHelper.put(Vec2d(x + width, y), borderTopColor)
        vertexHelper.end()

        RenderUtils2D.releaseGl()
        glLineWidth(1.0f)
    }

    private fun isLocked(): Boolean {
        return locked
    }

    private fun getEnderChestData(): MutableList<ItemStack> {
        return CachedContainerManager.getEnderChestInventory().toMutableList()
    }

    class GuiPreview(inventorySlotsIn: Container, val parentContainer: ItemStack) : GuiContainer(inventorySlotsIn) {
        var posX: Int = 0
        var posY: Int = 0
        init {
            this.ySize = CONTAINER_GUI_SIZE
            this.mc = Minecraft.getMinecraft()
            this.fontRenderer = this.mc.fontRenderer
            this.width = mc.displayWidth
            this.height = mc.displayHeight
        }

        override fun drawScreen(mouseX: Int, mouseY: Int, partialTicks: Float) {
            drawPreview(posX, posY, parentContainer, inventorySlots.inventorySlots.map { it.stack }, 500)

            var hoveringOver: Slot? = null
            val rx = posX.toDouble() + 8
            val ry = posY.toDouble() - 5

            for (slot in inventorySlots.inventorySlots) {
                if (slot.hasStack) {
                    val px = rx + slot.xPos
                    val py = ry + slot.yPos
                    if (isPointInRegion(px.toInt(), py.toInt(), 16, 16, mouseX, mouseY)) {
                        hoveringOver = slot
                    }
                }
            }

            if (hoveringOver != null) {
                drawHoveredItem((rx + hoveringOver.xPos).toInt(), (ry + hoveringOver.yPos).toInt(), hoveringOver)
            }

            // might need some rework for nested
            if (isPointInRegion(posX, posY, xSize, ySize, mouseX, mouseY)) {
                isMouseInPreviewGui = true
            }

            GlStateManager.disableBlend()
            GlStateManager.color(1f, 1f, 1f, 1.0f)
        }

        private fun drawHoveredItem(drawX: Int, drawY: Int, hoveringOver: Slot) {
            // background of the gui
            GlStateManager.disableLighting()
            GlStateManager.disableDepth()
            GlStateManager.colorMask(true, true, true, false)
            drawGradientRect(
                drawX,
                drawY,
                drawX + 16,
                drawY + 16,
                -2130706433,
                -2130706433)
            GlStateManager.colorMask(true, true, true, true)
            GlStateManager.enableDepth()

            if (hoveringOver.stack.item is ItemShulkerBox || hoveringOver.stack.item.block == Blocks.ENDER_CHEST) {
                drawPreview(drawX + 8, drawY + 8, hoveringOver.stack, getContainerContents(hoveringOver.stack), 700)
            } else {
                // tool tip
                GlStateManager.color(1f, 1f, 1f, 1.0f)
                GlStateManager.pushMatrix()
                isModGeneratedToolTip = true
                renderToolTip(hoveringOver.stack, drawX + 8, drawY + 8)
                isModGeneratedToolTip = false
                GlStateManager.popMatrix()
                GlStateManager.enableDepth()
            }
        }

        private fun drawPreview(drawX: Int, drawY: Int, container: ItemStack, containerContents: List<ItemStack>, z: Int) {
            val depth = z.toDouble()
            val x = drawX.toDouble()
            val y = drawY.toDouble()
            val previewWidth = 176
            val previewHeight = 70
            val vertexHelper = VertexHelper(GlStateUtils.useVbo())

            GlStateManager.disableDepth()
            RenderUtils2D.drawRoundedRectFilled(
                vertexHelper,
                Vec2d(x, y),
                Vec2d(x + previewWidth, y + previewHeight),
                1.0,
                color = backgroundColor
            )

            drawRectOutline(vertexHelper, x + 1, y + 1, (previewWidth - 2).toDouble(), (previewHeight - 2.toFloat()).toDouble())

            FontRenderAdapter.drawString(container.displayName, (x + 4).toFloat(), (y + 2).toFloat(), customFont = useCustomFont)
            GlStateManager.enableDepth()

            RenderHelper.enableGUIStandardItemLighting()
            GlStateManager.enableRescaleNormal()
            GlStateManager.enableColorMaterial()
            GlStateManager.enableLighting()

            val rx = x + 8
            val ry = y - 5
            for (slot in inventorySlots.inventorySlots) {
                val px = rx + slot.xPos
                val py = ry + slot.yPos
                RenderUtils2D.drawItem(containerContents[slot.slotIndex], px.toInt(), py.toInt(), z = (depth + 1).toFloat())
            }

            GlStateManager.disableLighting()
        }

        override fun drawGuiContainerBackgroundLayer(partialTicks: Float, mouseX: Int, mouseY: Int) {
            // do nothing
        }
    }

    class PreviewContainer(val inventory: PreviewInventory, val size: Int) : Container() {
        init {
            for (i in 0 until size) {
                val x = i % 9 * 18
                val y = (i / 9 + 1) * 18 + 1
                addSlotToContainer(Slot(inventory, i, x, y))
            }
        }

        override fun canInteractWith(playerIn: EntityPlayer): Boolean {
            return false
        }
    }

    class PreviewInventory(val contents: MutableList<ItemStack>) : IInventory {
        override fun getName(): String {
            return ""
        }

        override fun hasCustomName(): Boolean {
            return false
        }

        override fun getDisplayName(): ITextComponent {
            return TextComponentString("")
        }

        override fun getSizeInventory(): Int {
            return contents.size
        }

        override fun isEmpty(): Boolean {
            return contents.isEmpty()
        }

        override fun getStackInSlot(index: Int): ItemStack {
            return contents[index]
        }

        override fun decrStackSize(index: Int, count: Int): ItemStack {
            throw UnsupportedOperationException("no")
        }

        override fun removeStackFromSlot(index: Int): ItemStack {
            val itemStack = this.contents[index]
            this.contents[index] = ItemStack.EMPTY
            return itemStack
        }

        override fun setInventorySlotContents(index: Int, stack: ItemStack) {
            this.contents[index] = stack
        }

        override fun getInventoryStackLimit(): Int {
            return 27
        }

        override fun markDirty() {
        }

        override fun isUsableByPlayer(player: EntityPlayer): Boolean {
            return false
        }

        override fun openInventory(player: EntityPlayer) {

        }

        override fun closeInventory(player: EntityPlayer) {
        }

        override fun isItemValidForSlot(index: Int, stack: ItemStack): Boolean {
            return index > 0 && index < contents.size && (contents[index] == stack)
        }

        override fun getField(id: Int): Int {
            return 0
        }

        override fun setField(id: Int, value: Int) {
        }

        override fun getFieldCount(): Int {
            return 0
        }

        override fun clear() {
        }
    }
}
