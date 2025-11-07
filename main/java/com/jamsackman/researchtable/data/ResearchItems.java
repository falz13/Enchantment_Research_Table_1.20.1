package com.jamsackman.researchtable.data;

import com.google.gson.*;
import com.jamsackman.researchtable.ResearchTableMod;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.resource.Resource;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Datapack-driven loader for research items.
 * Reads every JSON in data ... research_items...*.json
 *
 * JSON can be:
 *   { "research_items": [ { "item": "...", "enchantment": "...", "points": N }, ... ] }
 * or just:
 *   [ { "item": "...", "enchantment": "...", "points": N }, ... ]
 *
 * Internally stores: itemId -> (enchantmentId -> points)
 */
public final class ResearchItems implements SimpleSynchronousResourceReloadListener {
    public static final ResearchItems INSTANCE = new ResearchItems();

    // itemId -> (enchantmentId -> points)
    private static final Map<String, Map<String, Integer>> MAP = new HashMap<>();

    private static final Gson GSON = new GsonBuilder().setLenient().create();
    private static final String FOLDER = "research_items";
    private static final Identifier BOOK_ID = new Identifier("minecraft", "book");

    private ResearchItems() {}

    /** Call once from your mod init. */
    public static void init() {
        ResourceManagerHelper.get(ResourceType.SERVER_DATA).registerReloadListener(INSTANCE);
    }

    @Override
    public Identifier getFabricId() {
        return new Identifier(ResearchTableMod.MODID, "research_items_loader");
    }

    @Override
    public void reload(ResourceManager manager) {
        MAP.clear();

        int files = 0;
        int added = 0;
        int fallbackAdded = 0;
        int skipped = 0;

        // Find every *.json under any namespace's research_items folder
        Map<Identifier, Resource> found = manager.findResources(FOLDER, id -> id.getPath().endsWith(".json"));

        if (found.isEmpty()) {
            ResearchTableMod.LOGGER.info("[ResearchTable] No {}/*.json files found in data packs.", FOLDER);
        }

        for (Map.Entry<Identifier, Resource> e : found.entrySet()) {
            files++;
            try (var in = e.getValue().getInputStream();
                 var reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {

                JsonElement root = JsonParser.parseReader(reader);
                if (root == null) continue;

                JsonArray arr;
                if (root.isJsonObject() && root.getAsJsonObject().has("research_items")) {
                    arr = root.getAsJsonObject().getAsJsonArray("research_items");
                } else if (root.isJsonArray()) {
                    arr = root.getAsJsonArray();
                } else {
                    ResearchTableMod.LOGGER.warn("[ResearchTable] {} missing 'research_items' array.", e.getKey());
                    continue;
                }

                for (JsonElement el : arr) {
                    if (!el.isJsonObject()) { skipped++; continue; }
                    JsonObject obj = el.getAsJsonObject();

                    String itemId = getString(obj, "item");
                    String enchId = getString(obj, "enchantment");
                    Integer points = getInt(obj, "points");

                    if (itemId == null || enchId == null || points == null) {
                        skipped++;
                        continue;
                    }

                    // Merge: allow multiple enchantments per item
                    MAP.computeIfAbsent(itemId, k -> new HashMap<>()).put(enchId, points);
                    added++;
                }
            } catch (Exception ex) {
                ResearchTableMod.LOGGER.error("[ResearchTable] Failed parsing {}: {}", e.getKey(), ex.toString());
            }
        }

        // Track which enchantments have at least one recognised item
        Set<String> enchantmentsWithRecognisedItems = new HashSet<>();
        Set<String> enchantmentsEncountered = new HashSet<>();

        MAP.forEach((itemId, enchMap) -> {
            if (enchMap == null || enchMap.isEmpty()) return;
            enchantmentsEncountered.addAll(enchMap.keySet());

            Identifier parsed = Identifier.tryParse(itemId);
            if (parsed != null && Registries.ITEM.containsId(parsed)) {
                enchantmentsWithRecognisedItems.addAll(enchMap.keySet());
            }
        });

        // Also consider every enchantment available in this version
        for (Identifier enchId : Registries.ENCHANTMENT.getIds()) {
            enchantmentsEncountered.add(enchId.toString());
        }

        // Provide a fallback research material (books) for enchantments without recognised items
        if (!enchantmentsEncountered.isEmpty() && Registries.ITEM.containsId(BOOK_ID)) {
            String bookKey = BOOK_ID.toString();
            Map<String, Integer> bookMap = MAP.computeIfAbsent(bookKey, k -> new HashMap<>());

            for (String enchId : enchantmentsEncountered) {
                if (enchantmentsWithRecognisedItems.contains(enchId)) continue;
                if (bookMap.putIfAbsent(enchId, 1) == null) {
                    fallbackAdded++;
                }
            }
        }

        ResearchTableMod.LOGGER.info(
                "[ResearchTable] Loaded {} mappings from {} file(s), skipped {} entries, added {} fallback(s).",
                added, files, skipped, fallbackAdded);
    }

    private static String getString(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        return (el != null && el.isJsonPrimitive()) ? el.getAsString() : null;
    }

    private static Integer getInt(JsonObject obj, String key) {
        JsonElement el = obj.get(key);
        try { return (el != null) ? el.getAsInt() : null; }
        catch (Exception ignored) { return null; }
    }

    // ---- Public query API ----

    /** Points this item contributes to this specific enchantment (0 if none). */
    public static int getPoints(String itemId, String enchantmentId) {
        var m = MAP.get(itemId);
        return (m == null) ? 0 : m.getOrDefault(enchantmentId, 0);
    }

    /** All enchantments (id -> points) this item can feed. */
    public static Map<String, Integer> getAllForItem(String itemId) {
        var m = MAP.get(itemId);
        return (m == null) ? Map.of() : Collections.unmodifiableMap(m);
    }

    /** Debug view: itemId -> (enchantmentId -> points) */
    public static Map<String, Map<String, Integer>> entries() {
        // Defensive copy not needed for read-only; wrap to prevent mutation.
        Map<String, Map<String, Integer>> outer = new HashMap<>();
        MAP.forEach((k, v) -> outer.put(k, Collections.unmodifiableMap(v)));
        return Collections.unmodifiableMap(outer);
    }

    /** Client-side: replace the current map with data received from the server. */
    public static void applySync(Map<String, Map<String, Integer>> data) {
        MAP.clear();
        data.forEach((itemId, enchMap) -> MAP.put(itemId, new HashMap<>(enchMap)));
    }
}