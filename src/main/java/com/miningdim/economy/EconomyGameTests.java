package com.miningdim.economy;

import com.miningdim.core.MiningConstants;
import com.miningdim.economy.EconomyConstants.HighValueOre;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

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
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());

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

    // ============================================================
    // recordMinedOreDrops: 方案 B 按产出物个数计入当日矿物计数 (Miner_Job_DesignSpec 第十章反通胀第一道硬约束)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recordMinedOreDropsCountsByDrops(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        EconomyWalletData ledger = new EconomyWalletData();
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
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        states.put(player.getUUID(), new PlayerAbuseState());
        EconomyService eco = new EconomyService(new EconomyWalletData(), new AbuseGuard(), states::get);

        helper.assertTrue(!eco.isAfkFrozen(player), "a fresh player is not AFK-frozen");
        states.get(player.getUUID()).setAfkFrozen(true);
        helper.assertTrue(eco.isAfkFrozen(player), "facade reports AFK-frozen once the abuse state is frozen");
        states.get(player.getUUID()).setAfkFrozen(false);
        helper.assertTrue(!eco.isAfkFrozen(player), "facade reports unfrozen once the abuse state clears");
        helper.succeed();
    }

    // ============================================================
    // grantDaily: 全服每人每日信用点 faucet 软上限 + 衰减 (经济文档 8.5: 0.97 逐档衰减 / 0.25 地板)
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void grantDailyFaucetSoftCapDecay(GameTestHelper helper) {
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        EconomyWalletData ledger = new EconomyWalletData();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());

        long cap = 1_000L;
        String key = "faucet_shared";

        // 第一档 (累计落在 [0, cap] 内): 全额入账, 实发 = raw。
        long first = eco.grantDaily(player, cap, key, cap);
        helper.assertTrue(first == cap, "first grant within the daily cap credits in full (1000)");

        // 第二档 (累计进入 (cap, 2*cap]): 衰减一档 ×0.97。再发 cap -> 累计 2*cap, tier=1, floor(1000*0.97)=970。
        long second = eco.grantDaily(player, cap, key, cap);
        helper.assertTrue(second == 970L, "second cap-batch decays one tier x0.97: floor(1000*0.97)=970");

        // 远超额: 逐档衰减跌破 0.25 地板后夹地板。预先把 faucet 计数推到极深档 (累计 >> cap)。
        // 直接再发一大批使累计远超: tier 极大 -> ratio 夹 0.25 -> floor(1000*0.25)=250。
        // 当前累计 = 2*cap; 再发 100*cap 使累计 = 102*cap, tier=101, 0.97^101≈0.045 < 0.25 -> 夹地板。
        long deep = eco.grantDaily(player, 100L * cap, key, cap);
        helper.assertTrue(deep == (long) Math.floor(100L * cap * 0.25D),
                "a deep over-cap batch clamps to the 0.25 floor: floor(100000*0.25)=25000");

        // 账本入账 = 三次实发之和 (faucet 入账落账本, 衰减后实发额)。
        long expectedBalance = first + second + deep;
        helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == expectedBalance,
                "wallet credit balance equals the sum of post-decay grants (1000 + 970 + 25000)");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void grantDailySharedAcrossFaucetKeys(GameTestHelper helper) {
        // 经济文档 8.5: 矿工卖矿与农夫卖菜传同一 faucetKey 即共享同一每人每日信用点天花板 (而非各自私有上限)。
        ServerPlayer player = helper.makeMockServerPlayerInLevel();
        EconomyWalletData ledger = new EconomyWalletData();
        EconomyService eco = new EconomyService(ledger, new AbuseGuard(), newStateResolver());

        long cap = 500L;
        String shared = "credit_faucet"; // 卖矿与卖菜共用此键。

        // 卖矿先发满 cap (落在首档, 全额)。
        long mining = eco.grantDaily(player, cap, shared, cap);
        helper.assertTrue(mining == cap, "mining faucet fills the shared cap in full (500)");
        // 卖菜随后用同一 key 再发 cap: 因共享累计已达 cap, 进入第二档 ×0.97, 而非另起一个私有上限全额发。
        long farming = eco.grantDaily(player, cap, shared, cap);
        helper.assertTrue(farming == (long) Math.floor(cap * 0.97D),
                "farming faucet sharing the same key is already in the decay tier: floor(500*0.97)=485");
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
