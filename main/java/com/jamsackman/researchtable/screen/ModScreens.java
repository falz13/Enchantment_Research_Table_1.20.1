package com.jamsackman.researchtable.screen;

import com.jamsackman.researchtable.ResearchTableMod;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.resource.featuretoggle.FeatureFlags;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.util.Identifier;

public final class ModScreens {
    public static ScreenHandlerType<ResearchTableScreenHandler> RESEARCH_TABLE;

    public static void registerAll() {
        RESEARCH_TABLE = Registry.register(
                Registries.SCREEN_HANDLER,
                new Identifier(ResearchTableMod.MODID, "research_table"),
                new ScreenHandlerType<>(ResearchTableScreenHandler::new, FeatureFlags.VANILLA_FEATURES)
        );
    }

    private ModScreens() {}
}