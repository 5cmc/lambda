package com.lambda.mixin.accessor.gui;

import net.minecraft.client.gui.ChatLine;
import net.minecraft.client.gui.GuiNewChat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

@Mixin(GuiNewChat.class)
public interface AccessorGuiNewChat {
    @Accessor("chatLines")
    public List<ChatLine> getChatLines();

    @Accessor("drawnChatLines")
    public List<ChatLine> getDrawnChatLines();
}
