package com.jamsackman.researchtable.mixin.access;

import net.minecraft.enchantment.Enchantment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Expose Enchantment#canAccept(Enchantment) (protected in 1.20.1). */
@Mixin(Enchantment.class)
public interface EnchantCompat {
    @Invoker("canAccept")
    boolean researchtable$canAccept(Enchantment other);
}