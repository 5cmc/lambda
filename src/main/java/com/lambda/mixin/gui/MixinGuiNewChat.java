package com.lambda.mixin.gui;

import com.lambda.client.module.modules.chat.AntiSpam;
import com.lambda.client.module.modules.chat.ExtraChatHistory;
import com.lambda.client.module.modules.player.PacketLogger;
import com.lambda.client.module.modules.render.NoRender;
import kotlin.random.Random;
import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiNewChat;
import net.minecraft.util.text.ITextComponent;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(GuiNewChat.class)
public abstract class MixinGuiNewChat {
    @Shadow @Final private List<ChatLine> chatLines;
    @Shadow @Final private List<ChatLine> drawnChatLines;
    @Shadow public abstract void printChatMessageWithOptionalDeletion(ITextComponent chatComponent, int chatLineId);

    @Redirect(method = "drawChat", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiNewChat;drawRect(IIIII)V"))
    private void drawRectBackgroundClean(int left, int top, int right, int bottom, int color) {
        if (!NoRender.INSTANCE.isEnabled() || !NoRender.INSTANCE.getChatGlobal()) {
            Gui.drawRect(left, top, right, bottom, color);
        }
    }

    @Inject(method = "setChatLine", at = @At(value = "INVOKE", target = "Ljava/util/List;size()I", ordinal = 0, remap = false), cancellable = true)
    public void setChatLineInvokeSize(ITextComponent chatComponent, int chatLineId, int updateCounter, boolean displayOnly, CallbackInfo ci) {
        ExtraChatHistory.handleSetChatLine(drawnChatLines, chatLines, chatComponent, chatLineId, updateCounter, displayOnly, ci);
    }

    @Inject(method = "printChatMessage", at = @At("HEAD"), cancellable = true)
    public void printChatMessageInject(ITextComponent chatComponent, CallbackInfo ci) {
        if ((AntiSpam.INSTANCE.isEnabled() && AntiSpam.INSTANCE.getDuplicates()) || (PacketLogger.INSTANCE.isEnabled() && PacketLogger.INSTANCE.getDeduping())) {
            // modify message in place if its a dupe
            AntiSpam.handlePrintChatMessage((GuiNewChat) (Object) this, chatComponent);
            // send message with a random ID. Otherwise all messages have ID 0. Needed to be able to remove old dupe messages.
            ci.cancel();
            printChatMessageWithOptionalDeletion(chatComponent, Random.Default.nextInt());
        }
    }
}