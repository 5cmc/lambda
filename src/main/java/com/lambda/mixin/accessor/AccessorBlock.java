package com.lambda.mixin.accessor;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(value = Block.class)
public interface AccessorBlock {

    @Invoker(value = "getSilkTouchDrop")
    ItemStack invokeSilkTouchDrop(IBlockState state);

}
