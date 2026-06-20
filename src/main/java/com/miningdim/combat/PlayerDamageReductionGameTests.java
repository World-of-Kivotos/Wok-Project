package com.miningdim.combat;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

/**
 * 玩家减伤单点结算纯逻辑 GameTest: keepFactor 的乘法连乘 + 全局帽钳制 + 脏值拒收 + 注册表。全为确定性数值断言
 * (删掉被测逻辑即挂), 不依赖世界/网络。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlayerDamageReductionGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "combat";
    private static final double EPS = 1e-9D;

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void keepFactorMultipliesAndCaps(GameTestHelper helper) {
        // 无源: 不减伤。
        helper.assertTrue(Math.abs(PlayerDamageReduction.keepFactor() - 1.0D) < EPS, "no source -> keep 1.0");
        // 单源 50%: keep 0.5。
        helper.assertTrue(Math.abs(PlayerDamageReduction.keepFactor(0.5D) - 0.5D) < EPS, "one 50% -> 0.5");
        // 两源 50% (塔罗+酒): 乘法 0.25 (= 75% 减伤), 未触帽。
        helper.assertTrue(Math.abs(PlayerDamageReduction.keepFactor(0.5D, 0.5D) - 0.25D) < EPS, "two 50% -> 0.25");
        // 两源 60%: 0.16 (>0.15 帽), 不钳。
        helper.assertTrue(Math.abs(PlayerDamageReduction.keepFactor(0.6D, 0.6D) - 0.16D) < EPS, "two 60% -> 0.16");
        // 0 减伤源: keep 1.0。
        helper.assertTrue(Math.abs(PlayerDamageReduction.keepFactor(0.0D) - 1.0D) < EPS, "zero rate -> 1.0");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void keepFactorClampsToGlobalCap(GameTestHelper helper) {
        // 三源 50%: 乘法 0.125 < 0.15 帽 -> 钳到 PLAYER_MIN_KEEP (最多减 85%)。
        helper.assertTrue(Math.abs(PlayerDamageReduction.keepFactor(0.5D, 0.5D, 0.5D) - CombatConstants.PLAYER_MIN_KEEP) < EPS,
                "three 50% -> clamped to min keep");
        // 单源 90%: 0.10 < 0.15 -> 钳到帽 (90% 单源也被压到 85%)。
        helper.assertTrue(Math.abs(PlayerDamageReduction.keepFactor(0.9D) - CombatConstants.PLAYER_MIN_KEEP) < EPS,
                "single 90% -> clamped to min keep");
        // 单源 100%: keep 0 -> 钳到帽 (绝不无敌)。
        helper.assertTrue(Math.abs(PlayerDamageReduction.keepFactor(1.0D) - CombatConstants.PLAYER_MIN_KEEP) < EPS,
                "single 100% -> clamped to min keep (never invulnerable)");
        // 帽 = 1 - 0.85 = 0.15。
        helper.assertTrue(Math.abs(CombatConstants.PLAYER_MIN_KEEP - 0.15D) < EPS, "min keep = 0.15 (max 85% reduction)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void keepFactorRejectsDirtyRates(GameTestHelper helper) {
        boolean tooHigh = false;
        try {
            PlayerDamageReduction.keepFactor(1.5D);
        } catch (IllegalArgumentException e) {
            tooHigh = true;
        }
        helper.assertTrue(tooHigh, "rate > 1 throws");
        boolean negative = false;
        try {
            PlayerDamageReduction.keepFactor(-0.1D);
        } catch (IllegalArgumentException e) {
            negative = true;
        }
        helper.assertTrue(negative, "rate < 0 throws");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void registryTracksSources(GameTestHelper helper) {
        int before = PlayerDamageReduction.sourceCount();
        PlayerDamageReduction.ReductionSource probe = new PlayerDamageReduction.ReductionSource() {
            @Override
            public String name() {
                return "test_probe";
            }

            @Override
            public double rate(net.minecraft.world.entity.player.Player victim, net.minecraft.world.damagesource.DamageSource source) {
                return 0.0D; // 探针不实际减伤 (返回 0), 仅验证注册计数。
            }
        };
        PlayerDamageReduction.register(probe);
        helper.assertTrue(PlayerDamageReduction.sourceCount() == before + 1, "register increments source count");
        helper.succeed();
    }
}
