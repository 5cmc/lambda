package com.lambda.client.module.modules.render

import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.TickTimer
import com.lambda.client.util.TimeUnit
import net.minecraft.client.entity.AbstractClientPlayer
import net.minecraft.client.renderer.GlStateManager
import net.minecraft.item.ItemBlock
import net.minecraft.item.ItemStack
import net.minecraft.util.EnumHand
import net.minecraft.util.EnumHandSide
import net.minecraft.util.math.MathHelper.sin
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

object ItemModel : Module(
    name = "ItemModel",
    description = "Modify hand item rendering in first person",
    category = Category.RENDER,
    alias = arrayOf("ViewModel", "SmallShield", "LowerOffhand")
) {
    private val mode by setting("Mode", Mode.BOTH)
    private val page by setting("Page", Page.POSITION)
    private val noItemBobbing by setting("No Item Bobbing", false)
    private val noEatAnimation by setting("No Eat Animation", false)
    val noSway by setting("No Item Sway", false)

    private val posX by setting("Pos X", 0.0f, -5.0f..5.0f, 0.025f, { page == Page.POSITION })
    private val posY by setting("Pos Y", 0.0f, -5.0f..5.0f, 0.025f, { page == Page.POSITION })
    private val posZ by setting("Pos Z", 0.0f, -5.0f..5.0f, 0.025f, { page == Page.POSITION })
    private val posXR by setting("Pos X Right", 0.0f, -5.0f..5.0f, 0.025f, { page == Page.POSITION && mode == Mode.SEPARATE })
    private val posYR by setting("Pos Y Right", 0.0f, -5.0f..5.0f, 0.025f, { page == Page.POSITION && mode == Mode.SEPARATE })
    private val posZR by setting("Pos Z Right", 0.0f, -5.0f..5.0f, 0.025f, { page == Page.POSITION && mode == Mode.SEPARATE })

    private val rotateX by setting("Rotate X", 0.0f, -180.0f..180.0f, 1.0f, { page == Page.ROTATION })
    private val rotateY by setting("Rotate Y", 0.0f, -180.0f..180.0f, 1.0f, { page == Page.ROTATION })
    private val rotateZ by setting("Rotate Z", 0.0f, -180.0f..180.0f, 1.0f, { page == Page.ROTATION })
    private val rotateXR by setting("Rotate X Right", 0.0f, -180.0f..180.0f, 1.0f, { page == Page.ROTATION && mode == Mode.SEPARATE })
    private val rotateYR by setting("Rotate Y Right", 0.0f, -180.0f..180.0f, 1.0f, { page == Page.ROTATION && mode == Mode.SEPARATE })
    private val rotateZR by setting("Rotate Z Right", 0.0f, -180.0f..180.0f, 1.0f, { page == Page.ROTATION && mode == Mode.SEPARATE })

    private val scale by setting("Scale", 1.0f, 0.1f..3.0f, 0.025f, { page == Page.SCALE })
    private val scaleR by setting("Scale Right", 1.0f, 0.1f..3.0f, 0.025f, { page == Page.SCALE && mode == Mode.SEPARATE })

    private val modifyHand by setting("Modify Hand", false)

    private val animateRotation by setting("Animation", true, visibility = { page == Page.ANIMATION })
    private val animateOnlyBlocks by setting("Animation Only Blocks", false, visibility = { page == Page.ANIMATION && animateRotation })
    private val animateX by setting("Animation X", true, visibility = { page == Page.ANIMATION && animateRotation })
    private val animateXSpeed = setting("Animation X Speed", 20, 1..20, 1, visibility = { page == Page.ANIMATION && animateRotation && animateX })
    private val animateY by setting("Animation Y", false, visibility = { page == Page.ANIMATION && animateRotation })
    private val animateYSpeed = setting("Animation Y Speed", 20, 1..20, 1, visibility = { page == Page.ANIMATION && animateRotation && animateY })
    private val animateZ by setting("Animation Z", false, visibility = { page == Page.ANIMATION && animateRotation })
    private val animateZSpeed = setting("Animation Z Speed", 20, 1..20, 1, visibility = { page == Page.ANIMATION && animateRotation && animateZ })
    private val animateRotationR by setting("Animation Right", true, visibility = { page == Page.ANIMATION && mode == Mode.SEPARATE })
    private val animateXR by setting("Animation X Right", true, visibility = { page == Page.ANIMATION && mode == Mode.SEPARATE && animateRotationR })
    private val animateXSpeedR = setting("Animation X Speed Right", 20, 1..20, 1, visibility = { page == Page.ANIMATION && mode == Mode.SEPARATE && animateRotationR && animateXR })
    private val animateYR by setting("Animation Y Right", false, visibility = { page == Page.ANIMATION && mode == Mode.SEPARATE && animateRotationR })
    private val animateYSpeedR = setting("Animation Y Speed Right", 20, 1..20, 1, visibility = { page == Page.ANIMATION && mode == Mode.SEPARATE && animateRotationR && animateYR })
    private val animateZR by setting("Animation Z Right", false, visibility = { page == Page.ANIMATION && mode == Mode.SEPARATE && animateRotationR })
    private val animateZSpeedR = setting("Animation Z Speed Right", 20, 1..20, 1, visibility = { page == Page.ANIMATION && mode == Mode.SEPARATE && animateRotationR && animateZR })
    private val syncT by setting("Sync", false, consumer = { _, _ ->
        animateXT = 0
        animateXTimer.reset()
        animateYT = 0
        animateYTimer.reset()
        animateZT = 0
        animateZTimer.reset()
        animateXTR = 0
        animateXTimerR.reset()
        animateYTR = 0
        animateYTimerR.reset()
        animateZTR = 0
        animateZTimerR.reset()
        return@setting false
       }, visibility = { page == Page.ANIMATION && mode == Mode.SEPARATE })

    private val animateXTimer = TickTimer(timeUnit = TimeUnit.MILLISECONDS)
    private var animateXT = 0
    private val animateYTimer = TickTimer(timeUnit = TimeUnit.MILLISECONDS)
    private var animateYT = 0
    private val animateZTimer = TickTimer(timeUnit = TimeUnit.MILLISECONDS)
    private var animateZT = 0
    private val animateXTimerR = TickTimer(timeUnit = TimeUnit.MILLISECONDS)
    private var animateXTR = 0
    private val animateYTimerR = TickTimer(timeUnit = TimeUnit.MILLISECONDS)
    private var animateYTR = 0
    private val animateZTimerR = TickTimer(timeUnit = TimeUnit.MILLISECONDS)
    private var animateZTR = 0

    private enum class Mode {
        BOTH, SEPARATE
    }

    private enum class Page {
        POSITION, ROTATION, SCALE, ANIMATION
    }

    @JvmStatic
    fun translate(stack: ItemStack, hand: EnumHand, player: AbstractClientPlayer) {
        if (isDisabled || !modifyHand && stack.isEmpty) return

        val enumHandSide = getEnumHandSide(player, hand)

        if (mode == Mode.BOTH) {
            translate(posX, posY, posZ, getSideMultiplier(enumHandSide))
        } else {
            if (enumHandSide == EnumHandSide.LEFT) {
                translate(posX, posY, posZ, -1.0f)
            } else {
                translate(posXR, posYR, posZR, 1.0f)
            }
        }
    }

    private fun translate(x: Float, y: Float, z: Float, sideMultiplier: Float) {
        GlStateManager.translate(x * sideMultiplier, y, -z)
    }

    @JvmStatic
    fun rotateAndScale(stack: ItemStack, hand: EnumHand, player: AbstractClientPlayer) {
        if (isDisabled || !modifyHand && stack.isEmpty) return

        val enumHandSide = getEnumHandSide(player, hand)

        if (mode == Mode.BOTH) {
            rotate(rotateX, rotateY, rotateZ, getSideMultiplier(enumHandSide))
            GlStateManager.scale(scale, scale, scale)
            if (animateOnlyBlocks) {
                if (stack.item is ItemBlock) animate(enumHandSide)
            } else {
                animate(enumHandSide)
            }

        } else {
            if (enumHandSide == EnumHandSide.LEFT) {
                rotate(rotateX, rotateY, rotateZ, -1.0f)
                GlStateManager.scale(scale, scale, scale)
                if (animateOnlyBlocks) {
                    if (stack.item is ItemBlock) animate(enumHandSide)
                } else {
                    animate(enumHandSide)
                }
            } else {
                rotate(rotateXR, rotateYR, rotateZR, 1.0f)
                GlStateManager.scale(scaleR, scaleR, scaleR)
                if (animateOnlyBlocks) {
                    if (stack.item is ItemBlock) animateR()
                } else {
                    animateR()
                }
            }
        }
    }

    @JvmStatic
    fun transformSideFirstPerson(hand: EnumHandSide, ci: CallbackInfo) {
        if (isEnabled && noItemBobbing) {
            val i = if (hand == EnumHandSide.RIGHT) 1 else -1
            GlStateManager.translate(i.toFloat() * 0.56f, -0.52f, -0.72f)
            ci.cancel()
        }
    }

    @JvmStatic
    fun transformEatFirstPerson(ci: CallbackInfo) {
        if (isEnabled && noEatAnimation) {
            ci.cancel()
        }
    }

    private fun animate(enumHandSide: EnumHandSide) {
        if (animateRotation) {
            if (animateX && animateXTimer.tick(animateXSpeed.range.endInclusive - animateXSpeed.value, true)) {
                animateXT += 1;
            }
            if (animateY && animateYTimer.tick(animateYSpeed.range.endInclusive - animateYSpeed.value, true)) {
                animateYT += 1;
            }
            if (animateZ && animateZTimer.tick(animateZSpeed.range.endInclusive - animateZSpeed.value, true)) {
                animateZT += 1;
            }
            val x = if (animateX) animateXT * sin(180f) else 0f
            val y = if (animateY) animateYT * sin(180f) else 0f
            val z = if (animateZ) animateZT * sin(180f) else 0f

            rotate(x, y, z, getSideMultiplier(enumHandSide))
        }
    }

    private fun animateR() {
        if (animateRotation) {
            if (animateXR && animateXTimerR.tick(animateXSpeedR.range.endInclusive - animateXSpeedR.value, true)) {
                animateXTR += 1;
            }
            if (animateYR && animateYTimerR.tick(animateYSpeedR.range.endInclusive - animateYSpeedR.value, true)) {
                animateYTR += 1;
            }
            if (animateZR && animateZTimerR.tick(animateZSpeedR.range.endInclusive - animateZSpeedR.value, true)) {
                animateZTR += 1;
            }
            val x = if (animateXR) animateXTR * sin(180f) else 0f
            val y = if (animateYR) animateYTR * sin(180f) else 0f
            val z = if (animateZR) animateZTR * sin(180f) else 0f

            rotate(x, y, z, getSideMultiplier(EnumHandSide.RIGHT))
        }
    }

    private fun rotate(x: Float, y: Float, z: Float, sideMultiplier: Float) {
        GlStateManager.rotate(x, 1.0f, 0.0f, 0.0f)
        GlStateManager.rotate(y * sideMultiplier, 0.0f, 1.0f, 0.0f)
        GlStateManager.rotate(z * sideMultiplier, 0.0f, 0.0f, 1.0f)
    }

    private fun getEnumHandSide(player: AbstractClientPlayer, hand: EnumHand): EnumHandSide =
        if (hand == EnumHand.MAIN_HAND) player.primaryHand else player.primaryHand.opposite()

    private fun getSideMultiplier(side: EnumHandSide) =
        if (side == EnumHandSide.LEFT) -1.0f else 1.0f
}