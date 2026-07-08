package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.Map;

/**
 * 小男孩 LITTLE_BOY (spec 7.4 一次性核弹, 批4 波2) 纯逻辑 + capability GameTest。断言
 * {@link ChampionLittleBoyPlan} 的触发血线/打断门槛公式/边缘衰减精确表/档表数值/红线名义帽, 与
 * {@link MiningChampionData#removeAffix} 的摘词条语义 + NBT 往返 (纯数据 + 真 zombie 挂载 capability)。
 * 删被测折算/门槛/衰减/摘词条逻辑对应断言必挂。真服 (蓄力光柱/玩家累计打断/引爆逐玩家 AOE + 免疫缓冲) 由
 * {@code ChampionLittleBoyHandler} 每 tick/秒施加 (真服验)。
 *
 * template = "empty", batch = "champion_little_boy"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ChampionLittleBoyGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_little_boy";
    private static final double EPS = 1e-6D;

    // ============================================================
    // 定值锚点 (蓄力 100t / 半径 8 / 背水血线 0.60): 改值/删常量必挂
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void planConstants(GameTestHelper helper) {
        // 整型常量经非 final 局部读出再比 (否则 IDE 把 常量==字面量 折为"恒等式"告警; 局部非常量表达式不折)。
        long chargeTicks = ChampionLittleBoyPlan.CHARGE_TICKS;
        long ticksPerSecond = ChampionLittleBoyPlan.TICKS_PER_SECOND;
        int minPlayers = ChampionLittleBoyPlan.MIN_PLAYER_COUNT;
        helper.assertTrue(chargeTicks == 100L, "蓄力 = 5s = 100 tick");
        helper.assertTrue(ticksPerSecond == 20L, "20 tick/秒");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.BLAST_RADIUS - 8.0D) < EPS, "AOE 半径 8 格");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.TRIGGER_HP_FRACTION - 0.60D) < EPS, "背水触发血线 60%");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.INTERRUPT_DAMAGE_PER_PLAYER - 120.0D) < EPS,
                "每玩家打断门槛系数 120");
        helper.assertTrue(minPlayers == 1, "到场玩家数下限 1");
        helper.succeed();
    }

    // ============================================================
    // 触发血线 shouldTrigger (首次 <= 60%; 含边界)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void triggerBloodline(GameTestHelper helper) {
        helper.assertTrue(!ChampionLittleBoyPlan.shouldTrigger(1.00D), "满血不触发");
        helper.assertTrue(!ChampionLittleBoyPlan.shouldTrigger(0.61D), "61% 不触发 (>60%)");
        helper.assertTrue(ChampionLittleBoyPlan.shouldTrigger(0.60D), "恰 60% 触发 (含边界)");
        helper.assertTrue(ChampionLittleBoyPlan.shouldTrigger(0.59D), "59% 触发");
        helper.assertTrue(ChampionLittleBoyPlan.shouldTrigger(0.00D), "0% 触发");

        // NaN 脏值自然抛 (不静默兜)。
        boolean rejected = false;
        try {
            ChampionLittleBoyPlan.shouldTrigger(Double.NaN);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "shouldTrigger(NaN) 抛 IAE");
        helper.succeed();
    }

    // ============================================================
    // 打断门槛 = max(1, 玩家数) x 120 (1/2/3 人 = 120/240/360; 0/负钳 1 人)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void interruptThresholdByPlayerCount(GameTestHelper helper) {
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.interruptThreshold(1) - 120.0D) < EPS, "1 人 = 120");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.interruptThreshold(2) - 240.0D) < EPS, "2 人 = 240");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.interruptThreshold(3) - 360.0D) < EPS, "3 人 = 360");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.interruptThreshold(5) - 600.0D) < EPS, "5 人 = 600");
        // 到场 0 / 负 (理论不出现) 按下限 1 人计 = 120。
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.interruptThreshold(0) - 120.0D) < EPS, "0 人 钳 1 人 = 120");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.interruptThreshold(-3) - 120.0D) < EPS, "负数 钳 1 人 = 120");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void interruptReachedBoundary(GameTestHelper helper) {
        double threshold = ChampionLittleBoyPlan.interruptThreshold(1); // 120
        helper.assertTrue(!ChampionLittleBoyPlan.isInterrupted(119.9D, threshold), "119.9 < 120 未打断");
        helper.assertTrue(ChampionLittleBoyPlan.isInterrupted(120.0D, threshold), "恰 120 打断 (含边界)");
        helper.assertTrue(ChampionLittleBoyPlan.isInterrupted(121.0D, threshold), "121 打断");
        helper.assertTrue(!ChampionLittleBoyPlan.isInterrupted(0.0D, threshold), "0 累计未打断");
        helper.succeed();
    }

    // ============================================================
    // 边缘衰减精确表 (中心 1.0 / 4 格 0.75 / 8 格 0.5; 超半径钳边缘)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void edgeFalloffPrecise(GameTestHelper helper) {
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.edgeFalloff(0.0D) - 1.00D) < EPS, "中心 0 格 = 1.0 满伤");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.edgeFalloff(2.0D) - 0.875D) < EPS, "2 格 = 0.875");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.edgeFalloff(4.0D) - 0.75D) < EPS, "半径中点 4 格 = 0.75");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.edgeFalloff(6.0D) - 0.625D) < EPS, "6 格 = 0.625");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.edgeFalloff(8.0D) - 0.50D) < EPS, "边缘 8 格 = 0.5 半伤");
        // 超半径按边缘 0.5 钳 (防御; handler 只对半径内玩家调此)。
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.edgeFalloff(10.0D) - 0.50D) < EPS, "超半径 10 格 钳 0.5");

        // 负距离脏值自然抛。
        boolean rejected = false;
        try {
            ChampionLittleBoyPlan.edgeFalloff(-1.0D);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "edgeFalloff(负距离) 抛 IAE");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void withinBlastBoundary(GameTestHelper helper) {
        helper.assertTrue(ChampionLittleBoyPlan.withinBlast(0.0D), "中心在半径内");
        helper.assertTrue(ChampionLittleBoyPlan.withinBlast(8.0D), "恰半径在内 (含边界)");
        helper.assertTrue(!ChampionLittleBoyPlan.withinBlast(8.01D), "超半径不在内");
        helper.succeed();
    }

    // ============================================================
    // 档表数值 aoePct {0,0,0,0.70,0.85} (仅超凡/闪耀有意义)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void aoePctTable(GameTestHelper helper) {
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.aoePct(AffixQuality.COMMON)) < EPS, "普通档 0 占位");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.aoePct(AffixQuality.UNCOMMON)) < EPS, "中级档 0 占位");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.aoePct(AffixQuality.RARE)) < EPS, "高级档 0 占位");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.aoePct(AffixQuality.EPIC) - 0.70D) < EPS, "超凡 = 70% maxHP");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.aoePct(AffixQuality.LEGENDARY) - 0.85D) < EPS, "闪耀 = 85% maxHP");
        helper.succeed();
    }

    // ============================================================
    // 单人 AOE = aoePct x maxHP x 边缘衰减 (超凡/闪耀 x 三距离精确值)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blastDamageCombines(GameTestHelper helper) {
        // 超凡 70%, maxHp 100: 中心 70 / 中点 52.5 / 边缘 35。
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.blastDamage(AffixQuality.EPIC, 100.0D, 0.0D) - 70.0D) < EPS,
                "超凡 100血 中心 = 70");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.blastDamage(AffixQuality.EPIC, 100.0D, 4.0D) - 52.5D) < EPS,
                "超凡 100血 4格 = 52.5");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.blastDamage(AffixQuality.EPIC, 100.0D, 8.0D) - 35.0D) < EPS,
                "超凡 100血 边缘 = 35");
        // 闪耀 85%, maxHp 200: 中心 170 / 边缘 85。
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.blastDamage(AffixQuality.LEGENDARY, 200.0D, 0.0D) - 170.0D) < EPS,
                "闪耀 200血 中心 = 170");
        helper.assertTrue(Math.abs(ChampionLittleBoyPlan.blastDamage(AffixQuality.LEGENDARY, 200.0D, 8.0D) - 85.0D) < EPS,
                "闪耀 200血 边缘 = 85");

        // maxHp 非正脏值自然抛。
        boolean rejected = false;
        try {
            ChampionLittleBoyPlan.blastDamage(AffixQuality.EPIC, 0.0D, 0.0D);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "blastDamage(maxHp<=0) 抛 IAE");
        helper.succeed();
    }

    // ============================================================
    // 红线自查: 名义 %maxHP 不越带预兆可躲技能硬帽 0.90 (0.85 < 0.90)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void telegraphedCapRedline(GameTestHelper helper) {
        double maxAoePct = ChampionLittleBoyPlan.aoePct(AffixQuality.LEGENDARY); // 0.85
        helper.assertTrue(maxAoePct < ChampionRedlines.TELEGRAPHED_HIT_CAP_PCT,
                "闪耀 aoePct 0.85 < 名义帽 0.90 (aoePct 表上调越帽即挂)");
        // 中心满衰减档 (最坏情况) 折回 %maxHP 仍 <= 0.90 名义帽。
        double centerPctOfMax = ChampionLittleBoyPlan.blastDamage(AffixQuality.LEGENDARY, 100.0D, 0.0D) / 100.0D;
        helper.assertTrue(centerPctOfMax <= ChampionRedlines.TELEGRAPHED_HIT_CAP_PCT + EPS,
                "中心满衰减 %maxHP 不越 0.90 名义帽");
        helper.succeed();
    }

    // ============================================================
    // 摘词条 removeAffix (纯数据): affixes() 不含 + NBT 往返不含 + 幂等
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removeAffixPureDataAndNbtRoundtrip(GameTestHelper helper) {
        // 7★ 冠军装小男孩 + 燃烧: 摘小男孩后仅剩燃烧, 星级/有效血不动。
        MiningChampionData data = new MiningChampionData();
        data.promote(7, Map.of(AffixDef.LITTLE_BOY, AffixQuality.EPIC, AffixDef.BURNING, AffixQuality.EPIC), 9000.0D);
        helper.assertTrue(data.has(AffixDef.LITTLE_BOY), "摘前装配小男孩");

        boolean removed = data.removeAffix(AffixDef.LITTLE_BOY);
        helper.assertTrue(removed, "removeAffix 报告确有移除");
        helper.assertTrue(!data.has(AffixDef.LITTLE_BOY), "摘后 has(小男孩) = false");
        helper.assertTrue(data.affixes().size() == 1 && data.has(AffixDef.BURNING), "摘后仅剩燃烧 (其它词条不动)");
        helper.assertTrue(data.star() == 7 && Math.abs(data.effectiveHp() - 9000.0D) < EPS,
                "摘词条不改星级/有效血");

        // NBT 往返: 序列化后反序列化到新实例, 仍不含小男孩、保留燃烧与星级。
        MiningChampionData restored = new MiningChampionData();
        restored.deserializeNBT(data.serializeNBT());
        helper.assertTrue(!restored.has(AffixDef.LITTLE_BOY), "NBT 往返后仍不含小男孩 (摘除随往返落地)");
        helper.assertTrue(restored.has(AffixDef.BURNING) && restored.star() == 7, "往返保留燃烧 + 星级");

        // 幂等: 再摘一次 no-op 返 false。
        helper.assertTrue(!data.removeAffix(AffixDef.LITTLE_BOY), "重复摘除幂等 no-op 返 false");
        helper.succeed();
    }

    // ============================================================
    // 摘词条 (真 zombie 挂载 capability): 验 provider 挂载 + 活 cap 上摘除生效
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void removeAffixOnLiveZombieCapability(GameTestHelper helper) {
        // 经完整 spawn 路径入世 (非裸 create): 确保走 MiningChampions.onAttachCapabilities 挂 provider, cap 必附。
        Zombie zombie = helper.spawn(EntityType.ZOMBIE, new BlockPos(0, 1, 0));

        // 每只 Mob 挂 champion_data provider (MiningChampions.onAttachCapabilities); 取活 cap。
        MiningChampionData champ = MiningChampions.get(zombie).orElse(null);
        helper.assertTrue(champ != null, "zombie 挂载自研 champion capability");

        champ.promote(7, Map.of(AffixDef.LITTLE_BOY, AffixQuality.EPIC, AffixDef.BURNING, AffixQuality.EPIC), 9000.0D);
        helper.assertTrue(champ.has(AffixDef.LITTLE_BOY), "活 cap 上装配小男孩");

        boolean removed = champ.removeAffix(AffixDef.LITTLE_BOY);
        helper.assertTrue(removed, "活 cap removeAffix 报告移除");
        helper.assertTrue(!champ.has(AffixDef.LITTLE_BOY), "活 cap 摘后不含小男孩");
        helper.assertTrue(champ.has(AffixDef.BURNING), "活 cap 保留其它词条");

        // 活 cap 序列化往返: 摘除随存盘落地 (provider.serializeNBT 委托 data.serializeNBT)。
        MiningChampionData restored = new MiningChampionData();
        restored.deserializeNBT(champ.serializeNBT());
        helper.assertTrue(!restored.has(AffixDef.LITTLE_BOY), "活 cap NBT 往返后不含小男孩");
        helper.assertTrue(restored.has(AffixDef.BURNING) && restored.star() == 7, "活 cap 往返保留燃烧 + 星级");

        zombie.discard();
        helper.succeed();
    }
}
