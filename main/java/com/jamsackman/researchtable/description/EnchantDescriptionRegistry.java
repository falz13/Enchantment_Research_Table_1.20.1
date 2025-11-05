package com.jamsackman.researchtable.description;

import net.fabricmc.fabric.api.resource.IdentifiableResourceReloadListener;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.SinglePreparationResourceReloader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Identifier;
import net.minecraft.util.profiler.Profiler;

import java.lang.reflect.Type;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Loads enchantment descriptions from:
 * data/*researchtable/enchant_descriptions/*.json
 */
public class EnchantDescriptionRegistry
        extends SinglePreparationResourceReloader<Map<String, String>>
        implements IdentifiableResourceReloadListener {

    private static final Gson GSON = new Gson();
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() {}.getType();
    private static final String DIRECTORY = "researchtable/enchant_descriptions";

    private static Map<String, String> DESCRIPTIONS = new HashMap<>();

    @Override
    public Identifier getFabricId() {
        return new Identifier("researchtable", "enchant_descriptions_loader");
    }

    public static String get(String enchantId) {
        return DESCRIPTIONS.getOrDefault(enchantId, "No description available.");
    }

    public static Map<String, String> getAll() {
        return Collections.unmodifiableMap(DESCRIPTIONS);
    }

    @Override
    protected Map<String, String> prepare(ResourceManager manager, Profiler profiler) {
        System.out.println("[ResearchTable] Scanning datapacks for enchant descriptions...");

        Map<String, String> result = new HashMap<>();

        // 1) Correct root for SERVER_DATA: path relative to namespace root
        var foundA = manager.findResources("enchant_descriptions", id -> id.getPath().endsWith(".json"));

        // 2) Extra-safe fallback (some setups mistakenly include namespace in path)
        var foundB = manager.findResources("researchtable/enchant_descriptions", id -> id.getPath().endsWith(".json"));

        // merge keys (A wins if duplicated)
        Map<Identifier, net.minecraft.resource.Resource> merged = new HashMap<>(foundA);
        foundB.forEach(merged::putIfAbsent);

        System.out.println("[ResearchTable] findResources matched " + merged.size() + " file(s):");
        for (var e : merged.entrySet()) {
            System.out.println(" - " + e.getKey()); // e.g. researchtable:enchant_descriptions/vanilla.json
        }

        for (var entry : merged.entrySet()) {
            Identifier id = entry.getKey();

            // Only load our namespace to avoid other mods’ files by accident
            if (!"researchtable".equals(id.getNamespace())) continue;

            var res = entry.getValue();
            try (var in = res.getInputStream();
                 var reader = new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8)) {
                Map<String, String> fileData = GSON.fromJson(reader, MAP_TYPE);
                int count = (fileData == null) ? 0 : fileData.size();
                System.out.println("[ResearchTable] Loaded " + count + " entries from " + id);
                if (fileData != null) result.putAll(fileData);
            } catch (Exception e) {
                System.err.println("[ResearchTable] Failed loading file: " + id);
                e.printStackTrace();
            }
        }

        return result;
    }

    @Override
    protected void apply(Map<String, String> prepared, ResourceManager manager, Profiler profiler) {
        DESCRIPTIONS = prepared;
        System.out.println("[ResearchTable] Loaded " + DESCRIPTIONS.size() + " enchantment descriptions.");
    }
}