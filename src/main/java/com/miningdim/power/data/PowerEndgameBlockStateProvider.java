package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.endgame.LowTemperatureControllerBlock;
import net.minecraft.core.Direction;
import net.minecraft.data.PackOutput;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraftforge.client.model.generators.BlockModelBuilder;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.client.model.generators.ModelFile;
import net.minecraftforge.common.data.ExistingFileHelper;

/** 低温控制器使用四向工作态模型，亮态只替换前面板。 */
final class PowerEndgameBlockStateProvider extends BlockStateProvider {

    PowerEndgameBlockStateProvider(PackOutput output, ExistingFileHelper existingFiles) {
        super(output, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void registerStatesAndModels() {
        ModelFile idle = orientableModel("low_temperature_controller", "low_temperature_controller_front");
        ModelFile active = orientableModel("low_temperature_controller_on", "low_temperature_controller_front_on");
        DirectionProperty facing = LowTemperatureControllerBlock.FACING;
        BooleanProperty lit = LowTemperatureControllerBlock.LIT;
        var variants = getVariantBuilder(PowerRegistry.LOW_TEMPERATURE_CONTROLLER.get());
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            int rotationY = switch (direction) {
                case NORTH -> 0;
                case EAST -> 90;
                case SOUTH -> 180;
                case WEST -> 270;
                default -> throw new IllegalStateException("controller facing must be horizontal: " + direction);
            };
            variants.partialState().with(facing, direction).with(lit, false)
                    .modelForState().modelFile(idle).rotationY(rotationY).addModel();
            variants.partialState().with(facing, direction).with(lit, true)
                    .modelForState().modelFile(active).rotationY(rotationY).addModel();
        }
    }

    @Override
    public String getName() {
        return "终局低温控制器方块状态: " + MiningConstants.MODID;
    }

    private BlockModelBuilder orientableModel(String modelId, String frontTexture) {
        return models().withExistingParent(modelId, mcLoc("block/orientable"))
                .texture("top", modLoc("block/low_temperature_controller_top"))
                .texture("side", modLoc("block/low_temperature_controller_side"))
                .texture("front", modLoc("block/" + frontTexture));
    }
}
