package com.miningdim.champion;

import com.miningdim.champion.integration.AoeImmunityBuffer;
import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 大额 AOE 2s 免疫缓冲拦截判定纯逻辑 GameTest (批4 波0; ChampionStarAffix spec 红线 3 TDD)。
 *
 * 只断言 {@link AoeImmunityBuffer#shouldBlockDamage} 三输入真值表 (受害者缓冲中 / 攻击者为冠军直接伤害 /
 * 是否豁免类型)。缓冲账本 (grant/isBuffered/sweep) 直接委托代理 A 的 {@link ExpiryLedger} 冻结契约, 其到期语义在
 * ExpiryLedger 自测覆盖, 本类不重复测 (只测本组件独有的拦截判定)。全部断言为具体真值 (删拦截式任一条件某组合真值
 * 必翻, 见各断言注释)。
 *
 * template = "empty", batch = "champion_aoe_immunity_buffer"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class AoeImmunityBufferGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_aoe_immunity_buffer";

    // ============================================================
    // 拦截真值表 8 组合: 仅 (缓冲中 且 冠军直伤 且 非豁免) 拦, 其余 7 组放行
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void blocksOnlyWhenBufferedChampionAndNotExempt(GameTestHelper helper) {
        // 唯一拦截组合: 缓冲中 + 冠军直接伤害 + 非豁免类型 -> 掐 0 取消。
        helper.assertTrue(AoeImmunityBuffer.shouldBlockDamage(true, true, false),
                "缓冲中 + 冠军直伤 + 非豁免 -> 拦 (唯一拦截组合)");

        // 缓冲维度翻转 (删 victimBuffered 条件本组必由 false 翻 true): 未缓冲则不拦。
        helper.assertTrue(!AoeImmunityBuffer.shouldBlockDamage(false, true, false),
                "未缓冲 + 冠军直伤 + 非豁免 -> 放行 (钉死 victimBuffered 条件)");
        // 冠军维度翻转 (删 attackerIsChampion 条件本组必翻): 非冠军来源不拦 (环境/玩家互伤/原版怪)。
        helper.assertTrue(!AoeImmunityBuffer.shouldBlockDamage(true, false, false),
                "缓冲中 + 非冠军来源 + 非豁免 -> 放行 (钉死 attackerIsChampion 条件)");
        // 豁免维度翻转 (删 !isExemptDamageType 条件本组必翻): 豁免类型不拦 (DoT/处决/反震按各自规则走)。
        helper.assertTrue(!AoeImmunityBuffer.shouldBlockDamage(true, true, true),
                "缓冲中 + 冠军直伤 + 豁免类型 -> 放行 (钉死 !isExemptDamageType 条件)");

        // 其余 4 组 (至少两条件为假) 一律放行, 补齐 8 组真值表。
        helper.assertTrue(!AoeImmunityBuffer.shouldBlockDamage(false, false, false),
                "全否 -> 放行");
        helper.assertTrue(!AoeImmunityBuffer.shouldBlockDamage(false, false, true),
                "未缓冲 + 非冠军 + 豁免 -> 放行");
        helper.assertTrue(!AoeImmunityBuffer.shouldBlockDamage(false, true, true),
                "未缓冲 + 冠军直伤 + 豁免 -> 放行");
        helper.assertTrue(!AoeImmunityBuffer.shouldBlockDamage(true, false, true),
                "缓冲中 + 非冠军 + 豁免 -> 放行");
        helper.succeed();
    }
}
