package com.jamsackman.researchtable.screen;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.jamsackman.researchtable.ResearchTableMod;
import com.jamsackman.researchtable.data.ResearchItems;
import com.jamsackman.researchtable.mixin.access.EnchantCompat;

import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.Property;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.slot.Slot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;

public class ResearchTableScreenHandler extends ScreenHandler {
    // --- Layout ---
    public static final int INPUT_X = 74;
    public static final int INPUT_Y = 30;

    public static final int INV_X   = 67;
    public static final int INV_Y   = 82;
    public static final int HOTBAR_Y = INV_Y + 59;

    public static final int INPUT_SLOT = 0;
    public static final int LAPIS_SLOT = 1;

    public static final int LAPIS_X = 134;
    public static final int LAPIS_Y = 22;

    public static final int PREVIEW_SLOT = 2;

    private final SimpleInventory previewInv = new SimpleInventory(1) {
        @Override public void markDirty() {
            super.markDirty();
            ResearchTableScreenHandler.this.sendContentUpdates();
        }
    };

    // server copy of the client’s current selections (ench -> level)
    private final Map<Enchantment, Integer> serverSelections = new HashMap<>();
    private int serverLevelCost = 0;
    private int serverLapisCost  = 0;

    private final Inventory input;
    private final PlayerInventory playerInv;

    private final Property imbueModeProp = Property.create(); // 0 = research, 1 = imbue
    private final ScreenHandlerContext context;

    public boolean isImbueMode() { return imbueModeProp.get() == 1; }
    public void setImbueMode(boolean on) {
        imbueModeProp.set(on ? 1 : 0); // still syncs to client

        // Server-side: on ANY tab change, give items back
        if (playerInv.player instanceof ServerPlayerEntity sp) {
            returnInputsToPlayer(sp);
        }
    }

    // --- ctor overloads so you can pass context from your BlockEntity open handler
    public ResearchTableScreenHandler(int syncId, PlayerInventory playerInventory) {
        this(syncId, playerInventory, ScreenHandlerContext.EMPTY);
    }

    // Return input + lapis to the player's inventory (drop leftovers), then clear slots.
    private void returnInputsToPlayer(ServerPlayerEntity sp) {
        for (int i = 0; i < 2; i++) { // 0 = INPUT_SLOT, 1 = LAPIS_SLOT
            ItemStack s = input.getStack(i);
            if (!s.isEmpty()) {
                ItemStack toGive = s.copy();
                boolean fullyInserted = sp.getInventory().insertStack(toGive); // mutates toGive
                if (!fullyInserted && !toGive.isEmpty()) {
                    sp.dropItem(toGive, false);
                }
                input.setStack(i, ItemStack.EMPTY);
            }
        }
        input.markDirty();
        // Clear preview too, to avoid “ghost” previews when leaving tabs
        previewInv.setStack(0, ItemStack.EMPTY);
        previewInv.markDirty();
        sendContentUpdates();
    }

