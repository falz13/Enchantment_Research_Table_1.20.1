package com.jamsackman.researchtable.mixin;

import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.EnchantedBookItem;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.AnvilScreenHandler;
import net.minecraft.screen.Property;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Method;
import java.util.Map;

@Mixin(AnvilScreenHandler.class)
public abstract class AnvilScreenHandlerMixin {

    // Present on 1.20.1
    @Shadow @Final private Property levelCost;
    @Shadow private int repairItemUsage;
    @Shadow private String newItemName;

    // ============================= Result assembly =============================
    @Inject(method = "updateResult", at = @At("HEAD"), cancellable = true)
    private void researchtable$onlyRepair(CallbackInfo ci) {
        ScreenHandler self = (ScreenHandler)(Object)this;

        // Slots: 0=left, 1=right, 2=output
        Slot leftSlot   = self.getSlot(0);
        Slot rightSlot  = self.getSlot(1);
        Slot outputSlot = self.getSlot(2);

        ItemStack left  = leftSlot.getStack();
        ItemStack right = rightSlot.getStack();

        // Clear by default
        outputSlot.setStack(ItemStack.EMPTY);
        this.levelCost.set(0);
        this.repairItemUsage = 0;

        if (left.isEmpty()) {
            ci.cancel();
            return;
        }

        // Let vanilla do rename-only
        if (right.isEmpty()) {
            return;
        }

        // Block books/enchanted as material
        if (right.getItem() instanceof EnchantedBookItem) { ci.cancel(); return; }
        if (!EnchantmentHelper.get(right).isEmpty())       { ci.cancel(); return; }

        boolean sameItem     = ItemStack.areItemsEqual(left, right) && left.isDamageable() && right.isDamageable();
        boolean validMaterial= researchtable$isValidRepairMaterial(left, right);

        if (!sameItem && !validMaterial) { ci.cancel(); return; }

        ItemStack result = left.copy();

        if (sameItem) {
            int max = result.getMaxDamage();
            int dur1 = max - left.getDamage();
            int dur2 = max - right.getDamage();
            int bonus = (int)(max * 0.12);
            int combined = Math.min(max, dur1 + dur2 + bonus);
            int newDamage = Math.max(0, max - combined);

            if (newDamage >= left.getDamage()) { ci.cancel(); return; }

            result.setDamage(newDamage);
            this.repairItemUsage = 1; // exactly one same-item
        } else {
            if (!result.isDamageable()) { ci.cancel(); return; }
            int currentDamage = left.getDamage();
            if (currentDamage <= 0)     { ci.cancel(); return; }

            int max = result.getMaxDamage();
            int repairPerUnit = Math.max(1, max / 4);

            int have = right.getCount();
            int unitsNeeded = (currentDamage + repairPerUnit - 1) / repairPerUnit; // ceil
            int units = Math.min(have, unitsNeeded);
            if (units <= 0) { ci.cancel(); return; }

            int repaired = units * repairPerUnit;
            int newDamage = Math.max(0, currentDamage - repaired);
            if (newDamage >= currentDamage) { ci.cancel(); return; }

            result.setDamage(newDamage);
            this.repairItemUsage = units; // <- exact, bounded
        }

        // Preserve rename
        if (this.newItemName != null && !this.newItemName.isBlank()) {
            result.setCustomName(Text.literal(this.newItemName));
        } else {
            result.removeCustomName();
        }

        // Cost = sum of non-curse levels on LEFT (min 1)
        int totalLevels = researchtable$sumEnchantLevels(left);
        this.levelCost.set(Math.max(1, totalLevels));

        outputSlot.setStack(result);
        self.sendContentUpdates();
        ci.cancel();
    }

    // ============================= Exact consumption on take =============================
    // Some UIs re-run updateResult() and can disturb repairItemUsage; clamp consumption here.
    @Inject(method = "onTakeOutput", at = @At("HEAD"), cancellable = true)
    private void researchtable$onTakeOutput(PlayerEntity player, ItemStack taken, CallbackInfo ci) {
        ScreenHandler self = (ScreenHandler)(Object)this;

        // slots
        Slot leftSlot   = self.getSlot(0);
        Slot rightSlot  = self.getSlot(1);
        Slot outputSlot = self.getSlot(2);

        ItemStack left  = leftSlot.getStack();
        ItemStack right = rightSlot.getStack();

        // We only manage cases where we produced a result (right was not empty when crafting)
        if (taken.isEmpty() || right.isEmpty()) return; // let vanilla handle rename-only etc.

        // Deduct XP exactly once based on current levelCost
        int cost = Math.max(0, this.levelCost.get());
        if (cost > 0) {
            player.addExperienceLevels(-cost);
        }

        // Consume EXACTLY repairItemUsage (never more)
        int use = Math.max(0, this.repairItemUsage);
        if (use > 0) {
            int toConsume = Math.min(use, right.getCount());
            if (toConsume > 0) {
                ItemStack r = right.copy();
                r.decrement(toConsume);
                rightSlot.setStack(r.isEmpty() ? ItemStack.EMPTY : r);
            }
        }

        // Left input is consumed by anvil operations that produce an output
        leftSlot.setStack(ItemStack.EMPTY);

        // Clear output now that it's taken
        outputSlot.setStack(ItemStack.EMPTY);

        // Reset costs/usage and notify
        this.levelCost.set(0);
        this.repairItemUsage = 0;
        self.sendContentUpdates();

        // We fully handled the take; stop vanilla from double-consuming
        ci.cancel();
    }

    // ============================= Helpers =============================
    @Unique
    private static int researchtable$sumEnchantLevels(ItemStack stack) {
        int sum = 0;
        for (Map.Entry<net.minecraft.enchantment.Enchantment, Integer> e : EnchantmentHelper.get(stack).entrySet()) {
            if (!e.getKey().isCursed()) sum += Math.max(1, e.getValue());
        }
        return sum;
    }

    /**
     * Ask the left item if the right item is a valid repair ingredient.
     * Tries canRepair(ItemStack,ItemStack) first, then isValidRepairItem(ItemStack,ItemStack).
     * Falls back to false (strict) if neither exists.
     */
    @Unique
    private static boolean researchtable$isValidRepairMaterial(ItemStack left, ItemStack right) {
        if (left.isEmpty() || right.isEmpty()) return false;
        if (!left.isDamageable()) return false;
        if (right.isDamageable()) return false;
        if (!right.isStackable()) return false;

        var item = left.getItem();
        try {
            Method m = item.getClass().getMethod("canRepair", ItemStack.class, ItemStack.class);
            m.setAccessible(true);
            Object ok = m.invoke(item, left, right);
            if (ok instanceof Boolean b) return b;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            return false;
        }

        try {
            Method m2 = item.getClass().getMethod("isValidRepairItem", ItemStack.class, ItemStack.class);
            m2.setAccessible(true);
            Object ok2 = m2.invoke(item, left, right);
            if (ok2 instanceof Boolean b2) return b2;
        } catch (NoSuchMethodException ignored) {
        } catch (Throwable t) {
            return false;
        }

        return false;
    }
}