package com.miningdim.champion;

import com.miningdim.champion.aggregate.PlayerControlAggregator;
import com.miningdim.champion.aggregate.PlayerDotAccumulator;
import com.miningdim.champion.aggregate.RetaliationAggregator;
import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 精英怪效果地基纯逻辑 GameTest (ChampionStarAffix spec 第十四章实现拆分 9; 本任务地基交付的两块共享基建)。
 *
 * 覆盖:
 *  - {@link ChampionAffixState} 品质读取器: 无 NBT 子表 (命令召唤冠军) 按 tier 兜底品质推导 + 抬到词条最低可用档;
 *    有 NBT 子表读回盖章 ordinal + 脏 NBT 越档钳回; writeQuality 往返一致; ordinal 越界拒。
 *  - {@link ChampionEffectRegistries} 三聚合器注册表: 按需创建复用同一实例 (保滚动窗状态) + clearAll 清三表 +
 *    reset 清全表 + 反伤构造给 attackerMaxHp。
 *  - {@link PlayerDotAccumulator} per-player DoT 秒窗缓冲: 逐源累加 + 跨秒 flush 经 DotAggregator 衰减到 15% maxHP
 *    合计封顶 + 同源刷新累加 + flush 后清缓冲。
 *
 * 严禁触 Champions 加载路径 (compileOnly 铁律): 只断言纯逻辑 (品质推导 / 注册表存取清理 / DoT 秒窗衰减), 不引用
 * top.theillusivec4.champions.*。断言均为具体业务结果 (删兜底/删钳制/删 flush 衰减/删清理后对应 test 立挂)。
 * template = "empty", batch = "champion_foundation"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionFoundationGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_foundation";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 品质读取器: 命令召唤/无 NBT 冠军按 tier 兜底 (ChampionAffixState.defaultQualityFor)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void defaultQualityByTier(GameTestHelper helper) {
        // 普通词条 (复合装甲, 最低可用 = 普通): 兜底 = 该星最高品质。
        // 5star 最高品质 = 高级(RARE); 10star = 闪耀(LEGENDARY); 1star = 普通(COMMON)。
        helper.assertTrue(
                ChampionAffixState.defaultQualityFor(AffixDef.COMPOSITE_ARMOR, StarRank.ofStar(5)) == AffixQuality.RARE,
                "5star composite armor default = RARE (star max quality)");
        helper.assertTrue(
                ChampionAffixState.defaultQualityFor(AffixDef.COMPOSITE_ARMOR, StarRank.ofStar(10)) == AffixQuality.LEGENDARY,
                "10star composite armor default = LEGENDARY");
        helper.assertTrue(
                ChampionAffixState.defaultQualityFor(AffixDef.COMPOSITE_ARMOR, StarRank.ofStar(1)) == AffixQuality.COMMON,
                "1star composite armor default = COMMON");

        // 前导 0 占位词条 (重型护甲最低高级RARE): 7star 最高品质=超凡EPIC >= 最低可用RARE -> 默认 EPIC。
        helper.assertTrue(
                ChampionAffixState.defaultQualityFor(AffixDef.HEAVY_ARMOR, StarRank.ofStar(7)) == AffixQuality.EPIC,
                "7star heavy armor default = EPIC (>= min usable RARE)");
        // 小男孩最低超凡EPIC: 7star 最高=EPIC 恰等于最低可用 -> 默认 EPIC (兜底不低于最低可用档)。
        helper.assertTrue(
                ChampionAffixState.defaultQualityFor(AffixDef.LITTLE_BOY, StarRank.ofStar(7)) == AffixQuality.EPIC,
                "7star little boy default = EPIC (min usable raised)");
        helper.succeed();
    }

    // ============================================================
    // 品质读取器: NBT 子表读回盖章 ordinal + 脏 NBT 越档钳回 (ChampionAffixState.qualityOf)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void qualityFromNbtAndClamp(GameTestHelper helper) {
        StarRank s7 = StarRank.ofStar(7); // 最高品质 = 超凡 EPIC。
        CompoundTag sub = new CompoundTag();

        // writeQuality 往返: 盖章中级(UNCOMMON ordinal=1)的燃烧, 读回 UNCOMMON。
        ChampionAffixState.writeQuality(sub, new AffixSelection(AffixDef.BURNING, AffixQuality.UNCOMMON));
        helper.assertTrue(sub.getInt("BURNING") == AffixQuality.UNCOMMON.ordinal(),
                "writeQuality stores ordinal under enum-name key");
        helper.assertTrue(
                ChampionAffixState.qualityOf(sub, AffixDef.BURNING, s7) == AffixQuality.UNCOMMON,
                "qualityOf reads back stamped UNCOMMON");

        // 脏 NBT 越档: 手写一个超过该星上限的 ordinal (闪耀 LEGENDARY=4 > 7star EPIC=3), 读时钳回 EPIC。
        CompoundTag dirty = new CompoundTag();
        dirty.putInt(ChampionAffixState.nbtKeyOf(AffixDef.BURNING), AffixQuality.LEGENDARY.ordinal());
        helper.assertTrue(
                ChampionAffixState.qualityOf(dirty, AffixDef.BURNING, s7) == AffixQuality.EPIC,
                "dirty over-tier ordinal clamped to star max EPIC");

        // 无该词条条目 (NBT 子表存在但缺键): 走 tier 兜底 (= 该星最高品质, 复合装甲最低普通)。
        helper.assertTrue(
                ChampionAffixState.qualityOf(sub, AffixDef.COMPOSITE_ARMOR, s7) == AffixQuality.EPIC,
                "missing key falls back to tier default EPIC");
        // null 子表 (完全无品质表): 同样走兜底。
        helper.assertTrue(
                ChampionAffixState.qualityOf(null, AffixDef.COMPOSITE_ARMOR, s7) == AffixQuality.EPIC,
                "null tag falls back to tier default");

        // 前导 0 占位词条脏 NBT 抬底: 给重型护甲 (最低高级RARE) 存普通(COMMON=0), 读时抬到 RARE。
        CompoundTag belowMin = new CompoundTag();
        belowMin.putInt(ChampionAffixState.nbtKeyOf(AffixDef.HEAVY_ARMOR), AffixQuality.COMMON.ordinal());
        helper.assertTrue(
                ChampionAffixState.qualityOf(belowMin, AffixDef.HEAVY_ARMOR, s7) == AffixQuality.RARE,
                "stored below-min raised to min usable RARE");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void qualityOrdinalBoundsRejected(GameTestHelper helper) {
        boolean rejected = false;
        try {
            ChampionAffixState.qualityFromOrdinal(5); // 仅 0-4 合法。
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "ordinal 5 (out of [0,4]) rejected, not silently coerced");

        boolean negRejected = false;
        try {
            ChampionAffixState.qualityFromOrdinal(-1);
        } catch (IllegalArgumentException expected) {
            negRejected = true;
        }
        helper.assertTrue(negRejected, "negative ordinal rejected");
        helper.succeed();
    }

    // ============================================================
    // 聚合器注册表: 按需创建复用 + clearAll/reset (ChampionEffectRegistries)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void registriesCreateReuseAndClear(GameTestHelper helper) {
        UUID player = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();
        try {
            helper.assertTrue(!ChampionEffectRegistries.hasDot(player), "no dot accumulator before access");
            helper.assertTrue(!ChampionEffectRegistries.hasControl(player), "no control aggregator before access");
            helper.assertTrue(!ChampionEffectRegistries.hasRetaliation(attacker), "no retaliation before access");

            // 按需创建; 同 UUID 二次取回同一实例 (复用保滚动窗状态)。
            PlayerDotAccumulator dot1 = ChampionEffectRegistries.dotFor(player);
            PlayerDotAccumulator dot2 = ChampionEffectRegistries.dotFor(player);
            helper.assertTrue(dot1 == dot2, "dotFor reuses same instance per player");
            helper.assertTrue(ChampionEffectRegistries.hasDot(player), "dot registered after access");

            PlayerControlAggregator ctl1 = ChampionEffectRegistries.controlFor(player);
            PlayerControlAggregator ctl2 = ChampionEffectRegistries.controlFor(player);
            helper.assertTrue(ctl1 == ctl2, "controlFor reuses same instance per player");

            // 反伤构造须 attackerMaxHp = 80 -> perSecondCap 30% = 24, perWindowCap 40% = 32。
            RetaliationAggregator ret1 = ChampionEffectRegistries.retaliationFor(attacker, 80.0D);
            RetaliationAggregator ret2 = ChampionEffectRegistries.retaliationFor(attacker, 9999.0D);
            helper.assertTrue(ret1 == ret2, "retaliationFor reuses first instance (maxHp not re-applied)");
            helper.assertTrue(Math.abs(ret1.perSecondCap() - 24.0D) < EPS, "retaliation per-second cap = 30% of 80");
            helper.assertTrue(Math.abs(ret1.perWindowCap() - 32.0D) < EPS, "retaliation per-window cap = 40% of 80");

            helper.assertTrue(ChampionEffectRegistries.totalSize() == 3, "three instances registered (dot+control+retaliation)");

            // clearAll 清该玩家三表 (player 占 dot+control, attacker 占 retaliation)。
            ChampionEffectRegistries.clearAll(player);
            helper.assertTrue(!ChampionEffectRegistries.hasDot(player), "dot cleared for player");
            helper.assertTrue(!ChampionEffectRegistries.hasControl(player), "control cleared for player");
            helper.assertTrue(ChampionEffectRegistries.hasRetaliation(attacker), "attacker retaliation untouched by player clear");

            ChampionEffectRegistries.clearAll(attacker);
            helper.assertTrue(!ChampionEffectRegistries.hasRetaliation(attacker), "attacker retaliation cleared");
            helper.assertTrue(ChampionEffectRegistries.totalSize() == 0, "all cleared");
        } finally {
            ChampionEffectRegistries.reset();
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void registriesResetClearsAll(GameTestHelper helper) {
        ChampionEffectRegistries.dotFor(UUID.randomUUID());
        ChampionEffectRegistries.controlFor(UUID.randomUUID());
        ChampionEffectRegistries.retaliationFor(UUID.randomUUID(), 80.0D);
        helper.assertTrue(ChampionEffectRegistries.totalSize() == 3, "three instances before reset");
        ChampionEffectRegistries.reset();
        helper.assertTrue(ChampionEffectRegistries.totalSize() == 0, "reset clears every table");
        helper.succeed();
    }

    // ============================================================
    // per-player DoT 秒窗缓冲: 逐源累加 + 跨秒 flush 经 DotAggregator 衰减 (PlayerDotAccumulator)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dotAccumulatorSecondWindowFlush(GameTestHelper helper) {
        double maxHp = 1_000.0D; // 15% 合计封顶 = 150。
        PlayerDotAccumulator acc = new PlayerDotAccumulator();
        Object burning = "burning";
        Object frost = "frost";

        // tick 0 首次 shouldFlush 开窗返 false (未到秒边界)。
        helper.assertTrue(!acc.shouldFlush(0L), "first shouldFlush opens window, no flush yet");

        // 本秒逐源记入: 燃烧 100 (分两跳 60+40 同源累加) + 寒霜冻伤 90 = 名义合计 190 > 封顶 150。
        acc.record(burning, 60.0D);
        acc.record(burning, 40.0D);
        acc.record(frost, 90.0D);
        helper.assertTrue(acc.pendingSourceCount() == 2, "two distinct dot sources buffered (same source merged)");

        // tick 19 还没跨秒, 不 flush。
        helper.assertTrue(!acc.shouldFlush(19L), "19 ticks not yet a full second");
        // tick 20 跨秒, flush。
        helper.assertTrue(acc.shouldFlush(20L), "20 ticks = one second, flush due");

        PlayerDotAccumulator.FlushResult r = acc.flush(maxHp, 20L);
        // 名义 190 > 150 封顶: 按贡献比例衰减, 合计精确 = 150。
        helper.assertTrue(Math.abs(r.total() - 150.0D) < EPS, "dot total capped at 15% maxHP = 150");
        double[] per = r.perSource();
        helper.assertTrue(per.length == 2, "two sources in flush result");
        // 燃烧名义 100/190, 寒霜 90/190; 衰减后燃烧 ~ 100*150/190 ≈ 78.947; 末源吸收余数保合计。
        helper.assertTrue(Math.abs(per[0] - (100.0D * 150.0D / 190.0D)) < 1e-3D, "burning scaled by contribution");
        helper.assertTrue(Math.abs((per[0] + per[1]) - 150.0D) < EPS, "per-source sums to capped total");
        // flush 后缓冲清空, 进入下一秒窗。
        helper.assertTrue(acc.pendingSourceCount() == 0, "buffer cleared after flush");

        // 下一秒未超顶: 名义合计 50 < 150 原样下发。
        acc.record(burning, 50.0D);
        helper.assertTrue(acc.shouldFlush(40L), "next second flush due at tick 40");
        PlayerDotAccumulator.FlushResult r2 = acc.flush(maxHp, 40L);
        helper.assertTrue(Math.abs(r2.total() - 50.0D) < EPS, "under-cap dot passes through unscaled");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void dotAccumulatorEmptyFlush(GameTestHelper helper) {
        PlayerDotAccumulator acc = new PlayerDotAccumulator();
        acc.shouldFlush(0L); // 开窗。
        PlayerDotAccumulator.FlushResult r = acc.flush(1_000.0D, 20L);
        helper.assertTrue(Math.abs(r.total()) < EPS, "empty flush total 0");
        helper.assertTrue(r.perSource().length == 0, "empty flush no per-source");
        helper.succeed();
    }
}
