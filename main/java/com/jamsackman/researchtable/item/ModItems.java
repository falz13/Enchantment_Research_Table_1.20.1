package com.jamsackman.researchtable.item;

import com.jamsackman.researchtable.block.ModBlocks;
import net.fabricmc.fabric.api.item.v1.FabricItemSettings;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;

public final class ModItems {
    public static final Item RESEARCH_TABLE_ITEM = Registry.register(
            Registries.ITEM,
            ModBlocks.RESEARCH_TABLE_ID,                 // <-- EXACT SAME ID
            new BlockItem(ModBlocks.RESEARCH_TABLE, new FabricItemSettings())
    );

    public static void registerAll() {} // force class init
}