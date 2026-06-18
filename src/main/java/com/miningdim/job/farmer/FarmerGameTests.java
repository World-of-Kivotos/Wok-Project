package com.miningdim.job.farmer;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyConstants;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.EconomyWalletData;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.job.JobXpCurve;
import com.miningdim.job.farmer.item.FarmerItems;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

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
    // 当日卖菜株数持久层 (FarmerSavedData: 仅株数 + 日戳, UTC 翻日整条清; 每日信用点 cap 已收敛进货币层)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRecordAccumulatesWithinDay(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF1L, 0xA1L);
        long day = 100L;
        // 同一日内多次记账累加株数 (供收购曲线定位边际单价档)。
        data.recordWheatSale(p, 50, day);
        data.recordWheatSale(p, 30, day);
        helper.assertTrue(data.wheatSoldToday(p, day) == 80, "same-day sold count accumulates to 50+30=80");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRolloverClearsCount(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF2L, 0xB2L);
        data.recordWheatSale(p, 100, 200L);
        helper.assertTrue(data.wheatSoldToday(p, 200L) == 100, "day 200 sold = 100");
        // 翻到下一日: 读取即整条清零 (株数归 0, 不留孤儿残值)。
        helper.assertTrue(data.wheatSoldToday(p, 201L) == 0, "day 201 sold rolls over to 0");
        // 翻日后再记账从新一日 0 起累加 (不继承旧日)。
        data.recordWheatSale(p, 5, 201L);
        helper.assertTrue(data.wheatSoldToday(p, 201L) == 5, "day 201 fresh accumulation = 5");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRolloverRemovesOrphanEntry(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF3L, 0xC3L);
        data.recordWheatSale(p, 10, 300L);
        // 翻日读取触发整条丢弃 (无孤儿日戳滞留); 落盘后重载该玩家应无任何卖菜记录。
        helper.assertTrue(data.wheatSoldToday(p, 301L) == 0, "rolled-over day reads 0 sold");
        net.minecraft.nbt.CompoundTag saved = data.save(new net.minecraft.nbt.CompoundTag());
        FarmerSavedData reloaded = FarmerSavedData.load(saved);
        helper.assertTrue(reloaded.wheatSoldToday(p, 301L) == 0,
                "reloaded data has no orphan stamp: rolled-over entry was removed before save");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void wheatSaleRecordRoundTrips(GameTestHelper helper) {
        FarmerSavedData data = new FarmerSavedData();
        java.util.UUID p = new java.util.UUID(0xF4L, 0xD4L);
        data.recordWheatSale(p, 2200, 400L); // 株数超 cap。
        net.minecraft.nbt.CompoundTag saved = data.save(new net.minecraft.nbt.CompoundTag());
        FarmerSavedData reloaded = FarmerSavedData.load(saved);
        helper.assertTrue(reloaded.wheatSoldToday(p, 400L) == 2200, "sold count survives save/load round-trip");
        helper.succeed();
    }

    // ============================================================
    // 卖菜端到端: 触发点可达 (Critical 1) + 经 EconomyServices 定位器真发币 (Critical 2) + 并入全服每日
    // 信用点 faucet 软上限 (Major)。经济门面注册进 EconomyServices 定位器后, FarmerWheatSellService.sell
    // 必须真扣库存小麦、真增当日卖出株数、经 grantDaily 真入账。删任一修复测试必挂 (见各断言注释)。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sellGrantsCreditsAndDecrementsInventory(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();
        EconomyWalletData ledger = registerFreshEconomy();
        try {
            // 给 100 株 mod 小麦 (远低于收购 softCap 2160, 故全价 base=1 -> 毛收 100)。
            int amount = 100;
            long today = FarmerClock.currentUtcDayStamp();
            FarmerSavedData data = FarmerSavedData.get(player.server.overworld());
            int soldBefore = data.wheatSoldToday(player.getUUID(), today); // 基线 (共享持久层, 取增量防跨测试串扰)。
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), amount));

            FarmerWheatSellService.SellResult result = FarmerWheatSellService.sell(player, amount);

            // 触发点可达且经济已注册 -> 非 offline (删 /farmer sell 触发点或回退 isBound 死 seam 即 offline, 此断言挂)。
            helper.assertFalse(result.economyOffline(),
                    "sell with registered economy must not be offline (trigger point + locator wired)");
            helper.assertTrue(result.soldCount() == amount,
                    "sell removes all " + amount + " wheat, got soldCount=" + result.soldCount());

            // 库存小麦清零 (先扣后发, 真扣物品)。
            int leftover = player.getInventory().clearOrCountMatchingItems(
                    s -> s.is(FarmerItems.FARMER_WHEAT.get()), 0, new net.minecraft.world.SimpleContainer(0));
            helper.assertTrue(leftover == 0, "inventory mod wheat decremented to 0 after sale, got " + leftover);

            // 当日卖出株数 += amount (收购曲线计数真增; 取增量, 不依赖共享持久层的绝对值)。
            int soldAfter = data.wheatSoldToday(player.getUUID(), today);
            helper.assertTrue(soldAfter - soldBefore == amount,
                    "wheatSoldToday increased by exactly " + amount + " after sale, delta="
                            + (soldAfter - soldBefore));

            // 经 EconomyServices 定位器真入账 (删定位器调用或回退死 seam -> grant 不发生, 余额 0, 此断言挂)。
            // base=1, 全在 softCap 内 -> 毛收 100; 全在每日 faucet 首档 (cap=2160) 内 -> 全额入账 100。
            helper.assertTrue(result.creditsGranted() == 100L,
                    "100 wheat at base 1 within both caps grants 100 credits, got " + result.creditsGranted());
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 100L,
                    "wallet credit balance reflects the granted 100 via the economy locator");
        } finally {
            EconomyServices.reset();
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sellWhenEconomyUnregisteredIsOffline(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();
        EconomyServices.reset(); // 确保未注册 (定位器空 -> sell 应判 offline, 不扣不发)。
        try {
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), 10));

            FarmerWheatSellService.SellResult result = FarmerWheatSellService.sell(player, 10);

            // 未注册经济 -> offline, 不扣物品 (回退为 "未注册仍发币" 或抛 IllegalStateException 此断言挂)。
            helper.assertTrue(result.economyOffline(),
                    "sell with no registered economy must return offline (isRegistered gate)");
            helper.assertTrue(result.soldCount() == 0, "offline sale removes nothing");
            helper.assertTrue(result.creditsGranted() == 0L, "offline sale grants nothing");
            int kept = player.getInventory().clearOrCountMatchingItems(
                    s -> s.is(FarmerItems.FARMER_WHEAT.get()), 0, new net.minecraft.world.SimpleContainer(0));
            helper.assertTrue(kept == 10, "offline sale keeps all 10 wheat in inventory, got " + kept);
        } finally {
            EconomyServices.reset();
        }
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sellSharesPerPlayerDailyFaucetCapWithOtherFaucets(GameTestHelper helper) {
        // Major: 卖菜并入全服每人每日信用点 faucet 软上限 (与其它 faucet 共享 WHEAT_SELL_FAUCET_KEY 命名空间),
        // 而非农夫私有 per-player 上限。先用同一 faucetKey 把当日累计原始信用点推到 2*cap (模拟矿工卖矿先发两档),
        // 再卖菜: 卖菜落进第二衰减档 (实发 < 毛收), 证明它读的是共享 faucet 计数器而非农夫私有上限。
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        player.getInventory().clearContent();
        EconomyWalletData ledger = registerFreshEconomy();
        try {
            long cap = FarmerConstants.DAILY_CREDIT_FAUCET_CAP; // 2160
            String sharedKey = FarmerConstants.WHEAT_SELL_FAUCET_KEY; // credit_faucet

            // 另一 faucet (如矿工卖矿) 先用同一 key 发两个完整 cap 档, 累计原始信用点推到 2*cap。
            // 第一档全额 cap; 第二档 floor(cap*0.97)。累计计数器 (原始, 非衰减后) = 2*cap。
            long firstTier = EconomyServices.economyService().grantDaily(player, cap, sharedKey, cap);
            long secondTier = EconomyServices.economyService().grantDaily(player, cap, sharedKey, cap);
            helper.assertTrue(firstTier == cap, "first cap-batch full (got " + firstTier + ")");
            helper.assertTrue(secondTier == (long) Math.floor(cap * EconomyConstants.ECONOMY_DECAY_BASE),
                    "second cap-batch decays one tier x0.97 (got " + secondTier + ")");

            // 卖 100 株小麦 (毛收 100, base=1 全在收购 softCap 内)。共享累计原始 = 2*cap, 本批 over=cap+100,
            // tier = (cap+100)/cap = 1 -> ratio 0.97 -> floor(100*0.97)=97。若卖菜走农夫私有上限 (回退), 则不受
            // 前述同 key faucet 影响, 全额 100, 此断言挂。
            int amount = 100;
            player.getInventory().add(new ItemStack(FarmerItems.FARMER_WHEAT.get(), amount));
            FarmerWheatSellService.SellResult result = FarmerWheatSellService.sell(player, amount);

            helper.assertTrue(result.soldCount() == amount, "all wheat sold");
            long expected = (long) Math.floor(100 * EconomyConstants.ECONOMY_DECAY_BASE);
            helper.assertTrue(result.creditsGranted() == expected,
                    "wheat sale sharing the daily faucet key is already in decay tier x0.97: floor(100*0.97)="
                            + expected + ", got " + result.creditsGranted());
            // 账本余额 = 三笔实发之和 (共享同一玩家钱包): cap + floor(cap*0.97) + decayed wheat sale。
            long expectedBalance = firstTier + secondTier + expected;
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == expectedBalance,
                    "wallet equals sum of all post-decay grants on the shared per-player cap (not a private cap)");
        } finally {
            EconomyServices.reset();
        }
    }

    // ---- 测试辅助 (与 FarmerSystem.onCropHarvested 的原始经验公式同源) ----

    /**
     * 新建一套内存经济门面 (账本 + AbuseGuard + 惰性 PlayerAbuseState 解析器) 注册进 {@link EconomyServices} 定位器,
     * 供卖菜端到端测试经定位器真发币。返回账本以便断言余额。调用方 finally 务必 {@link EconomyServices#reset()}。
     */
    private static EconomyWalletData registerFreshEconomy() {
        EconomyWalletData ledger = new EconomyWalletData();
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        Function<UUID, PlayerAbuseState> resolver = id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
        EconomyServices.reset();
        EconomyServices.registerEconomyService(new EconomyService(ledger, new AbuseGuard(), resolver));
        return ledger;
    }

    private static long rawXpForTier(FarmerTier tier) {
        return (long) FarmerConstants.SINGLE_CROP_XP * tier.yieldPerHarvest();
    }

    /** 高级地每小时收获次数 = 60min / 6min间隔 = 10 (表B; 仅吞吐校验用)。 */
    private static int harvestsPerHourHigh() {
        return 60 / FarmerTier.HIGH.growthIntervalMinutes();
    }
}
