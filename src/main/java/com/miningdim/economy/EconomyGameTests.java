package com.miningdim.economy;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.EconomyConstants.HighValueOre;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.UUID;

/**
 * 货币层核心逻辑 GameTest (经济文档第九章 tests 块 + 框架 spec 第三章 + 实现手册 GameTest 范式)。
 *
 * 断言具体业务数额/状态/副作用 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验):
 *  - tryDebit 余额不足边界 (余额 / 余额+1);
 *  - settleOreSale 衰减 (n=cap 全价 / n=cap+1 ×0.97 / 大 over 夹 0.25 地板; 钻石 base=500);
 *  - 先校验后扣杜绝双花 (序列双扣只第一次成功 —— 主线程不变量, 见 doubleDebit 注释);
 *  - AZURE 不可转移 (货币层无 P2P 入口, isTransferable 硬不变量);
 *  - NBT round-trip 边界 0 / Long.MAX_VALUE 防溢出 + grant 溢出抛 BALANCE_OVERFLOW;
 *  - setDirty 每次变更被调用;
 *  - tryChargeDaily 每日上限边界。
 *
 * 纯逻辑/SavedData 断言: 钱包与账本可在内存直接构造 (new), 不依赖世界写; 涉及 ServerPlayer 的门面方法
 * 用 helper.makeMockServerPlayerInLevel() 取真实 ServerPlayer。template = "empty"。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class EconomyGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "economy";

    // ============================================================
    // PlayerWallet: tryDebit 余额边界 + credit 溢出 + 非法金额
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tryDebitInsufficientBoundary(GameTestHelper helper) {
        PlayerWallet w = new PlayerWallet();
        w.credit(Currency.CREDIT, 100L);

        // 余额恰等于扣费: 成功, 余额归 0。
        helper.assertTrue(w.tryDebit(Currency.CREDIT, 100L), "debit exactly the balance must succeed");
        helper.assertTrue(w.balance(Currency.CREDIT) == 0L, "balance must be 0 after debiting full amount");

        // 余额比扣费少 1: 失败且不动余额 (先校验后扣)。
        w.credit(Currency.CREDIT, 100L);
        helper.assertTrue(!w.tryDebit(Currency.CREDIT, 101L), "debit balance+1 must fail");
        helper.assertTrue(w.balance(Currency.CREDIT) == 100L, "failed debit must not touch balance (still 100)");

        // 余额恰够: 第 100 次成功。
        helper.assertTrue(w.tryDebit(Currency.CREDIT, 100L), "debit at exact balance succeeds again");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void debitDoesNotDoubleSpend(GameTestHelper helper) {
        // 货币是主线程权威 (SavedData 仅主线程访问), 不存在真并发; "双扣只成功一次"在本层等价于"先校验后扣":
        // 对同一余额连续两次扣全额, 第一次成功后余额 0, 第二次必失败 —— 这正是杜绝双花的确定性保证。
        PlayerWallet w = new PlayerWallet();
        w.credit(Currency.CREDIT, 50L);
        boolean first = w.tryDebit(Currency.CREDIT, 50L);
        boolean second = w.tryDebit(Currency.CREDIT, 50L);
        helper.assertTrue(first, "first full debit succeeds");
        helper.assertTrue(!second, "second full debit on now-empty wallet must fail (no double spend)");
        helper.assertTrue(w.balance(Currency.CREDIT) == 0L, "balance debited exactly once");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void creditOverflowThrows(GameTestHelper helper) {
        PlayerWallet w = new PlayerWallet();
        w.credit(Currency.CREDIT, Long.MAX_VALUE);
        helper.assertTrue(w.balance(Currency.CREDIT) == Long.MAX_VALUE, "balance can hold Long.MAX_VALUE");

        // 再入账 1 应溢出 -> BALANCE_OVERFLOW (Math.addExact 检出, 不回绕为负)。
        boolean threw = false;
        try {
            w.credit(Currency.CREDIT, 1L);
        } catch (EconomyException e) {
            threw = e.reason() == EconomyException.Reason.BALANCE_OVERFLOW;
        }
        helper.assertTrue(threw, "crediting past Long.MAX_VALUE must throw BALANCE_OVERFLOW (not silently wrap)");
        helper.assertTrue(w.balance(Currency.CREDIT) == Long.MAX_VALUE,
                "balance must be unchanged after overflow rejection (still MAX_VALUE, not negative)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void illegalAmountThrows(GameTestHelper helper) {
        PlayerWallet w = new PlayerWallet();
        boolean creditThrew = false;
        try {
            w.credit(Currency.CREDIT, 0L);
        } catch (EconomyException e) {
            creditThrew = e.reason() == EconomyException.Reason.ILLEGAL_AMOUNT;
        }
        helper.assertTrue(creditThrew, "credit of 0 must throw ILLEGAL_AMOUNT");

        boolean debitThrew = false;
        try {
            w.tryDebit(Currency.CREDIT, -5L);
        } catch (EconomyException e) {
            debitThrew = e.reason() == EconomyException.Reason.ILLEGAL_AMOUNT;
        }
        helper.assertTrue(debitThrew, "debit of negative amount must throw ILLEGAL_AMOUNT");
        helper.succeed();
    }

    // ============================================================
    // NBT round-trip 边界 (0 / Long.MAX_VALUE 防溢出)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void walletNbtRoundTripBoundary(GameTestHelper helper) {
        PlayerWallet w = new PlayerWallet();
        // 边界 0: 全新钱包两币余额 0, round-trip 仍 0。
        PlayerWallet zeroReloaded = PlayerWallet.load(w.save());
        helper.assertTrue(zeroReloaded.balance(Currency.CREDIT) == 0L, "fresh wallet CREDIT round-trips to 0");
        helper.assertTrue(zeroReloaded.balance(Currency.AZURE) == 0L, "fresh wallet AZURE round-trips to 0");

        // 边界 Long.MAX_VALUE: 信用点 MAX、青辉石 MAX, round-trip 精确还原 (long 不丢精度/不溢出)。
        w.credit(Currency.CREDIT, Long.MAX_VALUE);
        w.credit(Currency.AZURE, Long.MAX_VALUE);
        PlayerWallet maxReloaded = PlayerWallet.load(w.save());
        helper.assertTrue(maxReloaded.balance(Currency.CREDIT) == Long.MAX_VALUE,
                "CREDIT Long.MAX_VALUE round-trips exactly");
        helper.assertTrue(maxReloaded.balance(Currency.AZURE) == Long.MAX_VALUE,
                "AZURE Long.MAX_VALUE round-trips exactly");
        helper.succeed();
    }

    // ============================================================
    // EconomyWalletData: setDirty 副作用 + tryChargeDaily 上限边界
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ledgerSetsDirtyOnEveryChange(GameTestHelper helper) {
        EconomyWalletData ledger = new EconomyWalletData();
        UUID id = UUID.randomUUID();

        // 初始未脏。
        helper.assertTrue(!ledger.isDirty(), "fresh ledger is not dirty");
        ledger.credit(id, Currency.CREDIT, 100L);
        helper.assertTrue(ledger.isDirty(), "credit must mark ledger dirty");

        ledger.setDirty(false);
        helper.assertTrue(ledger.tryDebit(id, Currency.CREDIT, 40L), "debit succeeds");
        helper.assertTrue(ledger.isDirty(), "successful debit must mark ledger dirty");

        // 失败扣费不应标脏 (无状态变更)。
        ledger.setDirty(false);
        helper.assertTrue(!ledger.tryDebit(id, Currency.CREDIT, 9_999L), "over-balance debit fails");
        helper.assertTrue(!ledger.isDirty(), "failed debit must NOT mark dirty (no state change)");

        helper.assertTrue(ledger.balance(id, Currency.CREDIT) == 60L, "balance 100 - 40 = 60 after the two debits");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tryChargeDailyCapBoundary(GameTestHelper helper) {
        EconomyWalletData ledger = new EconomyWalletData();
        UUID id = UUID.randomUUID();
        ledger.credit(id, Currency.CREDIT, 1_000L);
        long today = 20_000L; // 固定 UTC 日戳 (epochDay) 做确定性测试。

        // 当日上限 100: 先扣 60 (累计 60 <= 100) 成功。
        helper.assertTrue(ledger.tryChargeDaily(id, Currency.CREDIT, 60L, "pack", 100L, today),
                "first daily charge 60 within cap 100 succeeds");
        // 再扣 40 (累计 100 <= 100) 成功 (恰达上限)。
        helper.assertTrue(ledger.tryChargeDaily(id, Currency.CREDIT, 40L, "pack", 100L, today),
                "second daily charge 40 reaching cap 100 succeeds");
        // 再扣 1 (累计 101 > 100) 失败, 不扣余额。
        long balBefore = ledger.balance(id, Currency.CREDIT);
        helper.assertTrue(!ledger.tryChargeDaily(id, Currency.CREDIT, 1L, "pack", 100L, today),
                "third daily charge exceeding cap 100 fails");
        helper.assertTrue(ledger.balance(id, Currency.CREDIT) == balBefore,
                "over-cap daily charge must not touch balance");
        helper.assertTrue(balBefore == 900L, "balance reflects 1000 - 60 - 40 = 900");

        // 翻日后该 key 计数清零, 又可扣满。
        long tomorrow = today + 1L;
        helper.assertTrue(ledger.tryChargeDaily(id, Currency.CREDIT, 100L, "pack", 100L, tomorrow),
                "after UTC rollover the daily counter resets and a full-cap charge succeeds again");
        helper.assertTrue(ledger.balance(id, Currency.CREDIT) == 800L, "balance 900 - 100 = 800 next day");
        helper.succeed();
    }

    // ============================================================
    // settleOreSale 衰减结算 (经济文档 8.1 钻石 base=500 + 18.3 0.97/0.25)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleOreSaleDecay(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        EconomyWalletData ledger = new EconomyWalletData();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard());

        double base = ShopPriceTable.ORE_BASE_DIAMOND; // 8.1 ×10 锚: 500
        helper.assertTrue(base == 500.0D, "diamond ore anchor base price is 500 (economy spec 8.1)");

        int cap = EconomyConstants.DAILY_SOFTCAP_DIAMOND; // 64
        // n = cap: 全价 500 (over = 0, ratio = 1.0)。
        long atCap = eco.settleOreSale(player, HighValueOre.DIAMOND, cap, base);
        helper.assertTrue(atCap == 500L, "diamond at softCap (n=64) settles full price 500");

        // n = cap + 1: ×0.97 -> 485 (0.97^1)。
        long overByOne = eco.settleOreSale(player, HighValueOre.DIAMOND, cap + 1, base);
        helper.assertTrue(overByOne == 485L, "diamond at softCap+1 (n=65) settles 500*0.97 = 485");

        // 大 over: 夹 0.25 地板 -> 125 (review: n 极大期望 125.0)。
        long deep = eco.settleOreSale(player, HighValueOre.DIAMOND, cap + 100_000, base);
        helper.assertTrue(deep == 125L, "diamond deep over-cap settles at 0.25 floor: 500*0.25 = 125");

        // 入账落账本: 三次累计 500 + 485 + 125 = 1110 信用点。
        helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 1_110L,
                "three ore sales credit 500 + 485 + 125 = 1110 to the wallet");
        helper.succeed();
    }

    // ============================================================
    // EconomyService 门面: grant 溢出冒泡 + tryCharge 余额边界
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void serviceGrantOverflowBubbles(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        EconomyWalletData ledger = new EconomyWalletData();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard());

        eco.grant(player, Currency.CREDIT, Long.MAX_VALUE);
        helper.assertTrue(eco.creditBalance(player) == Long.MAX_VALUE, "grant fills balance to MAX_VALUE");

        boolean threw = false;
        try {
            eco.grant(player, Currency.CREDIT, 1L);
        } catch (EconomyException e) {
            threw = e.reason() == EconomyException.Reason.BALANCE_OVERFLOW;
        }
        helper.assertTrue(threw, "grant past MAX_VALUE bubbles BALANCE_OVERFLOW through the service facade");

        // tryCharge 余额边界: 扣 MAX 成功归 0, 再扣 1 失败。
        helper.assertTrue(eco.tryCharge(player, Currency.CREDIT, Long.MAX_VALUE), "charge full balance succeeds");
        helper.assertTrue(eco.creditBalance(player) == 0L, "balance 0 after full charge");
        helper.assertTrue(!eco.tryCharge(player, Currency.CREDIT, 1L), "charge on empty wallet fails");
        helper.succeed();
    }

    // ============================================================
    // AZURE 不可转移 (货币层无 P2P 入口; 反洗钱硬不变量, 经济文档 0.3-46/1.2)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void azureNotTransferable(GameTestHelper helper) {
        // 青辉石绑定不可转移 (DB 交易层据此拒绝挂单); 信用点可经 DB 交易通道转移。
        helper.assertTrue(!Currency.AZURE.isTransferable(), "AZURE must be non-transferable (RMT lockdown)");
        helper.assertTrue(Currency.CREDIT.isTransferable(), "CREDIT is transferable via the DB trade channel");

        // 货币层门面不暴露任何 P2P 方法 (反洗钱): IEconomyService 只有 balance/charge/grant/daily/settleOreSale。
        // 此断言守护"货币层无零成本转移后门"的契约 —— 若未来有人在门面加 transfer, 本测试需同步评审。
        long p2pMethods = java.util.Arrays.stream(IEconomyService.class.getMethods())
                .filter(m -> m.getName().equals("transfer") || m.getName().startsWith("transferTo"))
                .count();
        helper.assertTrue(p2pMethods == 0L,
                "IEconomyService must expose NO player-to-player transfer method (anti-money-laundering, 0.3-46)");
        helper.succeed();
    }

    // ============================================================
    // EconomyWalletData NBT round-trip (含每日计数与多钱包)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ledgerNbtRoundTrip(GameTestHelper helper) {
        EconomyWalletData ledger = new EconomyWalletData();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        ledger.credit(a, Currency.CREDIT, Long.MAX_VALUE);
        ledger.credit(a, Currency.AZURE, 7L);
        ledger.credit(b, Currency.CREDIT, 0L + 333L);
        long today = 20_000L;
        ledger.tryChargeDaily(b, Currency.CREDIT, 33L, "pack", 100L, today);

        CompoundTag tag = ledger.save(new CompoundTag());
        EconomyWalletData reloaded = EconomyWalletData.load(tag);

        helper.assertTrue(reloaded.balance(a, Currency.CREDIT) == Long.MAX_VALUE,
                "wallet A CREDIT MAX_VALUE round-trips exactly through the ledger");
        helper.assertTrue(reloaded.balance(a, Currency.AZURE) == 7L, "wallet A AZURE round-trips to 7");
        helper.assertTrue(reloaded.balance(b, Currency.CREDIT) == 300L,
                "wallet B CREDIT round-trips to 333 - 33 = 300 (daily charge persisted as a debit)");

        // 每日计数也持久化: 同日再扣到上限边界, 累计仍以已持久化的 33 起算 (33 + 67 = 100 恰达上限成功)。
        helper.assertTrue(reloaded.tryChargeDaily(b, Currency.CREDIT, 67L, "pack", 100L, today),
                "persisted daily counter continues from 33: a further 67 reaches cap 100");
        helper.assertTrue(!reloaded.tryChargeDaily(b, Currency.CREDIT, 1L, "pack", 100L, today),
                "the persisted daily counter is now at cap; a further 1 is rejected");
        helper.succeed();
    }
}
