package com.lambda.client.module.modules.player

import com.lambda.client.event.events.RightClickBlockEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.threads.safeListener
import net.minecraft.item.ItemBoat
import net.minecraft.network.play.client.CPacketPlayerTryUseItem
import net.minecraft.util.EnumHand

object BoatPlaceFix : Module(
    name = "BoatPlaceFix",
    description = "Fixes boats being placed on land on 2b2t",
    category = Category.PLAYER
) {
    init {
        safeListener<RightClickBlockEvent> {
            val item = when (it.hand) {
                EnumHand.MAIN_HAND -> {
                    player.heldItemMainhand.item
                }
                EnumHand.OFF_HAND -> {
                    player.heldItemOffhand.item
                }
            }
            if (item is ItemBoat) {
                it.cancel()
                connection.sendPacket(CPacketPlayerTryUseItem(it.hand))
            }
        }
    }
}