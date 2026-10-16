package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 腐烂底座的纯逻辑 GameTest: 时钟裁决与物品保鲜章
 * (docs/Spoilage_Granary_DesignSpec.md「时钟」「物品数据与存档」)。
 *
 * 全部断言为具体数值, 不依赖世界对象, 也不读配置 —— 保质期一律当参数传进去, 免得配置改动把用例带红。
 * 时钟测试一律新建独立实例, 不碰主世界的 {@code miningdim_freshness_clock}。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class FreshnessGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "freshness_foundation";

    /** 一个固定的挂钟小时序号, 避免用例受真实时间影响。 */
    private static final long WALL_HOUR = 500_000L;

    // ============================================================
    // 时钟: 只增不减 + 停服补偿 + 跳变按补偿
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clockStampsFirstObservationAndAdvancesNormally(GameTestHelper helper) {
        FreshnessClock clock = new FreshnessClock();
        helper.assertTrue(clock.observeHour(WALL_HOUR) == 0L,
                "首次观察不得补偏移 (还没有心跳可比)");
        helper.assertTrue(clock.lastSeenHour() == WALL_HOUR, "首次观察必须记下心跳");
        helper.assertTrue(clock.observeHour(WALL_HOUR + 1L) == 0L, "相邻整点属正常前进");
        helper.assertTrue(clock.offsetHours() == 0L, "正常前进不产生偏移");
        helper.assertTrue(clock.nowMinute(WALL_HOUR + 1L, 30L) == (WALL_HOUR + 1L) * 60L + 30L,
                "没有偏移时「现在」就是挂钟本身");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clockCompensatesStartupDowntimeInWholeHours(GameTestHelper helper) {
        FreshnessClock clock = new FreshnessClock();
        clock.observeHour(WALL_HOUR);
        // 心跳停在 WALL_HOUR, 5 小时后开服: 这 5 小时服务器没跑, 一律补进偏移 (食物不腐烂)。
        helper.assertTrue(clock.compensateStartupFrom(WALL_HOUR, WALL_HOUR + 5L) == 5L,
                "停服满 1 小时按整小时补");
        helper.assertTrue(clock.offsetHours() == 5L, "补偿量必须落进偏移");
        helper.assertTrue(clock.nowMinute(WALL_HOUR + 5L, 0L) == WALL_HOUR * 60L,
                "补 5 小时后「现在」仍停在下线那一刻");
        helper.assertTrue(clock.compensateStartupFrom(FreshnessClock.UNSET_HOUR, WALL_HOUR) == 0L,
                "没有心跳可依据时不补 (首次开服)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clockTreatsRuntimeJumpAsCompensationAndNeverFreezes(GameTestHelper helper) {
        FreshnessClock clock = new FreshnessClock();
        clock.observeHour(WALL_HOUR);
        // 进程挂起 / 宿主机休眠 / 对时跳变: 一次推进 3 小时, 补「差值 − 1」= 2 小时。
        helper.assertTrue(clock.observeHour(WALL_HOUR + 3L) == 2L,
                "一次推进 3 小时必须补 2 小时 (只留 1 小时正常流动)");
        helper.assertTrue(clock.offsetHours() == 2L, "补的量进偏移");
        // 挂钟往回跳 (回档 / NTP 回拨): 只增不减, 不倒退、也不冻结。
        long beforeJump = clock.offsetHours();
        helper.assertTrue(clock.observeHour(WALL_HOUR - 10L) == 0L, "挂钟回跳不产生新偏移");
        helper.assertTrue(clock.offsetHours() == beforeJump, "偏移只增不减");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clockTestOffsetEntryIsClampedToNonNegative(GameTestHelper helper) {
        FreshnessClock clock = new FreshnessClock();
        clock.setOffsetHoursForTest(-5L);
        helper.assertTrue(clock.offsetHours() == 0L, "测试入口同样受「只增不减」约束");
        clock.setOffsetHoursForTest(504L);
        helper.assertTrue(clock.offsetHours() == 504L, "测试入口可以落一个正偏移");
        helper.succeed();
    }

    // ============================================================
    // 物品保鲜章: 唯一 writer + 剩余现算 + 钳制
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void stampRoundsDownToWholeHourAndOmitsZeroStopHours(GameTestHelper helper) {
        ItemStack beef = new ItemStack(Items.BEEF);
        helper.assertTrue(!FreshnessData.isStamped(beef), "新物品不该带章");
        FreshnessData.stamp(beef, WALL_HOUR * 60L + 37L);
        helper.assertTrue(FreshnessData.batchMinute(beef) == WALL_HOUR * 60L,
                "批次时刻取拿到时的整点");
        helper.assertTrue(FreshnessData.stoppedHours(beef) == 0, "刚拿到手没有停表额度");
        var root = beef.getTag().getCompound(FreshnessConstants.ROOT);
        helper.assertTrue(root.contains(FreshnessConstants.KEY_BATCH, Tag.TAG_INT),
                "批次时刻必须是 IntTag (LongTag 会让同一批货叠不上)");
        helper.assertTrue(!root.contains(FreshnessConstants.KEY_STOPPED),
                "f = 0 不写");
        FreshnessData.stamp(beef, WALL_HOUR * 60L + 600L);
        helper.assertTrue(FreshnessData.batchMinute(beef) == WALL_HOUR * 60L,
                "已盖章的不再改章");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void remainingIsBatchPlusStopPlusShelfMinusNow(GameTestHelper helper) {
        ItemStack beef = new ItemStack(Items.BEEF);
        FreshnessData.stamp(beef, WALL_HOUR * 60L);
        long tenHoursLater = WALL_HOUR * 60L + 10L * 60L;
        helper.assertTrue(FreshnessData.remainingMinutes(beef, 48, tenHoursLater) == 38L * 60L,
                "48 小时制走了 10 小时应剩 38 小时");
        FreshnessData.setStoppedHours(beef, 5);
        helper.assertTrue(FreshnessData.stoppedHours(beef) == 5, "停表小时可写");
        helper.assertTrue(FreshnessData.remainingMinutes(beef, 48, tenHoursLater) == 43L * 60L,
                "停表 5 小时让剩余多出 5 小时");
        FreshnessData.setStoppedHours(beef, 9_999);
        helper.assertTrue(FreshnessData.stoppedHours(beef) == FreshnessConstants.STOP_CAP_HOURS,
                "停表小时必须钳到 21 天上限");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void rottenWhenRemainingReachesZero(GameTestHelper helper) {
        ItemStack cod = new ItemStack(Items.COD);
        FreshnessData.stamp(cod, WALL_HOUR * 60L);
        helper.assertTrue(!FreshnessData.isRotten(cod, 12, WALL_HOUR * 60L + 12L * 60L - 1L),
                "差一分钟还不算烂");
        helper.assertTrue(FreshnessData.isRotten(cod, 12, WALL_HOUR * 60L + 12L * 60L),
                "剩余 0 就算腐烂");
        helper.assertTrue(FreshnessData.isRotten(cod, 12, WALL_HOUR * 60L + 13L * 60L),
                "过了保质期当然算腐烂");
        ItemStack unstamped = new ItemStack(Items.COD);
        helper.assertTrue(FreshnessData.remainingMinutes(unstamped, 12, WALL_HOUR * 60L) == 12L * 60L,
                "还没盖章的按「到手开始计时」, 剩余是一整个保质期");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void clampPullsFutureBatchBackAndCapsStopHours(GameTestHelper helper) {
        ItemStack beef = new ItemStack(Items.BEEF);
        FreshnessData.stampAt(beef, (WALL_HOUR + 100L) * 60L, 9_999);
        FreshnessData.clamp(beef, WALL_HOUR * 60L);
        helper.assertTrue(FreshnessData.batchMinute(beef) == WALL_HOUR * 60L,
                "未来的批次时刻一律压回现在");
        helper.assertTrue(FreshnessData.stoppedHours(beef) == FreshnessConstants.STOP_CAP_HOURS,
                "超额的停表小时压回上限");
        helper.succeed();
    }

    // ============================================================
    // 合并比较器 + 村民货标记
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sameExceptFreshnessIgnoresOnlyBatchAndStopHours(GameTestHelper helper) {
        ItemStack fresh = new ItemStack(Items.BEEF);
        FreshnessData.stamp(fresh, WALL_HOUR * 60L);
        ItemStack older = new ItemStack(Items.BEEF);
        FreshnessData.stampAt(older, WALL_HOUR * 60L - 5L * 60L, 3);
        helper.assertTrue(FreshnessData.sameExceptFreshness(fresh, older),
                "只差新鲜度的同种货可以合并");
        ItemStack renamed = new ItemStack(Items.BEEF);
        renamed.setHoverName(net.minecraft.network.chat.Component.literal("test"));
        helper.assertTrue(!FreshnessData.sameExceptFreshness(fresh, renamed),
                "其它数据不同就不许合并");
        helper.assertTrue(!FreshnessData.sameExceptFreshness(fresh, new ItemStack(Items.COOKED_BEEF)),
                "不同物品当然不合并");
        ItemStack migrated = new ItemStack(Items.BEEF);
        FreshnessData.stamp(migrated, WALL_HOUR * 60L);
        FreshnessData.markMigrated(migrated);
        helper.assertTrue(!FreshnessData.sameExceptFreshness(fresh, migrated),
                "迁移货标记参与比较 (永不和新货合并)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void villagerGoodsLandAtSixHoursRemaining(GameTestHelper helper) {
        ItemStack bread = new ItemStack(Items.BREAD);
        long now = WALL_HOUR * 60L;
        FreshnessData.markVillagerGoods(bread, now, 48);
        helper.assertTrue(FreshnessData.isVillagerGoods(bread), "村民货要带标记");
        helper.assertTrue(FreshnessData.remainingMinutes(bread, 48, now) == 6L * 60L,
                "村民卖出的食物到手只剩 6 小时");
        helper.succeed();
    }

    // ============================================================
    // 给消费点用的查询 (FreshnessCheck)
    // ============================================================

    /**
     * 消费点 (`/farmer sell`、酿酒台、任务上交、市场) 用的那两个查询: **不腐烂的东西永远不算烂**。
     *
     * 这是一道很贵的门槛: 直接拼 `isRotten(stack, shelfLifeHours(stack), now)` 时, 骨头、火药、
     * 下界之星这些 `shelfLife == 0` 的上交物会被算成「剩余 0 分钟」而整批拒收 —— 这条用例就是钉它的。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void checkTreatsNonPerishablesAsNeverRotten(GameTestHelper helper) {
        long now = 500_000L * 60L;
        helper.assertTrue(!FreshnessCheck.isRottenNow(new ItemStack(Items.STONE, 1), now),
                "石头不腐烂, 永远不算烂");
        helper.assertTrue(!FreshnessCheck.isRottenNow(new ItemStack(Items.ROTTEN_FLESH, 1), now),
                "腐肉是豁免品 (它本来就是烂的), 任务上交照收");
        helper.assertTrue(!FreshnessCheck.isRottenNow(ItemStack.EMPTY, now), "空栈不算烂");
        helper.assertTrue(FreshnessCheck.remainingMinutes(new ItemStack(Items.STONE, 1), now) == Long.MAX_VALUE,
                "不腐烂的东西排到最后 (消费点按「先扣最老的」排序时不会被当成最老的那批)");

        // 会腐烂的: 盖一个过期的章就算烂, 没盖章的按整段保质期算 (不烂)。
        ItemStack rotten = new ItemStack(Items.BREAD, 1);
        int shelfLife = FreshnessCategories.shelfLifeHours(rotten);
        FreshnessData.stampAt(rotten, now - (long) shelfLife * 60L - 60L, 0);
        helper.assertTrue(FreshnessCheck.isRottenNow(rotten, now), "过期一小时的面包算烂");
        helper.assertTrue(!FreshnessCheck.isRottenNow(new ItemStack(Items.BREAD, 1), now),
                "没盖章的面包按整段保质期算, 不算烂");
        helper.assertTrue(FreshnessCheck.remainingMinutes(new ItemStack(Items.BREAD, 1), now)
                        == (long) shelfLife * 60L,
                "没盖章的面包剩余 = 一整个保质期");
        helper.succeed();
    }

    // ============================================================
    // 类别与豁免判定顺序
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void categoryTagsResolveAsDesigned(GameTestHelper helper) {
        helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.BEEF)).orElseThrow()
                        == FreshnessCategory.FRESH,
                "生牛肉必须是生鲜");
        helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.BREAD)).orElseThrow()
                        == FreshnessCategory.COOKED,
                "面包必须是熟食");
        helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.WHEAT)).orElseThrow()
                        == FreshnessCategory.GRAIN,
                "小麦必须是谷物");
        helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.APPLE)).orElseThrow()
                        == FreshnessCategory.PRODUCE,
                "苹果必须是果蔬");
        helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.POTATO)).orElseThrow()
                        == FreshnessCategory.ROOT,
                "马铃薯必须是根茎");
        helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.ROTTEN_FLESH)).isEmpty(),
                "腐肉豁免 (它是日常任务的上交物)");
        helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.WHEAT_SEEDS)).isEmpty(),
                "种子豁免 (生产资料)");
        helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.DIAMOND)).isEmpty(),
                "不能吃又不标类别的根本不腐烂");
        // 能吃但没标类别的一律兜底成熟食。
        helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.SUSPICIOUS_STEW)).orElseThrow()
                        == FreshnessCategory.COOKED,
                "能吃但没标类别的按熟食兜底");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void moduleRegisteredExemptionsParticipateFirst(GameTestHelper helper) {
        try {
            FreshnessExemptions.register("gametest/freshness", stack -> stack.is(Items.SUSPICIOUS_STEW));
            helper.assertTrue(FreshnessExemptions.owners().contains("gametest/freshness"),
                    "登记后必须能在归属清单里看到");
            helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.SUSPICIOUS_STEW)).isEmpty(),
                    "模块登记的豁免优先于熟食兜底");
        } finally {
            FreshnessExemptions.clearForTest();
        }
        helper.assertTrue(FreshnessCategories.categoryOf(new ItemStack(Items.SUSPICIOUS_STEW)).orElseThrow()
                        == FreshnessCategory.COOKED,
                "复位后兜底重新生效 (测试不得污染进程级登记表)");
        helper.succeed();
    }
}
