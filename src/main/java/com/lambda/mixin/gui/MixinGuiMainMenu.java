package com.lambda.mixin.gui;

import com.lambda.client.gui.mc.LambdaGuiIncompat;
import com.lambda.client.module.modules.client.MenuShader;
import com.lambda.client.util.KamiCheck;
import net.minecraft.client.gui.GuiMainMenu;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiMainMenu.class)
public abstract class MixinGuiMainMenu extends GuiScreen {

    @Inject(method = "initGui", at = @At("RETURN"))
    public void initGui$Inject$RETURN(CallbackInfo ci) {
        MenuShader.reset();
    }

    @Inject(method = "drawScreen", at = @At("RETURN"))
    public void drawScreen$Inject$RETURN(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (KamiCheck.INSTANCE.isKami() && !KamiCheck.INSTANCE.getDidDisplayWarning()) {
            KamiCheck.INSTANCE.setDidDisplayWarning(true);
            mc.displayGuiScreen(new LambdaGuiIncompat());
        }
    }

    @Redirect(method = "drawScreen", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiMainMenu;drawGradientRect(IIIIII)V"))
    private void drawScreen$Redirect$INVOKE$drawGradientRect(GuiMainMenu guiMainMenu, int left, int top, int right, int bottom, int startColor, int endColor) {
        if (MenuShader.INSTANCE.isDisabled()) {
            drawGradientRect(left, top, right, bottom, startColor, endColor);
        }
    }

    @Inject(method = "renderSkybox", at = @At("HEAD"), cancellable = true)
    private void renderSkybox$Inject$HEAD(int mouseX, int mouseY, float partialTicks, CallbackInfo ci) {
        if (MenuShader.INSTANCE.isEnabled()) {
            MenuShader.render();
            ci.cancel();
        }
    }
}
