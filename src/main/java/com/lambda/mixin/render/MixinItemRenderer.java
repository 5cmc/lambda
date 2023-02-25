package com.lambda.mixin.render;

import com.lambda.client.module.modules.player.Freecam;
import com.lambda.client.module.modules.render.ItemModel;
import net.minecraft.client.entity.AbstractClientPlayer;
import net.minecraft.client.renderer.ItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumHand;
import net.minecraft.util.EnumHandSide;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemRenderer.class)
public class MixinItemRenderer {
    @Inject(method = "rotateArm", at = @At("HEAD"), cancellable = true)
    private void rotateArm(float partialTicks, CallbackInfo ci) {
        if (Freecam.INSTANCE.isEnabled() && Freecam.INSTANCE.getCameraGuy() != null) {
            ci.cancel();
        }
        if (ItemModel.INSTANCE.isEnabled() && ItemModel.INSTANCE.getNoSway()) {
            ci.cancel();
        }
    }

    @Inject(method = "renderItemInFirstPerson(Lnet/minecraft/client/entity/AbstractClientPlayer;FFLnet/minecraft/util/EnumHand;FLnet/minecraft/item/ItemStack;F)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/GlStateManager;pushMatrix()V", shift = At.Shift.AFTER))
    private void transformSideFirstPersonInvokePushMatrix(AbstractClientPlayer player, float partialTicks, float pitch, EnumHand hand, float swingProgress, ItemStack stack, float equippedProgress, CallbackInfo ci) {
        ItemModel.translate(stack, hand, player);
    }

    @Inject(method = "renderItemInFirstPerson(Lnet/minecraft/client/entity/AbstractClientPlayer;FFLnet/minecraft/util/EnumHand;FLnet/minecraft/item/ItemStack;F)V", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/ItemRenderer;renderItemSide(Lnet/minecraft/entity/EntityLivingBase;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/renderer/block/model/ItemCameraTransforms$TransformType;Z)V"))
    private void transformSideFirstPersonInvokeRenderItemSide(AbstractClientPlayer player, float partialTicks, float pitch, EnumHand hand, float swingProgress, ItemStack stack, float equippedProgress, CallbackInfo ci) {
        ItemModel.rotateAndScale(stack, hand, player);
    }

    @Inject(method = "transformEatFirstPerson", at = @At("HEAD"), cancellable = true)
    private void transformEatFirstPerson(final float p_187454_1_, final EnumHandSide hand, final ItemStack stack, final CallbackInfo ci) {
        ItemModel.transformEatFirstPerson(ci);
    }

    @Inject(method = "transformSideFirstPerson", at = @At("HEAD"), cancellable = true)
    private void transformSideFirstPerson(final EnumHandSide hand, final float p_187459_2_, final CallbackInfo ci) {
        ItemModel.transformSideFirstPerson(hand, ci);
    }
}
