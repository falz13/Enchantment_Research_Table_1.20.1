package com.jamsackman.researchtable.blockentity;

import com.jamsackman.researchtable.block.ModBlockEntities;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.util.math.BlockPos;

public class ResearchTableBlockEntity extends BlockEntity {
    public ResearchTableBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.RESEARCH_TABLE_BE, pos, state);
    }
}