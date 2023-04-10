package com.lambda.client.module.modules.misc

import com.lambda.client.module.Category
import com.lambda.client.module.Module
import net.minecraft.network.EnumConnectionState
import net.minecraft.network.PacketBuffer
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo

object FakeVanillaClient : Module (
    name = "FakeVanillaClient",
    description = "Fakes your client as vanilla",
    category = Category.MISC,
    showOnArray = false) {

    @JvmStatic
    fun handleHandshakePacket(buf: PacketBuffer,
                              info: CallbackInfo,
                              protocolVersion: Int,
                              ip: String,
                              port: Int,
                              requestedState: EnumConnectionState) {
        if (isEnabled) {
            info.cancel()
            buf.writeVarInt(protocolVersion)
            buf.writeString(ip)
            buf.writeShort(port)
            buf.writeVarInt(requestedState.id)
        }
    }
}