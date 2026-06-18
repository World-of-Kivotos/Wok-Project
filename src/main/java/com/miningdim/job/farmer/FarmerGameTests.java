package com.miningdim.job.farmer;

import com.miningdim.core.MiningConstants;
import com.miningdim.job.JobXpCurve;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 农夫职业核心逻辑 GameTest (FarmingXP_Mod_DesignSpec 第六节测试断言 + 实现手册 GameTest 范式)。
 *
 * 断言具体业务结果 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验):
 *  - 放置上限/档位门控 (表A/表B): L1 第10块拒、L2 第13块拒、L4 拒高级地/L5 通;
 *  - 收获经验入账经框架 2000 系衰减 (表B 吞吐 + JobXpCurve): L5 满 25 块高级地 6h 入账精确值;
 *  - 经验硬顶边界 (9500 原始那一档): 跨 3800 有效经验前后单株有效经验差异;
 *  - 收购价递减 (第八节方案4): softCap 前后单株单价、卖菜跨边界总价连续;
 *  - 五档耕地参数表 (表B): 解锁等级/产量/成长间隔自洽。
 *
 * 纯逻辑断言不依赖结构, 用 template = "empty"。涉及 capability 挂载/世界写的端到端 (作物成长/破坏掉落)
 * 在 FarmerSystem 接入 MiningDim 后才生效 (本任务不接线), 故此处验证驱动这些事件的纯裁决/纯函数逻辑
 * (与挂载后玩家身上运行的同一份逻辑)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FarmerGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "farmer";

    // ============================================================
    // 放置上限 + 档位门控 (表A 方块上限 / 表B 解锁等级)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void placementCapPerLevel(GameTestHelper helper) {
        // 表A: L1 上限 9, L2 上限 12。capForLevel 精确查表。
        helper.assertTrue(FarmlandPlacementGuard.capForLevel(1) == 9, "L1 farmland cap must be 9");
        helper.assertTrue(FarmlandPlacementGuard.capForLevel(2) == 12, "L2 farmland cap must be 12");
        helper.assertTrue(FarmlandPlacementGuard.capForLevel(5) == 25, "L5 farmland cap must be 25");
        helper.assertTrue(FarmlandPlacementGuard.capForLevel(10) == 64, "L10 farmland cap must be 64");

        // L1 放第 10 块低级地被拒 (已放 9 = 上限, 第 10 块越界)。spec 第244行 "第13块在L1被拒" 是笔误,
        // 以表A L1=9 为准: 第 10 块拒。
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.LOW, 1, 9)
                        == FarmlandPlacementGuard.PlaceResult.REJECT_CAP_REACHED,
                "L1: placing 10th low farmland (already 9) is rejected by cap");
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.LOW, 1, 8)
                        == FarmlandPlacementGuard.PlaceResult.ALLOW,
                "L1: placing 9th low farmland (already 8) is allowed (cap 9)");

        // L2 放第 13 块低级地被拒 (上限 12, 已放 12, 第 13 越界)。
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.LOW, 2, 12)
                        == FarmlandPlacementGuard.PlaceResult.REJECT_CAP_REACHED,
                "L2: placing 13th low farmland (already 12) is rejected by cap");
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.LOW, 2, 11)
                        == FarmlandPlacementGuard.PlaceResult.ALLOW,
                "L2: placing 12th low farmland (already 11) is allowed (cap 12)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tierUnlockGate(GameTestHelper helper) {
        // 高级地解锁等级 L5: L4 玩家放高级地被拒, L5 玩家通 (表B 解锁等级边界)。
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.HIGH, 4, 0)
                        == FarmlandPlacementGuard.PlaceResult.REJECT_TIER_LOCKED,
                "L4 player cannot place HIGH farmland (unlocks at L5)");
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.HIGH, 5, 0)
                        == FarmlandPlacementGuard.PlaceResult.ALLOW,
                "L5 player can place HIGH farmland (just unlocked)");
        // 超凡地解锁 L9: L8 拒 / L9 通。
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.SUPREME, 8, 0)
                        == FarmlandPlacementGuard.PlaceResult.REJECT_TIER_LOCKED,
                "L8 player cannot place SUPREME farmland (unlocks at L9)");
        helper.assertTrue(
                FarmlandPlacementGuard.checkPlacement(FarmerTier.SUPREME, 9, 0)
                        == FarmlandPlacementGuard.PlaceResult.ALLOW,
                "L9 player can place SUPREME farmland");
        // 门控优先于上限: 即便已放 0 块, 档位未解锁仍先拒 (顺序: 档位 -> 上限)。
        helper.succeed();
    }

    // ============================================================
    // 收获经验入账经框架 2000 系衰减 (表B 吞吐 + JobXpCurve.applyDailyDecay)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void singleHarvestRawXp(GameTestHelper helper) {
        // 表B: 单作物经验固定 2; 一次成熟破坏的原始经验 = 2 × 该档产量。
        helper.assertTrue(rawXpForTier(FarmerTier.LOW) == 4L, "LOW harvest raw xp = 2 * yield 2 = 4");
        helper.assertTrue(rawXpForTier(FarmerTier.MEDIUM) == 6L, "MEDIUM harvest raw xp = 2 * yield 3 = 6");
        helper.assertTrue(rawXpForTier(FarmerTier.HIGH) == 8L, "HIGH harvest raw xp = 2 * yield 4 = 8");
        helper.assertTrue(rawXpForTier(FarmerTier.PREMIUM) == 10L, "PREMIUM harvest raw xp = 2 * yield 5 = 10");
        helper.assertTrue(rawXpForTier(FarmerTier.SUPREME) == 12L, "SUPREME harvest raw xp = 2 * yield 6 = 12");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void l5HighFarmSixHourEffectiveXp(GameTestHelper helper) {
        // 表B: 高级地原始吞吐 = 收获/时(10) × 产量(4) × 单作物经验(2) = 80 经验/块/时。
        // L5 满 25 块: 25 × 80 = 2000 原始/时; 6h = 12,000 原始。
        long rawPerBlockHour = (long) harvestsPerHourHigh() * FarmerTier.HIGH.yieldPerHarvest() * FarmerConstants.SINGLE_CROP_XP;
        helper.assertTrue(rawPerBlockHour == 80L, "HIGH throughput = 10*4*2 = 80 raw xp/block/hour (table B)");
        long sixHourRaw = 25L * rawPerBlockHour * 6L;
        helper.assertTrue(sixHourRaw == 12_000L, "L5 25 HIGH blocks 6h = 12000 raw xp");

        // 经框架 2000 系衰减 (单源真值 JobXpCurve, 取代 spec 表C T=1500)。权威模型为 "有效经验容量模型" (B 解释):
        // 分段边界划分有效经验轴, 每段按系数折算原始去填满该段有效容量。当日 0 起入 12000 原始 ->
        // [0,2000) 2000 原始填满 2000 有效 + [2000,2800) 2000 原始填满 800 有效 + [2800,3400) 3000 原始填满 600 有效
        // + [3400,3800) 5000 原始填满 400 有效 (累计原始 12000 恰好填到 3800) = 2000+800+600+400 = 3800 有效经验。
        // 说明: 旧实现误把分段边界当原始经验轴 (A 解释) 得 2636, 与工程师 spec 第八章 "12000 原始 -> 3800" 定值
        // 及农夫 spec FarmingXP_Mod_DesignSpec.md:79-85 "该段累计需要的原始经验" 同源模型背离, 已修正为 3800。
        long effective = JobXpCurve.applyDailyDecay(0L, sixHourRaw);
        helper.assertTrue(effective == 3_800L,
                "L5 25 HIGH blocks 6h decays to 3800 effective xp under the effective-capacity model "
                        + "(matches engineer spec ch.8 12000 raw -> 3800; old 2636 used the wrong axis)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dailyHardCapBoundary(GameTestHelper helper) {
        // 经验硬顶档 (框架末档 3800 有效经验之后 x0.02): 跨 3800 边界单株有效经验差异。
        // 一株超凡作物原始 12: 当日 3799 有效起入 12 -> [3799,3800)1*0.08 + [3800,3811)11*0.02 = floor(0.08+0.22)=0;
        // 当日 3800 有效起入 12 -> 全在末档 12*0.02 = floor(0.24)=0。两者都 floor 到 0 (末档近乎归零)。
        // 用更大的批量凸显边界: 当日 3000 起入 12 (全在 [2800,3400) x0.2) = floor(2.4)=2; 当日 3800 起入 12 = 0。
        long midSegment = JobXpCurve.applyDailyDecay(3_000L, 12L);
        long lastSegment = JobXpCurve.applyDailyDecay(3_800L, 12L);
        helper.assertTrue(midSegment == 2L, "12 raw at dailyXp 3000 (x0.2) -> 2 effective");
        helper.assertTrue(lastSegment == 0L, "12 raw at dailyXp 3800 (x0.02) -> floor(0.24) = 0 effective (hard cap)");
        helper.assertTrue(midSegment > lastSegment,
                "crossing into the last decay segment strictly reduces effective xp per harvest");
        helper.succeed();
    }

    // ============================================================
    // NPC 小麦动态收购价 (第八节方案4 — 与经验衰减独立)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatBuybackDecay(GameTestHelper helper) {
        long base = FarmerConstants.WHEAT_BASE_PRICE; // 1
        int cap = FarmerConstants.WHEAT_DAILY_SOFTCAP; // 2160
        // softCap 内全价: 第 1 株与第 cap 株均 = basePrice。
        helper.assertTrue(FarmerWheatBuyback.wheatBuyPrice(1, base) == base, "first wheat at full base price");
        helper.assertTrue(FarmerWheatBuyback.wheatBuyPrice(cap, base) == base, "wheat at softCap still full price");
        // 超 softCap 衰减: 第 cap+1 株价 = floor(base * 0.97^1)。base=1 时 floor(0.97)=0 -> 触地板比例。
        // 用更大 basePrice 校验衰减形状 (与曲线无关于 base 的比例一致): base=1000。
        long b = 1000L;
        helper.assertTrue(FarmerWheatBuyback.wheatBuyPrice(cap, b) == 1000L, "at softCap full price (base 1000)");
        helper.assertTrue(FarmerWheatBuyback.wheatBuyPrice(cap + 1, b) == (long) Math.floor(1000 * 0.97D),
                "at softCap+1 price = floor(base * 0.97^1) = 970");
        helper.assertTrue(FarmerWheatBuyback.wheatBuyPrice(cap + 2, b) == (long) Math.floor(1000 * Math.pow(0.97D, 2)),
                "at softCap+2 price = floor(base * 0.97^2)");
        // 地板: 大量超出后价不低于 base * floorRatio (0.25)。
        long deepPrice = FarmerWheatBuyback.wheatBuyPrice(cap + 100000, b);
        helper.assertTrue(deepPrice == (long) Math.floor(1000 * FarmerConstants.WHEAT_PRICE_FLOOR_RATIO),
                "deep over-cap price floors at base * 0.25 = 250");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatBuybackTotalIsContinuous(GameTestHelper helper) {
        long b = 1000L;
        int cap = FarmerConstants.WHEAT_DAILY_SOFTCAP;
        // 跨 softCap 边界逐株求和 = 各株单价之和 (连续, 无跳变)。卖 3 株, 起点 cap-1:
        // 第 cap 株 full(1000) + 第 cap+1 株 floor(970) + 第 cap+2 株 floor(940.9)=940 = 1000+970+940 = 2910。
        long total = FarmerWheatBuyback.totalBuyPrice(cap - 1, 3, b);
        long expected = FarmerWheatBuyback.wheatBuyPrice(cap, b)
                + FarmerWheatBuyback.wheatBuyPrice(cap + 1, b)
                + FarmerWheatBuyback.wheatBuyPrice(cap + 2, b);
        helper.assertTrue(total == expected, "batch total equals per-wheat sum across softCap boundary (continuous)");
        helper.assertTrue(total == 2910L, "3 wheat from cap-1: 1000 + 970 + 940 = 2910");
        helper.succeed();
    }

    // ============================================================
    // 五档耕地参数表自洽 (表B)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tierParamsTableB(GameTestHelper helper) {
        // 解锁等级: 每两级一档 (表B / 表A)。
        helper.assertTrue(FarmerTier.LOW.unlockLevel() == 1, "LOW unlocks L1");
        helper.assertTrue(FarmerTier.MEDIUM.unlockLevel() == 3, "MEDIUM unlocks L3");
        helper.assertTrue(FarmerTier.HIGH.unlockLevel() == 5, "HIGH unlocks L5");
        helper.assertTrue(FarmerTier.PREMIUM.unlockLevel() == 7, "PREMIUM unlocks L7");
        helper.assertTrue(FarmerTier.SUPREME.unlockLevel() == 9, "SUPREME unlocks L9");
        // 产量: 2/3/4/5/6 (表B 每次产量)。
        helper.assertTrue(FarmerTier.LOW.yieldPerHarvest() == 2, "LOW yield 2");
        helper.assertTrue(FarmerTier.SUPREME.yieldPerHarvest() == 6, "SUPREME yield 6");
        // 成长间隔 tick: 低 10min = 12000 tick; 超凡 4min = 4800 tick。
        helper.assertTrue(FarmerTier.LOW.growthIntervalTicks() == 10L * 60L * 20L, "LOW grow interval = 12000 ticks");
        helper.assertTrue(FarmerTier.SUPREME.growthIntervalTicks() == 4L * 60L * 20L, "SUPREME grow interval = 4800 ticks");
        // 单作物经验固定 2 (表B 主方案)。
        helper.assertTrue(FarmerConstants.SINGLE_CROP_XP == 2, "single crop xp fixed at 2 (table B main plan)");
        helper.succeed();
    }

    // ============================================================
    // 当日卖菜记录持久层 (FarmerSavedData: 株数 + 已发信用点 同条记录, UTC 翻日整条清)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRecordAccumulatesWithinDay(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF1L, 0xA1L);
        long day = 100L;
        // 同一日内多次记账累加株数与信用点 (两量同存一条记录)。
        data.recordWheatSale(p, 50, 50L, day);
        data.recordWheatSale(p, 30, 28L, day);
        helper.assertTrue(data.wheatSoldToday(p, day) == 80, "same-day sold count accumulates to 50+30=80");
        helper.assertTrue(data.wheatCreditedToday(p, day) == 78L, "same-day credited accumulates to 50+28=78");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRolloverClearsBothCountAndCredits(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF2L, 0xB2L);
        data.recordWheatSale(p, 100, 100L, 200L);
        helper.assertTrue(data.wheatSoldToday(p, 200L) == 100, "day 200 sold = 100");
        helper.assertTrue(data.wheatCreditedToday(p, 200L) == 100L, "day 200 credited = 100");
        // 翻到下一日: 读取即整条清零 (株数与信用点都归 0, 不留任一量的孤儿残值)。
        helper.assertTrue(data.wheatSoldToday(p, 201L) == 0, "day 201 sold rolls over to 0");
        helper.assertTrue(data.wheatCreditedToday(p, 201L) == 0L, "day 201 credited rolls over to 0");
        // 翻日后再记账从新一日 0 起累加 (不继承旧日)。
        data.recordWheatSale(p, 5, 5L, 201L);
        helper.assertTrue(data.wheatSoldToday(p, 201L) == 5, "day 201 fresh accumulation = 5");
        helper.assertTrue(data.wheatCreditedToday(p, 201L) == 5L, "day 201 fresh credited = 5");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRolloverRemovesOrphanEntry(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF3L, 0xC3L);
        data.recordWheatSale(p, 10, 10L, 300L);
        // 翻日读取触发整条丢弃 (无孤儿日戳滞留); 落盘后重载该玩家应无任何卖菜记录。
        helper.assertTrue(data.wheatSoldToday(p, 301L) == 0, "rolled-over day reads 0 sold");
        net.minecraft.nbt.CompoundTag saved = data.save(new net.minecraft.nbt.CompoundTag());
        FarmerSavedData reloaded = FarmerSavedData.load(saved);
        helper.assertTrue(reloaded.wheatSoldToday(p, 301L) == 0,
                "reloaded data has no orphan stamp: rolled-over entry was removed before save");
        helper.assertTrue(reloaded.wheatCreditedToday(p, 301L) == 0L,
                "reloaded data carries no orphan credited residue");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRecordRoundTrips(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF4L, 0xD4L);
        data.recordWheatSale(p, 2200, 2160L, 400L); // 株数超 cap, 信用点已被调用方钳到日上限 2160。
        net.minecraft.nbt.CompoundTag saved = data.save(new net.minecraft.nbt.CompoundTag());
        FarmerSavedData reloaded = FarmerSavedData.load(saved);
        helper.assertTrue(reloaded.wheatSoldToday(p, 400L) == 2200, "sold count survives save/load round-trip");
        helper.assertTrue(reloaded.wheatCreditedToday(p, 400L) == 2160L,
                "credited amount survives save/load (persisted, not reconstructed from buyback curve)");
        helper.succeed();
    }

    // ---- 测试辅助 (与 FarmerSystem.onCropHarvested 的原始经验公式同源) ----

    private static long rawXpForTier(FarmerTier tier) {
        return (long) FarmerConstants.SINGLE_CROP_XP * tier.yieldPerHarvest();
    }

    /** 高级地每小时收获次数 = 60min / 6min间隔 = 10 (表B; 仅吞吐校验用)。 */
    private static int harvestsPerHourHigh() {
        return 60 / FarmerTier.HIGH.growthIntervalMinutes();
    }
}
