package com.miningdim.job.farmer.granary;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.farmer.granary.GranaryStructureScanner.Failure;
import com.miningdim.job.farmer.granary.GranaryStructureScanner.ScanResult;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class GranaryAcceptanceGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "granary_acceptance";

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

    private static ScanResult scan(int interiorCells, int bins, int binsNotEnclosed, int centers,
                                  int vents, int coldSources) {
        return scan(interiorCells, bins, binsNotEnclosed, centers, vents, coldSources, Failure.NONE);
    }

    private static ScanResult scan(int interiorCells, int bins, int binsNotEnclosed, int centers,
                                  int vents, int coldSources, Failure failure) {
        return new ScanResult(interiorCells, bins, binsNotEnclosed, centers, vents, coldSources, failure);
    }
}