    public ResearchTableScreenHandler(int syncId, PlayerInventory playerInventory, ScreenHandlerContext context) {
        super(ModScreens.RESEARCH_TABLE, syncId);
        this.playerInv = playerInventory;
        this.context = context;
        this.input = new SimpleInventory(2);

        // sync imbueMode to client
        this.addProperty(imbueModeProp);
        this.setImbueMode(false); // start in "research" rules

        // 0: shared input slot (rules change by mode)
        this.addSlot(new Slot(input, INPUT_SLOT, INPUT_X, INPUT_Y) {
            @Override public boolean canInsert(ItemStack stack) {
                if (isImbueMode()) return !stack.isEmpty();
                return isAcceptedForResearch(stack);
            }
        });

        // Lapis-only slot
        this.addSlot(new Slot(input, LAPIS_SLOT, LAPIS_X, LAPIS_Y) {
            @Override public boolean canInsert(ItemStack stack) {
                return isImbueMode() && stack.isOf(Items.LAPIS_LAZULI);
            }
            @Override public boolean isEnabled() { return isImbueMode(); }
        });

        final int previewX = INPUT_X + 119;
        final int previewY = INPUT_Y - 8;

        this.addSlot(new Slot(this.previewInv, 0, previewX, previewY) {
            @Override public boolean canInsert(ItemStack stack) { return false; }

            @Override
            public boolean canTakeItems(PlayerEntity player) {
                if (!(player instanceof ServerPlayerEntity sp)) return false;
                ItemStack previewNow = getStack(); // the preview slot's stack before it's taken
                return ResearchTableScreenHandler.this.canAfford(sp, previewNow);
            }

            @Override
            public boolean isEnabled() {
                // Hide the preview slot entirely unless we're on the Imbue tab
                return isImbueMode();
            }

            @Override public void onTakeItem(PlayerEntity player, ItemStack taken) {
                if (player instanceof ServerPlayerEntity sp) {
                    ResearchTableScreenHandler.this.finalizeImbue(sp, taken);
                }
                super.onTakeItem(player, taken);
            }
        });

        // Player inventory (3 rows)
        for (int row = 0; row < 3; ++row) {
            for (int col = 0; col < 9; ++col) {
                this.addSlot(new Slot(playerInventory, col + row * 9 + 9, INV_X + col * 18, INV_Y + row * 18));
            }
        }

        // Hotbar
        for (int col = 0; col < 9; ++col) {
            this.addSlot(new Slot(playerInventory, col, INV_X + col * 18, HOTBAR_Y));
        }
    }

    // --- Helpers ---

