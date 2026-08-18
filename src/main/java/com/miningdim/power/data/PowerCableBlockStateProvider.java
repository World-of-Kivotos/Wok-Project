package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.cable.EnergyCableBlock;
import com.miningdim.power.cable.ConductorMaterial;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraftforge.client.model.generators.BlockModelBuilder;
import net.minecraftforge.client.model.generators.BlockStateProvider;
import net.minecraftforge.client.model.generators.ModelFile;
import net.minecraftforge.client.model.generators.MultiPartBlockStateBuilder;
import net.minecraftforge.common.data.ExistingFileHelper;

/** P1 线缆的六向细杆与端口 multipart 资源。 */
final class PowerCableBlockStateProvider extends BlockStateProvider {

    PowerCableBlockStateProvider(PackOutput output, ExistingFileHelper existingFiles) {
        super(output, MiningConstants.MODID, existingFiles);
    }

    @Override
    protected void registerStatesAndModels() {
        for (ConductorMaterial material : PowerRegistry.CABLES.keySet()) {
            registerCable(material.blockId(), PowerRegistry.CABLES.get(material).get());
        }
        registerCable("tungsten_heat_resistant_wire",
                PowerRegistry.TUNGSTEN_HEAT_RESISTANT_CABLE.get());
    }

    private void registerCable(String name, Block block) {
        ResourceLocation texture = modLoc("block/" + name);
        ModelFile core = cableModel(name + "_core", texture, 6, 6, 6, 10, 10, 10);
        ModelFile port = cableModel(name + "_port", texture, 6, 6, 0, 10, 10, 10);

        MultiPartBlockStateBuilder multipart = getMultipartBuilder(block);
        multipart.part().modelFile(core).addModel().end();
        addPort(multipart, port, EnergyCableBlock.NORTH, 0, 0);
        addPort(multipart, port, EnergyCableBlock.SOUTH, 0, 180);
        addPort(multipart, port, EnergyCableBlock.EAST, 0, 90);
        addPort(multipart, port, EnergyCableBlock.WEST, 0, 270);
        addPort(multipart, port, EnergyCableBlock.UP, 90, 0);
        addPort(multipart, port, EnergyCableBlock.DOWN, 270, 0);
    }

    @Override
    public String getName() {
        return "能源线缆方块状态: " + MiningConstants.MODID;
    }

    private ModelFile cableModel(String name, ResourceLocation texture,
                                 float fromX, float fromY, float fromZ,
                                 float toX, float toY, float toZ) {
        BlockModelBuilder model = models().getBuilder(name)
                .renderType("minecraft:cutout")
                .texture("cable", texture);
        model.element()
                .from(fromX, fromY, fromZ)
                .to(toX, toY, toZ)
                .textureAll("#cable")
                .end();
        return model;
    }

    private void addPort(MultiPartBlockStateBuilder multipart, ModelFile port,
                         BooleanProperty property, int rotationX, int rotationY) {
        multipart.part()
                .modelFile(port)
                .rotationX(rotationX)
                .rotationY(rotationY)
                .uvLock(false)
                .addModel()
                .condition(property, true)
                .end();
    }
}
