package com.lambda.client.module.modules.player

import com.lambda.client.mixin.extension.syncCurrentPlayItem
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.items.block
import com.lambda.client.util.math.RotationUtils.getRotationTo
import com.lambda.client.util.math.VectorUtils.toVec3dCenter
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.safeListener
import net.minecraft.item.ItemBlock
import net.minecraft.network.play.client.CPacketAnimation
import net.minecraft.network.play.client.CPacketPlayer
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock
import net.minecraft.util.EnumFacing
import net.minecraft.util.EnumHand
import net.minecraft.util.SoundCategory
import net.minecraft.util.math.BlockPos
import net.minecraftforge.fml.common.gameevent.TickEvent
import net.minecraftforge.fml.common.gameevent.TickEvent.ClientTickEvent

object Burrow : Module(
    name = "Burrow",
    description = "Burrow",
    category = Category.PLAYER
) {

    init {
        safeListener<ClientTickEvent> {
            if (it.phase != TickEvent.Phase.END) return@safeListener

            if (!world.isAirBlock(BlockPos(player.positionVector))) {
                MessageSendHelper.sendChatMessage("Already burrowed")
                disable()
            }

            playerController.syncCurrentPlayItem()
            // todo: swap to block hotbar slot if none held
            if (player.heldItemMainhand.isEmpty || player.heldItemMainhand.item !is ItemBlock) {
                MessageSendHelper.sendChatMessage("No block in hand")
                disable()
            }

            if (!player.onGround) {
                MessageSendHelper.sendChatMessage("Not on ground")
                disable()
            }

            val placePos = BlockPos(player.positionVector).down()
            val placeRotation = getRotationTo(player.positionVector.add(0.0, 1.24260060139474, 0.0), placePos.toVec3dCenter())
            connection.sendPacket(CPacketPlayer.Rotation(placeRotation.x, placeRotation.y, false))
            connection.sendPacket(CPacketPlayer.Position(player.posX, player.posY + 0.41999968688697, player.posZ, false))
            connection.sendPacket(CPacketPlayer.Position(player.posX, player.posY + 0.75000029999998, player.posZ, false))
            connection.sendPacket(CPacketPlayer.Position(player.posX, player.posY + 0.99999939999998, player.posZ, false))
            connection.sendPacket(CPacketPlayer.Position(player.posX, player.posY + 1.17000060178813, player.posZ, false))
            connection.sendPacket(CPacketPlayer.Position(player.posX, player.posY + 1.24260060139474, player.posZ, false))
            connection.sendPacket(CPacketPlayerTryUseItemOnBlock(placePos, EnumFacing.UP, EnumHand.MAIN_HAND, 0.5f, 1.0f, 0.5f))
            connection.sendPacket(CPacketAnimation(EnumHand.MAIN_HAND))
            connection.sendPacket(CPacketPlayer.Position(player.posX, player.posY + 1.31520060100135, player.posZ, false))
            connection.sendPacket(CPacketPlayer.Position(player.posX, player.posY + 2.4852030027895, player.posZ, false))
            connection.sendPacket(CPacketPlayer.Position(player.posX, player.posY + 1.24260060139474, player.posZ, false))
            connection.sendPacket(CPacketPlayer.Position(player.posX, player.posY, player.posZ, false))
            val soundType = player.heldItemMainhand.item.block.getSoundType(player.heldItemMainhand.item.block.defaultState, world, placePos, player)
            world.playSound(player, placePos, soundType.placeSound, SoundCategory.BLOCKS, soundType.getVolume(), soundType.getPitch())
            disable()
        }
    }
}