package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.job.farmer.granary.block.GranaryCenterBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryWoodVariantGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_wood_variants";
    private static final int SLOT = 64;

    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everyWoodVariantHasBothEntityTypesAndKeepsInventoryOnSave(GameTestHelper helper) {
        for (int index = 0; index < GranaryBlocks.WOOD_VARIANTS.size(); index++) {
            GranaryBlocks.WoodVariant variant = GranaryBlocks.WOOD_VARIANTS.get(index);
            BlockPos binPos = new BlockPos(index * 3, 1, 32);
            BlockPos centerPos = new BlockPos(index * 3, 1, 34);
            helper.setBlock(binPos, variant.bin().get());
            helper.setBlock(centerPos, variant.center().get());

            helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(binPos))
                    instanceof GranaryBinBlockEntity, variant.wood() + " 储粮囤创建了方块实体");
            helper.assertTrue(helper.getLevel().getBlockEntity(helper.absolutePos(centerPos))
                    instanceof GranaryCenterBlockEntity, variant.wood() + " 账台创建了方块实体");
            GranaryBinBlockEntity bin = (GranaryBinBlockEntity) helper.getLevel()
                    .getBlockEntity(helper.absolutePos(binPos));
            bin.setItem(0, new ItemStack(Items.WHEAT, index + 1));
            CompoundTag saved = bin.saveWithoutMetadata();
            GranaryBinBlockEntity reloaded = new GranaryBinBlockEntity(bin.getBlockPos(), bin.getBlockState());
            reloaded.load(saved);
            helper.assertTrue(reloaded.getItem(0).is(Items.WHEAT)
                            && reloaded.getItem(0).getCount() == index + 1,
                    variant.wood() + " 方块实体库存存读一致");
        }
        helper.assertTrue(GranaryBlocks.WOOD_VARIANTS.size() == 11, "登记了 11 种木材");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mixedWoodBinsAreCountedAsOneGranaryStructure(GameTestHelper helper) {
        int slot = 0;
        buildRoom(helper, slot);
        List<GranaryBlocks.WoodVariant> woods = GranaryBlocks.WOOD_VARIANTS;
        helper.setBlock(center(slot), woods.get(0).center().get());
        helper.setBlock(bin(slot, LOW, LOW), woods.get(0).bin().get());
        helper.setBlock(bin(slot, HIGH, HIGH), woods.get(2).bin().get());
        helper.setBlock(vent(slot), woods.get(2).vent().get());

        GranaryStructureScanner.ScanResult result = GranaryStructureScanner.scan(helper.getLevel(),
                helper.absolutePos(center(slot)));
        helper.assertTrue(result.ok(), "混合木材的小屋结构成立: " + result.failure());
        helper.assertTrue(result.bins() == 2 && result.binPositions().size() == 2,
                "橡木与白桦囤都计入结构, 实际 " + result.bins());
        helper.assertTrue(result.vents() == 1, "白桦气窗照常计数");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void packedWoodVariantKeepsItsItemAndQuotaAccounting(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GranarySavedData data = GranarySavedData.get(level);
        GranaryBlocks.WoodVariant variant = GranaryBlocks.WOOD_VARIANTS.get(10);
        BlockPos relative = new BlockPos(65, 1, 1);
        BlockPos pos = helper.absolutePos(relative);
        helper.setBlock(relative, variant.bin().get());
        UUID owner = UUID.randomUUID();
        long gid = data.nextGranaryId();
        helper.assertTrue(data.claimBin(level.dimension().location(), pos, owner, data.nextSeq()), "登记囤主额度");
        data.linkBin(level.dimension().location(), pos, gid);
        GranaryBinBlockEntity bin = (GranaryBinBlockEntity) level.getBlockEntity(pos);
        bin.setItem(0, new ItemStack(Items.WHEAT, 7));

        helper.assertTrue(data.binCount(owner) == 1, "打包前占用一个囤额度");
        GranaryTransfer.takeDown(level, pos, GranaryPending.Reason.LEFT, GranaryClock.nowHour(level));

        List<GranaryPending.Entry> pending = data.pendingOf(owner);
        helper.assertTrue(pending.size() == 1, "打包后有一条待领记录");
        GranaryPending.Entry entry = pending.get(0);
        helper.assertTrue(entry.count() == 8, "七份小麦加一个囤, 数量仍是 8: " + entry.count());
        helper.assertTrue(entry.items().stream().anyMatch(stack -> stack.is(Items.WHEAT) && stack.getCount() == 7),
                "库存数量原样进入待领粮单");
        helper.assertTrue(entry.items().stream().anyMatch(stack -> stack.is(variant.binItem().get())),
                "待领粮单保留 warped 木材囤物品");
        helper.assertTrue(data.binCount(owner) == 0, "打包移除方块后归还一个额度");
        helper.assertTrue(level.getBlockState(pos).isAir(), "打包方块已移除");
        data.setPending(owner, List.of());
        helper.succeed();
    }

    private static final int LOW = 1;
    private static final int HIGH = 3;

    private static BlockPos center(int slot) {
        return new BlockPos(slot * SLOT + 2, 2, 2);
    }

    private static BlockPos vent(int slot) {
        return new BlockPos(slot * SLOT + LOW - 1, 2, 2);
    }

    private static BlockPos bin(int slot, int x, int z) {
        return new BlockPos(slot * SLOT + x, LOW, z);
    }

    private static void buildRoom(GameTestHelper helper, int slot) {
        int base = slot * SLOT;
        for (int x = LOW - 1; x <= HIGH + 1; x++) {
            for (int y = LOW - 1; y <= HIGH + 1; y++) {
                for (int z = LOW - 1; z <= HIGH + 1; z++) {
                    boolean shell = x == LOW - 1 || x == HIGH + 1 || y == LOW - 1 || y == HIGH + 1
                            || z == LOW - 1 || z == HIGH + 1;
                    BlockState state = shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    helper.setBlock(new BlockPos(base + x, y, z), state);
                }
            }
        }
    }
}
