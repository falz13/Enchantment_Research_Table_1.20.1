package com.jamsackman.researchtable.block;

import net.minecraft.block.*;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
import com.jamsackman.researchtable.screen.ResearchTableScreenHandler;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;
import net.minecraft.world.World;

import com.jamsackman.researchtable.blockentity.ResearchTableBlockEntity;

public class ResearchTableBlock extends BlockWithEntity {

    // Base: full 16x16, 12 pixels tall
    private static final VoxelShape BASE = Block.createCuboidShape(
            0, 0, 0,
            16, 12, 16
    );

    // Top inset: 14x14 centred, 2 pixels tall
    // (so 1-pixel margin around each side, from 1→15)
    private static final VoxelShape TOP = Block.createCuboidShape(
            1, 12, 1,
            15, 14, 15
    );

    // Combine both parts into one solid shape
    private static final VoxelShape SHAPE = VoxelShapes.union(BASE, TOP);

    @Override
    public VoxelShape getCollisionShape(BlockState state, BlockView world, BlockPos pos, ShapeContext ctx) {
        return SHAPE;
    }

    // This one is the key for neighbor face culling:
    @Override
    public VoxelShape getCullingShape(BlockState state, BlockView world, BlockPos pos) {
        return SHAPE;
    }

    // (optional but can help lighting look correct for non-full blocks)
    @Override
    public boolean hasSidedTransparency(BlockState state) {
        return true;
    }

    public ResearchTableBlock(AbstractBlock.Settings settings) {
        super(settings);
    }

    @Override
    public BlockEntity createBlockEntity(BlockPos pos, BlockState state) {
        return new ResearchTableBlockEntity(pos, state);
    }

    @Override
    public BlockRenderType getRenderType(BlockState state) {
        // Use the normal baked JSON model instead of expecting a BER
        return BlockRenderType.MODEL;
    }

    @Override
    public void randomDisplayTick(BlockState state, World world, BlockPos pos, Random random) {
        super.randomDisplayTick(state, world, pos, random);

        if (random.nextInt(9) != 0) return;
        for (int dx = -3; dx <= 3; dx++) {
            for (int dz = -3; dz <= 3; dz++) {
                BlockPos shelfPos = pos.add(dx, 0, dz);
                if (world.getBlockState(shelfPos).isOf(Blocks.BOOKSHELF) && random.nextInt(16) == 0) {
                    double px = pos.getX() + 0.5;
                    double py = pos.getY() + 2.0;
                    double pz = pos.getZ() + 0.5;
                    double vx = (shelfPos.getX() - pos.getX()) / 16.0;
                    double vy = (random.nextDouble() - 0.5) * 0.1;
                    double vz = (shelfPos.getZ() - pos.getZ()) / 16.0;
                    world.addParticle(ParticleTypes.ENCHANT, px, py, pz, vx, vy, vz);
                }
            }
        }
    }

    @Override
    public ActionResult onUse(BlockState state, World world, BlockPos pos,
                              PlayerEntity player, Hand hand, BlockHitResult hit) {
        if (!world.isClient) {
            player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inv, p) -> new ResearchTableScreenHandler(syncId, inv, ScreenHandlerContext.create(world, pos)),
                    Text.translatable("container.research_table")
            ));
        }
        return ActionResult.SUCCESS;
    }
}