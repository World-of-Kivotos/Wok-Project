package com.miningdim.power.generator;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.GeneratorMultiblockBlock;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.cable.ConductorMaterial;
import com.miningdim.power.grid.VoltageClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.animal.Chicken;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GeneratorRuntimeGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_generator_runtime";

    private GeneratorRuntimeGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void menuDataUsesExactInt32SchemaAndDetachedClientMirror(GameTestHelper helper) {
        helper.assertTrue(GeneratorMenu.DATA_STATE == 0
                        && GeneratorMenu.DATA_STORED_FE_LOW == 1
                        && GeneratorMenu.DATA_STORED_FE_HIGH == 2
                        && GeneratorMenu.DATA_BUFFER_CAPACITY_LOW == 3
                        && GeneratorMenu.DATA_BUFFER_CAPACITY_HIGH == 4
                        && GeneratorMenu.DATA_TEMPERATURE_LOW == 5
                        && GeneratorMenu.DATA_TEMPERATURE_HIGH == 6
                        && GeneratorMenu.DATA_MELTDOWN_TEMPERATURE_LOW == 7
                        && GeneratorMenu.DATA_MELTDOWN_TEMPERATURE_HIGH == 8
                        && GeneratorMenu.DATA_BUFFER_REJECTION_LOW == 9
                        && GeneratorMenu.DATA_BUFFER_REJECTION_HIGH == 10
                        && GeneratorMenu.DATA_FUSE_STATE == 11
                        && GeneratorMenu.DATA_FUEL_REMAINING_LOW == 12
                        && GeneratorMenu.DATA_FUEL_REMAINING_HIGH == 13
                        && GeneratorMenu.DATA_FUEL_MAX_DAMAGE_LOW == 14
                        && GeneratorMenu.DATA_FUEL_MAX_DAMAGE_HIGH == 15
                        && GeneratorMenu.DATA_NETWORK_FAULT == 16
                        && GeneratorMenu.DATA_COUNT == 17,
                "发电机菜单数据索引必须保持 5 个 int32、2 个燃料 int32 与 3 个枚举字段的固定顺序");

        int[] reachableBoundaries = {
                0, 32_767, 32_768, 65_535, 65_536,
                GeneratorSpec.LOW.runtime().bufferCapacityFe(),
                GeneratorSpec.MEDIUM.runtime().bufferCapacityFe(),
                GeneratorSpec.HIGH.runtime().bufferCapacityFe(),
                GeneratorSpec.LOW.runtime().coreDurability(),
                GeneratorSpec.MEDIUM.runtime().coreDurability(),
                GeneratorSpec.HIGH.runtime().coreDurability(),
                1_000_000,
                200_000_000
        };
        for (int value : reachableBoundaries) {
            int low = GeneratorMenu.lowWord(value);
            int high = GeneratorMenu.highWord(value);
            int networkLow = (short) low;
            int networkHigh = (short) high;
            helper.assertTrue(GeneratorMenu.joinInt32(low, high) == value
                            && GeneratorMenu.joinInt32(networkLow, networkHigh) == value,
                    "32 位菜单值必须跨 signed short 传输后无损复原: " + value);
        }

        GeneratorBlockEntity controller = placeGenerator(helper, PowerRegistry.INDUSTRIAL_GENERATOR.get(),
                new BlockPos(3, 1, 3));
        ItemStack core = fuelCore(GeneratorSpec.LOW);
        core.setDamageValue(137);
        controller.inventory().setStackInSlot(GeneratorBlockEntity.SLOT_FUEL_CORE, core);

        ContainerData serverData = GeneratorMenu.dataFor(controller, false);
        ContainerData clientData = GeneratorMenu.dataFor(controller, true);
        ContainerData missingServerData = GeneratorMenu.dataFor(null, false);
        helper.assertTrue(!(serverData instanceof SimpleContainerData)
                        && clientData instanceof SimpleContainerData
                        && missingServerData instanceof SimpleContainerData
                        && serverData.getCount() == GeneratorMenu.DATA_COUNT
                        && clientData.getCount() == GeneratorMenu.DATA_COUNT,
                "客户端或缺失 BE 的发电机菜单必须使用独立 SimpleContainerData 镜像");

        for (int index = 0; index < GeneratorMenu.DATA_COUNT; index++) {
            clientData.set(index, (short) serverData.get(index));
        }
        int mirroredCapacity = GeneratorMenu.joinInt32(
                clientData.get(GeneratorMenu.DATA_BUFFER_CAPACITY_LOW),
                clientData.get(GeneratorMenu.DATA_BUFFER_CAPACITY_HIGH));
        int mirroredRemaining = GeneratorMenu.joinInt32(
                clientData.get(GeneratorMenu.DATA_FUEL_REMAINING_LOW),
                clientData.get(GeneratorMenu.DATA_FUEL_REMAINING_HIGH));
        int mirroredMaximum = GeneratorMenu.joinInt32(
                clientData.get(GeneratorMenu.DATA_FUEL_MAX_DAMAGE_LOW),
                clientData.get(GeneratorMenu.DATA_FUEL_MAX_DAMAGE_HIGH));
        helper.assertTrue(mirroredCapacity == controller.bufferCapacityFe()
                        && mirroredRemaining == core.getMaxDamage() - 137
                        && mirroredMaximum == core.getMaxDamage()
                        && clientData.get(GeneratorMenu.DATA_STATE) == controller.state().ordinal()
                        && clientData.get(GeneratorMenu.DATA_FUSE_STATE) == controller.fuseState().ordinal()
                        && clientData.get(GeneratorMenu.DATA_NETWORK_FAULT) == controller.networkFault().ordinal(),
                "客户端镜像必须精确重组容量、剩余耐久、最大耐久与三个枚举字段");

        controller.fuelCore().setDamageValue(138);
        helper.assertTrue(GeneratorMenu.joinInt32(
                        clientData.get(GeneratorMenu.DATA_FUEL_REMAINING_LOW),
                        clientData.get(GeneratorMenu.DATA_FUEL_REMAINING_HIGH)) == mirroredRemaining,
                "客户端菜单镜像不得绕过同步包直接读取客户端方块实体状态");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void threeSpecsKeepExactOutputDurationAndNbtContract(GameTestHelper helper) {
        assertRuntime(helper, GeneratorSpec.LOW, 192, 600, 200.0D, 4, 64, 8, 0.25D);
        assertRuntime(helper, GeneratorSpec.MEDIUM, 1_152, 900, 260.0D, 8, 192, 24, 0.40D);
        assertRuntime(helper, GeneratorSpec.HIGH, 3_072, 1_200, 320.0D, 24, 512, 64, 0.60D);

        for (GeneratorSpec spec : GeneratorSpec.values()) {
            BlockPos anchorRelative = new BlockPos(3 + spec.ordinal() * 5, 1, 3);
            GeneratorBlockEntity controller = placeGenerator(helper, generatorBlock(spec), anchorRelative);
            controller.inventory().setStackInSlot(GeneratorBlockEntity.SLOT_FUEL_CORE, fuelCore(spec));
            for (int tick = 0; tick < 20; tick++) {
                controller.serverTick();
            }
            int expectedFe = spec.runtime().peakFePerTick() * 20;
            helper.assertTrue(controller.storedFe() == expectedFe
                            && controller.fuelCore().getDamageValue() == 1
                            && controller.fuelCore().getMaxDamage() == spec.runtime().coreDurability(),
                    spec + " 发电机 20 tick 必须精确产生额定 FE 并损耗 1 点同档燃料芯耐久");
            CompoundTag saved = controller.saveWithoutMetadata();
            BlockPos anchor = helper.absolutePos(anchorRelative);
            GeneratorBlockEntity reloaded = new GeneratorBlockEntity(anchor, helper.getLevel().getBlockState(anchor));
            reloaded.load(saved);
            helper.assertTrue(reloaded.spec() == spec && reloaded.storedFe() == expectedFe
                            && reloaded.fuelCore().getDamageValue() == 1
                            && reloaded.fuelCore().getMaxDamage() == spec.runtime().coreDurability()
                            && reloaded.reactionTickRemainder() == 0,
                    spec + " 控制器 NBT 重载不得重置档位、缓冲、燃料耐久或 20 tick 余数");
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fullBufferTurnsOnlyRejectedGenerationIntoHeatAndRejectsWrongCore(GameTestHelper helper) {
        GeneratorBlockEntity controller = placeGenerator(helper, PowerRegistry.INDUSTRIAL_GENERATOR.get(),
                new BlockPos(3, 1, 3));
        controller.inventory().setStackInSlot(GeneratorBlockEntity.SLOT_FUEL_CORE,
                fuelCore(GeneratorSpec.LOW));
        for (int tick = 0; tick < 201; tick++) {
            controller.serverTick();
        }
        helper.assertTrue(controller.storedFe() == GeneratorSpec.LOW.runtime().bufferCapacityFe()
                        && controller.bufferRejectionFe() == 192
                        && Math.abs(controller.temperatureC() - 20.25D) < 0.000001D,
                "满缓冲后仅被拒收的 192 FE 必须升温 0.25C，缓冲不得溢出");

        controller.inventory().extractItem(GeneratorBlockEntity.SLOT_FUEL_CORE, 1, false);
        ItemStack mediumCore = fuelCore(GeneratorSpec.MEDIUM);
        ItemStack rejected = controller.inventory().insertItem(GeneratorBlockEntity.SLOT_FUEL_CORE, mediumCore, false);
        helper.assertTrue(controller.fuelCore().isEmpty()
                        && rejected.getItem() == mediumCore.getItem() && rejected.getCount() == 1,
                "工业机必须原样拒绝错档 MEDIUM 燃料芯");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nichromeFuseScramsAtEightyFivePercentAndResumesAtFiftyPercent(GameTestHelper helper) {
        GeneratorBlockEntity controller = placeGenerator(helper, PowerRegistry.INDUSTRIAL_GENERATOR.get(),
                new BlockPos(3, 1, 3));
        controller.inventory().setStackInSlot(GeneratorBlockEntity.SLOT_NICHROME_FUSE,
                new ItemStack(PowerRegistry.NICHROME_FUSE.get()));
        controller.inventory().setStackInSlot(GeneratorBlockEntity.SLOT_FUEL_CORE,
                fuelCore(GeneratorSpec.LOW));
        for (int tick = 0; tick < 812; tick++) {
            controller.serverTick();
        }
        helper.assertTrue(controller.state() == GeneratorState.SCRAM
                        && controller.fuseState() == GeneratorFuseState.TRIPPED
                        && controller.nichromeFuse().isEmpty()
                        && Math.abs(controller.temperatureC() - 173.0D) < 0.000001D,
                "工业机满拒收至 85% 温差必须消耗保险并在 173C SCRAM");
        ItemStack runningCore = controller.inventory().extractItem(GeneratorBlockEntity.SLOT_FUEL_CORE, 1, false);
        controller.inventory().setStackInSlot(GeneratorBlockEntity.SLOT_FUEL_CORE, runningCore);
        helper.assertTrue(controller.state() == GeneratorState.SCRAM,
                "高温 SCRAM 不得通过手动拔出再插回燃料芯绕过恢复门槛");
        for (int tick = 0; tick < 631; tick++) {
            controller.serverTick();
        }
        controller.inventory().setStackInSlot(GeneratorBlockEntity.SLOT_NICHROME_FUSE,
                new ItemStack(PowerRegistry.NICHROME_FUSE.get()));
        helper.assertTrue(controller.state() == GeneratorState.RUNNING
                        && controller.temperatureC() < 110.0D && controller.temperatureC() > 109.8D,
                "低于 50% 温差并换入新保险后必须自动恢复同一燃料芯的反应");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH, timeoutTicks = 20)
    public static void portFaceCapsOvervoltageAndLegacyShellRebuildStayBounded(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos anchorRelative = new BlockPos(3, 1, 3);
        GeneratorMultiblockBlock block = PowerRegistry.FUTURE_ENERGY_GENERATOR.get();
        BlockPos partialAnchorRelative = new BlockPos(12, 1, 3);
        BlockState partialAnchorState = block.defaultBlockState()
                .setValue(GeneratorMultiblockBlock.FACING, Direction.NORTH)
                .setValue(GeneratorMultiblockBlock.PART, GeneratorMultiblockBlock.ANCHOR_PART);
        helper.setBlock(partialAnchorRelative, partialAnchorState);
        helper.setBlock(partialAnchorRelative.south(), partialAnchorState
                .setValue(GeneratorMultiblockBlock.PART, GeneratorMultiblockBlock.PORT_PART));
        BlockPos partialAnchor = helper.absolutePos(partialAnchorRelative);
        level.removeBlockEntity(partialAnchor);
        level.removeBlockEntity(partialAnchor.south());
        helper.assertTrue(GeneratorBlockEntity.ensureLegacyEntities(level, partialAnchor) == null,
                "旧世界残壳缺少任一部件时不得补建为可运行发电机");

        placeGenerator(helper, block, anchorRelative);
        BlockPos anchor = helper.absolutePos(anchorRelative);
        BlockPos port = GeneratorMultiblockBlock.partPos(anchor, Direction.NORTH, GeneratorMultiblockBlock.PORT_PART);
        level.removeBlockEntity(anchor);
        level.removeBlockEntity(port);
        Direction output = Direction.SOUTH;
        helper.setBlock(anchorRelative.south(2), PowerRegistry.CABLES.get(ConductorMaterial.IRON).get());

        helper.runAfterDelay(3, () -> {
            BlockEntity rebuilt = level.getBlockEntity(anchor);
            helper.assertTrue(rebuilt instanceof GeneratorBlockEntity,
                    "线缆端点扫描遇到合法旧端口且缺 BE 时必须只补建控制器");
            GeneratorBlockEntity controller = (GeneratorBlockEntity) rebuilt;
            controller.inventory().setStackInSlot(GeneratorBlockEntity.SLOT_FUEL_CORE,
                    fuelCore(GeneratorSpec.HIGH));
            BlockEntity portEntity = level.getBlockEntity(port);
            helper.assertTrue(portEntity instanceof GeneratorPortBlockEntity,
                    "旧壳补建必须恢复唯一后部端口代理");
            for (Direction side : Direction.values()) {
                boolean exposed = portEntity.getCapability(ForgeCapabilities.ENERGY, side).isPresent();
                helper.assertTrue(exposed == (side == output), "端口只能在后部输出面暴露 FE: " + side);
            }
        });
        helper.runAfterDelay(7, () -> {
            GeneratorBlockEntity controller = (GeneratorBlockEntity) level.getBlockEntity(anchor);
            helper.assertTrue(controller.networkFault() == GeneratorNetworkFault.OVER_VOLTAGE
                            && controller.faultNetworkLimit() == VoltageClass.LOW
                            && controller.storedFe() > 0,
                    "HIGH 源接 LOW 网必须过压拒抽但持续反应并把 FE 留在本机缓冲");
            GeneratorPortBlockEntity portEntity = (GeneratorPortBlockEntity) level.getBlockEntity(port);
            IEnergyStorage energy = portEntity.getCapability(ForgeCapabilities.ENERGY, output)
                    .resolve().orElseThrow(() -> new IllegalStateException("missing generator port energy"));
            helper.assertTrue(energy.extractEnergy(Integer.MAX_VALUE, false) == GeneratorSpec.HIGH.runtime().peakFePerTick()
                            && energy.extractEnergy(Integer.MAX_VALUE, false) == 0,
                    "端口同 tick 输出必须被峰值 3072 FE/t 硬封顶");
            helper.succeed();
        });
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void meltdownProfilesKeepThreeTierRadiusDamageAndHardLimits(GameTestHelper helper) {
        assertMeltdownProfile(helper, GeneratorSpec.LOW, 4, 64, 8, 0.25D);
        assertMeltdownProfile(helper, GeneratorSpec.MEDIUM, 8, 192, 24, 0.40D);
        assertMeltdownProfile(helper, GeneratorSpec.HIGH, 24, 512, 64, 0.60D);

        BlockPos anchorRelative = new BlockPos(12, 12, 12);
        int radius = GeneratorSpec.MEDIUM.runtime().scatterRadius();
        for (BlockPos relative : BlockPos.betweenClosed(anchorRelative.offset(-radius, -radius, -radius),
                anchorRelative.offset(radius, radius, radius))) {
            int dx = relative.getX() - anchorRelative.getX();
            int dy = relative.getY() - anchorRelative.getY();
            int dz = relative.getZ() - anchorRelative.getZ();
            if (dx * dx + dy * dy + dz * dz <= radius * radius) {
                helper.setBlock(relative, Blocks.GLASS);
            }
        }
        GeneratorBlockEntity controller = placeGenerator(helper, PowerRegistry.MODERN_GENERATOR.get(), anchorRelative);
        BlockPos anchor = helper.absolutePos(anchorRelative);
        Vec3 center = Vec3.atCenterOf(anchor);
        Chicken centerTarget = helper.spawnWithNoFreeWill(EntityType.CHICKEN, anchorRelative.above(3));
        centerTarget.setPos(center.x, center.y, center.z);
        Chicken boundaryTarget = helper.spawnWithNoFreeWill(EntityType.CHICKEN, anchorRelative.above(3));
        boundaryTarget.setPos(center.x + GeneratorSpec.MEDIUM.runtime().scatterRadius(), center.y, center.z);
        BlockPos lowCableRelative = anchorRelative.east(4);
        helper.setBlock(lowCableRelative, PowerRegistry.CABLES.get(ConductorMaterial.IRON).get());
        int glassBefore = countBlocksInRadius(helper, anchorRelative, Blocks.GLASS, radius);

        controller.inventory().setStackInSlot(GeneratorBlockEntity.SLOT_FUEL_CORE,
                fuelCore(GeneratorSpec.MEDIUM));
        CompoundTag meltdown = controller.saveWithoutMetadata();
        meltdown.putInt("storedFe", GeneratorSpec.MEDIUM.runtime().bufferCapacityFe());
        meltdown.putDouble("temperature", GeneratorSpec.MEDIUM.runtime().meltdownTemperatureC()
                - GeneratorSpec.MEDIUM.runtime().maxRejectedTemperatureRiseCPerTick());
        controller.load(meltdown);
        controller.serverTick();

        helper.assertTrue(controller.state() == GeneratorState.MELTDOWN
                        && helper.getLevel().getBlockState(anchor).isAir(),
                "满缓冲拒收把温度推到硬阈值时必须由控制器真实触发熔毁并移除结构");
        helper.assertTrue(helper.getBlockState(lowCableRelative).is(Blocks.AIR),
                "MEDIUM 熔毁必须无掉落烧毁半径内 LOW 线缆");
        helper.assertTrue(Math.abs(centerTarget.getHealth() - 2.4F) < 0.0001F,
                "现代发电机爆心必须精确造成目标最大生命 40% 的伤害");
        helper.assertTrue(Math.abs(boundaryTarget.getHealth() - 4.0F) < 0.0001F,
                "实体位于 8 格熔毁边界时不得受到伤害");
        int destroyedGlass = glassBefore - countBlocksInRadius(helper, anchorRelative, Blocks.GLASS, radius);
        int firePoints = countBlocksInRadius(helper, anchorRelative, Blocks.FIRE, radius);
        helper.assertTrue(destroyedGlass > 0 && destroyedGlass <= 192,
                "现代发电机 TNT 类破坏必须实际发生且不得超过 192 个通用方块");
        helper.assertTrue(firePoints <= 24,
                "现代发电机熔毁产生的有效火点不得超过 24 个");
        helper.succeed();
    }

    private static void assertRuntime(GameTestHelper helper, GeneratorSpec spec, int peak, int durability,
                                      double meltdownTemperature, int radius, int blocks, int fires, double damage) {
        GeneratorSpec.Runtime runtime = spec.runtime();
        helper.assertTrue(runtime.peakFePerTick() == peak && runtime.coreDurability() == durability
                        && runtime.bufferCapacityFe() == peak * 200 && runtime.coreDurationTicks() == durability * 20
                        && runtime.meltdownTemperatureC() == meltdownTemperature,
                spec + " 必须保持峰值、燃料时长与 200 tick 缓冲的精确关系");
        assertMeltdownProfile(helper, spec, radius, blocks, fires, damage);
    }

    private static void assertMeltdownProfile(GameTestHelper helper, GeneratorSpec spec, int radius,
                                               int blocks, int fires, double damage) {
        GeneratorSpec.Runtime runtime = spec.runtime();
        helper.assertTrue(runtime.scatterRadius() == radius && runtime.maxDestructibleBlocks() == blocks
                        && runtime.maxFirePoints() == fires && runtime.centerDamageFraction() == damage,
                spec + " 熔毁半径、破坏上限、火点上限和中心百分比伤害不得漂移");
    }

    private static GeneratorBlockEntity placeGenerator(GameTestHelper helper, GeneratorMultiblockBlock block,
                                                         BlockPos anchorRelative) {
        Direction facing = Direction.NORTH;
        for (GeneratorMultiblockBlock.Part part : GeneratorMultiblockBlock.Part.values()) {
            helper.setBlock(GeneratorMultiblockBlock.partPos(anchorRelative, facing, part),
                    block.defaultBlockState().setValue(GeneratorMultiblockBlock.FACING, facing)
                            .setValue(GeneratorMultiblockBlock.PART, part));
        }
        BlockPos anchor = helper.absolutePos(anchorRelative);
        GeneratorBlockEntity controller = GeneratorBlockEntity.ensureLegacyEntities(helper.getLevel(), anchor);
        if (controller == null) {
            throw new IllegalStateException("failed to build generator controller at " + anchor);
        }
        return controller;
    }

    private static ItemStack fuelCore(GeneratorSpec spec) {
        return new ItemStack(switch (spec) {
            case LOW -> PowerRegistry.INDUSTRIAL_FUEL_CORE.get();
            case MEDIUM -> PowerRegistry.MODERN_FUEL_CORE.get();
            case HIGH -> PowerRegistry.FUTURE_FUEL_CORE.get();
        });
    }

    private static GeneratorMultiblockBlock generatorBlock(GeneratorSpec spec) {
        return switch (spec) {
            case LOW -> PowerRegistry.INDUSTRIAL_GENERATOR.get();
            case MEDIUM -> PowerRegistry.MODERN_GENERATOR.get();
            case HIGH -> PowerRegistry.FUTURE_ENERGY_GENERATOR.get();
        };
    }

    private static int countBlocksInRadius(GameTestHelper helper, BlockPos center, Block expected, int radius) {
        int count = 0;
        for (BlockPos relative : BlockPos.betweenClosed(center.offset(-radius, -radius, -radius),
                center.offset(radius, radius, radius))) {
            int dx = relative.getX() - center.getX();
            int dy = relative.getY() - center.getY();
            int dz = relative.getZ() - center.getZ();
            if (dx * dx + dy * dy + dz * dz <= radius * radius
                    && helper.getBlockState(relative).is(expected)) {
                count++;
            }
        }
        return count;
    }
}
