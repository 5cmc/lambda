package com.lambda.mixin.gui;

import com.lambda.client.util.RetryingServerPingRunner;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.ServerListEntryNormal;
import net.minecraft.client.multiplayer.ServerData;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;

@Mixin(value = ServerListEntryNormal.class)
public class MixinServerListEntryNormal {

    @Final @Shadow
    private GuiMultiplayer owner;
    @Final @Shadow
    private ServerData server;

    @Redirect(method = "drawEntry", at = @At(value = "INVOKE", target = "Ljava/util/concurrent/ThreadPoolExecutor;submit(Ljava/lang/Runnable;)Ljava/util/concurrent/Future;"))
    public Future redirectServerPinger(ThreadPoolExecutor threadPoolExecutor, Runnable runnable) {
        RetryingServerPingRunner serverPingRunner = new RetryingServerPingRunner(owner, server, threadPoolExecutor);
        serverPingRunner.run();
        return null;
    }

}
