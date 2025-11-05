package com.jamsackman.researchtable.client.render;

import com.jamsackman.researchtable.blockentity.ResearchTableBlockEntity;
import net.minecraft.client.render.*;
import net.minecraft.client.render.block.entity.BlockEntityRenderer;
import net.minecraft.client.render.block.entity.BlockEntityRendererFactory;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
import com.mojang.blaze3d.systems.RenderSystem;

public class ResearchTableBER implements BlockEntityRenderer<ResearchTableBlockEntity> {
    // Put the PNG at assets/researchtable/textures/emissive/research_table_top_glow.png
    private static final Identifier GLOW_TEX =
            new Identifier("researchtable", "textures/emissive/research_table_top_glow.png");

    public ResearchTableBER(BlockEntityRendererFactory.Context ctx) {
        // Debug to prove construction happens
        System.out.println("[ResearchTable] ResearchTableBER constructed");
    }

    @Override
    public void render(ResearchTableBlockEntity be,
                       float tickDelta,
                       MatrixStack matrices,
                       VertexConsumerProvider vcp,
                       int packedLight,
                       int packedOverlay) {
        matrices.push();

        // Slightly above your model’s top; if top is y=0.75, go a bit higher to avoid z-fighting
        float y = 0.82f;

        // Take the center 10x10 area (from 3..13 on both X/Z in block space)
        float x0 = 3f / 16f, z0 = 3f / 16f;
        float x1 = 13f / 16f, z1 = 13f / 16f;

        // Use 'eyes' layer = fullbright (ignores lightmap and tint); no translucency needed
        VertexConsumer vc = vcp.getBuffer(RenderLayer.getEyes(GLOW_TEX));

        // We draw two faces (top and bottom) to be visible from above and below.
        // UV is full texture (0..1). Your PNG should have only the 10x10 center opaque.

        // Top face (y constant)
        vc.vertex(matrices.peek().getPositionMatrix(), x0, y, z0).color(255,255,255,255).texture(0f, 0f).overlay(packedOverlay).light(0xF000F0).normal(0,1,0).next();
        vc.vertex(matrices.peek().getPositionMatrix(), x1, y, z0).color(255,255,255,255).texture(1f, 0f).overlay(packedOverlay).light(0xF000F0).normal(0,1,0).next();
        vc.vertex(matrices.peek().getPositionMatrix(), x1, y, z1).color(255,255,255,255).texture(1f, 1f).overlay(packedOverlay).light(0xF000F0).normal(0,1,0).next();
        vc.vertex(matrices.peek().getPositionMatrix(), x0, y, z1).color(255,255,255,255).texture(0f, 1f).overlay(packedOverlay).light(0xF000F0).normal(0,1,0).next();

        // Bottom face (helps if camera clips through slightly)
        vc.vertex(matrices.peek().getPositionMatrix(), x0, y, z1).color(255,255,255,255).texture(0f, 1f).overlay(packedOverlay).light(0xF000F0).normal(0,-1,0).next();
        vc.vertex(matrices.peek().getPositionMatrix(), x1, y, z1).color(255,255,255,255).texture(1f, 1f).overlay(packedOverlay).light(0xF000F0).normal(0,-1,0).next();
        vc.vertex(matrices.peek().getPositionMatrix(), x1, y, z0).color(255,255,255,255).texture(1f, 0f).overlay(packedOverlay).light(0xF000F0).normal(0,-1,0).next();
        vc.vertex(matrices.peek().getPositionMatrix(), x0, y, z0).color(255,255,255,255).texture(0f, 0f).overlay(packedOverlay).light(0xF000F0).normal(0,-1,0).next();

        matrices.pop();
    }

    @Override
    public boolean rendersOutsideBoundingBox(ResearchTableBlockEntity be) {
        return false;
    }
}