package com.jamsackman.researchtable.mixin;

import com.jamsackman.researchtable.access.SlotXY;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import net.minecraft.screen.slot.Slot;

/**
 * Makes Slot.x / Slot.y mutable at runtime and exposes tiny setters/getters.
 */
@Mixin(Slot.class)
public abstract class SlotXYMixin implements SlotXY {

    @Mutable @Shadow @Final public int x;
    @Mutable @Shadow @Final public int y;

    @Override public int researchtable$getX() { return this.x; }
    @Override public int researchtable$getY() { return this.y; }
    @Override public void researchtable$setX(int x) { this.x = x; }
    @Override public void researchtable$setY(int y) { this.y = y; }
}