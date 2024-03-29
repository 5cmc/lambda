package com.lambda.client.module.modules.misc

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.PlayerAttackEvent
import com.lambda.client.mixin.extension.syncCurrentPlayItem
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import com.lambda.client.util.combat.CombatUtils
import com.lambda.client.util.combat.CombatUtils.equipBestWeapon
import com.lambda.client.util.items.block
import com.lambda.client.util.items.hotbarSlots
import com.lambda.client.util.items.id
import com.lambda.client.util.items.swapToSlot
import com.lambda.client.util.threads.safeListener
import com.lambda.mixin.accessor.AccessorBlock
import net.minecraft.block.state.IBlockState
import net.minecraft.enchantment.EnchantmentHelper
import net.minecraft.entity.EntityLivingBase
import net.minecraft.init.Enchantments
import net.minecraftforge.event.entity.player.PlayerInteractEvent.LeftClickBlock
import net.minecraftforge.fml.common.gameevent.TickEvent
import org.lwjgl.input.Mouse
import java.util.*

object AutoTool : Module(
    name = "AutoTool",
    description = "Automatically switch to the best tools when mining or attacking",
    category = Category.MISC
) {
    private val switchBack = setting("Switch Back", true)
    private val silkTouch by setting("Silk Touch", true)
    private val timeout by setting("Timeout", 1, 1..20, 1, { switchBack.value }, unit = " ticks")
    private val swapWeapon by setting("Switch Weapon", false)
    private val preferWeapon by setting("Prefer", CombatUtils.PreferWeapon.SWORD)


    private var shouldMoveBack = false
    private var startSlot = 0
    private var switchTimer = TickTimer(TimeUnit.TICKS)
    private val rand: Random = Random(0)

    init {
        safeListener<LeftClickBlock> {
            if (shouldMoveBack || !switchBack.value) equipBestTool(world.getBlockState(it.pos))
        }

        safeListener<PlayerAttackEvent> {
            if (swapWeapon && it.entity is EntityLivingBase) equipBestWeapon(preferWeapon)
        }

        safeListener<TickEvent.ClientTickEvent> {
            if (mc.currentScreen != null || !switchBack.value) return@safeListener

            val mouse = Mouse.isButtonDown(0)
            if (mouse && !shouldMoveBack) {
                switchTimer.reset()
                shouldMoveBack = true
                startSlot = player.inventory.currentItem
                playerController.syncCurrentPlayItem()
            } else if (!mouse && shouldMoveBack && switchTimer.tick(timeout, false)) {
                shouldMoveBack = false
                player.inventory.currentItem = startSlot
                playerController.syncCurrentPlayItem()
            }
        }
    }

    private fun SafeClientEvent.equipBestTool(blockState: IBlockState) {
        player.hotbarSlots.maxByOrNull {
            val stack = it.stack
            if (stack.isEmpty) {
                0.0f
            } else {
                var speed = stack.getDestroySpeed(blockState)
                if (silkTouch
                    && isSilkTouchableBlock(blockState)
                    && EnchantmentHelper.getEnchantmentLevel(Enchantments.SILK_TOUCH, stack) > 0) {
                    speed += 100.0f
                }
                if (speed > 1.0f) {
                    val efficiency = EnchantmentHelper.getEnchantmentLevel(Enchantments.EFFICIENCY, stack)
                    if (efficiency > 0) {
                        speed += efficiency * efficiency + 1.0f
                    }
                }

                speed
            }
        }?.let {
            swapToSlot(it)
        }
    }

    private fun SafeClientEvent.isSilkTouchableBlock(blockState: IBlockState): Boolean {
        return blockState.block.canSilkHarvest(world, player.position, blockState, player)
            && (
                blockState.block.quantityDropped(rand) == 0
                || blockState.block.getItemDropped(blockState, rand, 0).block.id != (blockState.block as AccessorBlock).invokeSilkTouchDrop(blockState).item.block.id
            )
    }

    init {
        switchBack.valueListeners.add { _, it ->
            if (!it) shouldMoveBack = false
        }
    }
}