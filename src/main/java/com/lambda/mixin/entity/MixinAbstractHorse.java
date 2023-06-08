package com.lambda.mixin.entity;

import com.lambda.client.module.modules.movement.EntityControl;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.passive.AbstractHorse;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(value = AbstractHorse.class)
public abstract class MixinAbstractHorse extends EntityLivingBase {

    public MixinAbstractHorse(World worldIn) {
        super(worldIn);
    }

    @Inject(method = "canBeSteered", at = @At("HEAD"), cancellable = true)
    private void canBeSteered(final CallbackInfoReturnable<Boolean> cir) {
        if (EntityControl.INSTANCE.isEnabled()) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "isHorseSaddled", at = @At("HEAD"), cancellable = true)
    private void isHorseSaddled(final CallbackInfoReturnable<Boolean> cir) {
        if (EntityControl.INSTANCE.isEnabled()) {
            cir.setReturnValue(true);
        }
    }
}
