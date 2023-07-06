package com.lambda.mixin.optifine;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Thanks optifine :wheelchair:
 */
@Pseudo
@SuppressWarnings("UnresolvedMixinReference")
@Mixin(targets = "Config", remap = false)
public class MixinConfig {
    @Inject(method = "isFastRender", at = @At("HEAD"), cancellable = true, remap = false)
    private static void isFastRender(CallbackInfoReturnable<Boolean> isFastRender) {
        isFastRender.setReturnValue(false);
    }

    @Inject(method = "dbg", at = @At("HEAD"), cancellable = true, remap = false)
    private static void dbgInject(String message, CallbackInfo cir) {
        cir.cancel();
    }
    @Inject(method = "warn", at = @At("HEAD"), cancellable = true, remap = false)
    private static void warnInject(String message, CallbackInfo cir) {
        cir.cancel();
    }
    @Inject(method = "info", at = @At("HEAD"), cancellable = true, remap = false)
    private static void infoInject(String message, CallbackInfo cir) {
        cir.cancel();
    }
    @Inject(method = "log", at = @At("HEAD"), cancellable = true, remap = false)
    private static void logInject(String message, CallbackInfo cir) {
        cir.cancel();
    }
    @Inject(method = "detail", at = @At("HEAD"), cancellable = true, remap = false)
    private static void detailInject(String message, CallbackInfo cir) {
        cir.cancel();
    }
    @Inject(method = "error", at = @At("HEAD"), cancellable = true, remap = false)
    private static void errorInject(String message, CallbackInfo cir) {
        cir.cancel();
    }
}
