package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.mineral.PowerMineral;
import com.miningdim.power.mineral.PowerMineralRegistry;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraftforge.client.model.generators.BlockModelBuilder;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

/** 矿石方块统一叠加灰度矿脉贴图，由方块颜色处理器提供矿种颜色。 */
final class PowerMineralBlockStateProvider extends BlockStateProvider {

    private static final float OVERLAY_OFFSET = 0.01F;

    PowerMineralBlockStateProvider(PackOutput output, ExistingFileHelper existingFiles) {
        super(output, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void registerStatesAndModels() {
        BlockModelBuilder template = models().withExistingParent("tinted_ore", mcLoc("block/block"))
                .renderType("minecraft:cutout")
                .texture("ore", modLoc("block/ore_overlay"))
                .texture("particle", "#ore");
        template.element().from(0, 0, 0).to(16, 16, 16).cube("#base").end();
        template.element()
                .from(-OVERLAY_OFFSET, -OVERLAY_OFFSET, -OVERLAY_OFFSET)
                .to(16 + OVERLAY_OFFSET, 16 + OVERLAY_OFFSET, 16 + OVERLAY_OFFSET)
                .allFaces((direction, face) -> face.texture("#ore").tintindex(0).cullface(direction))
                .end();
        for (PowerMineral mineral : PowerMineral.values()) {
            registerOre(PowerMineralRegistry.ore(mineral).get(),
                    new ResourceLocation("minecraft", "block/stone"), template);
            registerOre(PowerMineralRegistry.deepslateOre(mineral).get(),
                    new ResourceLocation("minecraft", "block/deepslate"), template);
        }
    }

    private void registerOre(Block block, ResourceLocation baseTexture, BlockModelBuilder template) {
        BlockModelBuilder model = models().getBuilder(blockName(block))
                .parent(template)
                .texture("base", baseTexture);
        simpleBlockWithItem(block, model);
    }

    private String blockName(Block block) {
        return net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(block).getPath();
    }
}
