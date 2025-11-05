package com.jamsackman.researchtable.mixin;

import com.jamsackman.researchtable.ResearchTableMod;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(VillagerEntity.class)
public abstract class VillagerEntityMixin {

    @Inject(method = "fillRecipes", at = @At("TAIL"))
    private void researchtable$stripEnchantedTrades(CallbackInfo ci) {
        VillagerEntity self = (VillagerEntity)(Object)this;

        // gamerule toggle
        boolean enabled = self.getWorld()
                .getGameRules()
                .getBoolean(ResearchTableMod.GR_DISABLE_VILLAGER_ENCHANTED_TRADES);
        if (!enabled) return;

        TradeOfferList offers = self.getOffers();
        if (offers == null || offers.isEmpty()) return;

        for (int i = 0; i < offers.size(); i++) {
            TradeOffer offer = offers.get(i);
            if (offer == null) continue;

            ItemStack sell = offer.getSellItem();
            if (sell.isEmpty()) continue;

            boolean isEnchantedBook = sell.isOf(Items.ENCHANTED_BOOK);
            boolean hasEnchantsNbt  = sell.getEnchantments() != null && !sell.getEnchantments().isEmpty();

            if (!isEnchantedBook && !hasEnchantsNbt) continue;

            // Build a “plain” replacement for the sell item
            ItemStack plain = isEnchantedBook
                    ? new ItemStack(Items.BOOK, sell.getCount())
                    : new ItemStack(sell.getItem(), sell.getCount());

            // Strip possible enchant NBT keys just in case
            plain.removeSubNbt("Enchantments");
            plain.removeSubNbt("StoredEnchantments");

            // Rebuild the offer preserving costs/limits/xp
            TradeOffer replacement = new TradeOffer(
                    offer.getOriginalFirstBuyItem(),
                    offer.getSecondBuyItem(),
                    plain,
                    offer.getMaxUses(),
                    offer.getMerchantExperience(),
                    offer.getDemandBonus(),     // <-- int demandBonus first
                    offer.getPriceMultiplier()  // <-- then float priceMultiplier
            );

            offers.set(i, replacement);
        }
    }
}