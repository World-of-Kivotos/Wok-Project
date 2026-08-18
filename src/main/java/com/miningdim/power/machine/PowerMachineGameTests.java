package com.miningdim.power.machine;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerMachineConfig;
import com.miningdim.power.PowerMachineRegistry;
import com.miningdim.power.mineral.PowerMineral;
import com.miningdim.power.mineral.PowerMineralRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PowerMachineGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_machine_runtime";

    private PowerMachineGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void fivePurifyingBranchesKeepExactInputsTimeEnergyAndOutputs(GameTestHelper helper) {
        Item borax = PowerMineralRegistry.rawMaterial(PowerMineral.BORAX).get();
        List<PurifyingCase> cases = List.of(
                new PurifyingCase(PurifyingProfile.COPPER_DEOXIDIZING, Items.COPPER_INGOT, borax, 20,
                        PowerMachineRegistry.DEOXIDIZED_COPPER_INGOT.get(), 200, 20, 20, 1),
                new PurifyingCase(PurifyingProfile.OFC_COPPER, PowerMachineRegistry.DEOXIDIZED_COPPER_INGOT.get(),
                        borax, 40, PowerMachineRegistry.OFC_COPPER_INGOT.get(), 400, 40, 40, 1),
                new PurifyingCase(PurifyingProfile.PHOSPHORUS_DEOXIDIZING,
                        PowerMachineRegistry.DEOXIDIZED_COPPER_INGOT.get(), Items.BONE_MEAL, 20,
                        PowerMachineRegistry.PHOSPHORUS_DEOXIDIZED_COPPER_INGOT.get(), 200, 20, 20, 1),
                new PurifyingCase(PurifyingProfile.OFE_COPPER, PowerMachineRegistry.OFC_COPPER_INGOT.get(),
                        PowerMachineRegistry.ARGON_CANISTER.get(), 1, PowerMachineRegistry.OFE_COPPER_INGOT.get(),
                        800, 128, 100, 100),
                new PurifyingCase(PurifyingProfile.GOLD_4N, Items.GOLD_INGOT,
                        PowerMachineRegistry.ARGON_CANISTER.get(), 1, PowerMachineRegistry.GOLD_4N_INGOT.get(),
                        600, 64, 100, 100));

        BlockPos relative = new BlockPos(3, 1, 3);
        for (int index = 0; index < cases.size(); index++) {
            PurifyingCase test = cases.get(index);
            assertPurifyingRuntime(helper, test);
            helper.setBlock(relative, Blocks.AIR);
            MetallurgicPurifierBlockEntity purifier = placePurifier(helper, relative);
            purifier.inventory().setStackInSlot(MetallurgicPurifierBlockEntity.SLOT_BASE,
                    new ItemStack(test.base()));
            purifier.inventory().setStackInSlot(MetallurgicPurifierBlockEntity.SLOT_INFUSION,
                    new ItemStack(test.infusion(), test.infusionItemCount()));
            int totalInjected = runPurifier(helper, purifier, test.durationTicks(), test.fePerTick());

            ItemStack output = purifier.inventory().getStackInSlot(MetallurgicPurifierBlockEntity.SLOT_OUTPUT);
            helper.assertTrue(output.is(test.result()) && output.getCount() == 1
                            && purifier.inventory().getStackInSlot(MetallurgicPurifierBlockEntity.SLOT_BASE).isEmpty()
                            && purifier.inventory().getStackInSlot(MetallurgicPurifierBlockEntity.SLOT_INFUSION).isEmpty()
                            && purifier.infusionUnits() == 0 && purifier.progress() == 0
                            && !purifier.getBlockState().getValue(PowerMachineBlock.LIT)
                            && purifier.storedFe() == 0 && totalInjected == test.durationTicks() * test.fePerTick(),
                    test.profile() + " 必须精确耗尽基材、灌注、时间与 FE 并只产出目标物，实际输出=" + output);
            for (int slot = MetallurgicPurifierBlockEntity.SLOT_BASE;
                 slot < MetallurgicPurifierBlockEntity.SLOT_COUNT; slot++) {
                purifier.inventory().setStackInSlot(slot, ItemStack.EMPTY);
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void purifierLocksInfusionAndBlockedOutputCannotAbsorbOrConsume(GameTestHelper helper) {
        Item borax = PowerMineralRegistry.rawMaterial(PowerMineral.BORAX).get();
        MetallurgicPurifierBlockEntity purifier = placePurifier(helper, new BlockPos(3, 1, 3));
        IEnergyStorage energy = requireEnergy(purifier);
        helper.assertTrue(energy.canReceive() && !energy.canExtract() && energy.extractEnergy(1, false) == 0,
                "提纯机 ENERGY 必须只收不放");
        purifier.inventory().setStackInSlot(MetallurgicPurifierBlockEntity.SLOT_BASE,
                new ItemStack(Items.COPPER_INGOT));
        purifier.inventory().setStackInSlot(MetallurgicPurifierBlockEntity.SLOT_INFUSION,
                new ItemStack(borax, 20));
        purifier.inventory().setStackInSlot(MetallurgicPurifierBlockEntity.SLOT_OUTPUT,
                new ItemStack(Items.COBBLESTONE, Items.COBBLESTONE.getMaxStackSize()));
        helper.assertTrue(energy.receiveEnergy(20, false) == 20, "堵塞前注入的 20 FE 必须进入机器缓冲");
        for (int tick = 0; tick < 10; tick++) {
            purifier.serverTick();
        }
        helper.assertTrue(purifier.storedFe() == 20 && purifier.progress() == 0 && purifier.infusionUnits() == 0
                        && purifier.inventory().getStackInSlot(MetallurgicPurifierBlockEntity.SLOT_BASE).getCount() == 1
                        && purifier.inventory().getStackInSlot(MetallurgicPurifierBlockEntity.SLOT_INFUSION).getCount() == 20,
                "输出槽堵塞时提纯机不得吸收灌注、推进进度、扣 FE 或吞基材");

        purifier.inventory().setStackInSlot(MetallurgicPurifierBlockEntity.SLOT_OUTPUT, ItemStack.EMPTY);
        purifier.serverTick();
        ItemStack rejected = purifier.inventory().insertItem(MetallurgicPurifierBlockEntity.SLOT_INFUSION,
                new ItemStack(Items.BONE_MEAL, 20), false);
        helper.assertTrue(purifier.progress() == 1 && purifier.infusionUnits() == 20
                        && rejected.is(Items.BONE_MEAL) && rejected.getCount() == 20,
                "已锁定硼砂灌注后必须完整拒绝磷源，避免脱氧铜两条分支被错误混配");

        helper.assertTrue(energy.receiveEnergy(100_000, false) == 100_000,
                "提纯机同步测试必须预置跨 short 边界的 FE");
        int purifierStored = MetallurgicPurifierMenu.joinInt32(
                (short) purifier.data().get(MetallurgicPurifierBlockEntity.DATA_STORED_FE_LOW),
                (short) purifier.data().get(MetallurgicPurifierBlockEntity.DATA_STORED_FE_HIGH));
        int purifierCapacity = MetallurgicPurifierMenu.joinInt32(
                (short) purifier.data().get(MetallurgicPurifierBlockEntity.DATA_ENERGY_CAPACITY_LOW),
                (short) purifier.data().get(MetallurgicPurifierBlockEntity.DATA_ENERGY_CAPACITY_HIGH));
        AirSeparationUnitBlockEntity syncSeparator = new AirSeparationUnitBlockEntity(BlockPos.ZERO,
                PowerMachineRegistry.AIR_SEPARATOR_BLOCK.get().defaultBlockState());
        IEnergyStorage separatorEnergy = requireEnergy(syncSeparator);
        helper.assertTrue(separatorEnergy.receiveEnergy(100_000, false) == 100_000,
                "空分机同步测试必须预置跨 short 边界的 FE");
        int separatorStored = AirSeparationMenu.joinInt32(
                (short) syncSeparator.data().get(AirSeparationUnitBlockEntity.DATA_STORED_FE_LOW),
                (short) syncSeparator.data().get(AirSeparationUnitBlockEntity.DATA_STORED_FE_HIGH));
        int separatorCapacity = AirSeparationMenu.joinInt32(
                (short) syncSeparator.data().get(AirSeparationUnitBlockEntity.DATA_ENERGY_CAPACITY_LOW),
                (short) syncSeparator.data().get(AirSeparationUnitBlockEntity.DATA_ENERGY_CAPACITY_HIGH));
        helper.assertTrue(purifierStored == 100_000 && purifierCapacity == 102_400
                        && separatorStored == 100_000 && separatorCapacity == 614_400,
                "客户端 short 数据包重组必须保留两台机器的跨字 FE 与完整容量");

        int[] configuredCapacity = {100};
        MachineEnergyStorage shrinking = new MachineEnergyStorage(() -> configuredCapacity[0], () -> { });
        helper.assertTrue(shrinking.receiveEnergy(100, false) == 100,
                "动态容量测试必须先填满原始 100 FE 容量");
        configuredCapacity[0] = 50;
        helper.assertTrue(shrinking.receiveEnergy(1, false) == 0 && shrinking.getEnergyStored() == 100
                        && shrinking.getMaxEnergyStored() == 100,
                "配置热缩后既有 FE 不得被截断，且不得继续接收");
        shrinking.consume(60);
        CompoundTag savedEnergy = new CompoundTag();
        shrinking.save(savedEnergy);
        MachineEnergyStorage reloadedEnergy = new MachineEnergyStorage(() -> configuredCapacity[0], () -> { });
        reloadedEnergy.load(savedEnergy);
        helper.assertTrue(shrinking.getEnergyStored() == 40 && shrinking.getMaxEnergyStored() == 50
                        && reloadedEnergy.getEnergyStored() == 40 && reloadedEnergy.getMaxEnergyStored() == 50,
                "容量热缩后消费至新上限内必须正常落盘并以 40 FE 重载");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void purifierPausesWithoutFePersistsAndDoesNotSwallowRemovedBase(GameTestHelper helper) {
        Item borax = PowerMineralRegistry.rawMaterial(PowerMineral.BORAX).get();
        BlockPos relative = new BlockPos(3, 1, 3);
        MetallurgicPurifierBlockEntity purifier = placePurifier(helper, relative);
        purifier.inventory().setStackInSlot(MetallurgicPurifierBlockEntity.SLOT_BASE,
                new ItemStack(Items.COPPER_INGOT));
        purifier.inventory().setStackInSlot(MetallurgicPurifierBlockEntity.SLOT_INFUSION,
                new ItemStack(borax, 20));
        int injected = runPurifier(helper, purifier, 50, 20);
        for (int tick = 0; tick < 20; tick++) {
            purifier.serverTick();
        }
        helper.assertTrue(purifier.progress() == 50 && purifier.storedFe() == 0 && purifier.infusionUnits() == 20,
                "无 FE 时进行中的提纯必须暂停且保持进度与灌注缓冲");

        CompoundTag saved = purifier.saveWithoutMetadata();
        MetallurgicPurifierBlockEntity reloaded = rebuildPurifier(helper, relative, saved);
        helper.assertTrue(reloaded.progress() == 50 && reloaded.storedFe() == 0 && reloaded.infusionUnits() == 20
                        && reloaded.activeRecipeId() != null,
                "提纯机 NBT 重载不得丢失活动配方、进度、FE 或灌注缓冲");
        injected += runPurifier(helper, reloaded, 1, 20);
        ItemStack removed = reloaded.inventory().extractItem(MetallurgicPurifierBlockEntity.SLOT_BASE, 1, false);
        helper.assertTrue(removed.is(Items.COPPER_INGOT) && removed.getCount() == 1,
                "进行中的基材必须能被玩家取回，不得在输入槽中消失");
        IEnergyStorage energy = requireEnergy(reloaded);
        helper.assertTrue(energy.receiveEnergy(20, false) == 20, "移除基材后仍可观察到未消费的注入 FE");
        injected += 20;
        reloaded.serverTick();
        helper.assertTrue(reloaded.progress() == 51 && reloaded.storedFe() == 20
                        && reloaded.inventory().getStackInSlot(MetallurgicPurifierBlockEntity.SLOT_OUTPUT).isEmpty()
                        && injected - reloaded.storedFe() == 51 * 20,
                "移除基材不得生成或吞掉物品；先前已耗 FE 不返还，之后也不得继续扣 FE");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void airSeparatorRejectsRunningModeChangeAndMakesExactArgon(GameTestHelper helper) {
        AirSeparatingRuntime runtime = PowerMachineConfig.airSeparating(AirSeparationMode.ARGON);
        helper.assertTrue(runtime.durationTicks() == 1_200 && runtime.fePerTick() == 512,
                "氩气默认必须保持 1200 tick 与 512 FE/t");
        AirSeparationUnitBlockEntity separator = placeSeparator(helper, new BlockPos(3, 1, 3));
        IEnergyStorage energy = requireEnergy(separator);
        helper.assertTrue(energy.canReceive() && !energy.canExtract() && energy.extractEnergy(1, false) == 0,
                "空分机 ENERGY 必须只收不放");
        int injected = runSeparator(helper, separator, 1, runtime.fePerTick());
        helper.assertTrue(!separator.setMode(AirSeparationMode.LIQUID_NITROGEN)
                        && separator.mode() == AirSeparationMode.ARGON && separator.progress() == 1,
                "空分工序一旦开始，菜单不得把氩气切换为液氮");
        injected += runSeparator(helper, separator, runtime.durationTicks() - 1, runtime.fePerTick());
        ItemStack output = separator.inventory().getStackInSlot(AirSeparationUnitBlockEntity.SLOT_OUTPUT);
        helper.assertTrue(output.is(PowerMachineRegistry.ARGON_CANISTER.get()) && output.getCount() == 1
                        && separator.progress() == 0 && separator.storedFe() == 0
                        && !separator.getBlockState().getValue(PowerMachineBlock.LIT)
                        && injected == 614_400,
                "氩气工序必须只在 1200 tick 后产出一罐并精确消耗 614400 FE");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void airSeparatorSwitchesAtZeroAndMakesExactLiquidNitrogenAfterNbtReload(GameTestHelper helper) {
        AirSeparatingRuntime runtime = PowerMachineConfig.airSeparating(AirSeparationMode.LIQUID_NITROGEN);
        helper.assertTrue(runtime.durationTicks() == 400 && runtime.fePerTick() == 256,
                "液氮默认必须保持 400 tick 与 256 FE/t");
        BlockPos relative = new BlockPos(3, 1, 3);
        AirSeparationUnitBlockEntity separator = placeSeparator(helper, relative);
        helper.assertTrue(separator.setMode(AirSeparationMode.LIQUID_NITROGEN)
                        && separator.mode() == AirSeparationMode.LIQUID_NITROGEN,
                "零进度空分机必须允许切换至液氮模式");
        int injected = runSeparator(helper, separator, 200, runtime.fePerTick());
        CompoundTag saved = separator.saveWithoutMetadata();
        AirSeparationUnitBlockEntity reloaded = rebuildSeparator(helper, relative, saved);
        helper.assertTrue(reloaded.mode() == AirSeparationMode.LIQUID_NITROGEN && reloaded.progress() == 200
                        && reloaded.storedFe() == 0,
                "空分机 NBT 重载必须保持液氮模式与中途进度");
        injected += runSeparator(helper, reloaded, 200, runtime.fePerTick());
        ItemStack output = reloaded.inventory().getStackInSlot(AirSeparationUnitBlockEntity.SLOT_OUTPUT);
        helper.assertTrue(output.is(PowerMachineRegistry.LIQUID_NITROGEN_CANISTER.get()) && output.getCount() == 1
                        && !output.is(PowerMachineRegistry.ARGON_CANISTER.get()) && reloaded.progress() == 0
                        && !reloaded.getBlockState().getValue(PowerMachineBlock.LIT)
                        && reloaded.storedFe() == 0 && injected == 102_400,
                "液氮工序必须只在 400 tick 后产出一罐并精确消耗 102400 FE");
        helper.succeed();
    }

    private static void assertPurifyingRuntime(GameTestHelper helper, PurifyingCase test) {
        PurifyingRuntime runtime = PowerMachineConfig.purifying(test.profile());
        helper.assertTrue(runtime.durationTicks() == test.durationTicks() && runtime.fePerTick() == test.fePerTick()
                        && runtime.infusionUnits() == test.infusionUnits()
                        && runtime.infusionUnitsPerItem() == test.infusionUnitsPerItem(),
                test.profile() + " 默认运行配置发生漂移");
    }

    private static int runPurifier(GameTestHelper helper, MetallurgicPurifierBlockEntity purifier,
                                   int ticks, int fePerTick) {
        IEnergyStorage energy = requireEnergy(purifier);
        for (int tick = 0; tick < ticks; tick++) {
            helper.assertTrue(energy.receiveEnergy(fePerTick, false) == fePerTick,
                    "提纯机每 tick 必须接收恰好 " + fePerTick + " FE");
            purifier.serverTick();
        }
        return ticks * fePerTick;
    }

    private static int runSeparator(GameTestHelper helper, AirSeparationUnitBlockEntity separator,
                                    int ticks, int fePerTick) {
        IEnergyStorage energy = requireEnergy(separator);
        for (int tick = 0; tick < ticks; tick++) {
            helper.assertTrue(energy.receiveEnergy(fePerTick, false) == fePerTick,
                    "空分机每 tick 必须接收恰好 " + fePerTick + " FE");
            separator.serverTick();
        }
        return ticks * fePerTick;
    }

    private static MetallurgicPurifierBlockEntity placePurifier(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, PowerMachineRegistry.PURIFIER_BLOCK.get());
        return requirePurifier(helper, relative);
    }

    private static MetallurgicPurifierBlockEntity rebuildPurifier(GameTestHelper helper, BlockPos relative,
                                                                   CompoundTag saved) {
        helper.setBlock(relative, Blocks.AIR);
        helper.setBlock(relative, PowerMachineRegistry.PURIFIER_BLOCK.get());
        MetallurgicPurifierBlockEntity reloaded = requirePurifier(helper, relative);
        reloaded.load(saved);
        return reloaded;
    }

    private static AirSeparationUnitBlockEntity placeSeparator(GameTestHelper helper, BlockPos relative) {
        helper.setBlock(relative, PowerMachineRegistry.AIR_SEPARATOR_BLOCK.get());
        return requireSeparator(helper, relative);
    }

    private static AirSeparationUnitBlockEntity rebuildSeparator(GameTestHelper helper, BlockPos relative,
                                                                  CompoundTag saved) {
        helper.setBlock(relative, Blocks.AIR);
        helper.setBlock(relative, PowerMachineRegistry.AIR_SEPARATOR_BLOCK.get());
        AirSeparationUnitBlockEntity reloaded = requireSeparator(helper, relative);
        reloaded.load(saved);
        return reloaded;
    }

    private static MetallurgicPurifierBlockEntity requirePurifier(GameTestHelper helper, BlockPos relative) {
        BlockPos absolute = helper.absolutePos(relative);
        if (helper.getLevel().getBlockEntity(absolute) instanceof MetallurgicPurifierBlockEntity purifier) {
            return purifier;
        }
        throw new IllegalStateException("missing purifier block entity at " + absolute);
    }

    private static AirSeparationUnitBlockEntity requireSeparator(GameTestHelper helper, BlockPos relative) {
        BlockPos absolute = helper.absolutePos(relative);
        if (helper.getLevel().getBlockEntity(absolute) instanceof AirSeparationUnitBlockEntity separator) {
            return separator;
        }
        throw new IllegalStateException("missing air separator block entity at " + absolute);
    }

    private static IEnergyStorage requireEnergy(MetallurgicPurifierBlockEntity purifier) {
        return purifier.getCapability(ForgeCapabilities.ENERGY, Direction.NORTH).resolve()
                .orElseThrow(() -> new IllegalStateException("missing purifier ENERGY capability"));
    }

    private static IEnergyStorage requireEnergy(AirSeparationUnitBlockEntity separator) {
        return separator.getCapability(ForgeCapabilities.ENERGY, Direction.NORTH).resolve()
                .orElseThrow(() -> new IllegalStateException("missing air separator ENERGY capability"));
    }

    private record PurifyingCase(PurifyingProfile profile, Item base, Item infusion, int infusionItemCount,
                                 Item result, int durationTicks, int fePerTick, int infusionUnits,
                                 int infusionUnitsPerItem) {
    }
}
