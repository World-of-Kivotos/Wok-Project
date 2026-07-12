package com.miningdim.champion;

import com.miningdim.champion.integration.PlayerLandingProtection;
import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 玩家 2s 抗位移落地保护 取消判定纯逻辑 GameTest (批4 波0; ChampionStarAffix spec 9.3 / 红线 6 TDD)。
 *
 * 只断言 {@link PlayerLandingProtection#shouldCancelKnockback} 的取消映射 (保护窗内取消 / 窗外放行 / 未记账放行);
 * 窗口以 spec 9.3 定值 {@link PlayerLandingProtection#PROTECTION_TICKS} 派生 (grantTick+PROTECTION_TICKS 为到期)。取消
 * 判定用真 ServerPlayer 在 dev GameTest 不可得, 故契约把可测缝下沉为以自建 {@link ExpiryLedger} 驱动的纯静态
 * 谓词, 本类直接 new ExpiryLedger 驱动逐 tick 边界核对。
 * 账本半开区间到期语义/grant 取更晚/sweep 判据由 {@link ExpiryLedgerGameTests} 自测覆盖, 本类不重复测 (只测本组件独有
 * 的取消映射)。全部断言为具体真值 (删 shouldCancelKnockback 的委托即翻某组合真值, 见各断言)。
 *
 * template = "empty", batch = "champion_landing_protection"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class PlayerLandingProtectionGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_landing_protection";

    /** 固定 UUID: 受保护玩家 A / 未记账旁观玩家 B (保护按 UUID 隔离, 逐 tick 边界核对确定性)。 */
    private static final UUID A = new UUID(0L, 1L);
    private static final UUID B = new UUID(0L, 2L);

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void cancelsWithinProtectionWindow(GameTestHelper helper) {
        // 授予当 tick 起、到期前一 tick 止均在窗内 -> 取消击退 (半开区间下界: 授予当刻即活)。
        ExpiryLedger ledger = new ExpiryLedger();
        long grantTick = 1000L;
        long expiry = grantTick + PlayerLandingProtection.PROTECTION_TICKS; // 1040
        ledger.grant(A, expiry);
        helper.assertTrue(PlayerLandingProtection.shouldCancelKnockback(A, grantTick, ledger),
                "授予当 tick (1000) 在保护窗内 -> 取消击退");
        helper.assertTrue(PlayerLandingProtection.shouldCancelKnockback(A, expiry - 1L, ledger),
                "到期前一 tick (1039) 仍在保护窗内 -> 取消击退");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void doesNotCancelAtOrAfterExpiry(GameTestHelper helper) {
        // 半开区间: 到期 tick 当刻及之后失效 -> 不取消, 放行原版击退结算 (钉死 shouldCancelKnockback 委托 isActive 的窗外假)。
        ExpiryLedger ledger = new ExpiryLedger();
        long expiry = 1000L + PlayerLandingProtection.PROTECTION_TICKS; // 1040
        ledger.grant(A, expiry);
        helper.assertTrue(!PlayerLandingProtection.shouldCancelKnockback(A, expiry, ledger),
                "到期 tick (1040) 当刻失效 -> 不取消 (放行击退)");
        helper.assertTrue(!PlayerLandingProtection.shouldCancelKnockback(A, expiry + 5L, ledger),
                "越过到期 tick (1045) 失效 -> 不取消");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void doesNotCancelUngrantedPlayer(GameTestHelper helper) {
        // 未记账玩家恒不取消 (保护按 UUID 隔离, 不因他人在窗而串号误取消)。
        ExpiryLedger ledger = new ExpiryLedger();
        ledger.grant(A, 1000L + PlayerLandingProtection.PROTECTION_TICKS);
        helper.assertTrue(!PlayerLandingProtection.shouldCancelKnockback(B, 1000L, ledger),
                "未授予保护的玩家 B -> 不取消 (即便查询点落在 A 的保护窗内)");
        // 空账本任何玩家不取消 (无授予即无保护)。
        ExpiryLedger empty = new ExpiryLedger();
        helper.assertTrue(!PlayerLandingProtection.shouldCancelKnockback(A, 0L, empty),
                "空账本任何玩家 -> 不取消");
        helper.succeed();
    }
}
