package com.lambda.mixin.optifine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@SuppressWarnings("UnresolvedMixinReference")
@Mixin(targets = "net.optifine.Log", remap = false)
public class MixinLog {
    @Inject(method = "dbg", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dbgLogInject(final String s, final CallbackInfo ci) {
        ci.cancel();
    }
    @Inject(method = "warn(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void warnLogInject(final String s, final CallbackInfo ci) {
        ci.cancel();
    }
    @Inject(method = "warn(Ljava/lang/String;Ljava/lang/Throwable;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void warnLogExceptionInject(final String s, final Throwable t, final CallbackInfo ci) {
        ci.cancel();
    }
    @Inject(method = "error(Ljava/lang/String;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void errorLogInject(final String s, final CallbackInfo ci) {
        ci.cancel();
    }
    @Inject(method = "error(Ljava/lang/String;Ljava/lang/Throwable;)V", at = @At("HEAD"), cancellable = true, remap = false)
    private static void errorLogExceptionInject(final String s, final Throwable t, final CallbackInfo ci) {
        ci.cancel();
    }
    @Inject(method = "log", at = @At("HEAD"), cancellable = true, remap = false)
    private static void logInject(final String s, final CallbackInfo ci) {
        ci.cancel();
    }
}
