package com.jamsackman.researchtable.client;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Client-side cache of the player's research (synced from server). */
public final class ResearchClientState {
    private static final Map<String, Integer> PROGRESS = new HashMap<>();
    private static final Set<String> UNLOCKED = new HashSet<>();

    private ResearchClientState() {}

    public static void clear() {
        PROGRESS.clear();
        UNLOCKED.clear();
    }

    public static void put(String id, int total) {
        PROGRESS.put(id, total);
    }

    public static void setUnlocked(Set<String> ids) {
        UNLOCKED.clear();
        UNLOCKED.addAll(ids);
    }

    public static Map<String, Integer> progress() {
        return Collections.unmodifiableMap(PROGRESS);
    }

    public static Set<String> unlocked() {
        return Collections.unmodifiableSet(UNLOCKED);
    }
}