package com.lambda.mixin.entity;

import com.lambda.client.module.modules.movement.EntityControl;
import net.minecraft.entity.passive.EntityAnimal;
import net.minecraft.entity.passive.EntityPig;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(EntityPig.class)
public abstract class MixinEntityPig extends EntityAnimal {

    public MixinEntityPig(World worldIn) {
        super(worldIn);
    }

    @Inject(method = "canBeSteered", at = @At("HEAD"), cancellable = true)
    public void canBeSteered(CallbackInfoReturnable<Boolean> returnable) {
        if (EntityControl.INSTANCE.isEnabled()) {
            returnable.setReturnValue(true);
        }
    }

    @Inject(method = "travel", at = @At(value = "INVOKE", target = "Lnet/minecraft/entity/passive/EntityAnimal;travel(FFF)V", shift = At.Shift.BEFORE, ordinal = 0), cancellable = true)
    public void travel(final float strafe, final float vertical, final float forward, final CallbackInfo ci) {
//        if (EntityControl.INSTANCE.isEnabled()) {
//            super.travel(strafe, vertical, 0.0f);
//            ci.cancel();
//        }
    }
}
