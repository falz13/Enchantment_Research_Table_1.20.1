package com.jamsackman.researchtable.state;

import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.world.PersistentState;

import java.util.*;

/** Stores per-player research: unlocked enchants + cumulative progress points. */
public class ResearchPersistentState extends PersistentState {

    public static class PlayerData {
        public final Set<String> unlocked = new HashSet<>();
        public final Map<String, Integer> progress = new HashMap<>();
    }

    private final Map<UUID, PlayerData> players = new HashMap<>();

    // Rolling total of research points earned (per player). We only add positives.
    private final Map<UUID, Integer> totalPoints = new HashMap<>();

    // Count of items imbued (per player).
    private final Map<UUID, Integer> imbuedCount = new HashMap<>();

    // Returns a copy of all progress for this player (enchantmentId -> total points)
    public java.util.Map<String, Integer> getAllProgressFor(java.util.UUID uuid) {
        PlayerData pd = getOrCreate(uuid);
        return new java.util.HashMap<>(pd.progress); // defensive copy
    }

    // Returns a copy of all unlocked enchantment ids for this player
    public java.util.Set<String> getAllUnlockedFor(java.util.UUID uuid) {
        PlayerData pd = getOrCreate(uuid);
        return new java.util.HashSet<>(pd.unlocked); // defensive copy
    }

    // Helper
    /** Ensure a specific enchantment’s progress is at least `points`. */
    public void setProgressToAtLeast(UUID uuid, String enchantmentId, int points) {
        PlayerData pd = getOrCreate(uuid);
        int cur = pd.progress.getOrDefault(enchantmentId, 0);
        if (points > cur) {
            pd.unlocked.add(enchantmentId);
            pd.progress.put(enchantmentId, points);
            markDirty();
        }
    }

    /** Reset all research data for a player (progress, unlocked, totals, imbue count). */
    public void resetAll(UUID uuid) {
        PlayerData pd = players.get(uuid);
        if (pd != null) {
            pd.unlocked.clear();
            pd.progress.clear();
        }
        totalPoints.remove(uuid);
        imbuedCount.remove(uuid);
        markDirty();
    }

    public ResearchPersistentState() {}

    public int getTotalPoints(UUID id) {
        return totalPoints.getOrDefault(id, 0);
    }
    public void addTotalPoints(UUID id, int delta) {
        if (delta > 0) { // keep totalPoints non-negative, only track earned
            totalPoints.put(id, getTotalPoints(id) + delta);
            markDirty();
        }
    }

    public int getImbuedCount(UUID id) {
        return imbuedCount.getOrDefault(id, 0);
    }
    public void incImbued(UUID id) {
        imbuedCount.put(id, getImbuedCount(id) + 1);
        markDirty();
    }

    /** Factory: read from NBT (used by getOrCreate). */
    public static ResearchPersistentState createFromNbt(NbtCompound nbt) {
        ResearchPersistentState s = new ResearchPersistentState();

        NbtList list = nbt.getList("players", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound p = list.getCompound(i);
            UUID uuid = p.getUuid("uuid");
            PlayerData pd = new PlayerData();

            NbtList unlockedList = p.getList("unlocked", NbtElement.STRING_TYPE);
            for (int j = 0; j < unlockedList.size(); j++) {
                pd.unlocked.add(unlockedList.getString(j));
            }

            NbtCompound prog = p.getCompound("progress");
            for (String key : prog.getKeys()) {
                pd.progress.put(key, prog.getInt(key));
            }

            s.players.put(uuid, pd);
        }

        // Load rolling totals
        NbtCompound tp = nbt.getCompound("rt_total_points");
        for (String k : tp.getKeys()) {
            s.totalPoints.put(UUID.fromString(k), tp.getInt(k));
        }

        // Load imbue counts
        NbtCompound ic = nbt.getCompound("rt_imbued_count");
        for (String k : ic.getKeys()) {
            s.imbuedCount.put(UUID.fromString(k), ic.getInt(k));
        }

        return s;
    }


    @Override
    public NbtCompound writeNbt(NbtCompound nbt) {
        NbtList list = new NbtList();
        for (Map.Entry<UUID, PlayerData> e : players.entrySet()) {
            NbtCompound p = new NbtCompound();
            p.putUuid("uuid", e.getKey());

            NbtList unlockedList = new NbtList();
            for (String id : e.getValue().unlocked) {
                unlockedList.add(NbtString.of(id));
            }
            p.put("unlocked", unlockedList);

            NbtCompound prog = new NbtCompound();
            e.getValue().progress.forEach(prog::putInt);
            p.put("progress", prog);

            list.add(p);
        }
        nbt.put("players", list);
        // Save rolling totals
        NbtCompound tp = new NbtCompound();
        for (Map.Entry<UUID, Integer> e : totalPoints.entrySet()) {
            tp.putInt(e.getKey().toString(), e.getValue());
        }
        nbt.put("rt_total_points", tp);

        // Save imbue counts
        NbtCompound ic = new NbtCompound();
        for (Map.Entry<UUID, Integer> e : imbuedCount.entrySet()) {
            ic.putInt(e.getKey().toString(), e.getValue());
        }
        nbt.put("rt_imbued_count", ic);
        return nbt;
    }

