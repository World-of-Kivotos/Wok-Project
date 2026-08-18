package com.miningdim.power.endgame;

import com.miningdim.core.MiningConstants;
import com.miningdim.power.PowerMachineRegistry;
import com.miningdim.power.PowerRegistry;
import com.miningdim.power.grid.EnergyNetworkManager;
import com.miningdim.power.grid.EnergyNetworkSnapshot;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.capabilities.ForgeCapabilities;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class LowTemperatureControllerGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "power_endgame_controller";

    private LowTemperatureControllerGameTests() {
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH,
            timeoutTicks = LowTemperatureControllerBlockEntity.COOLING_TICKS_PER_CANISTER * 2 + 20)
    public static void twoCanistersRunConsecutiveCoolingWindowsAndPersist(GameTestHelper helper) {
        BlockPos relative = new BlockPos(3, 1, 3);
        BlockPos absolute = helper.absolutePos(relative);
        BlockState state = PowerRegistry.LOW_TEMPERATURE_CONTROLLER.get().defaultBlockState();
        helper.setBlock(relative, state);
        helper.setBlock(relative.south(), PowerRegistry.CABLES.get(
                com.miningdim.power.cable.ConductorMaterial.NBTI_SUPERCONDUCTOR).get());
        EnergyNetworkManager manager = EnergyNetworkManager.get(helper.getLevel());
        BlockPos cableAbsolute = absolute.south();
        LowTemperatureControllerBlockEntity controller = (LowTemperatureControllerBlockEntity)
                helper.getLevel().getBlockEntity(absolute);
        helper.assertTrue(controller != null, "低温控制器必须创建真实方块实体");
        helper.assertTrue(controller.getCapability(ForgeCapabilities.ENERGY).resolve().isEmpty(),
                "低温控制器不得暴露 ENERGY 能力");

        controller.inventory().setStackInSlot(LowTemperatureControllerBlockEntity.SLOT_LIQUID_NITROGEN,
                new ItemStack(PowerMachineRegistry.LIQUID_NITROGEN_CANISTER.get(), 2));
        int activeTicks = 0;
        controller.serverTick();
        if (controller.isCoolingActive()
                && manager.snapshotAt(cableAbsolute).orElseThrow().coolingState()
                == EnergyNetworkSnapshot.CoolingState.ACTIVE) {
            activeTicks++;
        }
        helper.assertTrue(controller.inventory().getStackInSlot(
                        LowTemperatureControllerBlockEntity.SLOT_LIQUID_NITROGEN).getCount() == 1
                        && controller.remainingTicks() == 24_000
                        && controller.activeCoverageSegments() == 64
                        && helper.getBlockState(relative).getValue(LowTemperatureControllerBlock.LIT),
                "首个服务端 tick 必须消耗一罐液氮并留下 24000 tick、64 段覆盖且点亮");

        CompoundTag saved = controller.saveWithoutMetadata();
        LowTemperatureControllerBlockEntity reloaded = new LowTemperatureControllerBlockEntity(absolute, state);
        reloaded.load(saved);
        helper.assertTrue(reloaded.remainingTicks() == 24_000 && reloaded.activeCoverageSegments() == 64,
                "NBT 重载必须保持液氮剩余时间和 64 段覆盖");

        for (int tick = 0; tick < 23_999; tick++) {
            controller.serverTick();
            if (controller.isCoolingActive()
                    && manager.snapshotAt(cableAbsolute).orElseThrow().coolingState()
                    == EnergyNetworkSnapshot.CoolingState.ACTIVE) {
                activeTicks++;
            }
        }
        helper.assertTrue(controller.remainingTicks() == 1 && activeTicks == 24_000,
                "第一罐必须连续产生恰好24000个网络可观测活跃 tick");

        for (int tick = 0; tick < 24_001; tick++) {
            controller.serverTick();
            if (controller.isCoolingActive()
                    && manager.snapshotAt(cableAbsolute).orElseThrow().coolingState()
                    == EnergyNetworkSnapshot.CoolingState.ACTIVE) {
                activeTicks++;
            }
        }
        helper.assertTrue(controller.remainingTicks() == 0
                        && controller.activeCoverageSegments() == 0
                        && activeTicks == 48_000
                        && !helper.getBlockState(relative).getValue(LowTemperatureControllerBlock.LIT),
                "两罐连续交界不得出现空窗，累计必须恰好48000个活跃 tick后归零并熄灭");

        CompoundTag invalid = controller.saveWithoutMetadata();
        CompoundTag invalidItems = invalid.getCompound("items");
        ListTag invalidEntries = new ListTag();
        CompoundTag invalidEntry = new CompoundTag();
        invalidEntry.putInt("Slot", 0);
        invalidEntry.putString("id", "minecraft:stone");
        invalidEntry.putByte("Count", (byte) 1);
        invalidEntries.add(invalidEntry);
        invalidItems.put("Items", invalidEntries);
        invalid.put("items", invalidItems);
        boolean invalidInventoryRejected = false;
        try {
            LowTemperatureControllerBlockEntity invalidReload =
                    new LowTemperatureControllerBlockEntity(absolute, state);
            invalidReload.load(invalid);
        } catch (IllegalStateException expected) {
            invalidInventoryRejected = true;
        }
        helper.assertTrue(invalidInventoryRejected,
                "NBT 重载遇到非液氮库存物品时必须在边界拒绝");

        CompoundTag hiddenSlot = controller.saveWithoutMetadata();
        CompoundTag hiddenItems = hiddenSlot.getCompound("items");
        hiddenItems.putInt("Size", 2);
        ListTag hiddenEntries = new ListTag();
        CompoundTag hiddenEntry = new CompoundTag();
        hiddenEntry.putInt("Slot", 1);
        hiddenEntry.putString("id", "minecraft:stone");
        hiddenEntry.putByte("Count", (byte) 1);
        hiddenEntries.add(hiddenEntry);
        hiddenItems.put("Items", hiddenEntries);
        hiddenSlot.put("items", hiddenItems);
        boolean hiddenSlotRejected = false;
        try {
            LowTemperatureControllerBlockEntity hiddenSlotReload =
                    new LowTemperatureControllerBlockEntity(absolute, state);
            hiddenSlotReload.load(hiddenSlot);
        } catch (IllegalStateException expected) {
            hiddenSlotRejected = true;
        }
        helper.assertTrue(hiddenSlotRejected,
                "NBT 重载必须拒绝伪造的槽位数量与隐藏槽物品");
        controller.setRemoved();
        helper.setBlock(relative.south(), net.minecraft.world.level.block.Blocks.AIR);
        helper.succeed();
    }
}
