package com.jamsackman.researchtable;

import com.jamsackman.researchtable.block.ModBlockEntities;
import com.jamsackman.researchtable.client.ResearchClientState;
import com.jamsackman.researchtable.data.ResearchItems;
import com.jamsackman.researchtable.client.render.ResearchTableBER;
import com.jamsackman.researchtable.data.ResearchItems;
import com.jamsackman.researchtable.screen.ModScreens;
import com.jamsackman.researchtable.screen.ResearchTableScreen;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.blockrenderlayer.v1.BlockRenderLayerMap;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.screen.ingame.HandledScreens;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactories;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.Registries;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Formatting;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class ResearchTableClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("ResearchTableClient");

    @Override
    public void onInitializeClient() {
        LOGGER.info("[ResearchTable] Client initialized");
        HandledScreens.register(com.jamsackman.researchtable.screen.ModScreens.RESEARCH_TABLE,
                com.jamsackman.researchtable.screen.ResearchTableScreen::new);

        BlockEntityRendererFactories.register(
                ModBlockEntities.RESEARCH_TABLE_BE,    // <-- your BE type
                ResearchTableBER::new                  // <-- your renderer
        );

        BlockRenderLayerMap.INSTANCE.putBlock(
                com.jamsackman.researchtable.block.ModBlocks.RESEARCH_TABLE,
                RenderLayer.getCutout()
        );

        ItemTooltipCallback.EVENT.register((stack, context, lines) -> {
            if (stack == null || stack.isEmpty()) return;

            Identifier itemId = Registries.ITEM.getId(stack.getItem());
            if (itemId == null) return;

            Map<String, Integer> mappings = ResearchItems.getAllForItem(itemId.toString());
            if (mappings.isEmpty()) return;

            Set<String> unlocked = ResearchClientState.unlocked();
            List<String> enchantIds = new ArrayList<>(mappings.keySet());
            enchantIds.sort((a, b) -> {
                Identifier ida = Identifier.tryParse(a);
                Identifier idb = Identifier.tryParse(b);
                Enchantment ea = ida != null ? Registries.ENCHANTMENT.get(ida) : null;
                Enchantment eb = idb != null ? Registries.ENCHANTMENT.get(idb) : null;
                if (ResearchTableMod.isHiddenId(ida)) ea = null;
                if (ResearchTableMod.isHiddenId(idb)) eb = null;
                String na = (ea != null) ? Text.translatable(ea.getTranslationKey()).getString() : a;
                String nb = (eb != null) ? Text.translatable(eb.getTranslationKey()).getString() : b;
                return na.compareToIgnoreCase(nb);
            });

            for (String enchId : enchantIds) {
                if (ResearchTableMod.isHiddenId(enchId)) continue;

                Identifier enchantmentId = Identifier.tryParse(enchId);
                Enchantment enchantment = enchantmentId != null ? Registries.ENCHANTMENT.get(enchantmentId) : null;

                boolean discovered = enchantment != null && unlocked.contains(enchId);
                MutableText line = Text.literal("Research: ").formatted(Formatting.GRAY);
                if (discovered) {
                    line = line.append(Text.translatable(enchantment.getTranslationKey()).formatted(Formatting.GRAY));
                } else {
                    line = line.append(Text.translatable("screen.researchtable.undiscovered_hint").formatted(Formatting.GRAY));
                }
                lines.add(line);
            }
        });

        // Receive server → client research sync
        ClientPlayNetworking.registerGlobalReceiver(ResearchTableMod.SYNC_RESEARCH_PACKET, (client, handler, buf, responseSender) -> {
            // --- Read progress map ---
            int progCount = buf.readVarInt();
            Map<String, Integer> progress = new LinkedHashMap<>(progCount);
            for (int i = 0; i < progCount; i++) {
                String id = buf.readString(512);
                int total = buf.readVarInt();
                progress.put(id, total);
            }

            // --- Read unlocked set ---
            int unlockedCount = buf.readVarInt();
            Set<String> unlocked = new HashSet<>(unlockedCount);
            for (int i = 0; i < unlockedCount; i++) {
                unlocked.add(buf.readString(512));
            }

            // --- Apply on main thread ---
            client.execute(() -> {
                ResearchClientState.clear();
                // If method ref upsets your IDE, use a loop instead
                progress.forEach((id, total) -> ResearchClientState.put(id, total));

                // If your ResearchClientState.setUnlocked signature differs,
                // these two lines will make it compile either way:
                //   - if it wants a Collection<String>: the Set is fine
                //   - if it wants a List<String>: wrap it
                ResearchClientState.setUnlocked(unlocked);
                // or: ResearchClientState.setUnlocked(new java.util.ArrayList<>(unlocked));

                ResearchTableMod.LOGGER.info("[ResearchTable] Synced research: {} entries, {} unlocked", progress.size(), unlocked.size());
            });
        });

        // Receive server → client enchant description sync
        ClientPlayNetworking.registerGlobalReceiver(ResearchTableMod.SYNC_DESCRIPTIONS, (client, handler, buf, responseSender) -> {
            int size = buf.readVarInt();
            java.util.Map<String, String> map = new java.util.HashMap<>();
            for (int i = 0; i < size; i++) {
                String id = buf.readString();
                String desc = buf.readString();
                map.put(id, desc);
            }

            client.execute(() -> {
                com.jamsackman.researchtable.client.ClientEnchantDescriptions.clear();
                map.forEach(com.jamsackman.researchtable.client.ClientEnchantDescriptions::put);
                // optional debug:
                System.out.println("[ResearchTable] Received " + size + " descriptions from server");
            });
        });

        // Receive server → client research item sync (datapack-driven)
        ClientPlayNetworking.registerGlobalReceiver(ResearchTableMod.SYNC_RESEARCH_ITEMS_PACKET, (client, handler, buf, responseSender) -> {
            int outer = buf.readVarInt();
            Map<String, Map<String, Integer>> data = new HashMap<>(outer);
            for (int i = 0; i < outer; i++) {
                String itemId = buf.readString(512);
                int innerSize = buf.readVarInt();
                Map<String, Integer> inner = new HashMap<>(innerSize);
                for (int j = 0; j < innerSize; j++) {
                    String enchId = buf.readString(512);
                    int points = buf.readVarInt();
                    inner.put(enchId, points);
                }
                data.put(itemId, inner);
            }

            client.execute(() -> {
                ResearchItems.applySync(data);
                ResearchTableMod.LOGGER.info("[ResearchTable] Synced {} research item entries", data.size());
            });
        });
    }
}
