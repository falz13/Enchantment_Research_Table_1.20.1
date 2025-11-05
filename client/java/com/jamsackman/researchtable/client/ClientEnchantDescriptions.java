package com.jamsackman.researchtable.client;

import java.util.HashMap;
import java.util.Map;

public class ClientEnchantDescriptions {
    private static final Map<String, String> MAP = new HashMap<>();

    public static void clear() { MAP.clear(); }
    public static void put(String id, String text) { MAP.put(id, text); }
    public static String get(String id) { return MAP.getOrDefault(id, "???"); }
}