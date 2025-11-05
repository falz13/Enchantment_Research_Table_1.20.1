package com.jamsackman.researchtable.mixin;

import net.minecraft.screen.slot.Slot;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Slot.class)
public interface SlotAccessor {
    @Accessor("x") void researchtable$setX(int x);
    @Accessor("y") void researchtable$setY(int y);
}