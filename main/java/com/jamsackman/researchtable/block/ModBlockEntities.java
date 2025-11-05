package com.jamsackman.researchtable.block;

import com.jamsackman.researchtable.ResearchTableMod;
import com.jamsackman.researchtable.blockentity.ResearchTableBlockEntity;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.util.Identifier;

public class ModBlockEntities {
    public static BlockEntityType<com.jamsackman.researchtable.blockentity.ResearchTableBlockEntity> RESEARCH_TABLE_BE;

    public static void registerAll() {
        RESEARCH_TABLE_BE = Registry.register(
                Registries.BLOCK_ENTITY_TYPE,
                new Identifier(ResearchTableMod.MODID, "research_table"),
                FabricBlockEntityTypeBuilder.create(
                        com.jamsackman.researchtable.blockentity.ResearchTableBlockEntity::new,
                        ModBlocks.RESEARCH_TABLE
                ).build()
        );
    }
}