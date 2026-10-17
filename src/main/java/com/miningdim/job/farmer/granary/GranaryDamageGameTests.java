package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.freshness.FreshnessData;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.granary.GranaryStructureScanner.Failure;
import com.miningdim.job.farmer.granary.GranaryStructureScanner.ScanResult;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 损坏与宽限的 GameTest (docs/Spoilage_Granary_DesignSpec.md「损坏与宽限」)。
 *
 * 三组数字与一条顺序: 宽限取「出事起 7 天」与「出事后首次上线起 3 天」较晚的那个、最长 28 天;
 * 宽限内照常停表、过宽限照常腐烂; 过宽限之后才修好的要重新存满 24 小时。另外钉住「结算先于切换」:
 * 过期那一下是结算到**宽限截止那一刻**, 不是结算到「发现的那一刻」。
 *
 * 时刻由用例显式传给 {@code GranaryInspection.inspect(level, pos, nowHour)}, 于是不必去推世界里的挂钟。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryDamageGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_damage";

    /** 这一批结束后把账本清干净 (粮仓账本是全服共享的存档数据, 留着会干扰后面的批次与下一轮的自查)。 */
    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    /** 每条用例占自己的一条槽位 (同一批次的用例并行跑且共用区域)。 */
    private static final int SLOT = 16;

    private static final int LOW = 1;
    private static final int HIGH = 3;

    /** 故意挑一个大的出事时刻, 免得和「入仓时刻 0」撞上。 */
    private static final long DAMAGED_AT = 1_000_000L;
    private static final long SUSPENDED_AT = 2_000_000L;
    private static final long DAY = 24L;

    // ============================================================
    // 宽限算术 (纯逻辑)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void graceIsTheLaterOfSevenDaysAndThreeDaysAfterLogin(GameTestHelper helper) {
        // 没上线过: 出事起 7 天。
        helper.assertTrue(GranaryGrace.deadline(DAMAGED_AT, 0L) == DAMAGED_AT + 7L * DAY,
                "出事后还没上线, 就是 7 天");
        // 出事当天上线: 7 天更晚。
        helper.assertTrue(GranaryGrace.deadline(DAMAGED_AT, DAMAGED_AT) == DAMAGED_AT + 7L * DAY,
                "当天上线还是 7 天");
        // 第 6 天才上线: 上线起 3 天更晚 (还剩 3 天, 而不是 1 天)。
        helper.assertTrue(GranaryGrace.deadline(DAMAGED_AT, DAMAGED_AT + 6L * DAY) == DAMAGED_AT + 9L * DAY,
                "第 6 天才上线, 从上线那天起再给 3 天");
        // 一直不上线: 28 天封顶。
        helper.assertTrue(GranaryGrace.deadline(DAMAGED_AT, DAMAGED_AT + 100L * DAY) == DAMAGED_AT + 28L * DAY,
                "上限 28 天, 不上线也不能无限拖");

        helper.assertTrue(GranaryGrace.inGrace(DAMAGED_AT + 167L, DAMAGED_AT, 0L), "第 167 小时还在宽限内");
        helper.assertTrue(!GranaryGrace.inGrace(DAMAGED_AT + 168L, DAMAGED_AT, 0L),
                "正好到截止那一刻就不再宽限 (停表从这里停)");
        helper.assertTrue(GranaryGrace.remainingHours(DAMAGED_AT, DAMAGED_AT, 0L) == 7L * DAY,
                "出事当天还剩 7 天");
        helper.assertTrue(GranaryGrace.remainingHours(DAMAGED_AT + 20L * DAY, DAMAGED_AT, 0L) == 0L,
                "过期之后剩余一律算 0");
        helper.assertTrue(GranaryGrace.remainingHours(DAMAGED_AT, DAMAGED_AT, DAMAGED_AT + 100L * DAY)
                == 28L * DAY, "就算一直不上线, 也只剩 28 天封顶那么多");

        boolean threw = false;
        try {
            GranaryGrace.deadline(0L, 0L);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "没坏过就没有宽限可算 (出事时刻必须 > 0)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void structuralDamageOnlyLooksAtBaseRequirements(GameTestHelper helper) {
        // 漏风: 整座损坏。
        helper.assertTrue(GranaryDamage.structural(GranaryTier.SHED,
                scan(45, 5, 0, 1, 2, 0, Failure.LEAK)), "漏风判整座损坏");
        // 室内格数掉到本档基础要求以下 (粮棚 18 格): 整座损坏。
        helper.assertTrue(GranaryDamage.structural(GranaryTier.SHED, scan(12, 1, 0, 1, 1, 0)),
                "室内 12 格不够粮棚的 18 格基础要求");
        helper.assertTrue(!GranaryDamage.structural(GranaryTier.SHED, scan(20, 1, 0, 1, 1, 0)),
                "20 格够粮棚的基础要求 (哪怕放不下更多囤)");
        // 3 个囤要 27 格: 20 格只让「排不下的那几个」停用, 不算整座损坏 —— 这条界线最容易写错。
        helper.assertTrue(GranaryEnablement.decide(3, 20 / GranaryStructureScanner.CELLS_PER_BIN).unsupported() == 1,
                "20 格撑得住 2 个囤, 第 3 个排不上 (停用而不是整座损坏)");
        // 同一间屋, 冷库的基础要求是 36 格。
        helper.assertTrue(GranaryDamage.structural(GranaryTier.COLD, scan(27, 1, 0, 1, 1, 2)),
                "27 格对粮棚够、对冷库不够");
        helper.assertTrue(!GranaryDamage.structural(GranaryTier.COLD, scan(36, 1, 0, 1, 1, 2)),
                "36 格够冷库的基础要求");
        helper.assertTrue(GranaryDamage.describe(GranaryTier.SHED, scan(12, 1, 0, 1, 1, 0)).contains("18"),
                "提示要写清差多少格: " + GranaryDamage.describe(GranaryTier.SHED, scan(12, 1, 0, 1, 1, 0)));
        helper.succeed();
    }

    // ============================================================
    // 损坏: 宽限内照常停表
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void damagedGranaryKeepsClockingThroughTheGrace(GameTestHelper helper) {
        int slot = 0;
        Fixture fixture = accept(helper, slot);

        helper.assertTrue(!GranaryDamage.structural(GranaryTier.SHED, fixture.scan(helper)),
                "完好时不该判损坏");
        helper.assertTrue(fixture.bin(helper).stopping(), "验收之后囤在停表");

        // 屋里塞一片树叶 → 漏风 → 整座损坏。
        helper.setBlock(new BlockPos(slot * SLOT + 2, 2, HIGH), Blocks.OAK_LEAVES);
        GranaryInspection.Report report = fixture.inspect(helper, DAMAGED_AT);

        helper.assertTrue(report.record().status() == GranaryRecord.Status.DAMAGED, "结构破了要判损坏");
        helper.assertTrue(report.record().damagedAtHour() == DAMAGED_AT, "记下出事时刻 (宽限从它起算)");
        helper.assertTrue(report.record().tier() == GranaryTier.SHED, "损坏不改档位");
        helper.assertTrue(!report.record().acceptsDeposits(), "损坏只能取不能存");
        helper.assertTrue(report.enablement().enabled() == 0, "损坏的粮仓一个囤都不启用");

        GranaryBinBlockEntity bin = fixture.bin(helper);
        helper.assertTrue(bin.stopping(), "宽限期内照常停表 (「考试周回来东西照样完好」就在这里)");
        helper.assertTrue(GranaryDamage.describe(GranaryTier.SHED, report.scan()).contains("漏风"),
                "提醒要写明坏在哪: " + GranaryDamage.describe(GranaryTier.SHED, report.scan()));

        // 反复验算不刷新出事时刻 (宽限只从第一次被发现那一刻起算)。
        helper.assertTrue(fixture.inspect(helper, DAMAGED_AT + 30L).record().damagedAtHour() == DAMAGED_AT,
                "再看几次也不推迟宽限");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unacceptedGranaryIsNeverDamaged(GameTestHelper helper) {
        int slot = 3;
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(player, 1);

        buildRoom(helper, slot, 3);
        placeCenter(helper, player, center(slot));
        placeBin(helper, player, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        helper.setBlock(new BlockPos(slot * SLOT + 2, 2, HIGH), Blocks.OAK_LEAVES);

        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = GranarySavedData.get(level).centerGranary(level.dimension().location(), centerPos);
        GranaryInspection.Report report = GranaryInspection.inspect(level, centerPos, DAMAGED_AT);

        helper.assertTrue(report.record().status() == GranaryRecord.Status.UNACCEPTED,
                "没验收就谈不上损坏 (状态机里 U 只通向验收)");
        helper.assertTrue(report.record().damagedAtHour() == 0L, "没验收不记出事时刻");
        helper.assertTrue(GranarySavedData.get(level).granary(gid).status()
                == GranaryRecord.Status.UNACCEPTED, "账本里也还是未验收");
        helper.succeed();
    }

    // ============================================================
    // 修好: 宽限内不损失 / 过宽限重新起算
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void repairingWithinTheGraceLosesNothing(GameTestHelper helper) {
        int slot = 1;
        Fixture fixture = accept(helper, slot);
        GranaryBinBlockEntity bin = fixture.bin(helper);
        // 夹具: 往第一格放 4 个小麦并记下入仓时刻 (存入路径随界面分片加入, 这里直接摆账)。
        long storedAt = DAMAGED_AT - 100L;
        bin.setItem(0, new ItemStack(Items.WHEAT, 4));
        bin.markStored(0, storedAt);

        leak(helper, slot);
        helper.assertTrue(fixture.inspect(helper, DAMAGED_AT).record().status()
                == GranaryRecord.Status.DAMAGED, "先坏掉");
        helper.assertTrue(!fixture.record(helper).acceptsDeposits(), "损坏期间不能存");

        // 宽限内修好 (第 3 天)。
        repair(helper, slot);
        GranaryInspection.Report report = fixture.inspect(helper, DAMAGED_AT + 3L * DAY);
        helper.assertTrue(report.record().status() == GranaryRecord.Status.OK, "宽限内修好回到完好");
        helper.assertTrue(report.record().damagedAtHour() == 0L, "修好之后出事时刻清掉");
        helper.assertTrue(report.record().tier() == GranaryTier.SHED, "档位照旧");
        helper.assertTrue(report.enablement().enabled() == 1, "囤又启用了");
        helper.assertTrue(bin.stopping(), "修好之后照常停表");
        helper.assertTrue(bin.entryHour(0) == storedAt, "入仓锚点不动 (「和从没坏过一样」)");
        helper.assertTrue(fixture.data(helper).graceLogin(fixture.gid(helper), fixture.owner().getUUID()) == 0L,
                "修好之后宽限锚点清掉, 下次再坏是另一段宽限");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void expiredGraceStopsCreditAndRepairRestartsTheClock(GameTestHelper helper) {
        int slot = 2;
        Fixture fixture = accept(helper, slot);
        GranaryBinBlockEntity bin = fixture.bin(helper);
        // 出事那一刻刚好把东西存进去: 于是「结算到宽限截止」的应计数就是 7 天 − 1 小时。
        bin.setItem(0, new ItemStack(Items.WHEAT, 4));
        bin.markStored(0, DAMAGED_AT);
        helper.assertTrue(FreshnessData.stoppedHours(bin.getItem(0)) == 0, "新存进去的东西还没停过表");

        leak(helper, slot);
        fixture.inspect(helper, DAMAGED_AT);
        helper.assertTrue(bin.stopping(), "刚坏的时候还在宽限里, 照常停表");

        // 第 8 天才被发现: 已经过了 7 天宽限 → 照常腐烂。
        long late = DAMAGED_AT + 8L * DAY;
        fixture.inspect(helper, late);
        helper.assertTrue(!bin.stopping(), "过了宽限就不再停表 (照常腐烂)");
        helper.assertTrue(FreshnessData.stoppedHours(bin.getItem(0)) == (int) (7L * DAY) - 1,
                "结算到**宽限截止那一刻**为止, 不是结算到发现的那一刻; 实得 "
                        + FreshnessData.stoppedHours(bin.getItem(0)));
        helper.assertTrue(bin.entryHour(0) == DAMAGED_AT, "没过期的那条路上锚点不动");

        // 之后才修好: 状态回到完好, 但停表要重新存满 24 小时 (锚点换成现在, 本趟已计归零)。
        repair(helper, slot);
        GranaryInspection.Report report = fixture.inspect(helper, late);
        helper.assertTrue(report.record().status() == GranaryRecord.Status.OK, "修好回到完好");
        helper.assertTrue(bin.stopping(), "修好之后又能停表");
        helper.assertTrue(bin.entryHour(0) == late, "重新起算: 入仓锚点换成修好那一刻");
        helper.assertTrue(bin.creditedHours(0) == 0, "本趟已计归零");
        helper.assertTrue(FreshnessData.stoppedHours(bin.getItem(0)) == (int) (7L * DAY) - 1,
                "已经记在物品上的停表额度留着 (不没收)");
        helper.succeed();
    }

    // ============================================================
    // 上线: 记宽限锚点 + 提醒
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginAnchorsTheGraceOnlyOnce(GameTestHelper helper) {
        int slot = 4;
        Fixture fixture = accept(helper, slot);
        leak(helper, slot);
        fixture.inspect(helper, DAMAGED_AT);

        GranarySavedData data = fixture.data(helper);
        long gid = fixture.gid(helper);
        ServerPlayer owner = fixture.owner();
        helper.assertTrue(data.graceLogin(gid, owner.getUUID()) == 0L, "还没上线就没有锚点");

        // 第 6 天才上线: 锚点记在这一刻。
        long loginHour = DAMAGED_AT + 6L * DAY;
        GranarySystem.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(owner));
        helper.assertTrue(data.graceLogin(gid, owner.getUUID()) > 0L, "上线要记下宽限锚点");
        long anchor = data.graceLogin(gid, owner.getUUID());

        // 再上线一次不刷新锚点 (否则 3 天那条会变成「最后一次上线起 3 天」)。
        GranarySystem.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(owner));
        helper.assertTrue(data.graceLogin(gid, owner.getUUID()) == anchor, "只记第一次上线");

        // 与锚点无关的人不会被动到: 另一个没在这座粮仓里放囤的玩家不该留下锚点。
        ServerPlayer stranger = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        GranarySystem.onPlayerLoggedIn(new PlayerEvent.PlayerLoggedInEvent(stranger));
        helper.assertTrue(data.graceLogin(gid, stranger.getUUID()) == 0L, "没在这座粮仓里放囤就没有宽限");
        helper.assertTrue(loginHour > DAMAGED_AT, "用例用的是「过了几天才上线」");
        helper.succeed();
    }

    // ============================================================
    // 部件撑不住: 停用也走宽限期
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void suspendedBinsKeepClockingThroughTheirOwnGrace(GameTestHelper helper) {
        int slot = 5;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(owner, 1);

        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        placeBin(helper, owner, bin(slot, HIGH, HIGH));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());

        BlockPos centerPos = helper.absolutePos(center(slot));
        GranarySavedData data = GranarySavedData.get(level);
        helper.assertTrue(GranaryInspection.accept(level, centerPos, owner, SUSPENDED_AT).accepted(),
                "先验收成粮棚");
        GranaryBinBlockEntity bin = (GranaryBinBlockEntity) helper.getBlockEntity(bin(slot, LOW, LOW));
        bin.setItem(0, new ItemStack(Items.WHEAT, 4));
        bin.markStored(0, SUSPENDED_AT);
        helper.assertTrue(bin.stopping(), "验收后两个囤都在停表");

        // 把气窗换成石头: 粮仓**没坏** (气窗不参与损坏判据), 只是撑不住两个囤了 → 两个都停用。
        helper.setBlock(vent(slot), Blocks.STONE);
        GranaryInspection.Report suspended = GranaryInspection.inspect(level, centerPos, SUSPENDED_AT);
        helper.assertTrue(suspended.record().status() == GranaryRecord.Status.OK,
                "只拆气窗不算整座损坏");
        helper.assertTrue(suspended.scan().vents() == 0 && suspended.enablement().unsupported() == 2,
                "两个囤都排不上");
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, LOW, LOW)))
                == GranaryBinState.STOPPED, "排不上的转「停用」");
        helper.assertTrue(data.binSuspendedAt(level.dimension().location(),
                helper.absolutePos(bin(slot, LOW, LOW))) == SUSPENDED_AT, "记下停用时刻 (宽限从它起算)");
        helper.assertTrue(bin.stopping(), "停用也走宽限期: 宽限内表照走 (规格第 408 行那条)");

        // 过了宽限才发现: 结算到宽限截止那一刻, 然后停表。
        long late = SUSPENDED_AT + 8L * DAY;
        GranaryInspection.inspect(level, centerPos, late);
        helper.assertTrue(!bin.stopping(), "过宽限之后照常腐烂");
        helper.assertTrue(FreshnessData.stoppedHours(bin.getItem(0)) == (int) (7L * DAY) - 1,
                "补记到停用起 7 天为止, 实得 " + FreshnessData.stoppedHours(bin.getItem(0)));

        // 气窗装回去: 又启用, 但停表要重新存满 24 小时。
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        GranaryInspection.Report restored = GranaryInspection.inspect(level, centerPos, late);
        helper.assertTrue(restored.enablement().enabled() == 2, "补回气窗又撑得住了");
        helper.assertTrue(bin.stopping(), "重新停表");
        helper.assertTrue(bin.entryHour(0) == late, "重新起算: 入仓锚点换成补回来的那一刻");
        helper.assertTrue(bin.creditedHours(0) == 0, "本趟已计归零");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一座验收好的粮棚: 3×3×3 的石头屋 + 账台 + 一个囤 + 一扇气窗。 */
    private record Fixture(ServerPlayer owner, BlockPos centerRelative) {

        GranarySavedData data(GameTestHelper helper) {
            return GranarySavedData.get(helper.getLevel());
        }

        long gid(GameTestHelper helper) {
            ServerLevel level = helper.getLevel();
            return data(helper).centerGranary(level.dimension().location(), helper.absolutePos(centerRelative));
        }

        GranaryRecord record(GameTestHelper helper) {
            return data(helper).granary(gid(helper));
        }

        GranaryBinBlockEntity bin(GameTestHelper helper) {
            return (GranaryBinBlockEntity) helper.getBlockEntity(binRelative());
        }

        BlockPos binRelative() {
            return new BlockPos(centerRelative.getX() - 1, LOW, LOW);
        }

        GranaryStructureScanner.ScanResult scan(GameTestHelper helper) {
            return GranaryStructureScanner.scan(helper.getLevel(), helper.absolutePos(centerRelative));
        }

        GranaryInspection.Report inspect(GameTestHelper helper, long nowHour) {
            return GranaryInspection.inspect(helper.getLevel(), helper.absolutePos(centerRelative), nowHour);
        }
    }

    private static Fixture accept(GameTestHelper helper, int slot) {
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(owner, 1);
        buildRoom(helper, slot, 3);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        Fixture fixture = new Fixture(owner, center(slot));
        GranaryInspection.Acceptance acceptance =
                GranaryInspection.accept(helper.getLevel(), helper.absolutePos(center(slot)), owner);
        helper.assertTrue(acceptance.accepted(), "夹具: 先验收成粮棚, 实际 " + acceptance.rejection());
        helper.assertTrue(fixture.bin(helper).stopping(), "夹具: 验收后囤在停表");
        return fixture;
    }

    /** 让屋子漏风 (结构判定里最确定的失败原因)。 */
    private static void leak(GameTestHelper helper, int slot) {
        helper.setBlock(new BlockPos(slot * SLOT + 2, 2, HIGH), Blocks.OAK_LEAVES);
    }

    /** 把叶子换成空气, 屋子恢复封闭。 */
    private static void repair(GameTestHelper helper, int slot) {
        helper.setBlock(new BlockPos(slot * SLOT + 2, 2, HIGH), Blocks.AIR);
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

    private static ScanResult scan(int interiorCells, int bins, int binsNotEnclosed, int centers,
                                  int vents, int coldSources) {
        return scan(interiorCells, bins, binsNotEnclosed, centers, vents, coldSources, Failure.NONE);
    }

    private static ScanResult scan(int interiorCells, int bins, int binsNotEnclosed, int centers,
                                  int vents, int coldSources, Failure failure) {
        return new ScanResult(interiorCells, bins, binsNotEnclosed, centers, vents, coldSources, failure);
    }

    private static BlockPos center(int slot) {
        return new BlockPos(slot * SLOT + 2, 2, 2);
    }

    private static BlockPos vent(int slot) {
        return new BlockPos(slot * SLOT, 2, 2);
    }

    private static BlockPos bin(int slot, int x, int z) {
        return new BlockPos(slot * SLOT + x, LOW, z);
    }

    private static void buildRoom(GameTestHelper helper, int slot, int width) {
        int base = slot * SLOT;
        for (int x = 0; x <= width + 1; x++) {
            for (int y = 0; y <= HIGH + 1; y++) {
                for (int z = 0; z <= HIGH + 1; z++) {
                    boolean shell = x == 0 || x == width + 1 || y == 0 || y == HIGH + 1 || z == 0 || z == HIGH + 1;
                    BlockState state = shell ? Blocks.STONE.defaultBlockState() : Blocks.AIR.defaultBlockState();
                    helper.setBlock(new BlockPos(base + x, y, z), state);
                }
            }
        }
    }

    private static void setFarmerLevel(ServerPlayer player, int level) {
        com.miningdim.entry.MiningCapabilities.get(player)
                .orElseThrow(() -> new IllegalStateException("mock 玩家未挂载 capability, 无法摆放职业等级"))
                .jobProgress(JobId.FARMER)
                .setLevel(level);
    }
}
