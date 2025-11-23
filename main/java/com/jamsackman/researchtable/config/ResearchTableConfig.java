package com.jamsackman.researchtable.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Simple JSON-backed config for the Research Table mod.
 */
public class ResearchTableConfig {

    public enum LossSetting {
        NONE(0),
        LOSS_10(10),
        LOSS_20(20),
        LOSS_30(30),
        LOSS_40(40),
        LOSS_50(50),
        LOSS_60(60),
        LOSS_70(70),
        LOSS_80(80),
        LOSS_90(90),
        LOSS_100(100);

        private final int percent;

        LossSetting(int percent) {
            this.percent = percent;
        }

        public int getPercent() {
            return percent;
        }

        public float fraction() {
            return percent / 100f;
        }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Loss applied to research progress on death. Defaults to NONE. */
    public LossSetting researchLossOnDeath = LossSetting.NONE;

    public static Path getConfigPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("researchtable.json");
    }

    public static ResearchTableConfig load() {
        Path path = getConfigPath();
        if (Files.exists(path)) {
            try (Reader reader = Files.newBufferedReader(path)) {
                ResearchTableConfig cfg = GSON.fromJson(reader, ResearchTableConfig.class);
                if (cfg != null && cfg.researchLossOnDeath != null) {
                    return cfg;
                }
            } catch (IOException ignored) {
            }
        }

        ResearchTableConfig defaults = new ResearchTableConfig();
        defaults.save();
        return defaults;
    }

    public void save() {
        Path path = getConfigPath();
        try {
            Files.createDirectories(path.getParent());
            try (Writer writer = Files.newBufferedWriter(path)) {
                GSON.toJson(this, writer);
            }
        } catch (IOException e) {
            // log at call site if needed
        }
    }
}