    private static String toRoman(int number) {
        if (number <= 0) return "I";
        int[] values = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
        String[] numerals = {"M","CM","D","CD","C","XC","L","XL","X","IX","V","IV","I"};
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            while (number >= values[i]) {
                number -= values[i];
                sb.append(numerals[i]);
            }
        }
        return sb.toString();
    }

    private static Text createUnlockedMessage(Text base) {
        MutableText copy = base.copy();
        return copy.append(Text.literal(" unlocked!")).styled(style -> style.withColor(ResearchTableMod.COLOR_UNLOCKED));
    }

    /** True iff both enchantments mutually accept each other. */
    private static boolean areCompatible(Enchantment a, Enchantment b) {
        return accepts(a, b) && accepts(b, a);
    }

    private static boolean accepts(Enchantment owner, Enchantment other) {
        if (owner == null || other == null || owner == other) return true;
        try {
            return ((EnchantCompat) owner).researchtable$canAccept(other);
        } catch (Throwable t) {
            return true;
        }
    }

    // -------------------- Preview / cost calc for Imbue tab --------------------

    private void rebuildPreview(ServerPlayerEntity player) {
        ItemStack input = this.getSlot(INPUT_SLOT).getStack();

        previewInv.setStack(0, ItemStack.EMPTY);
        serverLevelCost = 0;
        serverLapisCost = 0;

        if (input.isEmpty() || serverSelections.isEmpty()) return;

        // Base: copy input
        ItemStack out = input.copy();

        // Merge existing + selected enchants
        Map<Enchantment,Integer> current = EnchantmentHelper.get(out);
        current.entrySet().removeIf(e -> ResearchTableMod.isHiddenEnch(e.getKey()));
        int currentLevels = current.values().stream().mapToInt(Integer::intValue).sum();

        Map<Enchantment,Integer> result = new HashMap<>(current);
        serverSelections.forEach((e, lvl) -> {
            boolean ok = current.keySet().stream().allMatch(cur -> areCompatible(e, cur));
            ok = ok && serverSelections.keySet().stream().filter(other -> other != e).allMatch(other -> areCompatible(e, other));
            if (ok) result.put(e, Math.min(lvl, e.getMaxLevel()));
            boolean allCompat = true;
            List<Enchantment> keys = new java.util.ArrayList<>(result.keySet());
            for (int i = 0; i < keys.size() && allCompat; i++) {
                for (int j = i + 1; j < keys.size() && allCompat; j++) {
                    if (!areCompatible(keys.get(i), keys.get(j))) allCompat = false;
                }
            }
            boolean acceptableForItem = keys.stream().allMatch(en -> {
                try { return en.isAcceptableItem(out); } catch (Throwable t) { return true; }
            });
            if (!allCompat || !acceptableForItem) {
                // if it fails, do NOT offer a preview or a cost — player must resolve selection
                previewInv.setStack(0, ItemStack.EMPTY);
                serverLevelCost = 0;
                serverLapisCost = 0;
                return;
            }
        });

        // selected levels (ignore IMBUED in cost)
        int levelIncrease = 0;
        for (var e : serverSelections.entrySet()) {
            int cur = current.getOrDefault(e.getKey(), 0);
            int target = Math.min(e.getValue(), e.getKey().getMaxLevel());
            levelIncrease += Math.max(0, target - cur);
        }

        double term1 = Math.pow(currentLevels, 1.45);
        double term2 = Math.pow(10.0 * levelIncrease, 0.8);
        serverLevelCost = (int)Math.ceil(term1 + term2);

        double l1 = Math.pow(currentLevels, 1.5);
        double l2 = Math.pow(levelIncrease, 1.5);
        serverLapisCost = Math.min(64, (int)Math.ceil(l1 + l2));

        // Add IMBUED (level 1) to the result (doesn't affect cost)
        Enchantment imbued = Registries.ENCHANTMENT.get(ResearchTableMod.IMBUED_ID);
        if (imbued != null) result.put(imbued, 1);

        // Write back enchants to out
        EnchantmentHelper.set(result, out);

        ResearchTableMod.LOGGER.info(
                "[server] rebuildPreview: levelCost={}, lapisCost={}, input={}",
                serverLevelCost, serverLapisCost, this.getSlot(INPUT_SLOT).getStack()
        );

        previewInv.setStack(0, out);
    }

    private boolean canAfford(ServerPlayerEntity player, /*@Nullable*/ ItemStack resultItem) {
        boolean haveResultContext =
                (resultItem != null && !resultItem.isEmpty())
                        || (!this.getSlot(INPUT_SLOT).getStack().isEmpty() && !serverSelections.isEmpty());

        if (!haveResultContext) {
            ResearchTableMod.LOGGER.info("[server] canAfford: no result/input context; deny");
            return false;
        }

        boolean enoughLevels = player.experienceLevel >= serverLevelCost;
        ItemStack lapis = this.getSlot(LAPIS_SLOT).getStack();
        boolean enoughLapis = !lapis.isEmpty() && lapis.getCount() >= serverLapisCost;

        ResearchTableMod.LOGGER.info(
                "[server] canAfford: levelCost={}, lapisCost={}, playerLvl={}, lapisInSlot={}, levelsOK={}, lapisOK={}",
                serverLevelCost, serverLapisCost, player.experienceLevel,
                lapis.getCount(), enoughLevels, enoughLapis
        );

        return enoughLevels && enoughLapis;
    }

    private void finalizeImbue(ServerPlayerEntity player, ItemStack resultTaken) {
        if (resultTaken == null || resultTaken.isEmpty()) {
            ResearchTableMod.LOGGER.info("[server] finalizeImbue: taken is empty, abort");
            return;
        }
        if (!canAfford(player, resultTaken)) {
            ResearchTableMod.LOGGER.info(
                    "[server] finalizeImbue: cannot afford post-take (lvlCost={}, lapisCost={}, playerLvl={}, lapisSlot={})",
                    serverLevelCost, serverLapisCost, player.experienceLevel, this.getSlot(LAPIS_SLOT).getStack()
            );
            return;
        }

        // Reconstruct the would-be result just like rebuildPreview did:
        ItemStack base = this.getSlot(INPUT_SLOT).getStack();
        if (base.isEmpty()) return;

        Map<Enchantment,Integer> current = EnchantmentHelper.get(base);
        current.entrySet().removeIf(e -> ResearchTableMod.isHiddenEnch(e.getKey()));
        Map<Enchantment,Integer> result = new HashMap<>(current);

        for (var e : serverSelections.entrySet()) {
            Enchantment ench = e.getKey();
            int target = Math.min(e.getValue(), ench.getMaxLevel());

            boolean ok = current.keySet().stream().allMatch(cur -> areCompatible(ench, cur));
            ok = ok && serverSelections.keySet().stream().filter(other -> other != ench)
                    .allMatch(other -> areCompatible(ench, other));

            if (!ok) return; // hard stop: incompatible selection
            result.put(ench, target);
        }

// Pairwise check after merge
        List<Enchantment> ks = new java.util.ArrayList<>(result.keySet());
        for (int i = 0; i < ks.size(); i++) {
            for (int j = i + 1; j < ks.size(); j++) {
                if (!areCompatible(ks.get(i), ks.get(j))) return;
            }
        }

// Acceptable for the item type
        ItemStack check = base.copy();
        EnchantmentHelper.set(result, check);
        boolean acceptable = ks.stream().allMatch(en -> {
            try { return en.isAcceptableItem(check); } catch (Throwable t) { return true; }
        });
        if (!acceptable) return;

        // ---- Spend lapis (write back into the real inventory backing the slot)
        Slot lapisSlot = this.getSlot(LAPIS_SLOT);
        Inventory lapisInv = lapisSlot.inventory;
        int lapisIdx = lapisSlot.getIndex();
        ItemStack lap = lapisInv.getStack(lapisIdx).copy();
        lap.decrement(serverLapisCost);
        lapisInv.setStack(lapisIdx, lap);
        lapisInv.markDirty();
        lapisSlot.markDirty();

        // ---- Spend levels
        player.addExperienceLevels(-serverLevelCost);

        // ---- Destroy original input item
        Slot inputSlot = this.getSlot(INPUT_SLOT);
        Inventory inputInv = inputSlot.inventory;
        int inputIdx = inputSlot.getIndex();
        inputInv.setStack(inputIdx, ItemStack.EMPTY);
        inputInv.markDirty();
        inputSlot.markDirty();

        // Count imbues + award tiered advancements
        var state = ResearchTableMod.getResearchState(player.server);
        state.incImbued(player.getUuid());
        int c = state.getImbuedCount(player.getUuid());

        if (c >= 1)   ResearchTableMod.grant(player, ResearchTableMod.ADV_IMBUE_1);
        if (c >= 5)   ResearchTableMod.grant(player, ResearchTableMod.ADV_IMBUE_5);
        if (c >= 25)  ResearchTableMod.grant(player, ResearchTableMod.ADV_IMBUE_25);
        if (c >= 100) ResearchTableMod.grant(player, ResearchTableMod.ADV_IMBUE_100);

        // ---- Clear state / preview
        serverSelections.clear();
        previewInv.setStack(0, ItemStack.EMPTY);
        previewInv.markDirty();

        this.sendContentUpdates();

        ResearchTableMod.LOGGER.info(
                "[server] finalizeImbue: applied. playerLvlNow={}, lapisNow={}, inputNow={}",
                player.experienceLevel,
                this.getSlot(LAPIS_SLOT).getStack().getCount(),
                this.getSlot(INPUT_SLOT).getStack()
        );
    }

    public void serverSetImbueSelections(ServerPlayerEntity player,
                                         Map<Identifier, Integer> ids) {
        serverSelections.clear();

        ItemStack base = this.getSlot(INPUT_SLOT).getStack();
        Map<Enchantment, Integer> current = EnchantmentHelper.get(base);
        current.entrySet().removeIf(e -> ResearchTableMod.isHiddenEnch(e.getKey()));

        ids.forEach((id, lvl) -> {
            if (ResearchTableMod.isHiddenId(id)) return;
            Enchantment ench = Registries.ENCHANTMENT.get(id);
            if (ench == null) return;

            int clamped = Math.min(Math.max(1, lvl), ench.getMaxLevel());
            if (clamped <= 0) return;

            boolean ok = current.keySet().stream().allMatch(cur -> areCompatible(ench, cur));
            if (!ok) return;

            ok = serverSelections.keySet().stream()
                    .filter(other -> other != ench)
                    .allMatch(other -> areCompatible(ench, other));
            if (!ok) return;

            if (base.isEmpty()) return;
            try {
                if (!ench.isAcceptableItem(base)) return;
            } catch (Throwable ignored) {}

            serverSelections.put(ench, clamped);
        });
        rebuildPreview(player);
        this.sendContentUpdates();
    }

    private static boolean isImbued(ItemStack stack) {
        return EnchantmentHelper.getLevel(ResearchTableMod.IMBUED, stack) > 0;
    }

    private boolean isAcceptedForResearch(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;

        // a) "Imbued" items not allowed
        if (isImbued(stack)) return false;

        // b) enchanted items/books give points
        if (!EnchantmentHelper.get(stack).isEmpty()) return true;

        // c) “research material” items listed in your datapack/table
        Identifier id = Registries.ITEM.getId(stack.getItem());
        return id != null && ResearchItems.entries().containsKey(id.toString());
    }

    @Override
    public boolean canUse(PlayerEntity player) { return true; }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        ItemStack ret = ItemStack.EMPTY;
        Slot slot = this.slots.get(index);
        if (slot != null && slot.hasStack()) {
            ItemStack stack = slot.getStack();
            ret = stack.copy();

            if (index == INPUT_SLOT || index == LAPIS_SLOT) {
                // from either table slot -> player
                if (!this.insertItem(stack, 2, this.slots.size(), true)) return ItemStack.EMPTY;
                slot.onQuickTransfer(stack, ret);
            } else {
                // from player -> table
                boolean moved = false;

                if (isImbueMode()) {
                    if (stack.isOf(Items.LAPIS_LAZULI)) {
                        moved = this.insertItem(stack, LAPIS_SLOT, LAPIS_SLOT + 1, false);
                    }
                    if (!moved) {
                        moved = this.insertItem(stack, INPUT_SLOT, INPUT_SLOT + 1, false);
                    }
                } else {
                    if (isAcceptedForResearch(stack)) {
                        moved = this.insertItem(stack, INPUT_SLOT, INPUT_SLOT + 1, false);
                    }
                }

                if (!moved) {
                    if (index < 28) {
                        if (!this.insertItem(stack, 28, this.slots.size(), false)) return ItemStack.EMPTY;
                    } else if (!this.insertItem(stack, 2, 28, false)) {
                        return ItemStack.EMPTY;
                    }
                }
            }

            if (stack.isEmpty()) slot.setStack(ItemStack.EMPTY);
            else slot.markDirty();
            if (stack.getCount() == ret.getCount()) return ItemStack.EMPTY;
            slot.onTakeItem(player, stack);
        }
        return ret;
    }

    @Override
    public void onClosed(PlayerEntity player) {
        super.onClosed(player);
        if (player instanceof ServerPlayerEntity sp) {
            returnInputsToPlayer(sp);
        }
    }

    public void setServerSelections(Map<Enchantment, Integer> map) {
        serverSelections.clear();
        serverSelections.putAll(map);
        updatePreviewItem();
    }

    private void updatePreviewItem() {
        ItemStack base = this.getSlot(INPUT_SLOT).getStack();
        if (base.isEmpty() || serverSelections.isEmpty() || !isImbueMode()) {
            previewInv.setStack(0, ItemStack.EMPTY);
            return;
        }

        ItemStack out = base.copy();
        for (var e : serverSelections.entrySet()) {
            Enchantment ench = e.getKey();
            int lvl = Math.min(e.getValue(), ench.getMaxLevel());
            if (lvl > 0) out.addEnchantment(ench, lvl);
        }

        out.addEnchantment(ResearchTableMod.IMBUED, 1);
        out.setCustomName(Text.literal("Imbued ").append(base.getName()));

        previewInv.setStack(0, out);
    }

    @Override
    public void onContentChanged(Inventory inv) {
        super.onContentChanged(inv);

        if (playerInv.player instanceof ServerPlayerEntity sp) {
            if (inv == this.getSlot(INPUT_SLOT).inventory || inv == this.getSlot(LAPIS_SLOT).inventory) {
                if (isImbueMode()) {
                    rebuildPreview(sp);
                }
            }
        }
    }

    // -------------------- Research consume logic (with bookshelf bonus) --------------------

    public void consumeOne(ServerPlayerEntity player) {
        ItemStack stack = input.getStack(INPUT_SLOT);
        if (stack.isEmpty()) return;

        var state = ResearchTableMod.getResearchState(player.server);

        // Use the table’s actual position via ScreenHandlerContext
        float shelfMult = context.get((world, pos) -> bookshelfMultiplier(world, pos), 1.0f);

        Runnable cue = () -> {
            var world = player.getWorld();
            if (world instanceof ServerWorld sw) {
                var blue = new net.minecraft.particle.DustParticleEffect(new org.joml.Vector3f(0.15f, 0.35f, 0.95f), 1.0f);
                sw.spawnParticles(blue, player.getX(), player.getY() + 1.0, player.getZ(), 20, 0.35, 0.35, 0.35, 0.0);
            }
            world.playSound(
                    null, player.getBlockPos(),
                    net.minecraft.sound.SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE,
                    net.minecraft.sound.SoundCategory.PLAYERS,
                    0.6f, 1.25f
            );
        };

        // ----- Case A: enchanted items/books (discovery path) -----
        Map<Enchantment, Integer> enchants = EnchantmentHelper.get(stack);
        if (!enchants.isEmpty()) {
            cue.run();

            enchants.forEach((ench, level) -> {
                if (ResearchTableMod.isHiddenEnch(ench)) return;

                final String enchIdStr = Registries.ENCHANTMENT.getId(ench).toString();

                int beforeTotal  = state.getProgress(player.getUuid(), enchIdStr);
                int beforeUsable = com.jamsackman.researchtable.state.ResearchPersistentState.usableLevelFor(beforeTotal);

                int base = Math.max(1, level) * 100 * stack.getCount();
                int gained = Math.max(1, Math.round(base * shelfMult)); // apply bookshelf bonus
                state.addProgress(player.getUuid(), enchIdStr, gained);

                if (gained > 0) {
                    int before = state.getTotalPoints(player.getUuid());
                    state.addTotalPoints(player.getUuid(), gained);
                    if (before == 0) ResearchTableMod.grant(player, ResearchTableMod.ADV_FIRST_POINTS);

                    int total = state.getTotalPoints(player.getUuid());
                    if (total >= 5_000)   ResearchTableMod.grant(player, ResearchTableMod.ADV_5K_POINTS);
                    if (total >= 20_000)  ResearchTableMod.grant(player, ResearchTableMod.ADV_20K_POINTS);
                    if (total >= 100_000) ResearchTableMod.grant(player, ResearchTableMod.ADV_100K_POINTS);
                }

                int afterTotal  = state.getProgress(player.getUuid(), enchIdStr);
                int rawUsable   = com.jamsackman.researchtable.state.ResearchPersistentState.usableLevelFor(afterTotal);
                int afterUsable = Math.min(rawUsable, ench.getMaxLevel());

                player.sendMessage(Text.literal("Researched ").append(ench.getName(Math.max(1, level))), false);

                int nextThreshold = com.jamsackman.researchtable.state.ResearchPersistentState
                        .pointsForLevel(Math.min(afterUsable + 1, ench.getMaxLevel()));
                int capThreshold  = com.jamsackman.researchtable.state.ResearchPersistentState
                        .pointsForLevel(ench.getMaxLevel());
                nextThreshold = Math.min(nextThreshold, capThreshold);

                player.sendMessage(
                        Text.literal(
                                "Total: " + afterTotal + " / " + nextThreshold +
                                        " to unlock Level " + toRoman(Math.min(afterUsable + 1, ench.getMaxLevel()))
                        ),
                        false
                );

                var world = player.getWorld();
                if (afterUsable > beforeUsable) {
                    Text baseName = Text.translatable(ench.getTranslationKey());
                    for (int lvl = beforeUsable + 1; lvl <= afterUsable; lvl++) {
                        player.sendMessage(createUnlockedMessage(baseName), false);
                        world.playSound(
                                null, player.getBlockPos(),
                                net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP,
                                net.minecraft.sound.SoundCategory.PLAYERS,
                                0.9f, 1.0f + (lvl * 0.03f)
                        );
                    }
                    if (afterUsable >= ench.getMaxLevel()) {
                        world.playSound(
                                null, player.getBlockPos(),
                                net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                                net.minecraft.sound.SoundCategory.PLAYERS,
                                1.0f, 1.0f
                        );
                        if (world instanceof ServerWorld sw2) {
                            sw2.spawnParticles(net.minecraft.particle.ParticleTypes.ENCHANT,
                                    player.getX(), player.getY() + 1.2, player.getZ(),
                                    40, 0.6, 0.6, 0.6, 0.0
                            );
                        }
                    }
                }
            });

            state.markDirty();
            input.setStack(INPUT_SLOT, ItemStack.EMPTY);
            this.sendContentUpdates();
            return;
        }

        // ----- Case B: research materials (only if already discovered) -----
        var itemId = Registries.ITEM.getId(stack.getItem());
        if (itemId == null) return;

        // entries(): Map<itemId, Map<enchantId, points>>
        var enchMap = ResearchItems.entries().get(itemId.toString());
        if (enchMap == null || enchMap.isEmpty()) return;

        // Only feed enchants the player has already discovered
        var discovered = enchMap.entrySet().stream()
                .filter(e -> state.getProgress(player.getUuid(), e.getKey()) > 0)
                .toList();

        if (discovered.isEmpty()) {
            player.sendMessage(Text.translatable("screen.researchtable.not_discovered"), true);
            return;
        }

        cue.run();

        int totalGained = 0;

        for (var e : discovered) {
            String targetEnchId = e.getKey();      // e.g. "minecraft:fire_aspect"
            int basePoints      = Math.max(1, e.getValue()) * stack.getCount();
            int gained          = Math.max(1, Math.round(basePoints * shelfMult)); // apply bookshelf bonus

            int beforeTotal  = state.getProgress(player.getUuid(), targetEnchId);
            int beforeUsable = com.jamsackman.researchtable.state.ResearchPersistentState.usableLevelFor(beforeTotal);

            state.addProgress(player.getUuid(), targetEnchId, gained);
            totalGained += gained;

            // feedback & unlock toasts per-enchant
            int afterTotal  = state.getProgress(player.getUuid(), targetEnchId);
            int afterUsable = com.jamsackman.researchtable.state.ResearchPersistentState.usableLevelFor(afterTotal);

            Enchantment target = null;
            Identifier tid = Identifier.tryParse(targetEnchId);
            if (tid != null) target = Registries.ENCHANTMENT.get(tid);
            int maxLevel = (target != null) ? target.getMaxLevel() : Integer.MAX_VALUE;
            afterUsable = Math.min(afterUsable, maxLevel);

            String niceName = (target != null) ? target.getName(1).getString() : targetEnchId;
            player.sendMessage(Text.literal("Researched " + niceName + " +" + gained), false);

            int nextThreshold = com.jamsackman.researchtable.state.ResearchPersistentState
                    .pointsForLevel(Math.min(afterUsable + 1, (target != null) ? target.getMaxLevel() : afterUsable + 1));
            if (target != null) {
                int cap = com.jamsackman.researchtable.state.ResearchPersistentState.pointsForLevel(target.getMaxLevel());
                nextThreshold = Math.min(nextThreshold, cap);
            }

            player.sendMessage(
                    Text.literal("Total: " + afterTotal + " / " + nextThreshold +
                            " to unlock Level " + toRoman(Math.min(afterUsable + 1, (target != null) ? target.getMaxLevel() : afterUsable + 1)) ),
                    false
            );

            var world = player.getWorld();
            if (afterUsable > beforeUsable) {
                Text baseName = (target != null)
                        ? Text.translatable(target.getTranslationKey())
                        : Text.literal(niceName);
                for (int lvl = beforeUsable + 1; lvl <= afterUsable; lvl++) {
                    player.sendMessage(createUnlockedMessage(baseName), false);
                    world.playSound(
                            null, player.getBlockPos(),
                            net.minecraft.sound.SoundEvents.ENTITY_PLAYER_LEVELUP,
                            net.minecraft.sound.SoundCategory.PLAYERS,
                            0.9f, 1.0f + (lvl * 0.03f)
                    );
                }
                if (target != null && afterUsable >= target.getMaxLevel()) {
                    world.playSound(
                            null, player.getBlockPos(),
                            net.minecraft.sound.SoundEvents.UI_TOAST_CHALLENGE_COMPLETE,
                            net.minecraft.sound.SoundCategory.PLAYERS,
                            1.0f, 1.0f
                    );
                    if (world instanceof ServerWorld sw2) {
                        sw2.spawnParticles(net.minecraft.particle.ParticleTypes.ENCHANT,
                                player.getX(), player.getY() + 1.2, player.getZ(),
                                40, 0.6, 0.6, 0.6, 0.0
                        );
                    }
                }
            }
        }

        // Update global totals & advancements once
        if (totalGained > 0) {
            int before = state.getTotalPoints(player.getUuid());
            state.addTotalPoints(player.getUuid(), totalGained);
            if (before == 0) ResearchTableMod.grant(player, ResearchTableMod.ADV_FIRST_POINTS);

            int total = state.getTotalPoints(player.getUuid());
            if (total >= 5_000)   ResearchTableMod.grant(player, ResearchTableMod.ADV_5K_POINTS);
            if (total >= 20_000)  ResearchTableMod.grant(player, ResearchTableMod.ADV_20K_POINTS);
            if (total >= 100_000) ResearchTableMod.grant(player, ResearchTableMod.ADV_100K_POINTS);
        }

        state.markDirty();
        input.setStack(INPUT_SLOT, ItemStack.EMPTY);
        this.sendContentUpdates();
    }

    // -------------------- Bookshelf scan (table position) --------------------

    /** Scan bookshelves near the given pos. +1% each, cap +25%. */
    private static float bookshelfMultiplier(net.minecraft.world.World world, BlockPos origin) {
        int count = 0;
        BlockPos.Mutable m = new BlockPos.Mutable();

        for (int dy = -5; dy <= 1; dy++) {
            for (int dz = -3; dz <= 3; dz++) {
                for (int dx = -3; dx <= 3; dx++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (world.getBlockState(m).isOf(net.minecraft.block.Blocks.BOOKSHELF)) {
                        count++;
                        if (count >= 25) return 1.25f; // early cap
                    }
                }
            }
        }
        return 1.0f + Math.min(25f, count) / 100f;
    }
}