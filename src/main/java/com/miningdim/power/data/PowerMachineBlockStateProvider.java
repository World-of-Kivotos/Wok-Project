package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerMachineRegistry;
import com.miningdim.power.machine.PowerMachineBlock;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraftforge.client.model.generators.BlockModelBuilder;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.client.model.generators.ModelFile;
import net.minecraftforge.common.data.ExistingFileHelper;

/** 提纯机与空分机的水平朝向、工作态方块模型。 */
final class PowerMachineBlockStateProvider extends BlockStateProvider {

    PowerMachineBlockStateProvider(PackOutput output, ExistingFileHelper existingFiles) {
        super(output, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void registerStatesAndModels() {
        registerMachine(PowerMachineRegistry.PURIFIER_BLOCK.get(), "metallurgic_purifier");
        registerMachine(PowerMachineRegistry.AIR_SEPARATOR_BLOCK.get(), "air_separation_unit");
    }

    @Override
    public String getName() {
        return "能源加工机器方块状态: " + MiningConstants.MODID;
    }

    private void registerMachine(Block block, String id) {
        ModelFile idle = orientableModel(id, id, id + "_front");
        ModelFile active = orientableModel(id + "_on", id, id + "_front_on");
        DirectionProperty facing = PowerMachineBlock.FACING;
        BooleanProperty lit = PowerMachineBlock.LIT;
        var variants = getVariantBuilder(block);
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int rotationY = switch (direction) {
                case NORTH -> 0;
                case EAST -> 90;
                case SOUTH -> 180;
                case WEST -> 270;
                default -> throw new IllegalStateException("machine facing must be horizontal: " + direction);
            };
            variants.partialState().with(facing, direction).with(lit, false)
                    .modelForState().modelFile(idle).rotationY(rotationY).addModel();
            variants.partialState().with(facing, direction).with(lit, true)
                    .modelForState().modelFile(active).rotationY(rotationY).addModel();
        }
    }

    private BlockModelBuilder orientableModel(String modelId, String baseId, String frontTexture) {
        return models().withExistingParent(modelId, mcLoc("block/orientable"))
                .texture("top", modLoc("block/" + baseId + "_top"))
                .texture("side", modLoc("block/" + baseId + "_side"))
                .texture("front", modLoc("block/" + frontTexture));
    }
}
