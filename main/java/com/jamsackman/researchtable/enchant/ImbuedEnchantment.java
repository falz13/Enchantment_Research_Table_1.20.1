package com.jamsackman.researchtable.enchant;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentTarget;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;

public class ImbuedEnchantment extends Enchantment {
    public ImbuedEnchantment() {
        super(Rarity.VERY_RARE, EnchantmentTarget.BREAKABLE, new EquipmentSlot[] {
                EquipmentSlot.MAINHAND, EquipmentSlot.OFFHAND,
                EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET
        });
    }

    @Override public int getMaxLevel() { return 1; }

    // Make it “cosmetic/marker”: allow on anything you choose to tag
    @Override public boolean isAcceptableItem(ItemStack stack) { return true; }

    // Don’t conflict with anything
    @Override public boolean canAccept(Enchantment other) { return true; }

    // Keep it out of table/villager pools (apply programmatically)
    @Override public boolean isTreasure() { return true; }
    @Override public boolean isAvailableForRandomSelection() { return false; }
    @Override public boolean isAvailableForEnchantedBookOffer() { return false; }

    // Optional: show as a curse-style red entry if you like
    @Override public boolean isCursed() { return false; }
}