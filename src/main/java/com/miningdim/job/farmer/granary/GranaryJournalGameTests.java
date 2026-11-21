package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.List;
import java.util.UUID;

/**
 * 粮仓流水 (docs/Spoilage_Granary_DesignSpec.md「官仓」「官仓流水」) 的 GameTest。
 *
 * 锁四件事: 谁存谁取都要留下记录 (含公开模式那两种)、每座粮仓只留最近 300 条、随存档原样往返、
 * 界面的「流水」页读的就是它 (而不是自己另算一页)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryJournalGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_journal";

    /** 这一批结束后把账本清干净 (粮仓账本是全服共享的存档数据, 流水趴在它上面)。 */
    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    private static final int SLOT = 16;
    private static final int LOW = 1;
    private static final int HIGH = 3;
    private static final long NOW_HOUR = 3_000_000L;

    // ============================================================
    // 1. 存与取都留痕
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void depositAndTakeAreRecordedWithWhoAndHowMuch(GameTestHelper helper) {
        Fixture fixture = accept(helper, 1);
        ServerLevel level = helper.getLevel();
        GranaryBinBlockEntity bin = fixture.bin(helper);
        ServerPlayer owner = fixture.owner();
        long gid = fixture.gid(helper);

        owner.getInventory().setItem(9, new ItemStack(Items.WHEAT, 5));
        GranaryDeposit.depositAt(level, bin, owner, true, NOW_HOUR * 60L);
        List<GranaryJournal.Entry> afterDeposit = fixture.data(helper).journalOf(gid);
        helper.assertTrue(afterDeposit.size() == 1, "存一次留一条, 实得 " + afterDeposit.size());
        GranaryJournal.Entry stored = afterDeposit.get(0);
        helper.assertTrue(stored.kind() == GranaryJournal.Kind.STORE
                        && stored.actor().equals(owner.getUUID()) && stored.count() == 5,
                "流水要写清谁存了几件, 实得 " + stored);
        helper.assertTrue(stored.bin().equals(bin.getBlockPos()), "还要写清是哪个囤");
        helper.assertTrue(stored.itemKey().equals(Items.WHEAT.getDescriptionId()),
                "记物品而不是件数编号, 实得 " + stored.itemKey());

        // 取: 走真正的槽 (take 走 Slot.onTake, 与界面里那一下同一条路)。
        GranaryBinMenu menu = new GranaryBinMenu(0, owner.getInventory(), bin);
        Slot slot = menu.slots.get(0);
        ItemStack taken = slot.remove(2);
        slot.onTake(owner, taken);
        List<GranaryJournal.Entry> afterTake = fixture.data(helper).journalOf(gid);
        helper.assertTrue(afterTake.size() == 2, "取一次再留一条, 实得 " + afterTake.size());
        GranaryJournal.Entry took = afterTake.get(1);
        helper.assertTrue(took.kind() == GranaryJournal.Kind.TAKE && took.count() == 2,
                "取出也要留痕并写清件数, 实得 " + took);
        helper.succeed();
    }

    // ============================================================
    // 2. 公开模式那两种另有标记
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void publicDonationsAndTakesAreMarked(GameTestHelper helper) {
        Fixture fixture = accept(helper, 2);
        ServerLevel level = helper.getLevel();
        GranaryBinBlockEntity bin = fixture.bin(helper);
        fixture.becomeStateGranary(helper);
        GranaryPublicService.apply(level, bin, fixture.owner(), GranaryPublicService.Action.DONATE_ON, 2);
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        long gid = fixture.gid(helper);

        stranger.getInventory().setItem(9, new ItemStack(Items.BREAD, 3));
        GranaryDeposit.depositAt(level, bin, stranger, true, NOW_HOUR * 60L);
        List<GranaryJournal.Entry> entries = fixture.data(helper).journalOf(gid);
        helper.assertTrue(entries.size() == 1 && entries.get(0).kind() == GranaryJournal.Kind.DONATE,
                "公开捐粮要记成 DONATE (而不是普通存入), 实得 " + entries);

        GranaryPublicService.apply(level, bin, fixture.owner(), GranaryPublicService.Action.TAKE_ON, 1);
        GranaryBinMenu menu = new GranaryBinMenu(0, stranger.getInventory(), bin);
        Slot slot = menu.slots.get(0);
        helper.assertTrue(slot.mayPickup(stranger), "公开取用开着时陌生人能拿");
        ItemStack taken = slot.remove(1);
        slot.onTake(stranger, taken);
        entries = fixture.data(helper).journalOf(gid);
        helper.assertTrue(entries.size() == 2 && entries.get(1).kind() == GranaryJournal.Kind.PUBLIC_TAKE,
                "公开取用要记成 PUBLIC_TAKE, 实得 " + entries);
        helper.succeed();
    }

    // ============================================================
    // 3. 上限 300 条与存档往返
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void journalKeepsOnlyTheNewestThreeHundred(GameTestHelper helper) {
        Fixture fixture = accept(helper, 3);
        long gid = fixture.gid(helper);
        GranarySavedData data = fixture.data(helper);
        for (int i = 1; i <= GranaryJournal.MAX_ENTRIES + 5; i++) {
            data.appendJournal(gid, new GranaryJournal.Entry(i, fixture.owner().getUUID(),
                    GranaryJournal.Kind.STORE, fixture.level(helper).dimension().location(),
                    fixture.bin(helper).getBlockPos(), Items.WHEAT.getDescriptionId(), 1));
        }
        List<GranaryJournal.Entry> kept = data.journalOf(gid);
        helper.assertTrue(kept.size() == GranaryJournal.MAX_ENTRIES,
                "只留最近 " + GranaryJournal.MAX_ENTRIES + " 条, 实得 " + kept.size());
        helper.assertTrue(kept.get(0).hour() == 6L,
                "丢的是最旧的那几条 (先记 1..5 该被挤掉), 实得 " + kept.get(0).hour());

        GranarySavedData reloaded = GranarySavedData.load(data.save(new CompoundTag()));
        helper.assertTrue(reloaded.journalOf(gid).equals(kept), "流水随存档原样往返");
        helper.succeed();
    }

    // ============================================================
    // 4. 界面的「流水」页读的就是它
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void theLedgerPageShowsTheJournal(GameTestHelper helper) {
        Fixture fixture = accept(helper, 4);
        ServerLevel level = helper.getLevel();
        GranaryBinBlockEntity bin = fixture.bin(helper);
        ServerPlayer owner = fixture.owner();

        // 还没有记录时明说「还没有存取记录」。
        GranaryInspection.Report report = GranaryCenterView.reportAt(level, fixture.centerPos(helper));
        helper.assertTrue(report != null, "账台边上要能查出这座粮仓");
        List<String> empty = GranaryCenterView.lines(GranaryCenterPage.LEDGER, level, owner, report);
        helper.assertTrue(empty.size() == 1
                        && textOf(empty.get(0)).contains(GranaryCenterView.NO_LEDGER_KEY),
                "空流水要明说, 实得 " + empty);

        owner.getInventory().setItem(9, new ItemStack(Items.WHEAT, 4));
        GranaryDeposit.depositAt(level, bin, owner, true, NOW_HOUR * 60L);
        List<String> lines = GranaryCenterView.lines(GranaryCenterPage.LEDGER, level, owner, report);
        helper.assertTrue(lines.size() == 1, "一条流水一行, 实得 " + lines.size());
        String row = textOf(lines.get(0));
        helper.assertTrue(row.contains("granary.journal.store") && row.contains("item.minecraft.wheat"),
                "行里要有动作与物品, 实得 " + row);
        helper.assertTrue(row.contains("4"), "行里要有件数, 实得 " + row);
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    private record Fixture(ServerPlayer owner, BlockPos centerRelative) {

        ServerLevel level(GameTestHelper helper) {
            return helper.getLevel();
        }

        GranarySavedData data(GameTestHelper helper) {
            return GranarySavedData.get(helper.getLevel());
        }

        BlockPos centerPos(GameTestHelper helper) {
            return helper.absolutePos(centerRelative);
        }

        BlockPos binRelative() {
            return new BlockPos(centerRelative.getX() - 1, LOW, LOW);
        }

        GranaryBinBlockEntity bin(GameTestHelper helper) {
            return (GranaryBinBlockEntity) helper.getBlockEntity(binRelative());
        }

        long gid(GameTestHelper helper) {
            return data(helper).centerGranary(helper.getLevel().dimension().location(), centerPos(helper));
        }

        void becomeStateGranary(GameTestHelper helper) {
            ServerLevel level = helper.getLevel();
            GranarySavedData data = data(helper);
            long gid = gid(helper);
            data.putGranary(data.granary(gid).accepted(GranaryTier.STATE, owner.getUUID(), NOW_HOUR));
            data.setBinState(level.dimension().location(), helper.absolutePos(binRelative()),
                    GranaryBinState.ACTIVE, NOW_HOUR);
        }
    }

    private static Fixture accept(GameTestHelper helper, int slot) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(owner, 1);
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, binRel(slot));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        helper.assertTrue(GranaryInspection.accept(helper.getLevel(), helper.absolutePos(center(slot)), owner)
                .accepted(), "夹具: 先验收成粮棚");
        return new Fixture(owner, center(slot));
    }

    private static void placeCenter(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        GranarySystem.releaseCenter(level, pos);
        helper.setBlock(relative, GranaryBlocks.CENTER.get());
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                BlockSnapshot.create(level.dimension(), level, pos), Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "账台应当放得下");
        GranaryBlocks.CENTER.get().setPlacedBy(level, pos, level.getBlockState(pos), player, ItemStack.EMPTY);
    }

    private static void placeBin(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        GranarySavedData.get(level).releaseBin(level.dimension().location(), pos);
        helper.setBlock(relative, GranaryBlocks.BIN.get());
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                BlockSnapshot.create(level.dimension(), level, pos), Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "储粮囤应当放得下");
        GranaryBlocks.BIN.get().setPlacedBy(level, pos, level.getBlockState(pos), player, ItemStack.EMPTY);
    }

    private static BlockPos center(int slot) {
        return new BlockPos(slot * SLOT + 2, LOW, 2);
    }

    private static BlockPos binRel(int slot) {
        return new BlockPos(slot * SLOT + 1, LOW, 1);
    }

    private static BlockPos vent(int slot) {
        return new BlockPos(slot * SLOT + 3, HIGH, 3);
    }

    private static void buildRoom(GameTestHelper helper, int slot, int width) {
        int base = slot * SLOT;
        for (int x = 0; x <= width + 1; x++) {
            for (int y = 0; y <= HIGH + 1; y++) {
                for (int z = 0; z <= width + 1; z++) {
                    boolean shell = x == 0 || x == width + 1 || y == 0 || y == HIGH + 1 || z == 0 || z == width + 1;
                    BlockState state = shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    helper.setBlock(new BlockPos(base + x, y, z), state);
                }
            }
        }
    }

    private static void setFarmerLevel(ServerPlayer player, int level) {
        com.miningdim.entry.MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家未挂载 capability"))
                .jobProgress(JobId.FARMER)
                .setLevel(level);
    }

    private static String textOf(String json) {
        Component component = Component.Serializer.fromJson(json);
        return component == null ? "" : component.toString();
    }
}
