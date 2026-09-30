package com.miningdim.job.agent.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixPool;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionElectroChargePlan;
import com.miningdim.core.MiningConstants;
import com.miningdim.job.agent.panel.AgentScanIntel;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 战术扫描 L7 技能时序查表 ({@link AgentScanStatsReader#mechanicOf}) 的纯逻辑 GameTest。
 *
 * 锁两件事: (1) 技能池每一条词条都有时序行 —— 将来新增技能词条却忘了在读取器里登记, 面板就会对它静默地什么都
 * 不显示, 本批第一条立刻挂; (2) 没有对应概念的子项必须是 null 而不是被补成 0 (小男孩无冷却 / 自我修复无伤害打断
 * 阈值 / 视觉干扰无蓄力), 有概念的子项必须等于驱动该技能 handler 的那个常量。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AgentScanStatsReaderGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "agent";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void everySkillPoolAffixHasMechanicTimingsAndNothingElseDoes(GameTestHelper helper) {
        for (AffixDef def : AffixDef.values()) {
            AgentScanIntel.Mechanic mechanic = AgentScanStatsReader.mechanicOf(def, def.minUsableQuality());
            if (def.pool() == AffixPool.SKILL) {
                helper.assertTrue(mechanic != null && def.name().equals(mechanic.affixId()),
                        "技能池词条 " + def + " 必须有技能时序行 (affixId 与词条行同口径), 实得 " + mechanic);
                helper.assertTrue(mechanic.chargeSeconds() != null || mechanic.cooldownSeconds() != null,
                        "技能池词条 " + def + " 至少得有蓄力或冷却之一, 否则这一行对玩家毫无信息");
            } else {
                helper.assertTrue(mechanic == null, "非技能池词条 " + def + " 不进技能时序表, 实得 " + mechanic);
            }
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void mechanicTimingsMatchSkillPlansAndOmitMissingConcepts(GameTestHelper helper) {
        AgentScanIntel.Mechanic littleBoy = AgentScanStatsReader.mechanicOf(AffixDef.LITTLE_BOY, AffixQuality.EPIC);
        helper.assertTrue(littleBoy.chargeSeconds() == 5.0D && littleBoy.interruptDamagePerPlayer() == 120.0D,
                "小男孩: 蓄力 5s, 打断门槛每人 120 (ChampionLittleBoyPlan), 实得 " + littleBoy);
        helper.assertTrue(littleBoy.cooldownSeconds() == null,
                "小男孩是一次性核弹 (起手即摘词条), 没有冷却, 必须是 null 而不是 0");

        AgentScanIntel.Mechanic selfRepair = AgentScanStatsReader.mechanicOf(AffixDef.SELF_REPAIR, AffixQuality.RARE);
        helper.assertTrue(selfRepair.chargeSeconds() == 6.0D && selfRepair.cooldownSeconds() == 25.0D,
                "自我修复: 读条 6s / 冷却 25s (ChampionSelfRepairCycle), 实得 " + selfRepair);
        helper.assertTrue(selfRepair.interruptDamagePerPlayer() == null,
                "自我修复靠近战命中打断, 没有伤害阈值, 不得编一个数");

        AgentScanIntel.Mechanic electro = AgentScanStatsReader.mechanicOf(AffixDef.ELECTRO_CHARGE, AffixQuality.RARE);
        helper.assertTrue(electro.chargeSeconds() == ChampionElectroChargePlan.CHARGE_TICKS / 20.0D
                        && electro.cooldownSeconds() == ChampionElectroChargePlan.cycleTicks(AffixQuality.RARE) / 20.0D,
                "电磁蓄力: 蓄力与周期取自 ChampionElectroChargePlan (高级 12s), 实得 " + electro);
        helper.assertTrue(electro.cooldownSeconds() == 12.0D, "高级电磁蓄力周期 12s, 实得 " + electro.cooldownSeconds());

        AgentScanIntel.Mechanic visual = AgentScanStatsReader.mechanicOf(AffixDef.VISUAL_DISRUPTION,
                AffixQuality.LEGENDARY);
        helper.assertTrue(visual.chargeSeconds() == null && visual.cooldownSeconds() == 7.0D,
                "视觉干扰瞬发无蓄力, 闪耀周期 7s, 实得 " + visual);
        helper.succeed();
    }
}
