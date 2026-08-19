package com.miningdim.power.generator;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.grid.CableThermics;
import com.miningdim.power.grid.VoltageClass;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 前期两台预热式发电机的运行契约。
 *
 * 与燃料芯发电机相反，这里的温度是正向变量：冷机产出必须为零，满温才满载。全部断言取精确数值，
 * 删掉温度到功率的换算或删掉热源判定，任何一条都会立刻挂。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PreheatGeneratorGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "preheat_generator";
    private static final BlockPos MACHINE_REL = new BlockPos(3, 2, 3);
    private static final double EPSILON = 1.0E-6D;
    private static final List<String> BLOCK_ASSET_IDS = List.of(
            "coal_generator", "geothermal_generator",
            "industrial_power_cell", "modern_power_cell", "future_power_cell");

    private PreheatGeneratorGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void twoSpecsKeepExactRuntimeAndDerivedContract(GameTestHelper helper) {
        assertRuntime(helper, PreheatGeneratorSpec.COAL, 48, 300.0D, 1_200, 0.15D, 4);
        assertRuntime(helper, PreheatGeneratorSpec.GEOTHERMAL, 144, 600.0D, 2_400, 0.20D, 1);

        PreheatGeneratorSpec.Runtime coal = PreheatGeneratorSpec.COAL.runtime();
        helper.assertTrue(coal.bufferCapacityFe() == 9_600,
                "煤炭机缓冲必须是峰值乘 200 tick, 得到 " + coal.bufferCapacityFe());
        helper.assertTrue(Math.abs(coal.heatupCPerTick() - 280.0D / 1_200.0D) < EPSILON,
                "升温速率必须由工作温度与预热时长派生, 得到 " + coal.heatupCPerTick());

        // 冷机零产出、满温满载、正中间恰好一半, 这三点钉死温度到功率的换算。
        helper.assertTrue(coal.outputAt(CableThermics.AMBIENT_C) == 0, "冷机必须零产出");
        helper.assertTrue(coal.outputAt(300.0D) == 48, "满温必须满载");
        helper.assertTrue(coal.outputAt(160.0D) == 24, "半温必须半载, 得到 " + coal.outputAt(160.0D));
        helper.assertTrue(coal.outputAt(1_000.0D) == 48, "超过工作温度不得超载");

        PreheatGeneratorSpec.Runtime geothermal = PreheatGeneratorSpec.GEOTHERMAL.runtime();
        helper.assertTrue(geothermal.bufferCapacityFe() == 28_800,
                "地热机缓冲必须是峰值乘 200 tick, 得到 " + geothermal.bufferCapacityFe());
        helper.assertTrue(geothermal.outputAt(600.0D) == 144, "地热满温必须满载");
        helper.assertTrue(geothermal.outputAt(310.0D) == 72,
                "地热半温必须半载, 得到 " + geothermal.outputAt(310.0D));

        helper.assertTrue(PreheatGeneratorSpec.COAL.sourceVoltage() == VoltageClass.LOW
                        && PreheatGeneratorSpec.GEOTHERMAL.sourceVoltage() == VoltageClass.LOW,
                "前期两台一律 LOW 段电源");
        helper.assertTrue(PreheatGeneratorSpec.byId("coal") == PreheatGeneratorSpec.COAL
                        && PreheatGeneratorSpec.byId("geothermal") == PreheatGeneratorSpec.GEOTHERMAL,
                "规格 id 必须可反查");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void coalGeneratorPreheatsThenReachesPeakOutput(GameTestHelper helper) {
        PreheatGeneratorBlockEntity generator = placeCoal(helper);
        generator.inventory().setStackInSlot(PreheatGeneratorBlockEntity.SLOT_FUEL,
                new ItemStack(Items.COAL, 4));

        helper.assertTrue(generator.currentOutputFePerTick() == 0, "冷机开机瞬间不得发电");
        for (int tick = 0; tick < 600; tick++) {
            generator.serverTick();
        }
        double halfway = generator.temperatureC();
        helper.assertTrue(Math.abs(halfway - 160.0D) < EPSILON,
                "预热半程温度必须恰好走一半, 得到 " + halfway);
        helper.assertTrue(generator.currentOutputFePerTick() == 24,
                "半温必须半载, 得到 " + generator.currentOutputFePerTick());

        for (int tick = 0; tick < 600; tick++) {
            generator.serverTick();
        }
        helper.assertTrue(Math.abs(generator.temperatureC() - 300.0D) < EPSILON,
                "预热 1200 tick 后必须恰好到达工作温度, 得到 " + generator.temperatureC());
        helper.assertTrue(generator.currentOutputFePerTick() == 48,
                "满温必须满载, 得到 " + generator.currentOutputFePerTick());
        helper.assertTrue(generator.storedFe() == 9_600,
                "预热期间发的电必须进缓冲直到装满, 得到 " + generator.storedFe());
        helper.assertTrue(helper.getBlockState(MACHINE_REL).getValue(PreheatGeneratorBlock.LIT),
                "升温中的机器必须点亮");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void coalGeneratorHoldsFireWhenBufferIsFullAndCoolsWithoutFuel(GameTestHelper helper) {
        PreheatGeneratorBlockEntity generator = placeCoal(helper);
        generator.inventory().setStackInSlot(PreheatGeneratorBlockEntity.SLOT_FUEL,
                new ItemStack(Items.COAL, 4));
        for (int tick = 0; tick < 1_200; tick++) {
            generator.serverTick();
        }

        // 已满温且缓冲已满: 停火保温, 不得继续空烧燃料。
        int burnBefore = generator.burnTicksRemaining();
        for (int tick = 0; tick < 200; tick++) {
            generator.serverTick();
        }
        helper.assertTrue(generator.burnTicksRemaining() == burnBefore,
                "满温满缓冲时必须停火保温, 燃烧计时从 " + burnBefore + " 变成 " + generator.burnTicksRemaining());
        helper.assertTrue(Math.abs(generator.temperatureC() - 300.0D) < EPSILON, "保温期间温度不得回落");

        // 取走电后重新开火。
        IEnergyStorage storage = energyOf(helper, generator);
        int extracted = storage.extractEnergy(5_000, false);
        helper.assertTrue(extracted == 5_000, "缓冲必须可被网络抽取, 得到 " + extracted);
        generator.serverTick();
        helper.assertTrue(generator.burnTicksRemaining() == burnBefore - 1,
                "缓冲腾出空间后必须恢复燃烧, 得到 " + generator.burnTicksRemaining());

        // 抽空燃料后冷却, 且冷却速率精确。这里每 tick 都把缓冲抽干: 否则缓冲一填满就触发停火保温,
        // 燃烧计时会永远停在原地(这正是保温机制的预期行为, 但会让"等燃料烧完"变成死循环)。
        generator.inventory().setStackInSlot(PreheatGeneratorBlockEntity.SLOT_FUEL, ItemStack.EMPTY);
        int guard = 0;
        while (generator.burnTicksRemaining() > 0 && guard++ < 40_000) {
            storage.extractEnergy(Integer.MAX_VALUE, false);
            generator.serverTick();
        }
        helper.assertTrue(generator.burnTicksRemaining() == 0,
                "持续取电时燃料必须正常烧尽, 剩余 " + generator.burnTicksRemaining());
        double beforeCooling = generator.temperatureC();
        generator.serverTick();
        helper.assertTrue(Math.abs(beforeCooling - generator.temperatureC() - 0.15D) < EPSILON,
                "断料后必须按配置速率冷却, 温差 " + (beforeCooling - generator.temperatureC()));
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void geothermalRunsOnLavaSourceWithoutConsumingIt(GameTestHelper helper) {
        BlockPos below = MACHINE_REL.below();
        helper.setBlock(below, Blocks.LAVA);
        PreheatGeneratorBlockEntity generator = placeGeothermal(helper);

        for (int tick = 0; tick < 2_400; tick++) {
            generator.serverTick();
        }
        helper.assertTrue(Math.abs(generator.temperatureC() - 600.0D) < EPSILON,
                "地热预热 2400 tick 后必须到达工作温度, 得到 " + generator.temperatureC());
        helper.assertTrue(generator.currentOutputFePerTick() == 144,
                "地热满温必须满载, 得到 " + generator.currentOutputFePerTick());
        // 地热是可再生的: 岩浆源必须原样还在。
        helper.assertBlockPresent(Blocks.LAVA, below);
        helper.assertTrue(helper.getLevel().getFluidState(helper.absolutePos(below)).isSource(),
                "地热运行不得消耗脚下的岩浆源");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void geothermalRefusesNonSourceAndCoolsDown(GameTestHelper helper) {
        BlockPos below = MACHINE_REL.below();
        helper.setBlock(below, Blocks.LAVA);
        PreheatGeneratorBlockEntity generator = placeGeothermal(helper);
        for (int tick = 0; tick < 600; tick++) {
            generator.serverTick();
        }
        double heated = generator.temperatureC();
        helper.assertTrue(heated > CableThermics.AMBIENT_C, "岩浆源上必须升温");

        // 换成石头即失去热源: 流动残留与非岩浆方块都不算机位。
        helper.setBlock(below, Blocks.STONE);
        generator.serverTick();
        helper.assertTrue(Math.abs(heated - generator.temperatureC() - 0.20D) < EPSILON,
                "失去岩浆源后必须按配置速率冷却, 温差 " + (heated - generator.temperatureC()));

        for (int tick = 0; tick < 5_000; tick++) {
            generator.serverTick();
        }
        helper.assertTrue(Math.abs(generator.temperatureC() - CableThermics.AMBIENT_C) < EPSILON,
                "冷却必须停在环境温度, 得到 " + generator.temperatureC());
        helper.assertTrue(generator.currentOutputFePerTick() == 0, "冷机必须零产出");
        helper.assertFalse(helper.getBlockState(MACHINE_REL).getValue(PreheatGeneratorBlock.LIT),
                "冷机必须熄灭");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void energyPortIsExtractOnlyAndFuelSlotRejectsNonBurnables(GameTestHelper helper) {
        PreheatGeneratorBlockEntity generator = placeCoal(helper);
        IEnergyStorage storage = energyOf(helper, generator);

        helper.assertTrue(!storage.canReceive() && storage.canExtract(),
                "发电机端口必须只出不进");
        helper.assertTrue(storage.receiveEnergy(1_000, false) == 0,
                "外部电源不得向发电机灌电, 否则电网会出现能量环流");
        helper.assertTrue(storage.getMaxEnergyStored() == 9_600,
                "端口容量必须等于缓冲容量, 得到 " + storage.getMaxEnergyStored());
        helper.assertTrue(storage.extractEnergy(100, false) == 0, "空缓冲不得抽出电");

        helper.assertTrue(generator.inventory().isItemValid(PreheatGeneratorBlockEntity.SLOT_FUEL,
                        new ItemStack(Items.COAL)), "煤炭机必须接受可燃物");
        helper.assertFalse(generator.inventory().isItemValid(PreheatGeneratorBlockEntity.SLOT_FUEL,
                new ItemStack(Items.IRON_INGOT)), "煤炭机不得接受不可燃物");

        PreheatGeneratorBlockEntity geothermal = placeGeothermalAt(helper, MACHINE_REL.east(2));
        helper.assertFalse(geothermal.inventory().isItemValid(PreheatGeneratorBlockEntity.SLOT_FUEL,
                new ItemStack(Items.COAL)), "地热机没有燃料语义, 槽位必须一律拒收");
        helper.succeed();
    }

    /**
     * 保存与读取都不得抛异常。前期发电机是新玩家最早放下的方块之一，NBT 一旦不完整就让区块加载失败
     * 是不可接受的代价；不变量改为在读取时钳回合法区间。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void nbtRoundTripSurvivesAndClampsOutOfRangeValues(GameTestHelper helper) {
        PreheatGeneratorBlockEntity generator = placeCoal(helper);
        generator.inventory().setStackInSlot(PreheatGeneratorBlockEntity.SLOT_FUEL,
                new ItemStack(Items.COAL, 2));
        for (int tick = 0; tick < 300; tick++) {
            generator.serverTick();
        }
        CompoundTag saved = generator.saveWithFullMetadata();
        double savedTemperature = generator.temperatureC();
        int savedStored = generator.storedFe();

        PreheatGeneratorBlockEntity reloaded = new PreheatGeneratorBlockEntity(
                helper.absolutePos(MACHINE_REL), coalState());
        reloaded.load(saved);
        helper.assertTrue(Math.abs(reloaded.temperatureC() - savedTemperature) < EPSILON,
                "温度必须原样往返, 得到 " + reloaded.temperatureC());
        helper.assertTrue(reloaded.storedFe() == savedStored,
                "缓冲必须原样往返, 得到 " + reloaded.storedFe());
        helper.assertTrue(reloaded.inventory().getStackInSlot(PreheatGeneratorBlockEntity.SLOT_FUEL)
                .is(Items.COAL), "燃料槽必须原样往返");

        CompoundTag corrupted = generator.saveWithFullMetadata();
        corrupted.putDouble("temperatureC", 99_999.0D);
        corrupted.putInt("storedFe", Integer.MAX_VALUE);
        corrupted.putInt("burnTicksRemaining", -5);
        PreheatGeneratorBlockEntity clamped = new PreheatGeneratorBlockEntity(
                helper.absolutePos(MACHINE_REL), coalState());
        clamped.load(corrupted);
        helper.assertTrue(Math.abs(clamped.temperatureC() - 300.0D) < EPSILON,
                "越界温度必须钳到工作温度而不是抛异常, 得到 " + clamped.temperatureC());
        helper.assertTrue(clamped.storedFe() == 9_600,
                "越界电量必须钳到容量而不是抛异常, 得到 " + clamped.storedFe());
        helper.assertTrue(clamped.burnTicksRemaining() == 0,
                "负燃烧计时必须钳到零, 得到 " + clamped.burnTicksRemaining());
        helper.succeed();
    }

    /**
     * 资产对账。方块模型引用的贴图一旦缺失，游戏里表现为紫黑格而不是报错，靠肉眼巡检必然漏，
     * 故把"模型声明的每一张贴图都真实存在且是 16x16"钉成断言。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blockAssetsExistAndMatchModels(GameTestHelper helper) {
        Set<String> textureReferences = new LinkedHashSet<>();
        for (String blockId : BLOCK_ASSET_IDS) {
            JsonObject blockstate = readJson("/assets/miningdim/blockstates/" + blockId + ".json");
            JsonObject variants = blockstate.getAsJsonObject("variants");
            helper.assertTrue(variants.size() == 8,
                    blockId + " 必须覆盖 4 朝向 x 2 燃烧态, 得到 " + variants.size());
            for (String facing : List.of("north", "east", "south", "west")) {
                for (String lit : List.of("false", "true")) {
                    String variantKey = "facing=" + facing + ",lit=" + lit;
                    helper.assertTrue(variants.has(variantKey),
                            blockId + " 缺少 variant " + variantKey);
                    String expectedModel = "miningdim:block/" + blockId
                            + ("true".equals(lit) ? "_on" : "");
                    String actualModel = variants.getAsJsonObject(variantKey).get("model").getAsString();
                    helper.assertTrue(actualModel.equals(expectedModel),
                            blockId + " 的 " + variantKey + " 必须引用 " + expectedModel
                                    + ", 得到 " + actualModel);
                }
            }

            for (String suffix : List.of("", "_on")) {
                JsonObject model = readJson("/assets/miningdim/models/block/" + blockId + suffix + ".json");
                helper.assertTrue(model.get("parent").getAsString().equals("minecraft:block/orientable"),
                        blockId + suffix + " 必须使用 orientable 方块模型");
                JsonObject textures = model.getAsJsonObject("textures");
                for (String key : List.of("top", "side", "front")) {
                    String reference = textures.get(key).getAsString();
                    String expectedTexture = "miningdim:block/" + blockId + "_" + key
                            + ("front".equals(key) && "_on".equals(suffix) ? "_on" : "");
                    helper.assertTrue(reference.equals(expectedTexture),
                            blockId + suffix + " 的 " + key + " 必须引用 " + expectedTexture
                                    + ", 得到 " + reference);
                    textureReferences.add(reference);
                }
            }

            JsonObject itemModel = readJson("/assets/miningdim/models/item/" + blockId + ".json");
            helper.assertTrue(itemModel.get("parent").getAsString().equals("miningdim:block/" + blockId),
                    blockId + " 物品模型必须复用方块模型");
            JsonObject loot = readJson("/data/miningdim/loot_tables/blocks/" + blockId + ".json");
            helper.assertTrue(loot.getAsJsonArray("pools").size() == 1,
                    blockId + " 战利品表必须恰好一个池");
        }

        helper.assertTrue(textureReferences.size() == 20,
                "五台设备必须恰好引用 20 张唯一方块贴图, 得到 " + textureReferences.size()
                        + ": " + textureReferences);
        for (String reference : textureReferences) {
            String texturePath = "/assets/miningdim/textures/block/"
                    + reference.substring("miningdim:block/".length()) + ".png";
            BufferedImage image = readPng(texturePath);
            helper.assertTrue(image.getWidth() == 16 && image.getHeight() == 16,
                    texturePath + " 必须是 16x16, 得到 "
                            + image.getWidth() + "x" + image.getHeight());
            assertOpaqueAndWithoutPureWhite(helper, texturePath, image);
        }
        for (String blockId : BLOCK_ASSET_IDS) {
            assertFrontStateDifference(helper, blockId);
        }
        helper.succeed();
    }

    private static void assertOpaqueAndWithoutPureWhite(GameTestHelper helper, String path,
                                                        BufferedImage image) {
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int argb = image.getRGB(x, y);
                int alpha = argb >>> 24;
                helper.assertTrue(alpha == 255,
                        path + " 所有像素必须完全不透明, (" + x + "," + y + ") alpha=" + alpha);
                helper.assertTrue((argb & 0x00FFFFFF) != 0x00FFFFFF,
                        path + " 不得出现纯白像素, 失败坐标 (" + x + "," + y + ")");
                if (x == 0 || x == image.getWidth() - 1 || y == 0 || y == image.getHeight() - 1) {
                    int red = (argb >>> 16) & 0xFF;
                    int green = (argb >>> 8) & 0xFF;
                    int blue = argb & 0xFF;
                    helper.assertTrue(red < 224 || green < 224 || blue < 224,
                            path + " 最外圈不得出现 RGB 三通道均大于等于 224 的近白像素, 失败坐标 ("
                                    + x + "," + y + "), RGB=(" + red + "," + green + "," + blue + ")");
                }
            }
        }
    }

    private static void assertFrontStateDifference(GameTestHelper helper, String blockId) {
        String textureRoot = "/assets/miningdim/textures/block/" + blockId;
        BufferedImage inactive = readPng(textureRoot + "_front.png");
        BufferedImage active = readPng(textureRoot + "_front_on.png");
        int changedPixels = 0;
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                if (inactive.getRGB(x, y) == active.getRGB(x, y)) {
                    continue;
                }
                changedPixels++;
                helper.assertTrue(isAllowedFrontDifference(blockId, x, y),
                        blockId + " 亮灭态只能在工作窗内改变, 越界坐标 (" + x + "," + y + ")");
            }
        }
        helper.assertTrue(changedPixels > 0,
                blockId + " 的 front 与 front_on 必须至少有一个工作窗像素不同");
    }

    private static boolean isAllowedFrontDifference(String blockId, int x, int y) {
        return switch (blockId) {
            case "coal_generator" -> within(x, 6, 9) && within(y, 7, 10);
            case "geothermal_generator" -> (within(x, 5, 6) || within(x, 9, 10))
                    && (within(y, 6, 7) || within(y, 9, 10));
            case "industrial_power_cell" -> within(x, 5, 10) && within(y, 5, 7);
            case "modern_power_cell" -> within(x, 6, 9) && within(y, 5, 8);
            case "future_power_cell" -> within(x, 5, 10) && within(y, 5, 10);
            default -> throw new IllegalArgumentException("unknown block asset id: " + blockId);
        };
    }

    private static boolean within(int value, int minimum, int maximum) {
        return value >= minimum && value <= maximum;
    }

    private static JsonObject readJson(String path) {
        try (InputStream input = PreheatGeneratorGameTests.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("missing asset: " + path);
            }
            return JsonParser.parseReader(new InputStreamReader(input, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (IOException exception) {
            throw new IllegalStateException("unreadable asset: " + path, exception);
        }
    }

    private static BufferedImage readPng(String path) {
        try (InputStream input = PreheatGeneratorGameTests.class.getResourceAsStream(path)) {
            if (input == null) {
                throw new IllegalStateException("missing texture: " + path);
            }
            BufferedImage image = ImageIO.read(input);
            if (image == null) {
                throw new IllegalStateException("undecodable texture: " + path);
            }
            return image;
        } catch (IOException exception) {
            throw new IllegalStateException("unreadable texture: " + path, exception);
        }
    }

    private static void assertRuntime(GameTestHelper helper, PreheatGeneratorSpec spec, int peak,
                                      double workingTemperature, int preheatTicks, double cooling,
                                      int burnMultiplier) {
        PreheatGeneratorSpec.Runtime runtime = spec.runtime();
        helper.assertTrue(runtime.peakFePerTick() == peak
                        && Math.abs(runtime.workingTemperatureC() - workingTemperature) < EPSILON
                        && runtime.preheatTicks() == preheatTicks
                        && Math.abs(runtime.coolingCPerTick() - cooling) < EPSILON
                        && runtime.fuelBurnMultiplier() == burnMultiplier,
                spec + " 运行档位必须与出厂默认一致, 得到 " + runtime);
        helper.assertTrue(runtime.equals(spec.defaults()),
                spec + " 未改配置时运行值必须与出厂默认逐字段相等");
    }

    private static BlockState coalState() {
        return PowerRegistry.COAL_GENERATOR.get().defaultBlockState()
                .setValue(PreheatGeneratorBlock.FACING, Direction.NORTH);
    }

    private static PreheatGeneratorBlockEntity placeCoal(GameTestHelper helper) {
        return place(helper, MACHINE_REL, PowerRegistry.COAL_GENERATOR.get());
    }

    private static PreheatGeneratorBlockEntity placeGeothermal(GameTestHelper helper) {
        return place(helper, MACHINE_REL, PowerRegistry.GEOTHERMAL_GENERATOR.get());
    }

    private static PreheatGeneratorBlockEntity placeGeothermalAt(GameTestHelper helper, BlockPos relative) {
        return place(helper, relative, PowerRegistry.GEOTHERMAL_GENERATOR.get());
    }

    private static PreheatGeneratorBlockEntity place(GameTestHelper helper, BlockPos relative, Block block) {
        helper.setBlock(relative, block.defaultBlockState()
                .setValue(PreheatGeneratorBlock.FACING, Direction.NORTH));
        if (helper.getBlockEntity(relative) instanceof PreheatGeneratorBlockEntity generator) {
            return generator;
        }
        throw new IllegalStateException("preheat generator block entity missing at " + relative);
    }

    private static IEnergyStorage energyOf(GameTestHelper helper, PreheatGeneratorBlockEntity generator) {
        return generator.getCapability(ForgeCapabilities.ENERGY)
                .orElseThrow(() -> new IllegalStateException("preheat generator exposes no energy capability"));
    }
}
