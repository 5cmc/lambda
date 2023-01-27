package com.lambda.mixin.gui;

import com.lambda.client.gui.mc.LambdaGuiAntiDisconnect;
import com.lambda.client.manager.managers.ProxyManager;
import com.lambda.client.module.modules.misc.AntiDisconnect;
import com.lambda.client.module.modules.proxy.ManualDisconnect;
import com.lambda.client.util.Wrapper;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiIngameMenu;
import net.minecraft.client.gui.GuiMultiplayer;
import net.minecraft.client.gui.GuiScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GuiIngameMenu.class)
public class MixinGuiIngameMenu extends GuiScreen {

    @Inject(method = "initGui", at = @At("RETURN"))
    public void initGuiHook(CallbackInfo callbackInfo) {
        if (!mc.isSingleplayer()) {
            GuiButton openToLanButton = buttonList.remove(4);
            buttonList.add(new GuiButton(Integer.MIN_VALUE, openToLanButton.x, openToLanButton.y, openToLanButton.width, openToLanButton.height, "Server List"));
        }
        if (ManualDisconnect.INSTANCE.isEnabled() && ProxyManager.INSTANCE.isProxy()) {
            this.buttonList.add(new GuiButton(1337, this.width / 2 - 100, this.height / 4 + 128, "Disconnect Proxy"));
        }
    }


    @Inject(method = "actionPerformed", at = @At("HEAD"), cancellable = true)
    public void actionPerformed(GuiButton button, CallbackInfo callbackInfo) {
        switch (button.id) {
            case 1:
                if (AntiDisconnect.INSTANCE.isEnabled()) {
                    Wrapper.getMinecraft().displayGuiScreen(new LambdaGuiAntiDisconnect());
                    callbackInfo.cancel();
                }
                break;
            case 1337:
                ManualDisconnect.INSTANCE.dc();
                break;
            case Integer.MIN_VALUE:
                Wrapper.getMinecraft().displayGuiScreen(new GuiMultiplayer(this));
                break;
            default:
                break;
        }
    }
}
