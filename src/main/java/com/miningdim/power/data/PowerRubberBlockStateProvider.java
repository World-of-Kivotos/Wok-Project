package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.rubber.PowerRubberRegistry;
import com.miningdim.power.rubber.RubberLogBlock;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraftforge.client.model.generators.BlockModelBuilder;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.common.data.ExistingFileHelper;

/** 橡胶原木以同一方块的轴向和割胶状态生成模型，避免把冷却状态拆成独立注册 ID。 */
final class PowerRubberBlockStateProvider extends BlockStateProvider {

    PowerRubberBlockStateProvider(PackOutput output, ExistingFileHelper existingFiles) {
        super(output, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void registerStatesAndModels() {
        BlockModelBuilder log = columnModel("rubber_log", "rubber_log");
        BlockModelBuilder logHorizontal = horizontalColumnModel("rubber_log_horizontal", "rubber_log");
        BlockModelBuilder tappedLog = columnModel("rubber_log_tapped", "rubber_log_tapped");
        BlockModelBuilder tappedLogHorizontal = horizontalColumnModel("rubber_log_tapped_horizontal", "rubber_log_tapped");
        registerLogState(log, logHorizontal, tappedLog, tappedLogHorizontal);

        simpleBlock(PowerRubberRegistry.RUBBER_PLANKS.get(), models().cubeAll("rubber_planks", modLoc("block/rubber_planks")));
        simpleBlock(PowerRubberRegistry.RUBBER_LEAVES.get(), models().withExistingParent("rubber_leaves", mcLoc("block/leaves"))
                .texture("all", modLoc("block/rubber_leaves"))
                .renderType("minecraft:cutout_mipped"));
        simpleBlock(PowerRubberRegistry.RUBBER_SAPLING.get(), models().withExistingParent("rubber_tree_sapling", mcLoc("block/cross"))
                .texture("cross", modLoc("block/rubber_tree_sapling"))
                .renderType("minecraft:cutout"));
    }

    @Override
    public String getName() {
        return "橡胶方块状态: " + MiningConstants.MODID;
    }

    private BlockModelBuilder columnModel(String name, String sideTexture) {
        return models().withExistingParent(name, mcLoc("block/cube_column"))
                .texture("side", modLoc("block/" + sideTexture))
                .texture("end", modLoc("block/rubber_log_top"));
    }

    private BlockModelBuilder horizontalColumnModel(String name, String sideTexture) {
        return models().withExistingParent(name, mcLoc("block/cube_column_horizontal"))
                .texture("side", modLoc("block/" + sideTexture))
                .texture("end", modLoc("block/rubber_log_top"));
    }

    private void registerLogState(BlockModelBuilder log, BlockModelBuilder logHorizontal,
                                  BlockModelBuilder tappedLog, BlockModelBuilder tappedLogHorizontal) {
        var variants = getVariantBuilder(PowerRubberRegistry.RUBBER_LOG.get());
        variants.partialState().with(RubberLogBlock.TAPPED, false).with(RotatedPillarBlock.AXIS, Direction.Axis.Y)
                .modelForState().modelFile(log).addModel();
        variants.partialState().with(RubberLogBlock.TAPPED, false).with(RotatedPillarBlock.AXIS, Direction.Axis.X)
                .modelForState().modelFile(logHorizontal).rotationY(90).addModel();
        variants.partialState().with(RubberLogBlock.TAPPED, false).with(RotatedPillarBlock.AXIS, Direction.Axis.Z)
                .modelForState().modelFile(logHorizontal).rotationY(0).addModel();
        variants.partialState().with(RubberLogBlock.TAPPED, true).with(RotatedPillarBlock.AXIS, Direction.Axis.Y)
                .modelForState().modelFile(tappedLog).addModel();
        variants.partialState().with(RubberLogBlock.TAPPED, true).with(RotatedPillarBlock.AXIS, Direction.Axis.X)
                .modelForState().modelFile(tappedLogHorizontal).rotationY(90).addModel();
        variants.partialState().with(RubberLogBlock.TAPPED, true).with(RotatedPillarBlock.AXIS, Direction.Axis.Z)
                .modelForState().modelFile(tappedLogHorizontal).rotationY(0).addModel();
    }
}