    private PlayerData getOrCreate(UUID uuid) {
        return players.computeIfAbsent(uuid, u -> new PlayerData());
    }

    /** Adds progress and unlocks on first sight. Caller should call markDirty(). */
    public void addProgress(UUID uuid, String enchantmentId, int add) {
        PlayerData pd = getOrCreate(uuid);
        pd.unlocked.add(enchantmentId);
        pd.progress.put(enchantmentId, pd.progress.getOrDefault(enchantmentId, 0) + add);
    }

    /**
     * Converts cumulative research points into a usable enchantment level.
     * The scale is exponential, so higher levels require increasingly more points.
     *
     * Examples:
     *  - 0–19 pts → 0 (locked)
     *  - 20  pts → Level 1
     *  - 50  pts → Level 2
     *  - 150 pts → Level 3
     *  - 500 pts → Level 4
     *  - 2000 pts → Level 5
     *  - 8000 pts → Level 6
     *  - 32000 pts → Level 7
     *  - 128000 pts → Level 8
     *  - etc.
     */
    // Base cumulative thresholds for Levels I–V (×10 of the original 20/50/150/500/2000)
    private static final int[] BASE_THRESHOLDS = new int[] {
            500,    // I
            1500,    // II
            5000,   // III
            10000,   // IV
            20000   // V
    };

    /** Minimum points to unlock level I for single-level enchantments (e.g. Mending). */
    public static final int SINGLE_LEVEL_UNLOCK_THRESHOLD = 1500;

    // Optional: beyond max of BASE_THRESHOLDS, continue slightly exponential.
// This matches your earlier curve, just scaled up.
    private static int tailThresholdForLevel(int levelIndex) {
        // levelIndex: 5 => threshold just above Level V, and so on
        // Start from last base and grow ~x2.2 per level (tweak factor if you like).
        double growth = 2.2;
        double t = BASE_THRESHOLDS[BASE_THRESHOLDS.length - 1];
        int extra = levelIndex - (BASE_THRESHOLDS.length - 1);
        for (int i = 0; i < extra; i++) t *= growth;
        return (int) Math.ceil(t);
    }

    /** Return the cumulative points needed to unlock the given 1-based level (I=1). */
    public static int pointsForLevel(int level) {
        if (level <= 0) return Integer.MAX_VALUE;
        if (level <= BASE_THRESHOLDS.length) return BASE_THRESHOLDS[level - 1];
        // beyond the base list, use the tail growth
        return tailThresholdForLevel(level - 1);
    }

    /** Variant that accounts for single-level enchantments requiring more points. */
    public static int pointsForLevel(int level, int maxLevel) {
        if (maxLevel == 1 && level >= 1) {
            return SINGLE_LEVEL_UNLOCK_THRESHOLD;
        }
        return pointsForLevel(level);
    }

    /** Given total points, return usable level (capped by the enchant's max outside). */
    public static int usableLevelFor(int total) {
        if (total < BASE_THRESHOLDS[0]) return 0;
        int level = 0;
        while (true) {
            int nextLevel = level + 1;
            int need = pointsForLevel(nextLevel);
            if (total >= need) level = nextLevel;
            else break;
            // safety: stop somewhere reasonable
            if (nextLevel > 50) break;
        }
        return level;
    }

    /** Variant that applies the single-level threshold and caps to the provided max. */
    public static int usableLevelFor(int total, int maxLevel) {
        int usable = usableLevelFor(total);
        if (maxLevel == 1 && total < SINGLE_LEVEL_UNLOCK_THRESHOLD) {
            return 0;
        }
        return Math.min(usable, maxLevel);
    }

    /** Given current total, return the next threshold strictly above it (or the same if already above cap). */
    public static int nextThresholdAbove(int total) {
        int lvl = usableLevelFor(total);
        int next = pointsForLevel(lvl + 1);
        return next;
    }

    public Set<String> getUnlocked(UUID uuid) {
        return Collections.unmodifiableSet(getOrCreate(uuid).unlocked);
    }

    public int getProgress(UUID uuid, String enchantmentId) {
        return getOrCreate(uuid).progress.getOrDefault(enchantmentId, 0);
    }
}