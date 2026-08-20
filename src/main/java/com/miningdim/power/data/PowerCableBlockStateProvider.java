package com.miningdim.power.data;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.cable.EnergyCableBlock;
import com.miningdim.power.cable.ConductorMaterial;
import net.minecraft.core.Direction;
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
        ModelFile core = cableCoreModel(name + "_core", texture);
        ModelFile port = cablePortModel(name + "_port", texture);

        MultiPartBlockStateBuilder multipart = getMultipartBuilder(block);
        multipart.part().modelFile(core).addModel().end();
        addPort(multipart, port, EnergyCableBlock.NORTH, 0, 0);
        addPort(multipart, port, EnergyCableBlock.SOUTH, 0, 180);
        addPort(multipart, port, EnergyCableBlock.EAST, 0, 90);
        addPort(multipart, port, EnergyCableBlock.WEST, 0, 270);
        // 端口基准模型的臂在 -Z 半边(朝北), 与原版 observer/piston 同基准, 故绕 X 轴的取值必须与原版一致:
        // x=270 转到上、x=90 转到下。此前两者写反, 导致勾选 UP 时臂画在下方、勾选 DOWN 时画在上方,
        // 而碰撞箱走的是 EnergyCableBlock 的 arms 表(正确), 于是出现贴图与实体上下错位。
        addPort(multipart, port, EnergyCableBlock.UP, 270, 0);
        addPort(multipart, port, EnergyCableBlock.DOWN, 90, 0);
    }

    @Override
    public String getName() {
        return "能源线缆方块状态: " + MiningConstants.MODID;
    }

    private ModelFile cableCoreModel(String name, ResourceLocation texture) {
        BlockModelBuilder model = models().getBuilder(name)
                .renderType("minecraft:cutout")
                .texture("particle", texture)
                .texture("cable", texture);
        BlockModelBuilder.ElementBuilder element = model.element()
                .from(6, 6, 6)
                .to(10, 10, 10);
        for (Direction direction : Direction.values()) {
            element.face(direction)
                    .uvs(6, 6, 10, 10)
                    .texture("#cable")
                    .end();
        }
        element.end();
        return model;
    }

    private ModelFile cablePortModel(String name, ResourceLocation texture) {
        BlockModelBuilder model = models().getBuilder(name)
                .renderType("minecraft:cutout")
                .texture("particle", texture)
                .texture("cable", texture);
        BlockModelBuilder.ElementBuilder element = model.element()
                .from(6, 6, 0)
                .to(10, 10, 6);
        element.face(Direction.NORTH)
                .uvs(0, 6, 4, 10)
                .texture("#cable")
                .end();
        element.face(Direction.WEST)
                .uvs(0, 6, 6, 10)
                .texture("#cable")
                .end();
        element.face(Direction.EAST)
                .uvs(0, 6, 6, 10)
                .texture("#cable")
                .end();
        element.face(Direction.UP)
                .uvs(0, 6, 6, 10)
                .rotation(BlockModelBuilder.FaceRotation.CLOCKWISE_90)
                .texture("#cable")
                .end();
        element.face(Direction.DOWN)
                .uvs(0, 6, 6, 10)
                .rotation(BlockModelBuilder.FaceRotation.COUNTERCLOCKWISE_90)
                .texture("#cable")
                .end();
        element.end();
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
