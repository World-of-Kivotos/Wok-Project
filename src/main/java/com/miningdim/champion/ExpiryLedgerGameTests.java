package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 时限标记账本纯逻辑 GameTest (ChampionStarAffix spec 9.3 / 红线3+红线6; 批4 波0)。
 *
 * 钉死 {@link ExpiryLedger} 的半开区间到期语义 (expiry-1 活 / expiry 死)、重复 grant 取更晚者 (旧值不缩短已延长
 * 的保护)、sweep 只清过期且判据与 isActive 互补 (删任一处必挂)。用固定 UUID 逐 tick 边界核对。
 *
 * template = "empty", batch = "champion_expiry_ledger"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class ExpiryLedgerGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "champion_expiry_ledger";

    private static final UUID A = new UUID(0L, 1L);
    private static final UUID B = new UUID(0L, 2L);
    private static final UUID C = new UUID(0L, 3L);

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void activeIsHalfOpenAtExpiryBoundary(GameTestHelper helper) {
        // 半开区间: nowTick < expiryTick 才活。expiry=100 -> 99 活, 100 死, 101 死。
        ExpiryLedger ledger = new ExpiryLedger();
        ledger.grant(A, 100L);
        helper.assertTrue(ledger.isActive(A, 99L), "expiry-1 (99) 仍在保护期");
        helper.assertFalse(ledger.isActive(A, 100L), "到期 tick (100) 当刻即失效");
        helper.assertFalse(ledger.isActive(A, 101L), "越过到期 tick (101) 失效");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unknownIdIsInactive(GameTestHelper helper) {
        // 从未授予的 id 恒判无效 (不得因缺省抛异常或误判有效)。
        ExpiryLedger ledger = new ExpiryLedger();
        helper.assertFalse(ledger.isActive(A, 0L), "未授予的 id 判无效");
        helper.assertTrue(ledger.size() == 0, "空账本 size = 0");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void repeatGrantKeepsLaterNeverShortens(GameTestHelper helper) {
        // 重复 grant 取更晚者: 先 100 再 50 -> 仍 100 (旧的更早值不得砍短已有保护)。
        ExpiryLedger ledger = new ExpiryLedger();
        ledger.grant(A, 100L);
        ledger.grant(A, 50L);
        helper.assertTrue(ledger.isActive(A, 99L),
                "较早的二次 grant(50) 不缩短保护: 99 仍活 (若被砍到 50 则此刻已死)");
        helper.assertFalse(ledger.isActive(A, 100L), "上界仍为原 100");
        helper.assertTrue(ledger.size() == 1, "同 id 重复 grant 不增项");

        // 更晚的 grant 延长保护: 200 覆盖后 150 变活 (原 100 早已死)。
        ledger.grant(A, 200L);
        helper.assertTrue(ledger.isActive(A, 150L), "更晚 grant(200) 延长保护: 150 活");
        helper.assertTrue(ledger.isActive(A, 199L), "延长后 199 仍活");
        helper.assertFalse(ledger.isActive(A, 200L), "延长后新上界 200 失效");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sweepRemovesOnlyExpired(GameTestHelper helper) {
        // sweep 只清已过期 (nowTick >= expiryTick, 与 isActive 互补)。A=100 B=200 C=300, sweep(150) 只清 A。
        ExpiryLedger ledger = new ExpiryLedger();
        ledger.grant(A, 100L);
        ledger.grant(B, 200L);
        ledger.grant(C, 300L);
        int removed = ledger.sweep(150L);
        helper.assertTrue(removed == 1, "sweep(150) 移除 1 项 (仅 A), 实得 " + removed);
        helper.assertTrue(ledger.size() == 2, "余 2 项 (B, C)");
        helper.assertFalse(ledger.isActive(A, 150L), "A 已被清");
        helper.assertTrue(ledger.isActive(B, 150L), "B 未过期仍活");
        helper.assertTrue(ledger.isActive(C, 150L), "C 未过期仍活");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sweepAtExactExpiryRemovesItem(GameTestHelper helper) {
        // sweep 判据 nowTick >= expiryTick: 恰在到期 tick 就清 (与 isActive 半开边界一致)。
        ExpiryLedger ledger = new ExpiryLedger();
        ledger.grant(A, 100L);
        int removed = ledger.sweep(100L);
        helper.assertTrue(removed == 1, "sweep 恰在到期 tick (100) 移除该项");
        helper.assertTrue(ledger.size() == 0, "清后账本空");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sweepBeforeExpiryRemovesNothing(GameTestHelper helper) {
        // 未到期 sweep 不清任何项 (返 0, size 不变)。
        ExpiryLedger ledger = new ExpiryLedger();
        ledger.grant(A, 200L);
        ledger.grant(B, 300L);
        int removed = ledger.sweep(50L);
        helper.assertTrue(removed == 0, "全未过期 sweep 移除 0 项");
        helper.assertTrue(ledger.size() == 2, "size 不变仍 2");
        helper.succeed();
    }
}
