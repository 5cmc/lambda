package com.lambda.mixin.network;

import com.lambda.client.module.modules.misc.FakeVanillaClient;
import net.minecraft.network.EnumConnectionState;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.handshake.client.C00Handshake;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = C00Handshake.class)
public class MixinC00Handshake {
    @Shadow private int protocolVersion;
    @Shadow private String ip;
    @Shadow private int port;
    @Shadow private EnumConnectionState requestedState;

    @Inject(method = "writePacketData", at = @At(value = "HEAD"), cancellable = true)
    public void writePacketData(PacketBuffer buf, CallbackInfo info) {
        FakeVanillaClient.handleHandshakePacket(buf, info, protocolVersion, ip, port, requestedState);
    }

}
