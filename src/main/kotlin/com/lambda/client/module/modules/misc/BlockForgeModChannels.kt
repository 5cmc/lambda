package com.lambda.client.module.modules.misc

import com.lambda.client.LambdaMod
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.event.listener.listener
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import net.minecraft.network.play.client.CPacketCustomPayload
import net.minecraft.network.play.server.SPacketCustomPayload
import net.minecraftforge.fml.common.network.internal.FMLProxyPacket

object BlockForgeModChannels : Module(
    name = "BlockForgeModChannels",
    description = "Blocks Forge plugin network packets",
    category = Category.MISC,
    showOnArray = false
) {

    init {
        listener<PacketEvent.Send> (priority = Int.MIN_VALUE) {
            // single player needs these for some reason and it doesn't really matter if we send them here
            if (mc.integratedServer != null && mc.integratedServer?.serverIsInRunLoop() == true) return@listener
            when (it.packet) {
                is CPacketCustomPayload -> {
                    if (!it.packet.channelName.startsWith("MC|")) {
                        LambdaMod.LOG.info("Blocked packet ${it.packet.channelName} from being sent")
                        it.cancel()
                    }
                }
                is FMLProxyPacket -> {
                    if (!it.packet.channel().startsWith("MC|")) {
                        LambdaMod.LOG.info("Blocked packet ${it.packet.channel()} from being sent")
                        it.cancel()
                    }
                }
            }
        }

        listener<PacketEvent.Receive> (priority = Int.MIN_VALUE) {
            if (mc.integratedServer != null && mc.integratedServer?.serverIsInRunLoop() == true) return@listener
            when (it.packet) {
                is SPacketCustomPayload -> {
                    if (!it.packet.channelName.startsWith("MC|")) {
                        LambdaMod.LOG.info("Blocked packet ${it.packet.channelName} from being received")
                        it.cancel()
                    }
                }
                is FMLProxyPacket -> {
                    if (!it.packet.channel().startsWith("MC|")) {
                        LambdaMod.LOG.info("Blocked packet ${it.packet.channel()} from being received")
                        it.cancel()
                    }
                }
            }
        }
    }
}