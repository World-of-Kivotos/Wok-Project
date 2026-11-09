package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobId;
import com.miningdim.job.farmer.granary.block.GranaryBlocks;
import com.miningdim.job.farmer.granary.block.GranaryCenterBlockEntity;
import com.miningdim.testutil.MockGameTestPlayers;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.common.util.BlockSnapshot;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 账台的 GameTest: 登记、验收与启用重排 (docs/Spoilage_Granary_DesignSpec.md「建造与验收」「容量怎么算」)。
 *
 * 屋子仍用 setBlock 搭 (与结构那一批同一套形状), 但**账台与囤都走真实的放置路径** (放置事件 + setPlacedBy),
 * 因为验收与启用读的是账本, 而账本只由那条路写。
 *
 * 锁六件事: 放下账台就开号、右键能看潜行右键能验收、结构不成立时既不收囤也不给验收、升档要相应等级的
 * 验收人 (而且验收人不必是房主)、部件被拆时原来启用的转「停用」、拆掉账台就解散粮仓并把囤的连线清掉。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryInspectionGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_inspect";

    /** 这一批结束后把账本清干净 (粮仓账本是全服共享的存档数据, 留着会干扰后面的批次与下一轮的自查)。 */
    @net.minecraft.gametest.framework.AfterBatch(batch = BATCH)
    public static void clearLedger(ServerLevel level) {
        GranaryTestLedger.clear(level);
    }

    /** 每条用例占自己的一条槽位 (同一批次的用例并行跑且共用区域)。 */
    private static final int SLOT = 16;

    private static final int LOW = 1;
    private static final int HIGH = 3;   // 内部高度 3 层

    // ============================================================
    // 放账台 = 开一座粮仓
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placingTheCenterOpensAGranary(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(player, 1);

        BlockPos relative = new BlockPos(2, 2, 2);
        placeCenter(helper, player, relative);

        GranarySavedData data = GranarySavedData.get(level);
        BlockPos pos = helper.absolutePos(relative);
        long gid = data.centerGranary(level.dimension().location(), pos);
        helper.assertTrue(gid != 0L, "放下账台就应当开到号");

        GranaryRecord record = data.granary(gid);
        helper.assertTrue(record != null, "开号的同时要落一条登记项");
        helper.assertTrue(player.getUUID().equals(record.owner()), "谁放的账台谁就是房主");
        helper.assertTrue(record.status() == GranaryRecord.Status.UNACCEPTED, "刚放下还没验收");
        helper.assertTrue(!record.acceptsDeposits(), "没验收的粮仓不能存");
        helper.assertTrue(data.centerCount(player.getUUID()) == 1, "账台额度记上了");

        GranaryCenterBlockEntity center = (GranaryCenterBlockEntity) helper.getBlockEntity(relative);
        helper.assertTrue(center != null, "账台方块实体应当已创建");
        helper.assertTrue(center.granaryId() == gid, "方块实体上的编号是账本那份的镜像");

        // 拆掉账台: 粮仓跟着解散 (账本是权威, 不看方块实体还在不在)。
        level.destroyBlock(pos, false);
        helper.assertTrue(data.granary(gid) == null, "拆掉账台要解散粮仓");
        helper.assertTrue(data.centerGranary(level.dimension().location(), pos) == 0L, "账台与粮仓的连线也要清掉");
        helper.assertTrue(data.centerCount(player.getUUID()) == 0, "账台额度还回去了");
        helper.succeed();
    }

    // ============================================================
    // 右键看结构 / 潜行右键验收
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rightClickReportsAndSneakClickAccepts(GameTestHelper helper) {
        int slot = 1;
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(player, 1);

        buildRoom(helper, slot, 3);
        placeCenter(helper, player, center(slot));
        placeBin(helper, player, bin(slot, LOW, LOW));
        placeBin(helper, player, bin(slot, HIGH, HIGH));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());

        GranarySavedData data = GranarySavedData.get(level);
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = data.centerGranary(level.dimension().location(), centerPos);

        GranaryInspection.Report report = GranaryInspection.inspect(level, centerPos);
        helper.assertTrue(report.scan().ok(), "封闭小屋必须判成立, 实际 " + report.scan().failure());
        helper.assertTrue(report.verdictOf(GranaryTier.SHED).accepted(), "小屋够粮棚: "
                + report.verdictOf(GranaryTier.SHED).checklist());
        helper.assertTrue(report.enablement().enabled() == 0, "没验收就一个囤都不启用");
        helper.assertTrue(report.record().status() == GranaryRecord.Status.UNACCEPTED, "看一眼不会顺手验收");

        // 右键看: 只是报告, 不改状态。
        helper.assertTrue(use(helper, player, center(slot), false) == InteractionResult.CONSUME, "服务端消费这次交互");
        helper.assertTrue(data.granary(gid).status() == GranaryRecord.Status.UNACCEPTED, "看结构不落档");

        // 潜行右键: 验收。
        use(helper, player, center(slot), true);
        GranaryRecord accepted = data.granary(gid);
        helper.assertTrue(accepted.tier() == GranaryTier.SHED, "自己验收成粮棚");
        helper.assertTrue(accepted.status() == GranaryRecord.Status.OK, "验收后是完好");
        helper.assertTrue(player.getUUID().equals(accepted.acceptedBy()), "记下验收人");

        // 屋里的两个囤连上了, 而且都在启用 (粮棚可支撑 min(27÷9, 1×4) = 3)。
        helper.assertTrue(data.granaryOfBin(level.dimension().location(), helper.absolutePos(bin(slot, LOW, LOW))) == gid,
                "屋里的囤要连到这座粮仓");
        helper.assertTrue(data.granaryOfBin(level.dimension().location(), helper.absolutePos(bin(slot, HIGH, HIGH))) == gid,
                "两个囤都要连上");
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, LOW, LOW)))
                == GranaryBinState.ACTIVE, "验收后早入册的囤启用");
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, HIGH, HIGH)))
                == GranaryBinState.ACTIVE, "两个都撑得住, 都启用");
        helper.succeed();
    }

    // ============================================================
    // 结构不成立: 不收囤、不给验收
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void brokenStructureNeitherEnrolsBinsNorAccepts(GameTestHelper helper) {
        int slot = 2;
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(player, 1);

        buildRoom(helper, slot, 3);
        placeCenter(helper, player, center(slot));
        placeBin(helper, player, bin(slot, LOW, LOW));
        placeBin(helper, player, bin(slot, HIGH, HIGH));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        // 屋里塞一片树叶: 判漏风 (结构判定里最确定的一条失败原因)。
        helper.setBlock(new BlockPos(slot * SLOT + 2, 2, HIGH), Blocks.OAK_LEAVES);

        GranarySavedData data = GranarySavedData.get(level);
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = data.centerGranary(level.dimension().location(), centerPos);

        GranaryInspection.Report report = GranaryInspection.inspect(level, centerPos);
        helper.assertTrue(!report.scan().ok(), "屋里有树叶就不成立");
        helper.assertTrue(report.scan().failure() == GranaryStructureScanner.Failure.LEAK,
                "失败原因应当是漏风, 实际 " + report.scan().failure());
        helper.assertTrue(!report.verdictOf(GranaryTier.SHED).accepted(), "漏风的屋子谈不上验收");
        helper.assertTrue(report.verdictOf(GranaryTier.SHED).checklist().contains("漏风"),
                "清单要照实报: " + report.verdictOf(GranaryTier.SHED).checklist());
        helper.assertTrue(data.granaryOfBin(level.dimension().location(), helper.absolutePos(bin(slot, LOW, LOW))) == 0L,
                "结构不成立时不把囤收进粮仓");
        helper.assertTrue(report.enablement().enabled() == 0, "破屋子里的囤一个都不启用");

        use(helper, player, center(slot), true);
        helper.assertTrue(data.granary(gid).status() == GranaryRecord.Status.UNACCEPTED, "结构不成立时验收不通过");
        helper.assertTrue(data.granary(gid).tier() == null, "没通过就不锁档位");
        helper.succeed();
    }

    // ============================================================
    // 升档要相应等级的验收人
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void upgradeNeedsTheInspectorsFarmerLevel(GameTestHelper helper) {
        int slot = 3;
        ServerLevel level = helper.getLevel();
        ServerPlayer owner = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer lowLevel = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        ServerPlayer inspector = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(owner, 1);
        setFarmerLevel(lowLevel, 4);
        setFarmerLevel(inspector, 5);
        helper.assertTrue(!owner.getUUID().equals(inspector.getUUID()), "三个 mock 玩家要是不同的人");

        // 室内 4×3×3 = 36 格 (够冷库的基础要求), 两个冷源垫在地板里。
        buildRoom(helper, slot, 4);
        placeCenter(helper, owner, center(slot));
        placeBin(helper, owner, bin(slot, LOW, LOW));
        placeBin(helper, owner, bin(slot, HIGH, HIGH));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        helper.setBlock(new BlockPos(slot * SLOT + 2, 0, 2), Blocks.BLUE_ICE);
        helper.setBlock(new BlockPos(slot * SLOT + 3, 0, 2), Blocks.BLUE_ICE);

        GranarySavedData data = GranarySavedData.get(level);
        BlockPos centerPos = helper.absolutePos(center(slot));
        long gid = data.centerGranary(level.dimension().location(), centerPos);

        // 第一档: 房主自己点就成立。
        GranaryInspection.Acceptance first = GranaryInspection.accept(level, centerPos, owner);
        helper.assertTrue(first.accepted() && first.tier() == GranaryTier.SHED,
                "L1 的房主自己验收粮棚, 实际 " + first.rejection());
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, LOW, LOW)))
                == GranaryBinState.ACTIVE, "粮棚验收后囤启用");

        // 升冷库: L4 不够。
        GranaryInspection.Acceptance tooLow = GranaryInspection.accept(level, centerPos, lowLevel);
        helper.assertTrue(!tooLow.accepted() && tooLow.rejection() == GranaryInspection.Rejection.LEVEL_TOO_LOW,
                "L4 够不上冷库, 实际 " + tooLow.rejection());
        helper.assertTrue(data.granary(gid).tier() == GranaryTier.SHED, "没通过就不动档位");

        // L5 的验收人 (不必是房主): 通过, 档位锁在冷库, 验收人记的是他。
        GranaryInspection.Acceptance upgraded = GranaryInspection.accept(level, centerPos, inspector);
        helper.assertTrue(upgraded.accepted() && upgraded.tier() == GranaryTier.COLD,
                "L5 能把粮棚升成冷库, 实际 " + upgraded.rejection());
        GranaryRecord record = data.granary(gid);
        helper.assertTrue(record.tier() == GranaryTier.COLD, "档位升到冷库并锁定");
        helper.assertTrue(inspector.getUUID().equals(record.acceptedBy()), "记下替他验收的人");
        helper.assertTrue(owner.getUUID().equals(record.owner()), "房主不变");

        // 冷库的可支撑数 = min(38÷9, 1×4, 2×2) = 4, 两个囤照样启用。
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, HIGH, HIGH)))
                == GranaryBinState.ACTIVE, "冷库下两个囤都在启用");
        helper.succeed();
    }

    // ============================================================
    // 部件被拆: 原来启用的转「停用」
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void losingAVentSuspendsTheBinsThatWereActive(GameTestHelper helper) {
        int slot = 4;
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(player, 1);

        buildRoom(helper, slot, 3);
        placeCenter(helper, player, center(slot));
        placeBin(helper, player, bin(slot, LOW, LOW));
        placeBin(helper, player, bin(slot, HIGH, HIGH));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());

        GranarySavedData data = GranarySavedData.get(level);
        BlockPos centerPos = helper.absolutePos(center(slot));
        helper.assertTrue(GranaryInspection.accept(level, centerPos, player).accepted(), "先验收成粮棚");

        // 把气窗换成石头: 屋子照样封闭, 但一扇气窗都没有了 → 可支撑 0。
        helper.setBlock(vent(slot), Blocks.STONE);
        GranaryInspection.Report report = GranaryInspection.inspect(level, centerPos);
        helper.assertTrue(report.scan().ok(), "封回石头后屋子还是成立的, 实际 " + report.scan().failure());
        helper.assertTrue(report.scan().vents() == 0, "气窗没了");
        helper.assertTrue(report.enablement().unsupported() == 2, "两个囤都排不上了");

        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, LOW, LOW)))
                == GranaryBinState.STOPPED, "原来启用的囤转「停用」(走宽限期那支)");
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, HIGH, HIGH)))
                == GranaryBinState.STOPPED, "两个都转停用");
        helper.assertTrue(data.granaryOfBin(level.dimension().location(), helper.absolutePos(bin(slot, LOW, LOW))) != 0L,
                "停用不等于退出粮仓, 连线还在");

        // 气窗装回去: 又转回启用。
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());
        helper.assertTrue(GranaryInspection.inspect(level, centerPos).enablement().enabled() == 2, "补回气窗就转回启用");
        helper.assertTrue(data.binState(level.dimension().location(), helper.absolutePos(bin(slot, LOW, LOW)))
                == GranaryBinState.ACTIVE, "转回启用");
        helper.succeed();
    }

    // ============================================================
    // 拆账台 = 解散粮仓
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void breakingTheCenterDissolvesTheGranary(GameTestHelper helper) {
        int slot = 5;
        ServerLevel level = helper.getLevel();
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        setFarmerLevel(player, 1);

        buildRoom(helper, slot, 3);
        placeCenter(helper, player, center(slot));
        placeBin(helper, player, bin(slot, LOW, LOW));
        placeBin(helper, player, bin(slot, HIGH, HIGH));
        helper.setBlock(vent(slot), GranaryBlocks.VENT.get());

        GranarySavedData data = GranarySavedData.get(level);
        BlockPos centerPos = helper.absolutePos(center(slot));
        BlockPos binPos = helper.absolutePos(bin(slot, LOW, LOW));
        helper.assertTrue(GranaryInspection.accept(level, centerPos, player).accepted(), "先验收成粮棚");
        long gid = data.centerGranary(level.dimension().location(), centerPos);
        helper.assertTrue(data.binState(level.dimension().location(), binPos) == GranaryBinState.ACTIVE, "验收后囤启用");

        // 走无玩家路径拆账台 (爆炸、指令、活塞都归这条): 账本照样知道该解散哪座粮仓。
        level.destroyBlock(centerPos, false);

        helper.assertTrue(data.granary(gid) == null, "拆掉账台就解散粮仓");
        helper.assertTrue(data.centerGranary(level.dimension().location(), centerPos) == 0L, "账台连线清掉");
        helper.assertTrue(data.granaryOfBin(level.dimension().location(), binPos) == 0L, "囤不再连着粮仓");
        helper.assertTrue(data.binState(level.dimension().location(), binPos) == GranaryBinState.PENDING,
                "囤回到「没连上粮仓」的待启用, 只能取不能存");
        helper.assertTrue(player.getUUID().equals(data.ownerOf(level.dimension().location(), binPos)),
                "囤的归属还是囤主的, 解散粮仓不动归属");
        helper.assertTrue(data.centerCount(player.getUUID()) == 0, "账台额度还回去了");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 走真实路径放一座账台: 放置事件开号 + setPlacedBy 把编号镜像到方块实体。 */
    private static void placeCenter(GameTestHelper helper, ServerPlayer player, BlockPos relative) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        // 世界存档跨轮保留: 先把这个坐标清干净, 否则上一轮失败留下的登记项会让开号失败。
        GranarySystem.releaseCenter(level, pos);
        helper.setBlock(relative, GranaryBlocks.CENTER.get());
        BlockEvent.EntityPlaceEvent event = new BlockEvent.EntityPlaceEvent(
                BlockSnapshot.create(level.dimension(), level, pos), Blocks.AIR.defaultBlockState(), player);
        GranarySystem.onBlockPlaced(event);
        helper.assertTrue(!event.isCanceled(), "账台应当放得下");
        GranaryBlocks.CENTER.get().setPlacedBy(level, pos, level.getBlockState(pos), player, ItemStack.EMPTY);
    }

    /** 走真实路径放一个储粮囤 (额度、归属、入册序号都写在这条路上)。 */
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

    /** 右键 / 潜行右键账台。 */
    private static InteractionResult use(GameTestHelper helper, ServerPlayer player, BlockPos relative,
                                         boolean sneak) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        player.setShiftKeyDown(sneak);
        InteractionResult result = GranaryBlocks.CENTER.get().use(level.getBlockState(pos), level, pos, player,
                InteractionHand.MAIN_HAND, new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
        player.setShiftKeyDown(false);
        return result;
    }

    /** 账台: 室内 (2, 2, 2)。 */
    private static BlockPos center(int slot) {
        return new BlockPos(slot * SLOT + 2, 2, 2);
    }

    /** 气窗: 嵌在西墙上, 一面贴着屋里。 */
    private static BlockPos vent(int slot) {
        return new BlockPos(slot * SLOT, 2, 2);
    }

    /** 室内的某个格子 (两个对角各放一个囤)。 */
    private static BlockPos bin(int slot, int x, int z) {
        return new BlockPos(slot * SLOT + x, LOW, z);
    }

    /** 内部 width×3×3 的封闭石头盒子 (含地板与天花板); 只动自己这条槽位。 */
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
