package com.miningdim.job;

import com.miningdim.core.MiningConstants;
import com.miningdim.effect.VulnerabilityEffect;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.time.Instant;
import java.time.ZoneOffset;

/**
 * 职业框架地基 GameTest (JobFramework_Shared_Foundation_DesignSpec 第十二章测试断言 + 实现手册 GameTest 范式)。
 *
 * 断言具体业务结果 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验):
 *  - 经验曲线断点: 累计 16,900 -> L5, 累计 61,900 -> L10 (毕业); 删 CUMULATIVE_XP 表必挂。
 *  - 每日衰减分段边界: 1999/2000/3799/3800 边界系数跳变; 删衰减分段必挂。
 *  - grant 受衰减约束: 同日重复 grant 折算递减; 翻日 dailyXp 归零。
 *  - 进度 NBT 往返一致 + 旧存档缺键默认 level=1/xp=0。
 *  - 易伤数值阶梯 I-V (+20/35/50/70/100%) + 封顶 +100%。
 *  - capability: 给玩家某职业入账经验后等级精确。
 *
 * 纯逻辑断言不依赖结构, 用 template = "empty" (data/miningdim/structures/empty.nbt 空模板)。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class JobGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "job";

    // ============================================================
    // 经验曲线断点 (JobXpCurve)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void xpCurveLevelBreakpoints(GameTestHelper helper) {
        // 累计经验断点 -> 精确等级 (FarmingXP 表A / Engineer 7.1)。
        helper.assertTrue(JobXpCurve.levelForTotalXp(0L) == 1, "0 xp must be L1");
        helper.assertTrue(JobXpCurve.levelForTotalXp(3_299L) == 1, "3299 xp must still be L1 (L2 needs 3300)");
        helper.assertTrue(JobXpCurve.levelForTotalXp(3_300L) == 2, "3300 xp must be L2");
        helper.assertTrue(JobXpCurve.levelForTotalXp(16_900L) == 5, "16900 xp must be L5");
        helper.assertTrue(JobXpCurve.levelForTotalXp(16_899L) == 4, "16899 xp must be L4 (L5 needs 16900)");
        helper.assertTrue(JobXpCurve.levelForTotalXp(49_700L) == 9, "49700 xp must be L9");
        helper.assertTrue(JobXpCurve.levelForTotalXp(61_900L) == 10, "61900 xp must be L10 (graduation)");
        helper.assertTrue(JobXpCurve.levelForTotalXp(1_000_000L) == 10, "over-cap xp clamps to L10");
        // 累计断点反查值。
        helper.assertTrue(JobXpCurve.cumulativeXpForLevel(5) == 16_900L, "cumulative for L5 must be 16900");
        helper.assertTrue(JobXpCurve.cumulativeXpForLevel(10) == 61_900L, "cumulative for L10 must be 61900");
        helper.assertTrue(JobXpCurve.GRADUATION_XP == 61_900L, "graduation total must be 61900");
        helper.succeed();
    }

    // ============================================================
    // 每日有效经验软上限衰减 (JobXpCurve.applyDailyDecay) — 含边界值 1999/2000/3799/3800
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dailyDecaySegments(GameTestHelper helper) {
        // [0,2000) x1.0: 当日 0, 入 1000 -> 全额 1000。
        helper.assertTrue(JobXpCurve.applyDailyDecay(0L, 1_000L) == 1_000L, "first 1000 raw is full (x1.0)");
        // 边界 1999 起入 1 点仍在 x1.0 段。
        helper.assertTrue(JobXpCurve.applyDailyDecay(1_999L, 1L) == 1L, "at 1999 the next 1 raw is x1.0 -> 1");
        // 边界 2000 起入 100 点落 [2000,2800) x0.4 -> floor(40)=40。
        helper.assertTrue(JobXpCurve.applyDailyDecay(2_000L, 100L) == 40L, "at 2000 raw 100 x0.4 -> 40");
        // [2800,3400) x0.2: 当日 2800 入 100 -> 20。
        helper.assertTrue(JobXpCurve.applyDailyDecay(2_800L, 100L) == 20L, "at 2800 raw 100 x0.2 -> 20");
        // 边界 3799 入 1 点仍 [3400,3800) x0.08 -> floor(0.08)=0。
        helper.assertTrue(JobXpCurve.applyDailyDecay(3_799L, 1L) == 0L, "at 3799 raw 1 x0.08 floors to 0");
        // 边界 3800 入 1000 点落末档 x0.02 -> floor(20)=20 (近乎归零但非 0)。
        helper.assertTrue(JobXpCurve.applyDailyDecay(3_800L, 1_000L) == 20L, "at 3800 raw 1000 x0.02 -> 20");
        // 跨段一致性: 当日 1900 入 200 = [1900,2000) 100 原始 x1.0 + [2000,...) 100 原始 x0.4 = 100 + 40 = 140。
        helper.assertTrue(JobXpCurve.applyDailyDecay(1_900L, 200L) == 140L,
                "raw spanning 2000 boundary: 100 raw at x1.0 + 100 raw at x0.4 = 140");
        helper.succeed();
    }

    // ============================================================
    // 整日单笔原始经验 -> 工程师 spec 第八章 DECIDED 实算有效经验定值 (有效容量模型 B 端到端裁决)
    // 这是 JobXpCurve.applyDailyDecay 的核心交付物: 删/改坏跨段折算 (回退 A 解释) 必挂。
    // 逐行对齐 MillenniumEngineer_Mod_DesignSpec.md:223-229 (休闲 2200->2080 / 正常 4000->2800 /
    // 肝 7000->3400 / 肝满 12000->3800 / 极限 22000->4000)。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dailyDecayChapter8Profiles(GameTestHelper helper) {
        // 休闲: 日投 2200 原始 -> 2000 (x1.0 满段) + floor(200*0.4)=80 = 2080。
        helper.assertTrue(JobXpCurve.applyDailyDecay(0L, 2_200L) == 2_080L,
                "casual 2200 raw -> 2080 effective (spec ch.8)");
        // 正常: 日投 4000 原始 -> 2000 + floor(2000*0.4)=800 (填满 x0.4 段 800 有效) = 2800。
        helper.assertTrue(JobXpCurve.applyDailyDecay(0L, 4_000L) == 2_800L,
                "normal 4000 raw -> 2800 effective (spec ch.8)");
        // 肝: 日投 7000 原始 -> 2000 + 800 + floor(3000*0.2)=600 (填满 x0.2 段 600 有效) = 3400。
        helper.assertTrue(JobXpCurve.applyDailyDecay(0L, 7_000L) == 3_400L,
                "grind 7000 raw -> 3400 effective (spec ch.8)");
        // 肝满: 日投 12000 原始 -> 2000 + 800 + 600 + floor(5000*0.08)=400 (填满 x0.08 段 400 有效) = 3800。
        helper.assertTrue(JobXpCurve.applyDailyDecay(0L, 12_000L) == 3_800L,
                "max-grind 12000 raw -> 3800 effective (spec ch.8)");
        // 极限: 日投 22000 原始 -> 3800 (前四段填满) + floor(10000*0.02)=200 末档涓流 = 4000。
        helper.assertTrue(JobXpCurve.applyDailyDecay(0L, 22_000L) == 4_000L,
                "extreme 22000 raw -> 4000 effective (spec ch.8)");
        helper.succeed();
    }

    // ============================================================
    // grantXp 受衰减约束 + 翻日 (JobProgress)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void grantXpDecayAndRollover(GameTestHelper helper) {
        JobProgress p = new JobProgress();
        long day = 100L;
        // 第一笔 1500 全额 (x1.0): dailyXp=1500, xp=1500。
        long g1 = p.grantXp(1_500L, day);
        helper.assertTrue(g1 == 1_500L, "first 1500 raw full -> 1500 effective");
        helper.assertTrue(p.dailyXp() == 1_500L && p.xp() == 1_500L, "dailyXp/xp must be 1500 after first grant");
        // 第二笔 1000: [1500,2000) 500 x1.0 + [2000,2500) 500 x0.4 = 500 + 200 = 700。
        long g2 = p.grantXp(1_000L, day);
        helper.assertTrue(g2 == 700L, "second 1000 spanning boundary -> 700 effective");
        helper.assertTrue(p.dailyXp() == 2_200L, "dailyXp accumulates to 2200");
        helper.assertTrue(p.xp() == 2_200L, "xp accumulates to 2200");
        // 翻日: dayStamp 变化 -> dailyXp 归零, 新一天又全额。
        long g3 = p.grantXp(1_000L, day + 1L);
        helper.assertTrue(g3 == 1_000L, "new UTC day resets dailyXp -> next 1000 full again");
        helper.assertTrue(p.dailyXp() == 1_000L, "dailyXp resets to fresh 1000 on new day");
        helper.assertTrue(p.xp() == 3_200L, "total xp keeps accumulating across days: 2200 + 1000 = 3200");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void grantXpReachesLevels(GameTestHelper helper) {
        JobProgress p = new JobProgress();
        // 每天给一大笔, 跨多天累积到 L10 (单日衰减下需多日才到顶, 验证 level 随 xp 精确推进)。
        // 用一个不受当日衰减影响的直接累积校验: 连续多天各刷满末档前的高额。
        long day = 0L;
        for (int i = 0; i < 80 && p.level() < 10; i++) {
            p.grantXp(5_000L, day + i);
        }
        helper.assertTrue(p.level() == 10, "sustained daily grants must eventually reach L10");
        helper.assertTrue(p.xp() >= 61_900L, "xp at L10 must be >= graduation 61900");
        helper.succeed();
    }

    // ============================================================
    // Critical 回归守卫: 小额逐笔入账不被 floor 吞光 (修复前必挂)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void grantSmallChunksDoNotFloorToZero(GameTestHelper helper) {
        long day = 100L;
        // 先把当日有效经验填到 2000 (x1.0 满段), 之后落 [2000,2800) x0.4 档: 单笔原始 2 -> 0.8 有效。
        // 旧实现逐笔 floor(0.8)=0, 整条 x0.4 档在小额路径产出恒 0 (软上限带名存实亡); 修复后小数进位累加。
        JobProgress loop = new JobProgress();
        loop.grantXp(2_000L, day);
        helper.assertTrue(loop.xp() == 2_000L, "after 2000 raw the daily pointer sits exactly at 2000 effective");
        for (int i = 0; i < 10; i++) {
            loop.grantXp(2L, day); // 10 笔单株 (农夫 SINGLE_CROP_XP 量级), 每笔 0.8 有效。
        }
        // 10 * 0.8 = 8 有效经验必须真实进位入账; 旧实现逐笔 floor(0.8)=0 会令 xp 死钉 2000。
        // 断言下界 2007 (而非死等 2008): double 逐笔累加 0.8 存在亚单位舍入抖动, 真值落在 [2007,2008];
        // 关键是远高于旧缺陷的 2000, 删掉小数进位 (回退逐笔 floor) 必挂在此。
        helper.assertTrue(loop.xp() >= 2_007L,
                "ten grantXp(2) in the x0.4 band must accrue ~8 effective (0.8 each carried), not floor to 0");
        helper.assertTrue(loop.xp() > 2_000L, "small grants beyond 2000 must still raise effective xp (>0 gain)");

        // 与等量单次 grant 一致 (拆分不变 + 小额累进等价): 同样先填 2000, 再单笔 grant(20) = 8 有效。
        JobProgress single = new JobProgress();
        single.grantXp(2_000L, day);
        single.grantXp(20L, day);
        helper.assertTrue(single.xp() == 2_008L, "one grantXp(20) in the x0.4 band yields exactly 8 effective");
        // 拆笔结果不得超过单笔, 且差距 <= 1 整数单位 (纯亚单位 double 舍入), 即等价不被系统性放大/缩小。
        helper.assertTrue(loop.xp() <= single.xp() && single.xp() - loop.xp() <= 1L,
                "ten grantXp(2) equals one grantXp(20) up to sub-unit rounding (small-grant carry invariance)");
        helper.succeed();
    }

    // ============================================================
    // Critical 回归守卫: 拆分不变性 (大额拆成多笔不得系统性多刷/少刷, 修复前必挂)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void grantSplitInvariance(GameTestHelper helper) {
        long day = 100L;
        // 单笔 5000: 2000(x1.0) + 800(填满 x0.4) + 200(x0.2 段内 1000 原始) = 3000 有效。
        JobProgress whole = new JobProgress();
        whole.grantXp(5_000L, day);
        helper.assertTrue(whole.xp() == 3_000L, "single grant 5000 raw -> 3000 effective (capacity model)");

        // 拆成 4000 + 1000 (同日): 4000 -> 2800; 再 1000 从有效指针 2800 起 -> 200; 合计 3000, 必须与单笔等。
        JobProgress split = new JobProgress();
        split.grantXp(4_000L, day);
        helper.assertTrue(split.xp() == 2_800L, "first split grant 4000 raw -> 2800 effective");
        split.grantXp(1_000L, day);
        helper.assertTrue(split.xp() == whole.xp(),
                "grant(4000)+grant(1000) effective total must equal grant(5000) (split invariance, anti-arbitrage)");
        helper.succeed();
    }

    // ============================================================
    // currentUtcDayStamp UTC 口径独立断言 (业务用例改用固定常量戳后, 此处单独覆盖时钟正确性)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void utcDayStampIsUtcEpochDay(GameTestHelper helper) {
        long stamp = JobServiceImpl.currentUtcDayStamp();
        long expected = Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
        // 同一口径独立重算, 仅在 UTC 午夜窄窗口允许差 1 天; 其余必须相等 (gameTime/24000 或本地时区实现会偏离)。
        helper.assertTrue(Math.abs(stamp - expected) <= 1L,
                "currentUtcDayStamp must be UTC epoch-day (matches independent recompute within midnight window)");
        // 量级锚点: UTC epochDay 在 2022 之后 >= 19000; 排除 gameTime/24000 (极小) 或毫秒时间戳 (极大) 误实现。
        helper.assertTrue(stamp >= 19_000L,
                "currentUtcDayStamp magnitude must be a UTC epoch-day count (>=19000), not gameTime/24000 or millis");
        helper.succeed();
    }

    // ============================================================
    // 进度 NBT 往返 + 缺键默认 (JobProgress / JobData)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void progressNbtRoundTrip(GameTestHelper helper) {
        JobProgress src = new JobProgress();
        src.grantXp(20_000L, 7L); // 让它升到某级 (5000 全额 + 后续衰减)。
        src.setNanoReactorCdEndTick(12345L);
        CompoundTag tag = src.serializeNBT();

        JobProgress dst = new JobProgress();
        dst.deserializeNBT(tag);
        helper.assertTrue(dst.level() == src.level(), "level survives NBT round-trip");
        helper.assertTrue(dst.xp() == src.xp(), "xp survives NBT round-trip");
        helper.assertTrue(dst.dailyXp() == src.dailyXp(), "dailyXp survives NBT round-trip");
        helper.assertTrue(dst.dayStamp() == src.dayStamp(), "dayStamp survives NBT round-trip");
        helper.assertTrue(dst.nanoReactorCdEndTick() == 12345L, "engineer cd field survives NBT round-trip");

        // 缺键默认: 空 tag -> level=1/xp=0 (旧存档/新职业)。
        JobProgress empty = new JobProgress();
        empty.deserializeNBT(new CompoundTag());
        helper.assertTrue(empty.level() == 1 && empty.xp() == 0L, "missing keys default to L1/0xp");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jobDataEnumMapRoundTrip(GameTestHelper helper) {
        JobData src = new JobData();
        src.jobProgress(JobId.MINER).grantXp(10_000L, 3L);
        src.jobProgress(JobId.CHEF).grantXp(4_000L, 3L);
        CompoundTag tag = src.serializeNBT();

        JobData dst = new JobData();
        dst.deserializeNBT(tag);
        helper.assertTrue(dst.jobProgress(JobId.MINER).xp() == src.jobProgress(JobId.MINER).xp(),
                "miner progress survives EnumMap NBT round-trip");
        helper.assertTrue(dst.jobProgress(JobId.CHEF).xp() == src.jobProgress(JobId.CHEF).xp(),
                "chef progress survives EnumMap NBT round-trip");
        // 未写入的职业 (缺键) 取用时懒建默认 L1/0xp, 不污染存档。
        helper.assertTrue(dst.jobProgress(JobId.TAROT).level() == 1
                        && dst.jobProgress(JobId.TAROT).xp() == 0L,
                "absent job lazily defaults to L1/0xp");
        // copyFrom 全量复制 (Clone 语义)。
        JobData cloned = new JobData();
        cloned.copyFrom(src);
        helper.assertTrue(cloned.jobProgress(JobId.MINER).xp() == src.jobProgress(JobId.MINER).xp(),
                "copyFrom replicates all job progress");
        helper.succeed();
    }

    // ============================================================
    // JobId 成员一致性 (7 个, 含 AGENT/MUNITIONS 占位)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jobIdMembers(GameTestHelper helper) {
        helper.assertTrue(JobId.values().length == 7, "JobId must have 7 members (5 core + AGENT + MUNITIONS)");
        helper.assertTrue(JobId.byId("miner") == JobId.MINER, "byId(miner) resolves MINER");
        helper.assertTrue(JobId.byId("munitions") == JobId.MUNITIONS, "byId(munitions) resolves MUNITIONS");
        helper.assertTrue(JobId.byId("nonexistent") == null, "byId unknown returns null");
        helper.succeed();
    }

    // ============================================================
    // 易伤数值阶梯 + 封顶 (VulnerabilityEffect)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void vulnerabilityLadder(GameTestHelper helper) {
        // amplifier 0-4 = 易伤 I-V (TarotReader spec 60-64)。
        helper.assertTrue(VulnerabilityEffect.percentForAmplifier(0) == 0.20D, "Vulnerability I = +20%");
        helper.assertTrue(VulnerabilityEffect.percentForAmplifier(1) == 0.35D, "Vulnerability II = +35%");
        helper.assertTrue(VulnerabilityEffect.percentForAmplifier(2) == 0.50D, "Vulnerability III = +50%");
        helper.assertTrue(VulnerabilityEffect.percentForAmplifier(3) == 0.70D, "Vulnerability IV = +70%");
        helper.assertTrue(VulnerabilityEffect.percentForAmplifier(4) == 1.00D, "Vulnerability V = +100%");
        // 越界向最近端钳制 (防恶意等级击穿封顶): 超过 V 取 V 封顶, 负数取 I。
        helper.assertTrue(VulnerabilityEffect.percentForAmplifier(10) == 1.00D, "over-V amplifier clamps to V cap");
        helper.assertTrue(VulnerabilityEffect.percentForAmplifier(-1) == 0.20D, "negative amplifier clamps to I");
        helper.assertTrue(VulnerabilityEffect.MAX_VULNERABILITY_PCT == 1.00D, "total vulnerability cap is +100%");
        helper.succeed();
    }

    // ============================================================
    // capability 数据层: 给某职业入账经验后等级精确 + per-job 独立 + Clone 复制
    // ============================================================
    // 说明: capability 的 attach (RegisterCapabilitiesEvent / AttachCapabilitiesEvent) 在 JobFrameworkSystem
    // 接入 MiningDim 后才对玩家实体生效 (本任务不接线, 归集成阶段)。故此处直接验证 capability 持有的数据实现
    // JobPlayerData 的端到端契约 (grant -> level -> 读回 + copyFrom 复制), 这正是 capability 挂载后玩家身上
    // 运行的同一份逻辑; attach 接线后再由 GameTest mock player 覆盖 (见 notes)。

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void jobPlayerDataGrantAndLevel(GameTestHelper helper) {
        JobPlayerData data = new JobPlayerData();
        // 固定常量日戳 (与本文件其它衰减/曲线用例一致): 避免在 UTC 午夜跨日窄窗口取实时戳引入罕见 flaky。
        // currentUtcDayStamp 的 UTC 口径正确性由 utcDayStampIsUtcEpochDay 独立断言, 不在业务断言里引时钟。
        long day = 100L;
        JobProgress miner = data.jobProgress(JobId.MINER);

        // 当日给 3300 raw, 经有效容量模型折算 (B 解释; 见 JobXpCurve.applyDailyDecay): [0,2000) 段 2000 原始
        // 填满 2000 有效容量 (x1.0); 剩 1300 原始进 [2000,2800) 段 (x0.4) 折算 floor(1300*0.4)=520 有效。
        // = 2000 + 520 = 2520 有效经验 (< L2 的 3300, 仍 L1)。验证 grant 确实受软上限约束。
        miner.grantXp(3_300L, day);
        helper.assertTrue(miner.xp() == 2_520L,
                "3300 raw on a fresh day decays to 2520 effective (2000 raw fills x1.0 + 1300 raw at x0.4 = 520)");
        helper.assertTrue(miner.level() == 1, "2520 effective xp is below L2 threshold 3300, miner stays L1");

        // 翻日后再给 3300 raw, 又折算 2520 (新一天额度刷新): 累计 5040 > 3300 -> L2。
        miner.grantXp(3_300L, day + 1L);
        helper.assertTrue(miner.xp() == 5_040L, "second day adds another 2520 -> 5040 total effective xp");
        helper.assertTrue(miner.level() == 2, "5040 accumulated effective xp reaches L2 (threshold 3300)");

        // 其它职业不受影响 (per-job 独立)。
        helper.assertTrue(data.jobProgress(JobId.FARMER).level() == 1,
                "farmer stays L1 (per-job independent progress)");

        // Clone 复制 (PlayerEvent.Clone 用同一 copyFrom): 全职业进度全量复制, 不丢级。
        JobPlayerData cloned = new JobPlayerData();
        cloned.copyFrom(data);
        helper.assertTrue(cloned.jobProgress(JobId.MINER).level() == 2,
                "miner level survives JobPlayerData.copyFrom (Clone replication)");
        helper.assertTrue(cloned.jobProgress(JobId.MINER).xp() == miner.xp(),
                "miner xp survives JobPlayerData.copyFrom");
        helper.succeed();
    }
}
