package com.miningdim.economy;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.EconomyConstants.HighValueOre;
import com.miningdim.store.MiningDb;
import com.miningdim.store.MiningSchema;
import com.miningdim.store.MiningStoreException;
import com.miningdim.store.StoreMeta;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.testutil.TempStoreDb;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.nio.file.Path;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * 货币层核心逻辑 GameTest (经济文档第九章 tests 块 + 框架 spec 第三章 + 实现手册 GameTest 范式)。
 *
 * 断言具体业务数额/状态/副作用 (删被测核心逻辑测试必挂, 禁 is-not-null 弱校验):
 *  - tryDebit 余额不足边界 (余额 / 余额+1);
 *  - settleOreSale 两层串联 (逐矿 n=cap 全价 500 / n=cap+1 ×0.97=485 / 大 over 夹 1% 地板=5; 钻石 base=500),
 *    且现经 grantDaily 并入 credit_faucet 主闸真改余额 (非旧的直接 ledger.credit; 共享同一档则落 band1 ×0.6);
 *  - 地板从 0.25 改 1% (buyPrice 深档单价 base×0.01: 钻 5 / 金 1.2 / 残骸 45);
 *  - faucetCreditAfterDecay 主闸曲线 (累计 0 系数 1 / 60000 ×0.6 / 120000 ×0.36 / 极深夹 1% 地板),
 *    几何主项前 10 档 = 149093 (≈14.9 万正常落点), 深档 1% 地板留极薄线性尾巴 (不收敛、无数学硬顶), 拆分不变性 (一笔 2*tier == 两笔各 tier);
 *  - 先校验后扣杜绝双花 (序列双扣只第一次成功 —— 主线程不变量, 见 doubleDebit 注释);
 *  - operationId 双币原子扣款、持久幂等重放、完成/退款 Saga 状态在关闭连接重开后仍在;
 *  - OP 管理入口所用双币发放同时入账，任一币种溢出时两币均不变;
 *  - AZURE 不可转移 (货币层无 P2P 入口, isTransferable 硬不变量);
 *  - NBT round-trip 边界 0 / Long.MAX_VALUE 防溢出 + grant 溢出抛 BALANCE_OVERFLOW;
 *  - 已提交的余额与账本状态在关闭连接重开后仍在 (提交即落盘, 不再靠脏标记);
 *  - tryChargeDaily 每日上限边界;
 *  - grantAzureDaily / creditAzureDaily 青辉石每人每日产出硬上限 (超 cap 截断 / 撞顶 0 入账 / 跨 UTC 日重置;
 *    经济文档 8.5 战斗 faucet 并入每人每日上限; economy-02)。
 *
 * 纯逻辑断言用内存统一库 (SqliteEconomyLedger.openInMemory), 不依赖世界写; 凡断言"确实落盘"的用例
 * 必须改用 TempStoreDb 的真实文件库并真的关连接重开 —— 内存库的 journal_mode 实为 memory, 测不出落盘。涉及 ServerPlayer 的门面方法
 * 用 MockGameTestPlayers.makeMockServerPlayerWithChannel(helper) 取真实 ServerPlayer。template = "empty"。
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
    public static void dualCurrencyWalletMutationIsAtomic(GameTestHelper helper) {
        PlayerWallet wallet = new PlayerWallet();
        wallet.credit(Currency.CREDIT, 100L);
        wallet.credit(Currency.AZURE, 10L);

        helper.assertTrue(!wallet.tryDebitBundle(101L, 5L),
                "bundle fails when CREDIT is short");
        helper.assertTrue(wallet.balance(Currency.CREDIT) == 100L
                        && wallet.balance(Currency.AZURE) == 10L,
                "CREDIT shortage leaves both currencies untouched");

        helper.assertTrue(!wallet.tryDebitBundle(50L, 11L),
                "bundle fails when AZURE is short");
        helper.assertTrue(wallet.balance(Currency.CREDIT) == 100L
                        && wallet.balance(Currency.AZURE) == 10L,
                "AZURE shortage leaves both currencies untouched");

        helper.assertTrue(wallet.tryDebitBundle(80L, 4L),
                "bundle succeeds only after both balances pass preflight");
        helper.assertTrue(wallet.balance(Currency.CREDIT) == 20L
                        && wallet.balance(Currency.AZURE) == 6L,
                "successful bundle deducts CREDIT and AZURE exactly once");

        wallet.creditBundle(80L, 4L);
        helper.assertTrue(wallet.balance(Currency.CREDIT) == 100L
                        && wallet.balance(Currency.AZURE) == 10L,
                "atomic bundle refund restores both original balances");

        wallet.credit(Currency.CREDIT, Long.MAX_VALUE - 100L);
        boolean overflow = false;
        try {
            wallet.creditBundle(1L, 1L);
        } catch (EconomyException e) {
            overflow = e.reason() == EconomyException.Reason.BALANCE_OVERFLOW;
        }
        helper.assertTrue(overflow, "bundle refund rejects overflow before changing either currency");
        helper.assertTrue(wallet.balance(Currency.CREDIT) == Long.MAX_VALUE
                        && wallet.balance(Currency.AZURE) == 10L,
                "failed bundle refund leaves both balances unchanged");
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
    // 账本落盘语义 + tryChargeDaily 上限边界
    // ============================================================

    /**
     * 账本迁进 SQLite 后, "钱写没写下去"不再由脏标记表达, 而是由提交即落盘表达。
     * 因此这里必须用真实文件库并真的关掉连接重开 —— 这是唯一能证明余额确实落到磁盘的做法,
     * 内存库的 journal_mode 实为 memory, 换不出任何落盘信息。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void committedBalancesSurviveReopen(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        try {
            Path dbPath = dir.resolve("economy.db");
            UUID id = UUID.randomUUID();

            Connection first = TempStoreDb.openUnified(dbPath);
            try {
                EconomyLedger ledger = new SqliteEconomyLedger(first);
                ledger.credit(id, Currency.CREDIT, 100L);
                helper.assertTrue(ledger.tryDebit(id, Currency.CREDIT, 40L), "debit succeeds");
                helper.assertTrue(!ledger.tryDebit(id, Currency.CREDIT, 9_999L), "over-balance debit fails");
                helper.assertTrue(ledger.balance(id, Currency.CREDIT) == 60L,
                        "balance 100 - 40 = 60 after the two debits");
            } finally {
                MiningDb.close(first);
            }

            Connection second = TempStoreDb.openUnified(dbPath);
            try {
                EconomyLedger reopened = new SqliteEconomyLedger(second);
                helper.assertTrue(reopened.balance(id, Currency.CREDIT) == 60L,
                        "已提交的余额必须在关闭连接重开后仍是 60 (失败的那笔扣款不得留下任何痕迹)");
                helper.assertTrue(reopened.balance(id, Currency.AZURE) == 0L,
                        "从未入账过的货币重开后仍是 0");
            } finally {
                MiningDb.close(second);
            }
        } finally {
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bundleChargeIsIdempotentAndConflictSafe(GameTestHelper helper) {
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        UUID playerId = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        ledger.credit(playerId, Currency.CREDIT, 1_000L);
        ledger.credit(playerId, Currency.AZURE, 50L);

        EconomyOperationStatus first = ledger.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, playerId, operationId, 200L, 10L);
        helper.assertTrue(first == EconomyOperationStatus.CHARGED,
                "first sufficient bundle charge records CHARGED");
        helper.assertTrue(ledger.balance(playerId, Currency.CREDIT) == 800L
                        && ledger.balance(playerId, Currency.AZURE) == 40L,
                "first charge deducts both configured costs once");

        EconomyOperationStatus replay = ledger.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, playerId, operationId, 200L, 10L);
        helper.assertTrue(replay == EconomyOperationStatus.CHARGED,
                "same operationId replay returns its persisted successful state");
        helper.assertTrue(ledger.balance(playerId, Currency.CREDIT) == 800L
                        && ledger.balance(playerId, Currency.AZURE) == 40L,
                "same operationId replay does not deduct either currency again");

        boolean conflict = false;
        try {
            ledger.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, playerId, operationId, 201L, 10L);
        } catch (EconomyException e) {
            conflict = e.reason() == EconomyException.Reason.OPERATION_CONFLICT;
        }
        helper.assertTrue(conflict, "reusing operationId with a different amount is rejected as a conflict");

        UUID insufficientId = UUID.randomUUID();
        EconomyOperationStatus insufficient = ledger.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, playerId, insufficientId, 801L, 1L);
        helper.assertTrue(insufficient == EconomyOperationStatus.NONE,
                "insufficient first attempt returns NONE and creates no operation");
        helper.assertTrue(ledger.operationStatus(EconomyOperationDomain.CASE_OPENING, playerId, insufficientId) == EconomyOperationStatus.NONE,
                "insufficient attempt is not persisted as a successful operation");
        helper.assertTrue(ledger.balance(playerId, Currency.CREDIT) == 800L
                        && ledger.balance(playerId, Currency.AZURE) == 40L,
                "insufficient bundle leaves both currencies untouched");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void tryChargeDailyCapBoundary(GameTestHelper helper) {
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
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
    // creditAzureDaily: 青辉石 faucet 每人每日产出硬上限 (经济文档 8.5 战斗 faucet 并入每人每日上限; economy-02)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void azureDailyFaucetCapTruncates(GameTestHelper helper) {
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        UUID id = UUID.randomUUID();
        String key = EconomyConstants.AZURE_DAILY_FAUCET_KEY; // "azure_faucet"
        long cap = 30L; // 固定小 cap 做确定性断言 (不依赖常量当前值; 验"硬截断"语义本身)。
        long today = 50_000L; // 固定 UTC 日戳 (epochDay)。

        // 当日累计 0, 入账 18 (<= cap): 全额入账, 余额 18, 计数 18。
        helper.assertTrue(ledger.creditAzureDaily(id, key, 18L, cap, today) == 18L,
                "first azure grant 18 within cap 30 credits in full (18)");
        helper.assertTrue(ledger.balance(id, Currency.AZURE) == 18L, "azure balance is 18 after first grant");

        // 当日累计 18, 再入账 20 (18+20=38 > cap 30): 只发到刚好填满 cap 的 12 (30-18), 超出 8 被截断丢弃。
        // 这是 economy-02 的核心: 删 creditAzureDaily 的 room 截断逻辑 (直接 credit amount) 则余额=38 测试必挂。
        helper.assertTrue(ledger.creditAzureDaily(id, key, 20L, cap, today) == 12L,
                "second azure grant truncates to remaining room: cap 30 - granted 18 = 12 (not full 20)");
        helper.assertTrue(ledger.balance(id, Currency.AZURE) == 30L,
                "azure balance clamps at the daily cap 30 (the over-cap 8 is dropped, not minted)");

        // 当日已撞 cap, 再入账任意量: 0 入账, 余额不变 (撞顶后不再发)。
        helper.assertTrue(ledger.creditAzureDaily(id, key, 100L, cap, today) == 0L,
                "azure grant after the cap is hit credits nothing (hard truncation, not decay)");
        helper.assertTrue(ledger.balance(id, Currency.AZURE) == 30L, "azure balance stays at cap 30 after over-cap grant");

        // 跨 UTC 日重置: 翻日后当日计数清零, 又可领满 cap (余额累计到 30 + 30 = 60)。
        long tomorrow = today + 1L;
        helper.assertTrue(ledger.creditAzureDaily(id, key, 30L, cap, tomorrow) == 30L,
                "after UTC rollover the azure daily counter resets and a full-cap grant succeeds again");
        helper.assertTrue(ledger.balance(id, Currency.AZURE) == 60L,
                "azure balance accrues 30 (day1 cap) + 30 (day2 cap) = 60 across the rollover");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void grantAzureDailyServiceRoutesThroughCap(GameTestHelper helper) {
        // 门面层 grantAzureDaily (精英怪青辉石走此入口): 用真 cap 常量验"同一玩家当日连续领取超 cap 后实际入账被截断"。
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        UUID id = player.getUUID();
        long cap = EconomyConstants.AZURE_DAILY_FAUCET_CAP; // DRAFT 30

        // 连续领取累计超 cap: 累加实际入账必恰等于 cap (超额被截断), 而非各笔之和。
        long firstGrant = eco.grantAzureDaily(player, cap - 1L, cap); // 全额 cap-1。
        helper.assertTrue(firstGrant == cap - 1L, "first service grant of cap-1 credits in full (cap-1)");
        long secondGrant = eco.grantAzureDaily(player, 50L, cap);     // 只剩 1 的额度, 截断到 1。
        helper.assertTrue(secondGrant == 1L, "second service grant truncates to the last 1 of remaining cap room");
        long thirdGrant = eco.grantAzureDaily(player, 50L, cap);      // 已撞顶, 0。
        helper.assertTrue(thirdGrant == 0L, "third service grant after cap is hit credits 0");

        // 账本余额 = cap (绝不超过 cap, 哪怕拟领总量 = (cap-1)+50+50 远超 cap)。删 cap 逻辑则余额 = 99 测试必挂。
        helper.assertTrue(ledger.balance(id, Currency.AZURE) == cap,
                "azure balance is clamped exactly at the daily cap regardless of total requested (no per-day mint beyond cap)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void azureDailyFaucetRejectsIllegalArgs(GameTestHelper helper) {
        // 契约: amount<=0 / cap<=0 抛 ILLEGAL_AMOUNT 自然冒泡 (不静默返 0 掩盖非法入参)。
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        UUID id = UUID.randomUUID();
        String key = EconomyConstants.AZURE_DAILY_FAUCET_KEY;

        boolean amountThrew = false;
        try {
            ledger.creditAzureDaily(id, key, 0L, 30L, 50_000L);
        } catch (EconomyException e) {
            amountThrew = e.reason() == EconomyException.Reason.ILLEGAL_AMOUNT;
        }
        helper.assertTrue(amountThrew, "azure grant of 0 throws ILLEGAL_AMOUNT (no silent no-op)");

        boolean capThrew = false;
        try {
            ledger.creditAzureDaily(id, key, 5L, 0L, 50_000L);
        } catch (EconomyException e) {
            capThrew = e.reason() == EconomyException.Reason.ILLEGAL_AMOUNT;
        }
        helper.assertTrue(capThrew, "azure grant with cap 0 throws ILLEGAL_AMOUNT");
        helper.assertTrue(ledger.balance(id, Currency.AZURE) == 0L, "no azure credited after rejected illegal grants");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void azureFaucetMergedAcrossAgentAndChampionPaths(GameTestHelper helper) {
        // agent-azure 修复 (同精英怪 economy-02 同类): agent 路径青辉石产出 (贡献池精英死亡掉落 AgentRewardHandler 第 ~115
        // 行 + 周常悬赏 grantWeeklyBountyAzure) 与精英怪 ChampionRewardHandler 共用【同一】门面 grantAzureDaily(...,
        // AZURE_DAILY_FAUCET_CAP) -> 同一 azure_faucet 键。合并龙头语义: 两路当日产出累计受【同一】每人每日上限, 任一路
        // 都无法单独绕过日 cap 印钞。本测试用门面 grantAzureDaily 模拟"先精英怪掉一笔, 再 agent 路掉一笔"打同一玩家同一日,
        // 验两路合计被截断在单一 cap (而非各占一份 cap)。删 grantAzureDaily 的 cap 路由 (回退到旧的 grant(AZURE) 无日上限)
        // 则两笔全额入账 = 2*cap-1, 测试必挂。
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        UUID id = player.getUUID();
        long cap = EconomyConstants.AZURE_DAILY_FAUCET_CAP; // DRAFT 30: 两路共享此单一日上限。

        // 第一路 (精英怪 ChampionRewardHandler 掉落): 领满 cap-1, 全额入账。
        long championDrop = eco.grantAzureDaily(player, cap - 1L, cap);
        helper.assertTrue(championDrop == cap - 1L,
                "champion-path azure drop of cap-1 credits in full (cap-1) on the shared azure_faucet key");

        // 第二路 (agent 路径掉落 / 周常悬赏): 同一玩家同一日再领 cap-1, 但共享键当日只剩 1 的额度 -> 截断到 1
        // (而非另开一份 cap)。这正是合并龙头的核心: agent 路不享独立的青辉石日额度。
        long agentDrop = eco.grantAzureDaily(player, cap - 1L, cap);
        helper.assertTrue(agentDrop == 1L,
                "agent-path azure drop on the same day truncates to the last 1 of the SHARED cap (not a second full cap)");

        // 第三笔任一路: 已撞共享顶, 0 入账。
        long thirdDrop = eco.grantAzureDaily(player, 50L, cap);
        helper.assertTrue(thirdDrop == 0L, "any third azure grant after the shared cap is hit credits 0");

        // 余额 = cap (两路合计封顶, 绝不 = 2*(cap-1)): 删 cap 合并路由则余额 = 2*cap-1 = 59, 测试必挂。
        helper.assertTrue(ledger.balance(id, Currency.AZURE) == cap,
                "agent + champion azure for one player on one day is clamped at a single shared daily cap (not summed)");
        helper.succeed();
    }

    // ============================================================
    // settleOreSale 衰减结算 (经济文档 8.1 钻石 base=500 + 第十一章决策 1/3: 逐矿 0.97/1% 地板 -> 再并入主闸)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleOreSaleDecay(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());

        double base = ShopPriceTable.ORE_BASE_DIAMOND; // 8.1 ×10 锚: 500
        helper.assertTrue(base == 500.0D, "diamond ore anchor base price is 500 (economy spec 8.1)");

        int cap = EconomyConstants.DAILY_SOFTCAP_DIAMOND; // 64
        // 第十一章决策 3 两层串联: 逐矿 steering 毛值 (buyPrice) 再并入全服衰减主闸 (grantDaily, key=credit_faucet,
        // tier=60000)。本测试三笔毛值累计仅 990 (<< 60000), 全落主闸第 0 档 (系数 1.0), 故净额 == 逐矿毛值, 可直接断言
        // 逐矿 steering 曲线的整数毛值。返回值为"经主闸后净入账额", 不再是逐矿毛单价 (契约变更, 此处恰好相等)。

        // n = cap: 逐矿 over = 0, ratio = 1.0 -> 毛值 500; 主闸第 0 档 -> 净 500。
        long atCap = eco.settleOreSale(player, HighValueOre.DIAMOND, cap, base);
        helper.assertTrue(atCap == 500L,
                "diamond at softCap (n=64) settles full price 500 (per-ore ratio 1.0, faucet band0 coef 1.0)");

        // n = cap + 1: 逐矿 ×0.97 -> 毛值 485; 主闸仍第 0 档 -> 净 485 (0.97^1, 累计 985 < 60000)。
        long overByOne = eco.settleOreSale(player, HighValueOre.DIAMOND, cap + 1, base);
        helper.assertTrue(overByOne == 485L,
                "diamond at softCap+1 (n=65) settles 500*0.97 = 485 (faucet still band0)");

        // 大 over: 逐矿夹 1% 新地板 (决策 1: 0.25 -> 0.01) -> 毛值 floor(500*0.01)=5; 主闸仍第 0 档 -> 净 5。
        // 这是地板从 0.25 砍到 0.01 的关键断言: 旧口径此处为 125 (500*0.25), 新口径 5 (500*0.01)。
        long deep = eco.settleOreSale(player, HighValueOre.DIAMOND, cap + 100_000, base);
        helper.assertTrue(deep == 5L,
                "diamond deep over-cap settles at the new 1% floor: floor(500*0.01) = 5 (was 125 at 0.25 floor)");

        // 入账落账本: 三次毛值累计 990 全落主闸第 0 档 (系数 1.0, 990 << 60000 档大小), 净额 500 + 485 + 5 = 990。
        // 关键: settleOreSale 现经 grantDaily 真改余额 (非旧的直接 ledger.credit), 删主闸路由测试即挂 (余额会偏)。
        helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 990L,
                "three ore sales route through the main faucet and credit 500 + 485 + 5 = 990 (all in faucet band0)");
        helper.succeed();
    }

    // ============================================================
    // settleOreSale 主闸路由真改余额 (第十一章决策 3: 不再直接 ledger.credit, 必经 grantDaily 走 credit_faucet 主闸)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settleOreSaleRoutesThroughMainFaucet(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());

        UUID id = player.getUUID();
        String faucet = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY; // "credit_faucet"
        long tier = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER;    // 60000
        helper.assertTrue(faucet.equals("credit_faucet"), "shared faucet key is credit_faucet");
        helper.assertTrue(tier == 60_000L, "shared faucet tier is 60000 gross income per band");

        long today = new AbuseGuard().currentPlayerDayStamp();
        // 主闸先被卖菜 (同 key 同 tier) 推到第 1 档边界: 直接灌入 tier 的 raw 累计, 使后续卖矿落第 1 档 ×0.6。
        long beforeFarm = ledger.recordFaucetGrant(id, faucet, tier, today);
        helper.assertTrue(beforeFarm == 0L, "first faucet grant sees zero prior cumulative raw");

        // 现累计 raw = 60000 (第 1 档边界)。卖一块钻石 (n=64 全价毛值 500): 落第 1 档 -> 净 floor(500*0.6)=300。
        // 这证明卖矿与卖菜共享同一 (player, credit_faucet) 累计计数器 (决策 3 同闸), 而非各自私有满额。
        long sold = eco.settleOreSale(player, HighValueOre.DIAMOND, EconomyConstants.DAILY_SOFTCAP_DIAMOND,
                ShopPriceTable.ORE_BASE_DIAMOND);
        helper.assertTrue(sold == 300L,
                "ore-sale after the band-0 faucet is exhausted lands in band1 x0.6: floor(500*0.6) = 300");
        // 余额仅含卖矿净额 (卖菜那笔只推进 raw 计数器, 未入账): 300。
        helper.assertTrue(ledger.balance(id, Currency.CREDIT) == 300L,
                "wallet credited the post-faucet net 300 (settleOreSale mutates balance via grantDaily, not raw 500)");
        helper.succeed();
    }

    // ============================================================
    // EconomyService 门面: grant 溢出冒泡 + tryCharge 余额边界
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void serviceGrantOverflowBubbles(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());

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

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void serviceBundleGrantIsAtomic(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());

        helper.assertTrue(eco.creditBalance(player) == 0L && eco.heartstoneBalance(player) == 0L,
                "fresh ledger starts at zero on both currencies before the admin bundle grant");
        eco.grantBundle(player, 50_000L, 10L);
        helper.assertTrue(eco.creditBalance(player) == 50_000L,
                "bundle grant credits the requested 50000 CREDIT");
        helper.assertTrue(eco.heartstoneBalance(player) == 10L,
                "bundle grant credits the requested 10 AZURE");

        ledger.credit(player.getUUID(), Currency.CREDIT, Long.MAX_VALUE - 50_000L);
        boolean overflow = false;
        try {
            eco.grantBundle(player, 1L, 1L);
        } catch (EconomyException exception) {
            overflow = exception.reason() == EconomyException.Reason.BALANCE_OVERFLOW;
        }
        helper.assertTrue(overflow, "bundle grant rejects either-currency overflow");
        helper.assertTrue(eco.creditBalance(player) == Long.MAX_VALUE,
                "failed bundle grant leaves CREDIT unchanged at MAX_VALUE");
        helper.assertTrue(eco.heartstoneBalance(player) == 10L,
                "failed bundle grant leaves AZURE unchanged instead of partially crediting it");
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
    // faucet 入账的事务边界 (三步同生共死 + 连锁批量与逐笔等价)
    // ============================================================

    /**
     * 入账落账失败时, 当日原始累计必须一并回滚。
     *
     * 这三步 (推进原始累计 -> 推进小数余量 -> 落账) 若各自提交, 落账失败就会留下"衰减档位已经推进、钱却
     * 没发"的玩家: 他当天余下的 faucet 全部按更深的档位打折, 而且没有任何记录能说明少了多少。
     * 这里用余额已达 Long.MAX_VALUE 触发真实的入账溢出, 不靠任何桩。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void faucetGrantRollsBackCounterWhenCreditFails(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        UUID id = player.getUUID();
        String faucet = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY;
        long tier = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER;
        long today = new AbuseGuard().currentPlayerDayStamp();

        ledger.credit(id, Currency.CREDIT, Long.MAX_VALUE);

        boolean overflow = false;
        try {
            eco.grantDaily(player, 100L, faucet, tier);
        } catch (EconomyException e) {
            overflow = e.reason() == EconomyException.Reason.BALANCE_OVERFLOW;
        }
        helper.assertTrue(overflow, "余额已满时 faucet 入账必须抛 BALANCE_OVERFLOW");
        helper.assertTrue(ledger.balance(id, Currency.CREDIT) == Long.MAX_VALUE,
                "失败的入账不得改动余额");
        // 计数器若已推进, 这里返回的就是 100 而不是 0 —— 那正是"档位白涨"的形态。
        helper.assertTrue(ledger.recordFaucetGrant(id, faucet, 1L, today) == 0L,
                "落账失败必须连同当日原始累计一起回滚, 当日累计仍应是 0");
        helper.succeed();
    }

    /**
     * 连锁批量结算与逐块结算必须得出完全相同的余额与计数。
     * 合并事务是为了少写 WAL 与保证这一批的原子性, 绝不允许顺带改变业务结果。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void batchedChainSettlementMatchesPerDropSettlement(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID id = player.getUUID();
        int drops = 5;

        Map<UUID, PlayerAbuseState> batchStates = new HashMap<>();
        batchStates.put(id, new PlayerAbuseState());
        EconomyLedger batched = SqliteEconomyLedger.openInMemory();
        EconomyService batchedEco = new EconomyService(batched, new AbuseGuard(), batchStates::get);
        int counted = batchedEco.recordMinedOreDrops(player, Blocks.DIAMOND_ORE, drops);
        helper.assertTrue(counted == drops, "5 个钻石产出物应计入当日 5 个, 实为 " + counted);

        EconomyLedger perDrop = SqliteEconomyLedger.openInMemory();
        EconomyService perDropEco = new EconomyService(perDrop, new AbuseGuard(), newStateResolver());
        for (int n = 1; n <= drops; n++) {
            perDropEco.settleOreSale(player, HighValueOre.DIAMOND, n, ShopPriceTable.ORE_BASE_DIAMOND);
        }

        long batchedBalance = batched.balance(id, Currency.CREDIT);
        helper.assertTrue(batchedBalance > 0L, "批量结算必须真的入账 (否则本用例退化成两边都是 0 的空断言)");
        helper.assertTrue(batchedBalance == perDrop.balance(id, Currency.CREDIT),
                "批量与逐笔结算的余额必须完全相同: 批量 " + batchedBalance
                        + ", 逐笔 " + perDrop.balance(id, Currency.CREDIT));

        String faucet = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY;
        long today = new AbuseGuard().currentPlayerDayStamp();
        long batchedRaw = batched.recordFaucetGrant(id, faucet, 1L, today);
        long perDropRaw = perDrop.recordFaucetGrant(id, faucet, 1L, today);
        helper.assertTrue(batchedRaw == perDropRaw && batchedRaw > 0L,
                "批量与逐笔推进的当日原始累计必须完全相同: 批量 " + batchedRaw + ", 逐笔 " + perDropRaw);
        helper.succeed();
    }

    /**
     * 终态操作回收只能动终态。
     *
     * CHARGED 是在途的付款事实: 玩家的钱已经扣了、资产还没发。删掉它, 那笔钱就凭空消失且无从追溯 ——
     * 后续恢复既查不到该补发资产, 也查不到该退款。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void pruneRemovesOnlyTerminalOperations(GameTestHelper helper) {
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        UUID player = UUID.randomUUID();
        ledger.credit(player, Currency.CREDIT, 10_000L);
        ledger.credit(player, Currency.AZURE, 100L);

        UUID completedId = UUID.randomUUID();
        UUID refundedId = UUID.randomUUID();
        UUID inFlightId = UUID.randomUUID();
        ledger.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player, completedId, 100L, 1L);
        ledger.completeBundle(EconomyOperationDomain.CASE_OPENING, player, completedId);
        ledger.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player, refundedId, 100L, 1L);
        ledger.refundBundle(EconomyOperationDomain.CASE_OPENING, player, refundedId);
        ledger.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player, inFlightId, 100L, 1L);

        // 截止时刻取 long 上界: 所有终态记录都落在回收范围内, 只有 CHARGED 靠状态而非时间幸存。
        int pruned = ledger.pruneTerminalOperations(Long.MAX_VALUE);
        helper.assertTrue(pruned == 2, "只应回收 COMPLETED 与 REFUNDED 两条, 实为 " + pruned);
        helper.assertTrue(ledger.operationStatus(EconomyOperationDomain.CASE_OPENING, player, completedId)
                        == EconomyOperationStatus.NONE
                        && ledger.operationStatus(EconomyOperationDomain.CASE_OPENING, player, refundedId)
                        == EconomyOperationStatus.NONE,
                "终态记录回收后应查不到");
        helper.assertTrue(ledger.operationStatus(EconomyOperationDomain.CASE_OPENING, player, inFlightId)
                        == EconomyOperationStatus.CHARGED,
                "在途的 CHARGED 必须幸存, 否则这笔已扣的钱再也追不回来");
        helper.succeed();
    }

    // ============================================================
    // 旧存档 SavedData 一次性迁入 SQLite
    // ============================================================

    /**
     * 迁移必须逐项搬全: 两种余额、双币操作状态、扣费侧当日累计、faucet 侧当日累计与小数余量。
     * 少搬任何一项都是真金白银或额度凭空变动, 因此这里断言的是迁移后账本的【行为】, 而不是行数。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void legacySavedDataMigratesExactly(GameTestHelper helper) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID operationId = UUID.randomUUID();
        long today = 20_000L;

        EconomyWalletData legacy = EconomyWalletData.load(legacyTag(a, b, operationId, today));
        Connection conn = MiningDb.openInMemory();
        try {
            MiningSchema.apply(conn);
            EconomyLedgerBootstrap.migrateIfNeeded(conn, legacy, 1_723_000_000_000L);
            EconomyLedger ledger = new SqliteEconomyLedger(conn);

            helper.assertTrue(ledger.balance(a, Currency.CREDIT) == 1_234L
                            && ledger.balance(a, Currency.AZURE) == 56L,
                    "玩家 A 的两种余额必须逐分不差地搬过来");
            helper.assertTrue(ledger.balance(b, Currency.CREDIT) == 300L, "玩家 B 的余额必须搬过来");
            helper.assertTrue(ledger.operationStatus(EconomyOperationDomain.CASE_OPENING, a, operationId)
                            == EconomyOperationStatus.CHARGED,
                    "在途的 CHARGED 操作必须搬过来, 否则崩溃恢复会漏掉这笔已扣款");

            // 扣费侧当日累计已是 33: 再扣 67 恰达上限 100, 又扣 1 必须被拒。累计没搬过来的话 67+1 都会通过。
            helper.assertTrue(ledger.tryChargeDaily(b, Currency.CREDIT, 67L, "pack", 100L, today),
                    "迁移后的当日扣费累计从 33 续算: 再扣 67 恰达上限 100");
            helper.assertTrue(!ledger.tryChargeDaily(b, Currency.CREDIT, 1L, "pack", 100L, today),
                    "当日已达上限, 再扣 1 必须被拒");

            // faucet 侧: 原始累计 100 与小数余量 0.5 都必须在。
            helper.assertTrue(ledger.recordFaucetGrant(b, "credit_faucet", 10L, today) == 100L,
                    "迁移后的 faucet 原始累计必须是 100 (决定衰减档位)");
            helper.assertTrue(ledger.creditFaucetWithCarry(b, "credit_faucet", 0.5D, today) == 1L,
                    "迁移过来的 0.5 余量与本次 0.5 凑满 1 才落账, 余量没搬过来则本次只会落 0");

            helper.assertTrue("1723000000000".equals(
                            StoreMeta.get(conn, EconomyLedgerBootstrap.META_WALLETS_IMPORTED)),
                    "迁移标记必须写入且带时间戳");
        } finally {
            MiningDb.close(conn);
        }
        helper.succeed();
    }

    /** 第二次启动必须靠标记跳过; 否则每次开服都把旧存档余额再灌一遍。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void secondMigrationIsSkippedByMarker(GameTestHelper helper) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        EconomyWalletData legacy = EconomyWalletData.load(legacyTag(a, b, UUID.randomUUID(), 20_000L));
        Connection conn = MiningDb.openInMemory();
        try {
            MiningSchema.apply(conn);
            EconomyLedgerBootstrap.migrateIfNeeded(conn, legacy, 1_723_000_000_000L);
            EconomyLedger ledger = new SqliteEconomyLedger(conn);
            ledger.credit(a, Currency.CREDIT, 1_000L);

            EconomyLedgerBootstrap.migrateIfNeeded(conn, legacy, 1_723_000_099_999L);
            helper.assertTrue(ledger.balance(a, Currency.CREDIT) == 2_234L,
                    "第二次迁移必须整体跳过: 余额应是迁移的 1234 加上后来入账的 1000, 实为 "
                            + ledger.balance(a, Currency.CREDIT));
            helper.assertTrue("1723000000000".equals(
                            StoreMeta.get(conn, EconomyLedgerBootstrap.META_WALLETS_IMPORTED)),
                    "标记时间戳必须停在首次迁移");
        } finally {
            MiningDb.close(conn);
        }
        helper.succeed();
    }

    /** SQLite 侧已经在记账而旧存档还有数据且无标记: 两份账本都可能是权威, 只能拒绝启动。 */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void migrationRefusesWhenLedgerAlreadyHasRows(GameTestHelper helper) {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        EconomyWalletData legacy = EconomyWalletData.load(legacyTag(a, b, UUID.randomUUID(), 20_000L));
        Connection conn = MiningDb.openInMemory();
        try {
            MiningSchema.apply(conn);
            EconomyLedger ledger = new SqliteEconomyLedger(conn);
            ledger.credit(a, Currency.CREDIT, 777L);

            boolean refused = false;
            try {
                EconomyLedgerBootstrap.migrateIfNeeded(conn, legacy, 1_723_000_000_000L);
            } catch (MiningStoreException expected) {
                refused = true;
            }
            helper.assertTrue(refused, "统一库已有钱包行且旧存档未迁移时必须拒绝启动");
            helper.assertTrue(ledger.balance(a, Currency.CREDIT) == 777L,
                    "被拒绝时不得写入任何旧存档余额, 实为 " + ledger.balance(a, Currency.CREDIT));
            helper.assertTrue(StoreMeta.get(conn, EconomyLedgerBootstrap.META_WALLETS_IMPORTED) == null,
                    "被拒绝时不得留下迁移标记");
        } finally {
            MiningDb.close(conn);
        }
        helper.succeed();
    }

    /**
     * 造一份合库之前格式的 SavedData 标签。
     * 键名与结构在此独立重写而不复用生产常量: 旧存档格式是已经发生的历史, 必须被测试原样钉住 ——
     * 若将来有人改了读取端的键名而没做迁移, 这里才会失败。
     */
    private static CompoundTag legacyTag(UUID a, UUID b, UUID operationId, long today) {
        CompoundTag tag = new CompoundTag();

        ListTag wallets = new ListTag();
        wallets.add(legacyWallet(a, 1_234L, 56L));
        wallets.add(legacyWallet(b, 300L, 0L));
        tag.put("wallets", wallets);

        ListTag operations = new ListTag();
        CompoundTag operation = new CompoundTag();
        operation.putUUID("operationId", operationId);
        operation.putString("domain", EconomyOperationDomain.CASE_OPENING.id());
        operation.putUUID("playerId", a);
        operation.putLong("creditAmount", 100L);
        operation.putLong("azureAmount", 10L);
        operation.putString("status", EconomyOperationStatus.CHARGED.name());
        operations.add(operation);
        tag.put("bundleOperations", operations);

        ListTag charges = new ListTag();
        CompoundTag charge = new CompoundTag();
        charge.putString("key", b + "|pack");
        charge.putLong("amount", 33L);
        charge.putLong("dayStamp", today);
        charges.add(charge);
        tag.put("dailyCharges", charges);

        ListTag faucets = new ListTag();
        CompoundTag faucet = new CompoundTag();
        faucet.putString("key", b + "|credit_faucet");
        faucet.putLong("amount", 100L);
        faucet.putLong("dayStamp", today);
        faucet.putDouble("creditCarry", 0.5D);
        faucets.add(faucet);
        tag.put("dailyFaucets", faucets);
        return tag;
    }

    private static CompoundTag legacyWallet(UUID playerId, long credit, long azure) {
        CompoundTag entry = new CompoundTag();
        entry.putUUID("uuid", playerId);
        CompoundTag wallet = new CompoundTag();
        wallet.putLong("credit", credit);
        wallet.putLong("azure", azure);
        entry.put("wallet", wallet);
        return entry;
    }

    // ============================================================
    // 账本重开一致性 (多钱包 + 每日计数; 用真实文件库真的关连接重开)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ledgerStateSurvivesReopen(GameTestHelper helper) {
        Path dir = TempStoreDb.createTempDir();
        try {
            Path dbPath = dir.resolve("ledger.db");
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();
            long today = 20_000L;

            Connection first = TempStoreDb.openUnified(dbPath);
            try {
                EconomyLedger ledger = new SqliteEconomyLedger(first);
                ledger.credit(a, Currency.CREDIT, Long.MAX_VALUE);
                ledger.credit(a, Currency.AZURE, 7L);
                ledger.credit(b, Currency.CREDIT, 333L);
                ledger.tryChargeDaily(b, Currency.CREDIT, 33L, "pack", 100L, today);
            } finally {
                MiningDb.close(first);
            }

            Connection second = TempStoreDb.openUnified(dbPath);
            try {
                EconomyLedger reloaded = new SqliteEconomyLedger(second);
                helper.assertTrue(reloaded.balance(a, Currency.CREDIT) == Long.MAX_VALUE,
                        "wallet A CREDIT MAX_VALUE 重开后精确还原 (long 边界不丢精度)");
                helper.assertTrue(reloaded.balance(a, Currency.AZURE) == 7L, "wallet A AZURE 重开后仍是 7");
                helper.assertTrue(reloaded.balance(b, Currency.CREDIT) == 300L,
                        "wallet B CREDIT 重开后是 333 - 33 = 300 (每日扣费确实落了盘)");

                // 每日计数也持久化: 同日再扣到上限边界, 累计仍以已持久化的 33 起算 (33 + 67 = 100 恰达上限成功)。
                helper.assertTrue(reloaded.tryChargeDaily(b, Currency.CREDIT, 67L, "pack", 100L, today),
                        "persisted daily counter continues from 33: a further 67 reaches cap 100");
                helper.assertTrue(!reloaded.tryChargeDaily(b, Currency.CREDIT, 1L, "pack", 100L, today),
                        "the persisted daily counter is now at cap; a further 1 is rejected");
            } finally {
                MiningDb.close(second);
            }
        } finally {
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void bundleSagaLifecycleSurvivesRestartAndSupportsOfflineRecovery(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        UUID playerId = player.getUUID();
        UUID completedId = UUID.randomUUID();
        UUID refundableId = UUID.randomUUID();

        Path dir = TempStoreDb.createTempDir();
        try {
            Path dbPath = dir.resolve("saga.db");

            Connection before = TempStoreDb.openUnified(dbPath);
            try {
                EconomyLedger ledger = new SqliteEconomyLedger(before);
                ledger.credit(playerId, Currency.CREDIT, 1_000L);
                ledger.credit(playerId, Currency.AZURE, 100L);
                IEconomyService economy = new EconomyService(ledger, new AbuseGuard(), newStateResolver());

                helper.assertTrue(economy.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player, completedId, 100L, 10L)
                                == EconomyOperationStatus.CHARGED,
                        "service facade creates first CHARGED operation");
                helper.assertTrue(economy.completeBundle(EconomyOperationDomain.CASE_OPENING, playerId, completedId) == EconomyOperationStatus.COMPLETED,
                        "UUID-level recovery API commits CHARGED to COMPLETED");
                helper.assertTrue(economy.completeBundle(EconomyOperationDomain.CASE_OPENING, playerId, completedId) == EconomyOperationStatus.COMPLETED,
                        "complete replay is idempotent");

                helper.assertTrue(economy.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player, refundableId, 200L, 20L)
                                == EconomyOperationStatus.CHARGED,
                        "second operation remains CHARGED for crash-recovery simulation");
                helper.assertTrue(ledger.balance(playerId, Currency.CREDIT) == 700L
                                && ledger.balance(playerId, Currency.AZURE) == 70L,
                        "two operations have deducted their costs before the restart");
            } finally {
                MiningDb.close(before);
            }

            // 关连接重开 = 一次真实的服务端重启: 之后读到的一切都必须来自磁盘。
            Connection after = TempStoreDb.openUnified(dbPath);
            try {
                EconomyLedger reloaded = new SqliteEconomyLedger(after);
                IEconomyService recovered = new EconomyService(reloaded, new AbuseGuard(), newStateResolver());
                helper.assertTrue(recovered.operationStatus(EconomyOperationDomain.CASE_OPENING, playerId, completedId) == EconomyOperationStatus.COMPLETED,
                        "COMPLETED state survives the restart");
                helper.assertTrue(recovered.operationStatus(EconomyOperationDomain.CASE_OPENING, playerId, refundableId) == EconomyOperationStatus.CHARGED,
                        "in-flight CHARGED state survives the restart for startup recovery");

                helper.assertTrue(recovered.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player, refundableId, 200L, 20L)
                                == EconomyOperationStatus.CHARGED,
                        "replayed in-flight operation returns CHARGED after restart");
                helper.assertTrue(reloaded.balance(playerId, Currency.CREDIT) == 700L
                                && reloaded.balance(playerId, Currency.AZURE) == 70L,
                        "post-restart replay does not charge twice");

                helper.assertTrue(recovered.refundBundle(EconomyOperationDomain.CASE_OPENING, playerId, refundableId) == EconomyOperationStatus.REFUNDED,
                        "offline UUID recovery atomically refunds an in-flight charge");
                helper.assertTrue(reloaded.balance(playerId, Currency.CREDIT) == 900L
                                && reloaded.balance(playerId, Currency.AZURE) == 90L,
                        "refund restores exactly the second operation while retaining completed cost");
                helper.assertTrue(recovered.refundBundle(EconomyOperationDomain.CASE_OPENING, playerId, refundableId) == EconomyOperationStatus.REFUNDED,
                        "refund replay returns REFUNDED without a second credit");
                helper.assertTrue(reloaded.balance(playerId, Currency.CREDIT) == 900L
                                && reloaded.balance(playerId, Currency.AZURE) == 90L,
                        "refund replay cannot mint either currency");
                helper.assertTrue(recovered.completeBundle(EconomyOperationDomain.CASE_OPENING, playerId, refundableId) == EconomyOperationStatus.REFUNDED,
                        "a REFUNDED terminal operation cannot move back to COMPLETED");
                helper.assertTrue(recovered.refundBundle(EconomyOperationDomain.CASE_OPENING, playerId, completedId) == EconomyOperationStatus.COMPLETED,
                        "a COMPLETED terminal operation cannot be refunded");
            } finally {
                MiningDb.close(after);
            }

            Connection terminal = TempStoreDb.openUnified(dbPath);
            try {
                EconomyLedger terminalReload = new SqliteEconomyLedger(terminal);
                helper.assertTrue(terminalReload.operationStatus(EconomyOperationDomain.CASE_OPENING, playerId, completedId) == EconomyOperationStatus.COMPLETED,
                        "COMPLETED terminal state remains persistent");
                helper.assertTrue(terminalReload.operationStatus(EconomyOperationDomain.CASE_OPENING, playerId, refundableId) == EconomyOperationStatus.REFUNDED,
                        "REFUNDED terminal state remains persistent");
                helper.assertTrue(terminalReload.balance(playerId, Currency.CREDIT) == 900L,
                        "退款后的余额同样落了盘, 重开仍是 900");
            } finally {
                MiningDb.close(terminal);
            }
        } finally {
            TempStoreDb.deleteQuietly(dir);
        }
        helper.succeed();
    }

    // ============================================================
    // recordMinedOreDrops: 方案 B 按产出物个数计入当日矿物计数 (Miner_Job_DesignSpec 第十章反通胀第一道硬约束)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recordMinedOreDropsCountsByDrops(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), states::get);
        // 解析器需对未知 UUID 建态 (与 EconomySystem.playerState 同纪律), 否则 isAfkFrozen/记数取不到态。
        states.put(player.getUUID(), new PlayerAbuseState());

        Block diamond = Blocks.DIAMOND_ORE;
        // 连锁回放 3 个钻石产出: 计入后当日累计 = 3 (方案 B 按产出物个数, 非块数 +1)。
        int afterThree = eco.recordMinedOreDrops(player, diamond, 3);
        helper.assertTrue(afterThree == 3, "recording 3 produced diamonds yields daily count 3 (scheme B by item count)");
        // 再回放 2 个: 累计 = 5 (与单块路径同口径, 同一玩家态累加)。
        int afterTwoMore = eco.recordMinedOreDrops(player, diamond, 2);
        helper.assertTrue(afterTwoMore == 5, "a further 2 produced diamonds accumulate to 5 (same daily counter)");
        helper.assertTrue(states.get(player.getUUID()).dailyOreCount(HighValueOre.DIAMOND) == 5,
                "the underlying PlayerAbuseState reflects the replayed produced-item count (5), not the block count (2)");

        // 非高价矿不计 (返回 -1)。
        helper.assertTrue(eco.recordMinedOreDrops(player, Blocks.STONE, 10) == -1,
                "non high-value blocks are not counted (returns -1)");
        // producedCount <= 0 不计 (返回 -1, 不污染计数)。
        helper.assertTrue(eco.recordMinedOreDrops(player, diamond, 0) == -1,
                "producedCount <= 0 is not counted (returns -1)");
        helper.assertTrue(states.get(player.getUUID()).dailyOreCount(HighValueOre.DIAMOND) == 5,
                "rejected records leave the daily count unchanged at 5");

        // AFK 冻结态不计 (反挂机 18.4): 冻结后回放不增长计数。
        states.get(player.getUUID()).setAfkFrozen(true);
        helper.assertTrue(eco.recordMinedOreDrops(player, diamond, 4) == -1,
                "AFK-frozen players do not accrue ore count (anti-idle 18.4)");
        helper.assertTrue(states.get(player.getUUID()).dailyOreCount(HighValueOre.DIAMOND) == 5,
                "AFK-frozen replay leaves the daily count at 5");
        helper.succeed();
    }

    // ============================================================
    // isAfkFrozen: 门面读冻结态 (Miner_Job_DesignSpec 第九章反挂机前置拦截)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void isAfkFrozenReflectsState(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        states.put(player.getUUID(), new PlayerAbuseState());
        EconomyService eco = new EconomyService(SqliteEconomyLedger.openInMemory(), new AbuseGuard(), states::get);

        helper.assertTrue(!eco.isAfkFrozen(player), "a fresh player is not AFK-frozen");
        states.get(player.getUUID()).setAfkFrozen(true);
        helper.assertTrue(eco.isAfkFrozen(player), "facade reports AFK-frozen once the abuse state is frozen");
        states.get(player.getUUID()).setAfkFrozen(false);
        helper.assertTrue(!eco.isAfkFrozen(player), "facade reports unfrozen once the abuse state clears");
        helper.succeed();
    }

    // ============================================================
    // grantDaily: 全服每人每日信用点衰减主闸 (第十一章决策 2: 0.6 逐档几何衰减 / 1% 地板 / 60000 档大小; 几何主项前 10 档 ≈ 14.9 万)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void grantDailyFaucetSoftCapDecay(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());

        // 用本测试自带的小档 cap=1000 验证逐档几何衰减 (主闸真实档 60000 在 faucetCreditAfterDecay 专测覆盖)。
        long cap = 1_000L;
        String key = "faucet_shared";

        // 第 0 档 (累计 raw 落在 [0, cap) 内, 系数 0.6^0 = 1.0): 全额入账, 实发 = raw。
        long first = eco.grantDaily(player, cap, key, cap);
        helper.assertTrue(first == cap, "first grant in band0 (coef 1.0) credits in full (1000)");

        // 第 1 档 (累计 raw 进入 [cap, 2*cap), 系数 0.6^1 = 0.6): 再发 cap 整段落第 1 档 -> floor(1000*0.6)=600。
        long second = eco.grantDaily(player, cap, key, cap);
        helper.assertTrue(second == 600L,
                "second cap-batch sits wholly in band1 (coef 0.6): floor(1000*0.6) = 600 (geometric, not 0.97)");

        // 远超额一大批 (raw=100*cap, 当前累计 raw = 2*cap, 横跨第 2..101 档): 逐档积分, 第 k 档系数 max(0.01, 0.6^k),
        // 0.6^k 在 k>=10 时 (0.6^10≈0.006) 跌破 1% 地板后夹地板。逐档积分精确实发 = 1804.883456, 含上一笔 carry 0 ->
        // floor(1804.88..) = 1804 (旧 0.97/0.25 口径此处为 floor(100000*0.25)=25000, 新几何衰减夹 1% 地板后仅 1804)。
        long deep = eco.grantDaily(player, 100L * cap, key, cap);
        helper.assertTrue(deep == 1_804L,
                "a deep over-cap batch integrates geometrically and clamps to the 1% floor: 1804 (was 25000 at 0.25)");

        // 账本入账 = 三次实发之和 (faucet 入账落账本, 衰减后实发额)。
        long expectedBalance = first + second + deep; // 1000 + 600 + 1804 = 3404
        helper.assertTrue(expectedBalance == 3_404L, "post-decay grants sum to 1000 + 600 + 1804 = 3404");
        helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == expectedBalance,
                "wallet credit balance equals the sum of post-decay grants (3404)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void grantDailySharedAcrossFaucetKeys(GameTestHelper helper) {
        // 第十一章决策 3/4: 矿工卖矿与农夫卖菜传同一 faucetKey 即共享同一每人每日信用点衰减主闸 (而非各自私有上限)。
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());

        long cap = 500L;
        String shared = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_KEY; // "credit_faucet" 卖矿与卖菜共用此键。
        helper.assertTrue(shared.equals("credit_faucet"), "the shared faucet key constant resolves to credit_faucet");

        // 卖矿先发满第 0 档 (累计 raw 落 [0, cap), 系数 1.0, 全额)。
        long mining = eco.grantDaily(player, cap, shared, cap);
        helper.assertTrue(mining == cap, "mining faucet fills band0 in full (coef 1.0): 500");
        // 卖菜随后用同一 key 再发 cap: 共享累计 raw 已达 cap, 整段落第 1 档 ×0.6, 而非另起私有上限全额发。
        long farming = eco.grantDaily(player, cap, shared, cap);
        helper.assertTrue(farming == 300L,
                "farming faucet sharing the same key is already in band1 (coef 0.6): floor(500*0.6) = 300");
        helper.succeed();
    }

    // ============================================================
    // faucetCreditAfterDecay 主闸曲线 (第十一章决策 2): 逐档系数 max(1%, 0.6^k) + 几何主项前 10 档 ≈ 14.9 万 + 深档 1% 线性尾巴 + 拆分不变性
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void faucetDecayCurveBandCoefficients(GameTestHelper helper) {
        AbuseGuard guard = new AbuseGuard();
        long tier = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER; // 60000

        // 累计 raw = 0, 满档一笔: 落第 0 档 (系数 0.6^0 = 1.0) -> 全额 60000。
        helper.assertTrue(guard.faucetCreditAfterDecay(0L, tier, tier) == 60_000L,
                "band0 (cumulative 0) coefficient is 1.0: a full-tier grant credits 60000");
        // 累计 raw = 60000 (第 1 档边界), 满档一笔: 系数 0.6^1 = 0.6 -> floor(60000*0.6)=36000。
        helper.assertTrue(guard.faucetCreditAfterDecay(tier, tier, tier) == 36_000L,
                "band1 (cumulative 60000) coefficient is 0.6: floor(60000*0.6) = 36000");
        // 累计 raw = 120000 (第 2 档边界), 满档一笔: 系数 0.6^2 = 0.36 -> floor(60000*0.36)=21600。
        helper.assertTrue(guard.faucetCreditAfterDecay(2L * tier, tier, tier) == 21_600L,
                "band2 (cumulative 120000) coefficient is 0.36: floor(60000*0.36) = 21600");
        // 极深档 (累计 raw = 100*60000, 0.6^100 << 1% 地板): 整段夹 1% 地板 -> floor(60000*0.01)=600。
        helper.assertTrue(guard.faucetCreditAfterDecay(100L * tier, tier, tier) == 600L,
                "a deep band clamps to the 1% floor: floor(60000*0.01) = 600 (geometric coef below the floor)");

        // 精确版 (未 floor) 在第 1 档恰为 36000.0 (无小数), 第 2 档 21600.0: 验证 exact 与 floor 一致且无取整误差。
        double exactBand1 = guard.faucetCreditAfterDecayExact(tier, tier, tier);
        helper.assertTrue(exactBand1 == 36_000.0D, "exact band1 payout is exactly 36000.0 (no rounding loss)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void faucetDecayAsymptoteApproaches150k(GameTestHelper helper) {
        AbuseGuard guard = new AbuseGuard();
        long tier = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER; // 60000

        // 几何主项前 10 档和 = sum tier*0.6^k (k=0..9) = 149093 (≈14.9 万), 这是正常游玩落点 (正常 ~10 万 / 硬肝 ~14.9 万,
        // 基本撞顶)。但 0.6^10≈0.006<0.01, 自第 10 档 (累计毛收入 >=60 万) 起系数被 1% 地板钳住恒定, 此后每多 60000 毛恒发 +600,
        // 线性、不收敛、无数学硬顶 (faucet(0,1e9)≈1014 万); 该深档只有 xray/自动化挖得到, 实操封顶靠反矿透/反挂机巡查。
        // 取一笔从 0 起的极大 raw (1000*tier): 净额 = 几何主项 + 深档 1% 地板的线性薄收益线, 故严格大于纯几何主项 149093。
        long net = guard.faucetCreditAfterDecay(0L, 1_000L * tier, tier);
        // 任何"单档"实发都不超过 tier (band0 满额即 60000), 实发总额由几何主项 + 深档地板线性薄收益线构成, 受控可断言。
        // 关键不变量: 几何主项 (前 10 档) = 149093, 第 0 档单笔满额 == 60000 (== tier), 这是"正常游玩撞顶"的数值锚 (深档线性
        // 尾巴非数学硬顶, 实操由巡查兜底)。
        helper.assertTrue(guard.faucetCreditAfterDecay(0L, tier, tier) == tier,
                "a single full band0 grant credits exactly tier (60000): the per-band ceiling");
        // 几何主项 (前 10 档, 0.6^10≈0.006<1% 后转地板) = sum_{k=0..9} 60000*0.6^k = 149093 (向下取整)。
        long geometricHead = guard.faucetCreditAfterDecay(0L, 10L * tier, tier);
        helper.assertTrue(geometricHead == 149_093L,
                "the first 10 geometric bands sum to floor(149093.00736) = 149093, just under the 150000 asymptote");
        // 净额 (含深档地板薄收益) 必严格大于几何主项: 几何主项前 10 档封顶 ≈ 14.9 万, 深档每多 60000 毛恒 +600 线性叠加
        // (不收敛、无数学硬顶, 靠巡查兜底)。本断言只验"净额 > 几何主项 149093"这一真实行为, 不假设存在数学渐近上界。
        helper.assertTrue(net > geometricHead,
                "deeper throughput adds only the thin 1% floor income beyond the geometric head (monotone, capped trend)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void faucetDecaySplitInvariance(GameTestHelper helper) {
        AbuseGuard guard = new AbuseGuard();
        long tier = EconomyConstants.GLOBAL_DAILY_CREDIT_FAUCET_TIER; // 60000

        // 拆分不变性 (区间可加性 ∫[0,2t] = ∫[0,t] + ∫[t,2t]): 一笔 raw=2*tier 从 0 起 == 拆成两笔各 tier 逐笔推进累计。
        // 一次: exact(0, 2*tier) = 60000*1.0 + 60000*0.6 = 96000.0 (跨第 0/1 两档)。
        double oneShot = guard.faucetCreditAfterDecayExact(0L, 2L * tier, tier);
        helper.assertTrue(oneShot == 96_000.0D, "one grant of 2*tier integrates band0 + band1 = 60000 + 36000 = 96000");

        // 两笔: 第一笔 exact(0, tier)=60000.0, 第二笔把累计推进到 tier 后 exact(tier, tier)=36000.0, 和 == 96000.0。
        double splitA = guard.faucetCreditAfterDecayExact(0L, tier, tier);
        double splitB = guard.faucetCreditAfterDecayExact(tier, tier, tier);
        helper.assertTrue(splitA == 60_000.0D, "split part A (band0) is exactly 60000.0");
        helper.assertTrue(splitB == 36_000.0D, "split part B (band1) is exactly 36000.0");
        helper.assertTrue(splitA + splitB == oneShot,
                "split invariance: two tier-grants sum exactly to the single 2*tier grant (96000.0) — no split-mining bonus");

        // 非法入参自然冒泡 (契约: before<0 / raw<=0 / cap<=0 抛 IllegalArgumentException, 不静默返 0)。
        boolean threwBefore = false;
        try {
            guard.faucetCreditAfterDecayExact(-1L, tier, tier);
        } catch (IllegalArgumentException e) {
            threwBefore = true;
        }
        helper.assertTrue(threwBefore, "negative creditsBeforeThisGrant throws IllegalArgumentException (no silent clamp)");

        boolean threwRaw = false;
        try {
            guard.faucetCreditAfterDecayExact(0L, 0L, tier);
        } catch (IllegalArgumentException e) {
            threwRaw = true;
        }
        helper.assertTrue(threwRaw, "rawCreditAmount <= 0 throws IllegalArgumentException");
        helper.succeed();
    }

    // ============================================================
    // 地板从 0.25 -> 0.01 (第十一章决策 1): 逐矿 buyPrice 深档单价 ≈ base × 1%
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void priceFloorRatioIsOnePercent(GameTestHelper helper) {
        // 常量本身已从 0.25 改为 0.01 (单一来源, 同喂逐矿 buyPrice 与主闸 faucet 两层地板)。
        helper.assertTrue(EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO == 0.01D,
                "the unified price floor ratio is now 1% (0.01), down from 0.25");

        AbuseGuard guard = new AbuseGuard();
        int softCap = EconomyConstants.DAILY_SOFTCAP_DIAMOND; // 64
        double base = ShopPriceTable.ORE_BASE_DIAMOND;        // 500

        // 逐矿 buyPrice 极深档 (over 极大 -> 0.97^over 远小于 1% -> 夹地板): base * 0.01 = 500 * 0.01 = 5.0。
        double deepUnit = guard.buyPrice(HighValueOre.DIAMOND, softCap + 1_000_000, base);
        helper.assertTrue(deepUnit == 5.0D,
                "diamond deep-over per-ore unit clamps to base*1% = 500*0.01 = 5.0 (was 125.0 at the 0.25 floor)");

        // 金锭深档 (base 120): 120 * 0.01 = 1.2; 残骸 (base 4500): 4500 * 0.01 = 45.0。三者深档均 >= 1, 故 settleOreSale
        // 的 gross<=0 早返是防御性而非常态命中 (与实现 behaviorNotes 对齐)。
        double deepGold = guard.buyPrice(HighValueOre.GOLD, EconomyConstants.DAILY_SOFTCAP_GOLD + 1_000_000,
                ShopPriceTable.ORE_BASE_GOLD);
        helper.assertTrue(deepGold == 1.2D, "gold deep-over per-ore unit clamps to 120*0.01 = 1.2");
        double deepScrap = guard.buyPrice(HighValueOre.NETHERITE_SCRAP,
                EconomyConstants.DAILY_SOFTCAP_NETHERITE_SCRAP + 1_000_000, ShopPriceTable.ORE_BASE_NETHERITE_SCRAP);
        helper.assertTrue(deepScrap == 45.0D, "netherite scrap deep-over per-ore unit clamps to 4500*0.01 = 45.0");
        helper.succeed();
    }

    // ============================================================
    // economy-04: 取消的 BreakEvent 不发钱 (反洗钱: 取消的破坏仍走 settleOreSale = 凭空印钞)。
    // shouldSkipBreak 守卫: 取消 -> true (onBlockBreak 短路, 不到 recordAndSettleBreak); 未取消 -> false (照常结算)。
    // recordAndSettleBreak 是 onBlockBreak 唯一发钱出口: 直证它对钻石真入账 500, 故守卫是"取消即不入这 500"的闸。
    // 删 shouldSkipBreak 的 isCanceled (恒返 false) 则取消的破坏也落入 recordAndSettleBreak 发钱, 下方断言必挂。
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void canceledBreakDoesNotPayOreSale(GameTestHelper helper) {
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        // 构造真实钻石 BreakEvent (与 onBlockBreak 实际接收的同类型事件)。
        BlockState diamond = Blocks.DIAMOND_ORE.defaultBlockState();
        BlockEvent.BreakEvent normal = new BlockEvent.BreakEvent(
                helper.getLevel(), helper.absolutePos(new net.minecraft.core.BlockPos(0, 1, 0)), diamond, player);
        // 未取消: 守卫放行 (false), onBlockBreak 会继续到 recordAndSettleBreak。
        helper.assertFalse(EconomySystem.shouldSkipBreak(normal),
                "an un-canceled break is NOT skipped (settlement proceeds)");

        // 取消同一事件: 守卫拦截 (true), onBlockBreak 在到达发钱出口前短路。删 isCanceled 守卫则此断言为 false 必挂。
        normal.setCanceled(true);
        helper.assertTrue(EconomySystem.shouldSkipBreak(normal),
                "a canceled break IS skipped before any counting/settlement (anti-print guard)");

        // 直证发钱出口真入账: recordAndSettleBreak 对一颗钻石经主闸真入钱包 500 (首档 x1.0)。这是被守卫拦在
        // 取消事件之外的"那 500": 取消的破坏正因短路而拿不到它。
        EconomySystem system = new EconomySystem();
        EconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        // 保存并还原启动期已绑定的真实门面 (GameTest 在已启动服务端跑, 直接 reset 会让后续依赖取门面时 ISE)。
        IEconomyService prev = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(eco);
        try {
            PlayerAbuseState state = new PlayerAbuseState();
            long before = ledger.balance(player.getUUID(), Currency.CREDIT);
            helper.assertTrue(before == 0L, "fresh wallet starts at 0 credit");

            // 模拟 onBlockBreak 对【未取消】钻石破坏的结算路径 (守卫已放行后才到此): 真入账 500。
            system.recordAndSettleBreak(player, Blocks.DIAMOND_ORE, state);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 500L,
                    "the settlement path the guard protects actually mints 500 for one diamond (so skipping it withholds real money)");
            helper.assertTrue(state.dailyOreCount(HighValueOre.DIAMOND) == 1,
                    "the un-canceled break also counts one diamond into the daily ore count");
        } finally {
            if (prev != null) {
                EconomyServices.registerEconomyService(prev);
            } else {
                EconomyServices.reset();
            }
        }
        helper.succeed();
    }

    /**
     * 测试用 {@link PlayerAbuseState} 解析器: 以 UUID 为键惰性建态 (与 {@link EconomySystem#playerState} 同纪律,
     * 未知 UUID 建新态而非返回 null), 供门面 {@link EconomyService#recordMinedOreDrops}/{@link
     * EconomyService#isAfkFrozen} 取同一玩家态。每次调用返回独立 map 的解析器, 保证测试间不串态。
     */
    private static Function<UUID, PlayerAbuseState> newStateResolver() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
    }
}
