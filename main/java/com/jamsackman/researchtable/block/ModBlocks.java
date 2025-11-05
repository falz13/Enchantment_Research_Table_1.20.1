package com.jamsackman.researchtable.block;

import com.jamsackman.researchtable.ResearchTableMod;
import net.fabricmc.fabric.api.object.builder.v1.block.FabricBlockSettings;
import net.minecraft.block.Block;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public final class ModBlocks {
    public static final Identifier RESEARCH_TABLE_ID =
            new Identifier(ResearchTableMod.MODID, "research_table");

    public static final Block RESEARCH_TABLE = Registry.register(
            Registries.BLOCK,
            RESEARCH_TABLE_ID,
            new ResearchTableBlock(
                    FabricBlockSettings.create()
                            .strength(2.5f)
                            .nonOpaque()            // <-- important
                            .requiresTool()
            )
    );


    public static void registerAll() {} // force class init
}