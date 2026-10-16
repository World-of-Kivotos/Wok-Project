package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.GranaryStructureScanner.Failure;
import com.miningdim.job.farmer.granary.GranaryStructureScanner.ScanResult;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 粮仓验收、启用与登记项的 GameTest
 * (docs/Spoilage_Granary_DesignSpec.md「建造与验收」「容量怎么算」「档位管什么」「物品数据与存档」)。
 *
 * 锁四件事: 档位按**验收人的等级**当场判定且只升不降、清单报的是实际数字而不是「不满足要求」、
 * 撑不住的囤按**入册先后**落在最后 (部件补回来又能转回启用)、登记项与连线/启用状态能原样存读。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryAcceptanceGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_acceptance";

    /**
     * 本批只在存档账本里按坐标记账, 不摆方块 —— 位置仍旧选在别的用例够不到的地方,
     * 免得和别批真正摆下的储粮囤撞在同一个坐标上 (撞上会让别批的放置被账本拒绝)。
     */
    private static final BlockPos FAR = new BlockPos(200, 4, 200);

    // ============================================================
    // 档位与验收裁决 (纯逻辑)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tierFollowsTheClickersFarmerLevel(GameTestHelper helper) {
        helper.assertTrue(GranaryTier.SHED.unlockLevel() == 1, "粮棚人人可验收 (不练农夫也是 L1)");
        helper.assertTrue(GranaryTier.COLD.unlockLevel() == 5, "冷库要农夫 L5");
        helper.assertTrue(GranaryTier.STATE.unlockLevel() == 7, "官仓要农夫 L7");
        helper.assertTrue(!GranaryTier.SHED.acceptsFresh(), "粮棚不收生鲜");
        helper.assertTrue(GranaryTier.COLD.acceptsFresh() && GranaryTier.STATE.acceptsFresh(), "冷库起收生鲜");
        helper.assertTrue(GranaryTier.COLD.next() == GranaryTier.STATE, "冷库的上一档是官仓");
        helper.assertTrue(GranaryTier.STATE.next() == GranaryTier.STATE, "到顶就停在自己这一档");

        ScanResult good = scan(72, 6, 0, 1, 2, 4);
        helper.assertTrue(GranaryAcceptance.acceptableNow(null, 1, good, 0).orElseThrow().tier()
                == GranaryTier.SHED, "第一次验收从粮棚起");
        helper.assertTrue(GranaryAcceptance.acceptableNow(null, 4, good, 0).orElseThrow().tier()
                == GranaryTier.SHED, "还没验收时谁都能验收粮棚 (粮棚是 L1 门槛)");
        helper.assertTrue(GranaryAcceptance.acceptableNow(GranaryTier.SHED, 4, good, 0).isEmpty(),
                "L4 够不上冷库, 这一下没有可验收的档位");
        helper.assertTrue(GranaryAcceptance.acceptableNow(GranaryTier.SHED, 5, good, 0).orElseThrow().tier()
                == GranaryTier.COLD, "L5 能验收冷库");
        helper.assertTrue(GranaryAcceptance.acceptableNow(GranaryTier.COLD, 6, good, 0).isEmpty(),
                "L6 还够不上官仓");
        helper.assertTrue(GranaryAcceptance.acceptableNow(GranaryTier.COLD, 7, good, 0).orElseThrow().tier()
                == GranaryTier.STATE, "L7 在冷库之上再验收官仓");
        helper.assertTrue(GranaryAcceptance.acceptableNow(GranaryTier.STATE, 10, good, 0).isEmpty(),
                "官仓到顶, 没有更高的档位可验收");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void checklistNamesTheMissingPartsWithRealNumbers(GameTestHelper helper) {
        // 5 个囤: 室内要 max(18, 5×9)=45 格, 气窗要 ceil(5/4)=2 扇。
        ScanResult cramped = scan(20, 5, 0, 1, 1, 0);
        GranaryAcceptance.Verdict verdict = GranaryAcceptance.check(GranaryTier.SHED, cramped, 0);
        helper.assertTrue(!verdict.accepted(), "格数与气窗都不够就不能验收");
        helper.assertTrue(verdict.problems().size() == 2, "两条一起报, 不让房主来回试: " + verdict.checklist());
        helper.assertTrue(verdict.checklist().contains("45"), "清单报的是实际要的格数: " + verdict.checklist());
        helper.assertTrue(verdict.checklist().contains("2 扇"), "清单报的是实际要的气窗数: " + verdict.checklist());

        helper.assertTrue(GranaryAcceptance.check(GranaryTier.SHED, scan(45, 5, 0, 1, 2, 0), 0).accepted(),
                "45 格 + 2 扇气窗就成立 (粮棚不看冷源)");
        helper.assertTrue(GranaryAcceptance.check(GranaryTier.SHED, scan(45, 5, 0, 1, 2, 0), 0).checklist()
                .equals("结构与部件都满足"), "成立时清单不留问题");

        // 冷库/官仓: 冷源每 2 个囤 1 个且至少 2 个 —— 5 个囤要 3 个。
        GranaryAcceptance.Verdict cold = GranaryAcceptance.check(GranaryTier.COLD, scan(45, 5, 0, 1, 2, 2), 0);
        helper.assertTrue(!cold.accepted() && cold.checklist().contains("冷源"), "冷库缺冷源就验收不了");
        helper.assertTrue(cold.checklist().contains("3 个"), "冷源要求按囤数算, 不是一律 2 个: " + cold.checklist());
        helper.assertTrue(GranaryAcceptance.check(GranaryTier.COLD, scan(45, 5, 0, 1, 2, 3), 0).accepted(),
                "3 个冷源 (5 个囤) 就成立");

        // 结构本身不成立时, 原因照原样摆出来, 并且不再逐条算部件。
        GranaryAcceptance.Verdict leaky = GranaryAcceptance.check(GranaryTier.SHED, scan(45, 5, 0, 1, 2, 0, Failure.LEAK), 0);
        helper.assertTrue(!leaky.accepted() && leaky.checklist().contains("漏风"), "漏风要照实报: " + leaky.checklist());
        helper.assertTrue(GranaryAcceptance.check(GranaryTier.SHED, scan(45, 5, 0, 2, 2, 0), 0).checklist()
                .contains("2 座账台"), "一间屋两座账台要报账台数");
        helper.assertTrue(GranaryAcceptance.check(GranaryTier.SHED, scan(45, 4, 1, 1, 2, 0), 0).checklist()
                .contains("1 个储粮囤"), "嵌在墙里的囤要点名");
        helper.assertTrue(GranaryAcceptance.describe(Failure.TOO_LARGE).contains("隔成两间"),
                "太大要给出可行建议");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pendingBinsDoNotCountTowardsTheCellRequirement(GameTestHelper helper) {
        // 6 个囤里 3 个待启用: 只有 3 个入册, 格数按 3 算 (27), 气窗仍按屋里的 6 个算 (2 扇)。
        helper.assertTrue(GranaryAcceptance.check(GranaryTier.SHED, scan(27, 6, 0, 1, 2, 0), 3).accepted(),
                "待启用的囤不占格数要求");
        GranaryAcceptance.Verdict strict = GranaryAcceptance.check(GranaryTier.SHED, scan(27, 6, 0, 1, 2, 0), 0);
        helper.assertTrue(!strict.accepted(), "同样一间屋, 6 个都算入册就要 54 格, 27 格不够");
        helper.assertTrue(GranaryAcceptance.check(GranaryTier.SHED, scan(54, 6, 0, 1, 2, 0), 0).accepted(),
                "补齐到 54 格就成立");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void enablementKeepsTheEarliestBinsAndReenablesAfterRepair(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GranarySavedData data = GranarySavedData.get(level);
        UUID owner = UUID.randomUUID();
        ResourceLocation dimension = level.dimension().location();
        long gid = data.nextGranaryId();
        data.putGranary(GranaryRecord.unaccepted(gid, owner));

        BlockPos[] bins = new BlockPos[5];
        for (int index = 0; index < bins.length; index++) {
            bins[index] = helper.absolutePos(FAR.offset(index * 2, 0, 0));
            // 上一次跑崩在世界里留下的登记项先还回去: 坐标必须干净, 不然认领会失败、断言就变成了假阴性。
            data.releaseBin(dimension, bins[index]);
            helper.assertTrue(data.claimBin(dimension, bins[index], owner, index + 1),
                    "第 " + (index + 1) + " 个囤应当能入册");
            data.linkBin(dimension, bins[index], gid);
        }
        helper.assertTrue(data.binsOf(gid).size() == 5, "五个囤都连上了这座粮仓");

        // 只撑得住 3 个: 按入册先后, 前三个启用、后两个待启用 (从来没用过, 不是「停用」)。
        GranaryEnablement.Decision crowded = data.applyEnablement(gid, 3);
        helper.assertTrue(crowded.enabled() == 3 && crowded.unsupported() == 2, "撑 3 个就是 3 启用 2 排不上");
        for (int index = 0; index < 3; index++) {
            helper.assertTrue(data.binState(dimension, bins[index]) == GranaryBinState.ACTIVE,
                    "早入册的 " + (index + 1) + " 号照常启用");
        }
        helper.assertTrue(data.binState(dimension, bins[3]) == GranaryBinState.PENDING
                && data.binState(dimension, bins[4]) == GranaryBinState.PENDING,
                "最后入册的两个是新来的「待启用」, 不是「停用」");
        helper.assertTrue(data.isBinInactive(dimension, bins[4]), "待启用只能取不能存");

        // 拆了气窗撑不住 2 个: 原来启用的 3 号转成「停用」(它原来在停表, 要走宽限期)。
        GranaryEnablement.Decision shrunk = data.applyEnablement(gid, 2);
        helper.assertTrue(shrunk.enabled() == 2 && shrunk.unsupported() == 3, "部件拆了就有 3 个排不上");
        helper.assertTrue(data.binState(dimension, bins[2]) == GranaryBinState.STOPPED,
                "原本启用的 3 号转停用 (走宽限期那支)");
        helper.assertTrue(data.binState(dimension, bins[3]) == GranaryBinState.PENDING
                && data.binState(dimension, bins[4]) == GranaryBinState.PENDING,
                "本来就待启用的还是待启用, 不会被记成停用");
        helper.assertTrue(data.binState(dimension, bins[0]) == GranaryBinState.ACTIVE, "排在前面的不受影响");

        // 部件补回来: 全部转回启用。
        helper.assertTrue(data.applyEnablement(gid, 5).unsupported() == 0, "补齐部件后没有待启用的");
        for (BlockPos bin : bins) {
            helper.assertTrue(data.binState(dimension, bin) == GranaryBinState.ACTIVE, "补齐后都回到启用");
        }

        helper.assertTrue(GranaryEnablement.decide(200, 200).enabled() == GranaryEnablement.MAX_ACTIVE_BINS,
                "单座粮仓的启用数封在技术上限 128, 超出的排进待启用");
        boolean threw = false;
        try {
            GranaryEnablement.decide(-1, 0);
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        helper.assertTrue(threw, "负计数要直接报错, 不静默当成 0");

        for (BlockPos bin : bins) {
            helper.assertTrue(owner.equals(data.releaseBin(dimension, bin)), "用例收尾把账还回去");
        }
        helper.assertTrue(data.binsOf(gid).isEmpty(), "拆掉之后连线也一并清掉");
        helper.succeed();
    }

    // ============================================================
    // 登记项与存档
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recordStatusTransitionsAreOneWay(GameTestHelper helper) {
        UUID owner = UUID.randomUUID();
        UUID inspector = UUID.randomUUID();
        GranaryRecord record = GranaryRecord.unaccepted(11L, owner);
        helper.assertTrue(!record.accepted() && !record.acceptsDeposits(), "还没验收: 不能存, 也不停表");

        GranaryRecord accepted = record.accepted(GranaryTier.COLD, inspector, 4_000L);
        helper.assertTrue(accepted.accepted() && accepted.acceptsDeposits(), "验收后能存");
        helper.assertTrue(accepted.owner().equals(owner) && accepted.acceptedBy().equals(inspector),
                "验收人不必是房主, 两个都记下来");
        helper.assertTrue(accepted.tier() == GranaryTier.COLD && accepted.acceptedAtHour() == 4_000L,
                "档位在验收那一刻锁定");

        GranaryRecord damaged = accepted.damaged(5_000L);
        helper.assertTrue(damaged.status() == GranaryRecord.Status.DAMAGED && !damaged.acceptsDeposits(),
                "损坏了只能取不能存");
        helper.assertTrue(damaged.damagedAtHour() == 5_000L, "记下出事时刻 (宽限从它起算)");
        helper.assertTrue(damaged.tier() == GranaryTier.COLD, "损坏不改档位");
        helper.assertTrue(damaged.repaired().acceptsDeposits()
                && damaged.repaired().damagedAtHour() == 0L, "修好了回到完好, 出事时刻清掉");

        GranaryRecord loaded = GranaryRecord.load(damaged.save());
        helper.assertTrue(loaded.equals(damaged), "登记项原样存读");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void savedDataRoundTripsGranariesLinksAndBinStates(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        GranarySavedData data = GranarySavedData.get(level);
        UUID owner = UUID.randomUUID();
        UUID inspector = UUID.randomUUID();
        ResourceLocation dimension = level.dimension().location();
        long gid = data.nextGranaryId();
        data.putGranary(GranaryRecord.unaccepted(gid, owner).accepted(GranaryTier.STATE, inspector, 9_000L));

        BlockPos active = helper.absolutePos(FAR.offset(20, 0, 0));
        BlockPos stopped = helper.absolutePos(FAR.offset(22, 0, 0));
        // 同上: 先把这个坐标还干净, 认定这两笔登记项确实是本用例写进去的。
        data.releaseBin(dimension, active);
        data.releaseBin(dimension, stopped);
        helper.assertTrue(data.claimBin(dimension, active, owner, 1) && data.claimBin(dimension, stopped, owner, 2),
                "两个囤都应当能入册");
        data.linkBin(dimension, active, gid);
        data.linkBin(dimension, stopped, gid);
        data.setBinState(dimension, active, GranaryBinState.ACTIVE);
        data.setBinState(dimension, stopped, GranaryBinState.STOPPED);

        GranarySavedData reloaded = GranarySavedData.load(data.save(new CompoundTag()));
        helper.assertTrue(owner.equals(reloaded.ownerOf(dimension, active)), "归属随存档");
        helper.assertTrue(reloaded.seqOf(dimension, active) == 1 && reloaded.seqOf(dimension, stopped) == 2,
                "入册序号随存档 (启用顺序就是按它定的)");
        helper.assertTrue(reloaded.granaryOfBin(dimension, active) == gid, "连线随存档");
        helper.assertTrue(reloaded.binsOf(gid).size() == 2, "两座囤都还连在这座粮仓上");
        helper.assertTrue(reloaded.binState(dimension, active) == GranaryBinState.ACTIVE
                && reloaded.binState(dimension, stopped) == GranaryBinState.STOPPED,
                "启用状态连「待启用还是停用」一起随存档");

        GranaryRecord record = reloaded.granary(gid);
        helper.assertTrue(record != null && record.tier() == GranaryTier.STATE
                && record.acceptedBy().equals(inspector) && record.acceptedAtHour() == 9_000L,
                "档位/验收人/验收时刻随存档");
        helper.assertTrue(reloaded.binCount(owner) == 2, "派生计数从存档重建");

        // 拆掉一个囤: 连线与启用状态一并清掉, 不留悬空引用。
        reloaded.releaseBin(dimension, stopped);
        GranarySavedData again = GranarySavedData.load(reloaded.save(new CompoundTag()));
        helper.assertTrue(again.granaryOfBin(dimension, stopped) == 0L, "拆掉之后不再连着粮仓");
        helper.assertTrue(again.binState(dimension, stopped) == GranaryBinState.PENDING,
                "拆掉之后不再记得它停用过 (同一坐标重放一个囤要从待启用起)");
        helper.assertTrue(again.binsOf(gid).size() == 1, "粮仓里只剩一个囤");
        helper.assertTrue(again.binCount(owner) == 1, "额度还回去一个");

        // 收尾: 别把账本留给别批 (存档里那一条登记项没有囤, 不影响任何用例)。
        data.releaseBin(dimension, active);
        data.releaseBin(dimension, stopped);
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loadDropsDanglingIndexesAndRaisesTheIdWatermark(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        ResourceLocation dimension = level.dimension().location();
        UUID owner = UUID.randomUUID();
        BlockPos claimed = helper.absolutePos(FAR.offset(40, 0, 0));
        BlockPos stranger = helper.absolutePos(FAR.offset(42, 0, 0));

        // 手工拼一份「旧档」: 一个已入册的囤、一条登记项 7 号, 外加指向不存在的囤/粮仓的悬空索引,
        // 而且编号水位落后于已用编号 (回档或手工改档就是这个样子)。
        CompoundTag bin = new CompoundTag();
        bin.putString(GranaryConstants.KEY_DIMENSION, dimension.toString());
        bin.putLong(GranaryConstants.KEY_POS, claimed.asLong());
        bin.putUUID(GranaryConstants.KEY_OWNER, owner);
        bin.putInt(GranaryConstants.KEY_SEQ, 1);
        ListTag bins = new ListTag();
        bins.add(bin);

        ListTag granaries = new ListTag();
        granaries.add(GranaryRecord.unaccepted(7L, owner).save());

        ListTag links = new ListTag();
        links.add(link(dimension, claimed, 7L));
        links.add(link(dimension, stranger, 7L));       // 悬空: 这个坐标没有入册的囤
        links.add(link(dimension, claimed, 99L));       // 悬空: 99 号粮仓不存在

        ListTag states = new ListTag();
        states.add(state(dimension, claimed, GranaryBinState.STOPPED));
        states.add(state(dimension, stranger, GranaryBinState.ACTIVE));   // 悬空: 同上

        CompoundTag tag = new CompoundTag();
        tag.putInt(GranaryConstants.KEY_VERSION, GranaryConstants.SAVE_VERSION);
        tag.put(GranaryConstants.KEY_BINS, bins);
        tag.put(GranaryConstants.KEY_CENTERS, new ListTag());
        tag.put(GranaryConstants.KEY_GRANARIES, granaries);
        tag.put(GranaryConstants.KEY_BIN_GRANARY, links);
        tag.put(GranaryConstants.KEY_BIN_STATES, states);
        tag.putLong(GranaryConstants.KEY_NEXT_GID, 3L);

        GranarySavedData loaded = GranarySavedData.load(tag);
        helper.assertTrue(owner.equals(loaded.ownerOf(dimension, claimed)), "归属照读");
        helper.assertTrue(loaded.granary(7L) != null, "登记项照读");
        helper.assertTrue(loaded.granaryOfBin(dimension, claimed) == 7L, "有效连线留下");
        helper.assertTrue(loaded.granaryOfBin(dimension, stranger) == 0L, "悬空连线丢掉, 不是读不进来");
        helper.assertTrue(loaded.binState(dimension, claimed) == GranaryBinState.STOPPED, "有效启用状态留下");
        helper.assertTrue(loaded.binState(dimension, stranger) == GranaryBinState.PENDING,
                "悬空的启用状态丢掉, 不是硬塞给另一个囤");
        helper.assertTrue(loaded.nextGranaryId() == 8L,
                "编号水位按已用编号抬高, 不复用 7 号 (复用会把两座粮仓并成一座)");

        helper.assertTrue(rejects(versionMismatch()), "存档头版本不对直接报错");
        helper.assertTrue(rejects(withStates(state(dimension, claimed, "MELTED"))), "认不出的启用状态直接报错");
        ListTag duplicate = new ListTag();
        duplicate.add(GranaryRecord.unaccepted(7L, owner).save());
        duplicate.add(GranaryRecord.unaccepted(7L, UUID.randomUUID()).save());
        helper.assertTrue(rejects(withGranaries(duplicate)), "重复的粮仓编号直接报错");
        helper.assertTrue(rejects(withBins(bins, bins)), "重复入册的囤直接报错");
        helper.succeed();
    }

    // ============================================================
    // 夹具
    // ============================================================

    /** 一份结构判定结果 (本批只喂数字, 不摆真屋子 —— 真屋子由结构那一批的用例负责)。 */
    private static ScanResult scan(int interiorCells, int bins, int binsNotEnclosed, int centers,
                                  int vents, int coldSources) {
        return scan(interiorCells, bins, binsNotEnclosed, centers, vents, coldSources, Failure.NONE);
    }

    private static ScanResult scan(int interiorCells, int bins, int binsNotEnclosed, int centers,
                                  int vents, int coldSources, Failure failure) {
        return new ScanResult(interiorCells, bins, binsNotEnclosed, centers, vents, coldSources, failure);
    }

    private static CompoundTag link(ResourceLocation dimension, BlockPos pos, long gid) {
        CompoundTag tag = position(dimension, pos);
        tag.putLong(GranaryConstants.KEY_GID, gid);
        return tag;
    }

    private static CompoundTag position(ResourceLocation dimension, BlockPos pos) {
        CompoundTag tag = new CompoundTag();
        tag.putString(GranaryConstants.KEY_DIMENSION, dimension.toString());
        tag.putLong(GranaryConstants.KEY_POS, pos.asLong());
        return tag;
    }

    private static CompoundTag state(ResourceLocation dimension, BlockPos pos, GranaryBinState state) {
        return state(dimension, pos, state.name());
    }

    private static CompoundTag state(ResourceLocation dimension, BlockPos pos, String name) {
        CompoundTag tag = position(dimension, pos);
        tag.putString(GranaryConstants.KEY_STATE, name);
        return tag;
    }

    private static CompoundTag header() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(GranaryConstants.KEY_VERSION, GranaryConstants.SAVE_VERSION);
        tag.put(GranaryConstants.KEY_BINS, new ListTag());
        tag.put(GranaryConstants.KEY_CENTERS, new ListTag());
        tag.put(GranaryConstants.KEY_GRANARIES, new ListTag());
        tag.put(GranaryConstants.KEY_BIN_GRANARY, new ListTag());
        tag.put(GranaryConstants.KEY_BIN_STATES, new ListTag());
        return tag;
    }

    private static CompoundTag versionMismatch() {
        CompoundTag tag = header();
        tag.putInt(GranaryConstants.KEY_VERSION, GranaryConstants.SAVE_VERSION + 1);
        return tag;
    }

    private static CompoundTag withGranaries(ListTag granaries) {
        CompoundTag tag = header();
        tag.put(GranaryConstants.KEY_GRANARIES, granaries);
        return tag;
    }

    private static CompoundTag withStates(CompoundTag state) {
        CompoundTag tag = header();
        ListTag states = new ListTag();
        states.add(state);
        tag.put(GranaryConstants.KEY_BIN_STATES, states);
        return tag;
    }

    private static CompoundTag withBins(ListTag first, ListTag second) {
        CompoundTag tag = header();
        ListTag bins = new ListTag();
        bins.addAll(first);
        bins.addAll(second);
        tag.put(GranaryConstants.KEY_BINS, bins);
        return tag;
    }

    /** 读这份档应当报错 (而不是静默读成半份)。 */
    private static boolean rejects(CompoundTag tag) {
        try {
            GranarySavedData.load(tag);
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }
}
