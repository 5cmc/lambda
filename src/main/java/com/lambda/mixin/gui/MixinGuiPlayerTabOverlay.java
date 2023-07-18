package com.lambda.mixin.gui;

import com.lambda.client.manager.managers.ProxyManager;
import com.lambda.client.module.modules.render.ExtraTab;
import com.lambda.client.module.modules.render.TablistHatLayerForce;
import com.mojang.authlib.GameProfile;
import kotlin.collections.CollectionsKt;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiPlayerTabOverlay;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.network.NetworkPlayerInfo;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EnumPlayerModelParts;
import net.minecraft.network.NetworkManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

@Mixin(GuiPlayerTabOverlay.class)
public class MixinGuiPlayerTabOverlay {

    private List<NetworkPlayerInfo> preSubList = CollectionsKt.emptyList();
    private int lastRectLeft;
    private int lastRectTop;
    private int lastRectRight;
    private int lastRectBottom;


    @ModifyVariable(method = "renderPlayerlist", at = @At(value = "STORE", ordinal = 0), ordinal = 0)
    public List<NetworkPlayerInfo> renderPlayerlistStorePlayerListPre(List<NetworkPlayerInfo> list) {
        preSubList = list;
        return list;
    }

    @ModifyVariable(method = "renderPlayerlist", at = @At(value = "STORE", ordinal = 1), ordinal = 0)
    public List<NetworkPlayerInfo> renderPlayerlistStorePlayerListPost(List<NetworkPlayerInfo> list) {
        return ExtraTab.subList(preSubList, list);
    }

    // j4, number of columns
    @ModifyVariable(method = "renderPlayerlist", at = @At(value = "LOAD", ordinal = 5), index = 10)
    public int modifyColNumVar(int colNum) {
        if (ExtraTab.INSTANCE.isEnabled())
            return (int) Math.ceil(preSubList.size() / ((double) ExtraTab.INSTANCE.getRowsPerColumn()));
        else return colNum;
    }

    // i4, row count per column
    @ModifyVariable(method = "renderPlayerlist", at = @At(value = "LOAD", ordinal = 1), index = 9)
    public int modifyRowNumVar(int rowCount) {
        if (ExtraTab.INSTANCE.isEnabled())
            return ExtraTab.INSTANCE.getRowsPerColumn();
        else return rowCount;
    }

    @Redirect(method = "renderPlayerlist", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/GuiPlayerTabOverlay;drawRect(IIIII)V", ordinal = 2))
    public void getRectArgs(final int left, final int top, final int right, final int bottom, final int color) {
        if (ExtraTab.INSTANCE.isEnabled() && ExtraTab.INSTANCE.getOnlineTime() && ExtraTab.INSTANCE.getOnlineBar()) {
            lastRectLeft = left;
            lastRectTop = top;
            lastRectRight = right;
            lastRectBottom = bottom;
        } else {
            Gui.drawRect(left, top, right, bottom, color);
        }
    }

    @Redirect(method = "renderPlayerlist", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/network/NetworkPlayerInfo;getGameProfile()Lcom/mojang/authlib/GameProfile;", ordinal = 1))
    public GameProfile getPlayerRedirect(NetworkPlayerInfo instance) {
        GameProfile player = instance.getGameProfile();
        if (ExtraTab.INSTANCE.isEnabled() && ExtraTab.INSTANCE.getOnlineTime() && ExtraTab.INSTANCE.getOnlineBar()) {
            ExtraTab.drawExpendRect(lastRectLeft, lastRectTop, lastRectRight, lastRectBottom, player.getName());
        }
        return player;
    }

    @Inject(method = "getPlayerName", at = @At("HEAD"), cancellable = true)
    public void getPlayerName(NetworkPlayerInfo networkPlayerInfoIn, CallbackInfoReturnable<String> cir) {
        if (ExtraTab.INSTANCE.isEnabled() && ExtraTab.INSTANCE.getOnlineTime() && ExtraTab.INSTANCE.getOnlineTimer()) {
            cir.setReturnValue(ExtraTab.getPlayerName(networkPlayerInfoIn));
        }
    }

    @Inject(method = "drawPing", at = @At("HEAD"), cancellable = true)
    public void drawPingInject(int p_175245_1_, int p_175245_2_, int p_175245_3_, NetworkPlayerInfo networkPlayerInfoIn, CallbackInfo ci) {
        if (ExtraTab.INSTANCE.getPing() == ExtraTab.Mode.HIDDEN) {
            ci.cancel();
        }
    }

    @Redirect(method = "renderPlayerlist", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/player/EntityPlayer;isWearing(Lnet/minecraft/entity/player/EnumPlayerModelParts;)Z"))
    public boolean redirectHatCheck(final EntityPlayer instance, final EnumPlayerModelParts part) {
        if (TablistHatLayerForce.INSTANCE.isEnabled() && part == EnumPlayerModelParts.HAT) {
            return true;
        } else {
            return instance.isWearing(part);
        }
    }

    @Redirect(method = "renderPlayerlist", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/WorldClient;getPlayerEntityByUUID(Ljava/util/UUID;)Lnet/minecraft/entity/player/EntityPlayer;"))
    public EntityPlayer redirectEntityPlayer(final WorldClient instance, final UUID uuid) {
        if (TablistHatLayerForce.INSTANCE.isEnabled()) {
            EntityPlayer playerEntityByUUID = instance.getPlayerEntityByUUID(uuid);
            if (playerEntityByUUID == null) {
                return Minecraft.getMinecraft().player;
            } else {
                return playerEntityByUUID;
            }
        }
        return instance.getPlayerEntityByUUID(uuid);
    }

    @Redirect(method = "renderPlayerlist", at = @At(value = "INVOKE", target = "Lnet/minecraft/network/NetworkManager;isEncrypted()Z"))
    public boolean redirectEncryptedCheck(final NetworkManager instance) {
        // fix if using the proxy locally without account verification enabled - only applicable for debugging
        if (ProxyManager.INSTANCE.isProxy()) {
            return true;
        } else {
            return instance.isEncrypted();
        }
    }

}
