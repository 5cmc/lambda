package com.lambda.mixin.gui;

import com.lambda.client.manager.managers.ProxyManager;
import com.lambda.client.module.modules.render.ExtraTab;
import com.lambda.client.module.modules.render.TablistHatLayerForce;
import kotlin.collections.CollectionsKt;
import net.minecraft.client.Minecraft;
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
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.UUID;

@Mixin(GuiPlayerTabOverlay.class)
public class MixinGuiPlayerTabOverlay {

    private List<NetworkPlayerInfo> preSubList = CollectionsKt.emptyList();

    @ModifyVariable(method = "renderPlayerlist", at = @At(value = "STORE", ordinal = 0), ordinal = 0)
    public List<NetworkPlayerInfo> renderPlayerlistStorePlayerListPre(List<NetworkPlayerInfo> list) {
        preSubList = list;
        return list;
    }

    @ModifyVariable(method = "renderPlayerlist", at = @At(value = "STORE", ordinal = 1), ordinal = 0)
    public List<NetworkPlayerInfo> renderPlayerlistStorePlayerListPost(List<NetworkPlayerInfo> list) {
        return ExtraTab.subList(preSubList, list);
    }

    @Inject(method = "getPlayerName", at = @At("HEAD"), cancellable = true)
    public void getPlayerName(NetworkPlayerInfo networkPlayerInfoIn, CallbackInfoReturnable<String> cir) {
        if (ExtraTab.INSTANCE.isEnabled()) {
            cir.setReturnValue(ExtraTab.getPlayerName(networkPlayerInfoIn));
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
