package com.jamsackman.researchtable;

import com.jamsackman.researchtable.block.ModBlocks;
import com.jamsackman.researchtable.config.ResearchTableConfig;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleRegistry;
import net.fabricmc.fabric.api.gamerule.v1.GameRuleFactory;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.minecraft.world.GameRules;
import net.minecraft.loot.provider.number.ConstantLootNumberProvider;
import net.minecraft.loot.LootPool;
import net.minecraft.util.TypedActionResult;
import net.minecraft.item.BlockItem;
import net.minecraft.item.ItemStack;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.loot.v2.LootTableEvents;
import net.minecraft.block.Blocks;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import java.util.UUID;
import java.util.Map;
import java.util.Set;
import java.util.HashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.minecraft.server.network.ServerPlayerEntity;
import com.jamsackman.researchtable.description.EnchantDescriptionRegistry;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.minecraft.resource.ResourceType;
import com.jamsackman.researchtable.screen.ResearchTableScreenHandler;
import com.jamsackman.researchtable.state.ResearchPersistentState;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.PersistentStateManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.registry.Registry;
import net.minecraft.registry.Registries;
import com.jamsackman.researchtable.enchant.ImbuedEnchantment;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class ResearchTableMod implements ModInitializer {
    public static final String MODID = "researchtable";
    public static final Logger LOGGER = LoggerFactory.getLogger("ResearchTable");

    public static final int COLOR_UNLOCKED = 0xFFB6F2A2;

    public static final Identifier ADV_FIRST_POINTS = new Identifier(MODID, "progress/first_day_of_school");
    public static final Identifier ADV_5K_POINTS    = new Identifier(MODID, "progress/every_day_is_a_school_day");
    public static final Identifier ADV_20K_POINTS   = new Identifier(MODID, "progress/teachers_pet");
    public static final Identifier ADV_100K_POINTS  = new Identifier(MODID, "progress/with_honours");

    public static final Identifier ADV_IMBUE_1      = new Identifier(MODID, "imbue/first_day_on_the_job");
    public static final Identifier ADV_IMBUE_5      = new Identifier(MODID, "imbue/forging_on");
    public static final Identifier ADV_IMBUE_25     = new Identifier(MODID, "imbue/watch_out_bellows");
    public static final Identifier ADV_IMBUE_100    = new Identifier(MODID, "imbue/nailed_it");

    public static ResearchTableConfig CONFIG = ResearchTableConfig.load();

    // Packets
    public static final Identifier CONSUME_INPUT_PACKET = new Identifier(MODID, "consume_input");
    public static final Identifier SYNC_RESEARCH_PACKET  = new Identifier(MODID, "sync_research");
    public static final Identifier SYNC_RESEARCH_ITEMS_PACKET = new Identifier(MODID, "sync_research_items");
    public static final Identifier REQUEST_SYNC_PACKET = new Identifier(MODID, "request_sync");
    public static final Identifier SYNC_DESCRIPTIONS = new Identifier(MODID, "sync_descriptions");
    public static final Identifier SET_IMBUE_MODE = new Identifier("researchtable", "set_imbue_mode");
    public static final Identifier SET_IMBUE_SELECTIONS = new Identifier(MODID, "set_imbue_selections");
    public static final Identifier IMBUED_ID = new Identifier("researchtable", "imbued");
    public static Enchantment IMBUED;
    public static final Identifier SET_SELECTIONS = new Identifier(MODID, "set_selections");

    public static boolean isHiddenEnch(Enchantment e) {
        if (e == null) return false;
        Identifier id = Registries.ENCHANTMENT.getId(e);
        return IMBUED_ID.equals(id); // hide "researchtable:imbued"
    }
    public static boolean isHiddenId(Identifier id) {
        return IMBUED_ID.equals(id);
    }
    public static boolean isHiddenId(String idStr) {
        return IMBUED_ID.toString().equals(idStr);
    }

    public static void grant(ServerPlayerEntity player, Identifier advId) {
        var adv = player.server.getAdvancementLoader().get(advId);
        if (adv != null) {
            var tracker = player.getAdvancementTracker();
            if (!tracker.getProgress(adv).isDone()) {
                tracker.grantCriterion(adv, "earned");
            }
        }
    }

    // Register: default = true (block enchanted villager trades)
    public static final GameRules.Key<GameRules.BooleanRule> GR_DISABLE_VILLAGER_ENCHANTED_TRADES =
            GameRuleRegistry.register(
                    "rt_disableVillagerEnchantedTrades",
                    GameRules.Category.MOBS,
                    GameRuleFactory.createBooleanRule(true)
            );

    @Override
    public void onInitialize() {
        LOGGER.info("[ResearchTable] Mod initializing");
        CONFIG.save();
        ModBlocks.registerAll();
        com.jamsackman.researchtable.data.ResearchItems.init();
        com.jamsackman.researchtable.block.ModBlockEntities.registerAll();
        com.jamsackman.researchtable.screen.ModScreens.registerAll();
        com.jamsackman.researchtable.block.ModBlocks.registerAll();
        com.jamsackman.researchtable.item.ModItems.registerAll();

        // stop enchanting table use, drop etc
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.getBlockState(hitResult.getBlockPos()).isOf(Blocks.ENCHANTING_TABLE)) {
                return ActionResult.FAIL; // cancels opening
            }
            return ActionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            ItemStack stack = player.getStackInHand(hand);
            if (stack.getItem() instanceof BlockItem bi && bi.getBlock() == Blocks.ENCHANTING_TABLE) {
                return TypedActionResult.fail(stack); // can’t place
            }
            return TypedActionResult.pass(stack);
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, be) -> {
            if (state.isOf(Blocks.ENCHANTING_TABLE)) {
                if (!world.isClient) {
                    // remove without drops
                    world.setBlockState(pos, Blocks.AIR.getDefaultState(), 3);
                }
                return false; // cancel normal break handling (no drops/exp)
            }
            return true;
        });
        LootTableEvents.MODIFY.register((resourceManager, lootManager, id, tableBuilder, source) -> {
            if (!source.isBuiltin()) return;

            // Remove all loot from the enchanting table
            if (id.equals(new Identifier("minecraft", "blocks/enchanting_table"))) {
                // Replace all loot pools with an empty one
                tableBuilder.pool(LootPool.builder().rolls(ConstantLootNumberProvider.create(0)));
            }
        });

        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (world.isClient()) return ActionResult.PASS;
            var pos = hit.getBlockPos();
            var state = world.getBlockState(pos);
            if (state.isOf(Blocks.ENCHANTING_TABLE)) {
                world.setBlockState(pos, com.jamsackman.researchtable.block.ModBlocks.RESEARCH_TABLE.getDefaultState(), 3);
                // Optionally open your screen immediately here if desired
                return ActionResult.SUCCESS;
            }
            return ActionResult.PASS;
        });

        net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, env) -> com.jamsackman.researchtable.command.ResearchCommands.register(dispatcher)
        );

        // Datapack-driven enchant descriptions (server data)
        ResourceManagerHelper.get(ResourceType.SERVER_DATA)
                .registerReloadListener(new EnchantDescriptionRegistry());

        // Client -> Server: request a fresh research snapshot
        ServerPlayNetworking.registerGlobalReceiver(REQUEST_SYNC_PACKET, (server, player, handler, buf, responseSender) ->
                server.execute(() -> {
                    sendResearchSync(player);
                    sendResearchItemsSync(player);
                }));

        // Client -> Server: consume input
        ServerPlayNetworking.registerGlobalReceiver(CONSUME_INPUT_PACKET, (server, player, handler, buf, responseSender) ->
                server.execute(() -> onConsumeRequest(server, player)));

        ServerPlayNetworking.registerGlobalReceiver(SET_IMBUE_MODE, (server, player, handler, buf, responseSender) -> {
            boolean on = buf.readBoolean();
            server.execute(() -> {
                if (player.currentScreenHandler instanceof ResearchTableScreenHandler r) {
                    r.setImbueMode(on);
                    r.sendContentUpdates(); // push changes to client
                }
            });

        });

        ServerPlayNetworking.registerGlobalReceiver(SET_SELECTIONS, (server, player, handler, buf, response) -> {
            int n = buf.readVarInt();
            Map<Enchantment, Integer> map = new HashMap<>();
            for (int i = 0; i < n; i++) {
                Identifier id = Identifier.tryParse(buf.readString());
                int lvl = buf.readVarInt();
                Enchantment ench = (id != null) ? Registries.ENCHANTMENT.get(id) : null;
                if (ench != null) map.put(ench, lvl);
            }
            server.execute(() -> {
                if (player.currentScreenHandler instanceof ResearchTableScreenHandler rts) {
                    rts.setServerSelections(map);  // triggers updatePreviewItem()
                }
            });
        });

        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            server.execute(() -> {
                var players = server.getPlayerManager().getPlayerList();
                System.out.println("[ResearchTable] Resyncing enchant descriptions to " + players.size() + " player(s) after /reload");
                for (ServerPlayerEntity p : players) {
                    syncDescriptionsTo(p);
                    sendResearchItemsSync(p);
                }
            });
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            server.execute(() -> {
                sendResearchSync(handler.player);
                sendResearchItemsSync(handler.player);
            });
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // run next tick to avoid any race with initial data-pack load
            server.execute(() -> syncDescriptionsTo(handler.player));
        });

        IMBUED = Registry.register(Registries.ENCHANTMENT, IMBUED_ID, new ImbuedEnchantment());

        ServerPlayerEvents.AFTER_RESPAWN.register((oldPlayer, newPlayer, alive) -> {
            if (alive) return; // only apply on death respawn

            float loss = CONFIG.researchLossOnDeath.fraction();
            if (loss <= 0f) return;

            ResearchPersistentState state = getResearchState(newPlayer.server);
            int removed = state.applyDeathLoss(newPlayer.getUuid(), loss);
            if (removed > 0) {
                sendResearchSync(newPlayer);
                int percent = (int) (loss * 100);
                newPlayer.sendMessage(Text.literal("Research lost: " + removed + " (" + percent + "%)").formatted(Formatting.GRAY), false);
            }
        });

        ServerPlayNetworking.registerGlobalReceiver(SET_IMBUE_SELECTIONS, (server, player, handler, buf, rs) -> {
            // payload: varint N, then N * (String enchantId, varint level)
            int n = buf.readVarInt();
            java.util.Map<Identifier, Integer> sel = new java.util.HashMap<>();
            for (int i = 0; i < n; i++) {
                Identifier id = new Identifier(buf.readString());
                int lvl = buf.readVarInt();
                sel.put(id, lvl);
            }
            server.execute(() -> {
                if (player.currentScreenHandler instanceof com.jamsackman.researchtable.screen.ResearchTableScreenHandler r) {
                    r.serverSetImbueSelections(player, sel); // (we’ll add this)
                }
            });
        });
    }

    private static void syncDescriptionsTo(ServerPlayerEntity player) {
        var map = EnchantDescriptionRegistry.getAll();
        var buf = PacketByteBufs.create();
        buf.writeVarInt(map.size());
        map.forEach((id, desc) -> {
            buf.writeString(id);
            buf.writeString(desc);
        });
        ServerPlayNetworking.send(player, SYNC_DESCRIPTIONS, buf);
    }

    private void onConsumeRequest(MinecraftServer server, ServerPlayerEntity player) {
        if (player.currentScreenHandler instanceof ResearchTableScreenHandler rts) {
            rts.consumeOne(player);
            // After consuming, re-sync the updated research to this client
            sendResearchSync(player);
        }
    }

    /** Get or create the persistent research storage for this world. */
    public static ResearchPersistentState getResearchState(MinecraftServer server) {
        PersistentStateManager psm = server.getOverworld().getPersistentStateManager();
        // 1.20.1 order: fromNbt, createNew, name
        return psm.getOrCreate(ResearchPersistentState::createFromNbt, ResearchPersistentState::new, MODID + "_research");
    }

    /** Server → Client: Send this player's research map + unlocked set. */
    public static void sendResearchSync(ServerPlayerEntity player) {
        ResearchPersistentState state = getResearchState(player.server);
        UUID uuid = player.getUuid();

        Map<String, Integer> progress = state.getAllProgressFor(uuid);
        Set<String> unlocked = state.getAllUnlockedFor(uuid);

        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeVarInt(progress.size());
        progress.forEach((id, total) -> {
            buf.writeString(id);
            buf.writeVarInt(total);
        });

        buf.writeVarInt(unlocked.size());
        for (String id : unlocked) buf.writeString(id);

        ServerPlayNetworking.send(player, SYNC_RESEARCH_PACKET, buf);
    }

    /** Server → Client: Send the datapack-driven research item mappings. */
    public static void sendResearchItemsSync(ServerPlayerEntity player) {
        Map<String, Map<String, Integer>> entries = ResearchItems.entries();
        PacketByteBuf buf = PacketByteBufs.create();

        buf.writeVarInt(entries.size());
        entries.forEach((itemId, enchMap) -> {
            buf.writeString(itemId);
            buf.writeVarInt(enchMap.size());
            enchMap.forEach((enchId, points) -> {
                buf.writeString(enchId);
                buf.writeVarInt(points);
            });
        });

        ServerPlayNetworking.send(player, SYNC_RESEARCH_ITEMS_PACKET, buf);
    }
}