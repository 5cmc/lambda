package com.lambda.client.module.modules.misc

import com.lambda.client.event.events.ConnectionEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.text.MessageSendHelper
import com.lambda.client.util.threads.safeListener
import net.minecraft.network.play.client.CPacketConfirmTeleport
import net.minecraft.network.play.client.CPacketPlayer
import net.minecraft.network.play.client.CPacketPlayer.Position
import net.minecraft.network.play.client.CPacketPlayer.PositionRotation
import net.minecraft.network.play.server.SPacketPlayerPosLook
import net.minecraft.network.play.server.SPacketSpawnObject
import java.util.*
import java.util.concurrent.ConcurrentLinkedQueue


object TPCancel : Module(
    name = "TPCancel",
    description = "odpay experimental module, bypasses teleports from pearls (needs 1 block space above head)",
    category = Category.MISC
) {
    private val startSuspend by setting("Suspend on enable", false)
//    private val delay by setting("Delay", 5, 0..20, 1, unit = " ticks")

    private var TPs = 0
    private var thrownPearlId = -1
//    private var thrown = false
    private val packets: Queue<CPacketPlayer> = ConcurrentLinkedQueue()
    init {

        onEnable {
            TPs = 0
            thrownPearlId = -1
//            thrown = false
            packets.clear()
            if (startSuspend) {
//                TPs = 1
                MessageSendHelper.sendChatMessage("Packet suspension enabled")
                rubberband()
                thrownPearlId = -1337
            }
        }

        onDisable {
            if (!packets.isEmpty()) {
                sendPackets()
            }
        }

        safeListener<ConnectionEvent.Disconnect> {
            packets.clear()
            disable()
        }

        safeListener<PacketEvent.Receive>() {
            if (it.packet is SPacketSpawnObject && it.packet.type == 65) {
                if (mc.world.getClosestPlayer(it.packet.x, it.packet.y - 1.0, it.packet.z, 3.0, false)?.equals(mc.player) == true) {
                    MessageSendHelper.sendChatMessage("Pearl thrown, EID: " + it.packet.entityID.toString())



                    // send rubberband packet
//                    player.connection.sendPacket(CPacketPlayer.Position(player.posX, 1337.0, player.posZ, false))
                    rubberband()

                    thrownPearlId = it.packet.entityID


                }
            }
        }

        safeListener<PacketEvent.Send>() {
//            println(thrownPearlId)
            if (it.packet is CPacketPlayer) {
                if (thrownPearlId != -1) {
                    if (it.packet.getY(0.0) != 1337.0) {
                        packets.add(it.packet)
                        it.cancel()
                    }
                }
//                println(it.packet.getY(0.0))
//                println(it.cancelled)
            }
        }

        safeListener<PacketEvent.Receive> {
            if (it.packet is SPacketPlayerPosLook) {
//                MessageSendHelper.sendChatMessage("tp")
                if (thrownPearlId != -1) {
                    if (TPs > 0) {
                        MessageSendHelper.sendChatMessage("Rubberband corrected")
                        TPs -= 1
                    } else {
                        MessageSendHelper.sendChatMessage("TP caught, ID: " + it.packet.teleportId.toString())
                        it.cancel()
                        mc.player.connection.sendPacket(CPacketConfirmTeleport(it.packet.teleportId))
                        mc.player.connection.sendPacket(PositionRotation(it.packet.x, it.packet.y, it.packet.z, it.packet.yaw, it.packet.pitch, false))
                        mc.player.connection.sendPacket(Position(it.packet.x, it.packet.y, it.packet.z, false))
//                        rubberband()
                        thrownPearlId = -1
                        if (!packets.isEmpty()) {
                            sendPackets()
                        }
                    }
                }
//                if (it.packet.)
//                println(it.packet.x)
//                println(it.packet.y)
//                println(it.packet.z)
////                println(it.packet.flags)
//                println(it.packet.teleportId)
            }
        }

        safeListener<PacketEvent.Receive> {
//            if (it.packet is SPacketDestroyEntities && it.packet.entityIDs.get(0) == thrownPearlId) {
//                MessageSendHelper.sendChatMessage("Peal landed, EID: " + thrownPearlId)
//                thrownPearlId = -1
//                if (!packets.isEmpty()) {
//                    sendPackets()
//                }
//            }
        }



    }
    private fun sendPackets() {
        do {
            mc.player.connection.sendPacket(packets.poll())
        } while (!packets.isEmpty())
    }

    private fun rubberband() {
        TPs += 1
        // do not allow movement
        mc.player.motionX = 0.0
        mc.player.motionY = 0.0
        mc.player.motionZ = 0.0

        mc.player.movementInput.moveForward = 0.0f
        mc.player.movementInput.moveStrafe = 0.0f
        mc.player.connection.sendPacket(CPacketPlayer.Position(mc.player.posX, mc.player.posY + 1.0, mc.player.posZ, false)) // TODO: port function to MovementUtils, calculate a legit tp location that the player can reach
    }
}