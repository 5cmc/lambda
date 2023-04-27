package com.lambda.client.module.modules.player

import com.lambda.client.event.SafeClientEvent
import com.lambda.client.event.events.PacketEvent
import com.lambda.client.module.Category
import com.lambda.client.module.Module
import com.lambda.client.util.threads.safeListener
import com.lambda.mixin.accessor.network.AccessorNetHandlerPlayClient
import net.minecraft.network.play.client.CPacketConfirmTeleport
import net.minecraft.network.play.client.CPacketPlayer
import net.minecraft.network.play.server.SPacketPlayerPosLook

object NoRotate: Module(
    name = "NoRotate",
    description = "Prevents force rotations",
    category = Category.PLAYER
) {

    init {
        safeListener<PacketEvent.Receive>(priority = -5) {
            if (it.packet is SPacketPlayerPosLook && !it.cancelled) {
                it.cancel()
                mc.addScheduledTask { handlePosLook(it.packet) }
            }
        }
    }

    private fun SafeClientEvent.handlePosLook(packet: SPacketPlayerPosLook) {
        var x = packet.x
        var y = packet.y
        var z = packet.z
        var yaw = packet.yaw
        var pitch = packet.pitch

        if (packet.flags.contains(SPacketPlayerPosLook.EnumFlags.X)) {
            x += mc.player.posX
        } else {
            mc.player.motionX = 0.0
        }

        if (packet.flags.contains(SPacketPlayerPosLook.EnumFlags.Y)) {
            y += mc.player.posY
        } else {
            mc.player.motionY = 0.0
        }

        if (packet.flags.contains(SPacketPlayerPosLook.EnumFlags.Z)) {
            z += mc.player.posZ
        } else {
            mc.player.motionZ = 0.0
        }

        if (packet.flags.contains(SPacketPlayerPosLook.EnumFlags.X_ROT)) {
            pitch += mc.player.rotationPitch
        }

        if (packet.flags.contains(SPacketPlayerPosLook.EnumFlags.Y_ROT)) {
            yaw += mc.player.rotationYaw
        }

        mc.player.setPositionAndRotation(x, y, z,
            // retain current yaw and pitch client-side
            mc.player.rotationYaw,
            mc.player.rotationPitch)
        // spoof to server that we are using its rotation
        connection.sendPacket(CPacketConfirmTeleport(packet.teleportId))
        connection.sendPacket(CPacketPlayer.PositionRotation(
            mc.player.posX,
            mc.player.entityBoundingBox.minY,
            mc.player.posZ,
            yaw,
            pitch,
            false))
        if (!(mc.player.connection as AccessorNetHandlerPlayClient)
                .isDoneLoadingTerrain) {
            mc.player.prevPosX = mc.player.posX
            mc.player.prevPosY = mc.player.posY
            mc.player.prevPosZ = mc.player.posZ
            (mc.player.connection as AccessorNetHandlerPlayClient).isDoneLoadingTerrain = true
            mc.displayGuiScreen(null)
        }
    }
}