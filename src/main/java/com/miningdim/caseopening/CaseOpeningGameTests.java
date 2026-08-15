package com.miningdim.caseopening;

import com.google.gson.JsonObject;
import com.miningdim.caseopening.store.CaseDaoSqlite;
import com.miningdim.caseopening.store.CaseDb;
import com.miningdim.caseopening.store.CaseOpeningRow;
import com.miningdim.caseopening.store.CaseOpeningStatus;
import com.miningdim.caseopening.store.SkinAssetRow;
import com.miningdim.core.MiningConstants;
import com.miningdim.economy.AbuseGuard;
import com.miningdim.economy.Currency;
import com.miningdim.economy.EconomyOperationDomain;
import com.miningdim.economy.EconomyOperationStatus;
import com.miningdim.economy.EconomyService;
import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.EconomyLedger;
import com.miningdim.economy.SqliteEconomyLedger;
import com.miningdim.economy.IEconomyService;
import com.miningdim.economy.PlayerAbuseState;
import com.miningdim.store.MiningDb;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.testutil.TempStoreDb;
import com.miningdim.webui.server.WebUiBusinessException;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** Strong server-side tests for exact probability boundaries, SQL idempotency and the cross-store opening Saga. */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class CaseOpeningGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "caseopening";

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void exactProbabilityBoundaries(GameTestHelper helper) {
        assertRarity(helper, 0, CaseRarity.BLUE);
        assertRarity(helper, 79_109, CaseRarity.BLUE);
        assertRarity(helper, 79_110, CaseRarity.PURPLE);
        assertRarity(helper, 94_609, CaseRarity.PURPLE);
        assertRarity(helper, 94_610, CaseRarity.PINK);
        assertRarity(helper, 98_609, CaseRarity.PINK);
        assertRarity(helper, 98_610, CaseRarity.RED);
        assertRarity(helper, 99_599, CaseRarity.RED);
        assertRarity(helper, 99_600, CaseRarity.GOLD);
        assertRarity(helper, 99_999, CaseRarity.GOLD);

        boolean rejected = false;
        try {
            new CaseWeights(79_110, 15_500, 4_000, 990, 399);
        } catch (IllegalArgumentException expected) {
            rejected = true;
        }
        helper.assertTrue(rejected, "probability tables that do not total 100000 must be rejected");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void catalogueHasSeventeenOriginalEntries(GameTestHelper helper) {
        helper.assertTrue(CaseCatalog.skins().size() == 17, "founders case must contain exactly 17 skins");
        int[] expected = {7, 4, 3, 2, 1};
        for (CaseRarity rarity : CaseRarity.values()) {
            helper.assertTrue(CaseCatalog.skins(rarity).size() == expected[rarity.ordinal()],
                    rarity + " pool must contain " + expected[rarity.ordinal()] + " entries");
        }
        for (CaseSkin skin : CaseCatalog.skins()) {
            helper.assertTrue(skin.displayId().equals(new ResourceLocation(
                            "miningdim", "case_" + skin.skinId() + "_display")),
                    "display id follows the embedded TaCZ gunpack contract for " + skin.skinId());
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void ownedStateResponseIsCappedWithTotal(GameTestHelper helper) {
        List<SkinAssetRow> owned = new ArrayList<>();
        UUID ownerId = UUID.randomUUID();
        CaseSkin skin = CaseCatalog.requireSkin("arctic_grid");
        for (int index = 0; index < 61; index++) {
            owned.add(new SkinAssetRow(UUID.randomUUID(), ownerId, skin.skinId(), skin.rarity(),
                    skin.gunId().toString(), skin.displayId().toString(), UUID.randomUUID(), index, 0L));
        }
        CaseWebUiActions.OwnedSlice slice = CaseWebUiActions.ownedSlice(owned);
        helper.assertTrue(slice.assets().size() == CaseWebUiActions.OWNED_RESPONSE_LIMIT,
                "case.state serializes at most 60 owned assets");
        helper.assertTrue(slice.total() == 61,
                "case.state reports the uncapped ownedTotal separately");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void invalidWebRequestParametersAreTerminalBusinessErrors(GameTestHelper helper) {
        JsonObject missingOpeningId = new JsonObject();
        assertInvalidRequest(helper,
                () -> CaseWebUiActions.OPEN.handle(null, missingOpeningId),
                "case.open missing openingId");

        JsonObject invalidOpeningId = new JsonObject();
        invalidOpeningId.addProperty("openingId", "not-a-uuid");
        assertInvalidRequest(helper,
                () -> CaseWebUiActions.OPEN.handle(null, invalidOpeningId),
                "case.open invalid openingId");

        JsonObject invalidCaseId = new JsonObject();
        invalidCaseId.addProperty("openingId", UUID.randomUUID().toString());
        invalidCaseId.addProperty("caseId", "retired_case");
        assertInvalidRequest(helper,
                () -> CaseWebUiActions.OPEN.handle(null, invalidCaseId),
                "case.open invalid caseId");

        JsonObject invalidAssetId = new JsonObject();
        invalidAssetId.addProperty("assetId", "not-a-uuid");
        assertInvalidRequest(helper,
                () -> CaseWebUiActions.APPLY.handle(null, invalidAssetId),
                "case.apply invalid assetId");
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void sqliteCommitIsDurablyIdempotent(GameTestHelper helper) {
        CaseDaoSqlite dao = CaseDb.openInMemory();
        try {
            UUID openingId = UUID.randomUUID();
            UUID ownerId = UUID.randomUUID();
            UUID assetId = UUID.randomUUID();
            long now = 1_700_000_000_000L;
            CaseOpeningRow proposed = row(openingId, ownerId, assetId, now, CaseOpeningStatus.RESERVED);
            CaseOpeningRow first = dao.reserve(proposed);
            CaseOpeningRow replay = dao.reserve(proposed.withStatus(CaseOpeningStatus.DEBITED, now + 1));
            helper.assertTrue(first.status() == CaseOpeningStatus.RESERVED,
                    "first reservation is stored as RESERVED");
            helper.assertTrue(replay.status() == CaseOpeningStatus.RESERVED,
                    "re-reserving the same opening id returns the original row without overwriting it");
            helper.assertTrue(dao.markDebited(openingId, now + 2), "reservation advances to DEBITED");

            SkinAssetRow asset = asset(first);
            SkinAssetRow committed = dao.commitOpening(openingId, asset, now + 3);
            SkinAssetRow committedReplay = dao.commitOpening(openingId, asset, now + 4);
            helper.assertTrue(committed.assetId().equals(assetId), "first commit creates the intended asset");
            helper.assertTrue(committedReplay.assetId().equals(assetId),
                    "committing the same opening twice returns the same asset");
            helper.assertTrue(dao.ownedAssets(ownerId).size() == 1,
                    "idempotent commit creates exactly one ownership row");
            helper.assertTrue(dao.findOpening(openingId).status() == CaseOpeningStatus.COMMITTED,
                    "opening reaches durable COMMITTED state");
        } finally {
            CaseDb.close(dao);
        }
        helper.succeed();
    }

    /**
     * 账本里已存在该玩家一条金额不同的操作记录, 而 SQLite 侧没有对应开箱行时, 必须真扣款或失败,
     * 绝不能把那条记录当成本次开箱的付款凭据。
     *
     * 这正是"客户端提交的 openingId 被当作全局幂等键"的利用形态: 玩家知道自己全部历史 operationId,
     * 只要账本有记录而开箱库没有对应行 (运维单独回滚过 miningdim_cases.db, 或有第二个业务也在写 bundle
     * 操作), 复用该 ID 即可白拿。修复前 resume 先查 state 再决定是否扣款, state 非 NONE 就整段跳过扣款;
     * 修复后无条件调 charge, 由账本的全元组校验做闸门。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void openingRejectsLedgerOperationBelongingToAnotherCharge(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 100_000L);
            fake.grant(player, Currency.AZURE, 20L);
            UUID hijackedId = UUID.randomUUID();

            // 预置一笔金额与开箱价不同的已结清操作, 且不在 SQLite 留任何开箱行。
            helper.assertTrue(fake.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player,
                    hijackedId, 1_000L, 1L) == EconomyOperationStatus.CHARGED,
                    "预置操作应成功扣款并记为 CHARGED");
            helper.assertTrue(fake.completeBundle(EconomyOperationDomain.CASE_OPENING, player.getUUID(),
                    hijackedId) == EconomyOperationStatus.COMPLETED, "预置操作应推进为 COMPLETED");
            long creditAfterSetup = ledger.balance(player.getUUID(), Currency.CREDIT);
            long azureAfterSetup = ledger.balance(player.getUUID(), Currency.AZURE);

            boolean rejected = false;
            try {
                service(dao).open(player, hijackedId, CaseCatalog.CASE_ID);
            } catch (RuntimeException expected) {
                rejected = true;
            }

            helper.assertTrue(rejected, "复用他笔操作的 UUID 开箱必须失败, 不得静默放行");
            helper.assertTrue(dao.ownedAssets(player.getUUID()).isEmpty(),
                    "被拒绝的开箱不得产出任何皮肤资产");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == creditAfterSetup
                            && ledger.balance(player.getUUID(), Currency.AZURE) == azureAfterSetup,
                    "被拒绝的开箱不得改动任何一种货币余额");
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void openingChargesBothCurrenciesExactlyOnce(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 100_000L);
            fake.grant(player, Currency.AZURE, 20L);
            CaseOpeningService service = service(dao);
            UUID openingId = UUID.randomUUID();

            CaseOpeningService.OpenResult first = service.open(player, openingId, CaseCatalog.CASE_ID);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 50_000L,
                    "one opening destroys exactly 50000 CREDIT");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.AZURE) == 10L,
                    "one opening destroys exactly 10 AZURE");
            helper.assertTrue(fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), openingId) == EconomyOperationStatus.COMPLETED,
                    "currency operation reaches durable COMPLETED state after SQL ownership commit");

            CaseOpeningService.OpenResult replay = service.open(player, openingId, CaseCatalog.CASE_ID);
            helper.assertTrue(replay.replayed(), "same opening id is reported as a replay");
            helper.assertTrue(replay.asset().assetId().equals(first.asset().assetId()),
                    "replay returns the original asset rather than rolling again");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 50_000L
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 10L,
                    "replay performs no second currency debit");
            helper.assertTrue(dao.ownedAssets(player.getUUID()).size() == 1,
                    "replay leaves exactly one owned asset");
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void insufficientEitherCurrencyLeavesBothUntouched(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 49_999L);
            fake.grant(player, Currency.AZURE, 10L);
            CaseOpeningService service = service(dao);
            UUID openingId = UUID.randomUUID();
            boolean rejected = false;
            try {
                service.open(player, openingId, CaseCatalog.CASE_ID);
            } catch (WebUiBusinessException expected) {
                rejected = "INSUFFICIENT_FUNDS".equals(expected.errorCode());
            }
            helper.assertTrue(rejected, "opening is rejected when CREDIT is one short");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 49_999L,
                    "failed bundle debit leaves CREDIT untouched");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.AZURE) == 10L,
                    "failed bundle debit leaves AZURE untouched");
            helper.assertTrue(fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), openingId) == EconomyOperationStatus.NONE,
                    "insufficient bundle creates no charged economy operation");
            helper.assertTrue(dao.findOpening(openingId) == null,
                    "balance preflight rejects before reserving or writing any opening row");
            helper.assertTrue(dao.ownedAssets(player.getUUID()).isEmpty(),
                    "failed opening creates no ownership row");

            UUID throttledId = UUID.randomUUID();
            boolean throttled = false;
            try {
                service.open(player, throttledId, CaseCatalog.CASE_ID);
            } catch (WebUiBusinessException expected) {
                throttled = "RATE_LIMITED".equals(expected.errorCode());
            }
            helper.assertTrue(throttled,
                    "insufficient-funds spam is still rate-limited before a second balance read");
            helper.assertTrue(dao.findOpening(throttledId) == null,
                    "rate-limited insufficient request still performs no SQL write");
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unavailableTaczGunpackRejectsBeforeAnyDebitOrReservation(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 100_000L);
            fake.grant(player, Currency.AZURE, 20L);
            UUID openingId = UUID.randomUUID();
            CaseOpeningService service = service(dao, true, false);

            boolean rejected = false;
            try {
                service.open(player, openingId, CaseCatalog.CASE_ID);
            } catch (WebUiBusinessException expected) {
                rejected = "CASE_DISABLED".equals(expected.errorCode());
            }
            helper.assertTrue(!service.enabled(), "case.state reports disabled when the TaCZ gunpack is unavailable");
            helper.assertTrue(rejected, "opening fails fast when TaCZ did not accept the embedded gunpack");
            helper.assertTrue(dao.findOpening(openingId) == null,
                    "TaCZ preflight fails before writing a reservation");
            helper.assertTrue(fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), openingId) == EconomyOperationStatus.NONE,
                    "TaCZ preflight fails before creating an economy operation");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 100_000L
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 20L,
                    "TaCZ preflight leaves both balances untouched");

            UUID missingModId = UUID.randomUUID();
            CaseOpeningService missingMod = service(dao, false, true);
            boolean missingModRejected = false;
            try {
                missingMod.open(player, missingModId, CaseCatalog.CASE_ID);
            } catch (WebUiBusinessException expected) {
                missingModRejected = "CASE_DISABLED".equals(expected.errorCode());
            }
            helper.assertTrue(!missingMod.enabled() && missingModRejected,
                    "ModList absence independently disables state and rejects opening");
            helper.assertTrue(dao.findOpening(missingModId) == null
                            && fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), missingModId) == EconomyOperationStatus.NONE,
                    "missing TaCZ mod also fails before reservation and debit");
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void newOpeningIsRateLimitedBeforeReservation(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 150_000L);
            fake.grant(player, Currency.AZURE, 30L);
            CaseOpeningService service = service(dao);
            service.open(player, UUID.randomUUID(), CaseCatalog.CASE_ID);

            UUID rejectedId = UUID.randomUUID();
            boolean rejected = false;
            try {
                service.open(player, rejectedId, CaseCatalog.CASE_ID);
            } catch (WebUiBusinessException expected) {
                rejected = "RATE_LIMITED".equals(expected.errorCode());
            }
            helper.assertTrue(rejected, "a second new opening in the cooldown window is rejected");
            helper.assertTrue(dao.findOpening(rejectedId) == null,
                    "rate limiting happens before reserve/pre-roll SQL writes");
            helper.assertTrue(fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), rejectedId) == EconomyOperationStatus.NONE,
                    "rate limiting creates no economy operation");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 100_000L
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 20L,
                    "rate-limited request performs no second debit");
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginRecoveryCompletesChargedReservation(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 100_000L);
            fake.grant(player, Currency.AZURE, 20L);
            UUID openingId = UUID.randomUUID();
            CaseOpeningRow reserved = row(openingId, player.getUUID(), UUID.randomUUID(),
                    1_700_000_000_000L, CaseOpeningStatus.RESERVED);
            dao.reserve(reserved);
            helper.assertTrue(fake.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player, openingId, 50_000L, 10L)
                            == EconomyOperationStatus.CHARGED,
                    "test fixture simulates a crash after durable dual debit but before SQL phase update");

            int recovered = service(dao).recoverFor(player);
            helper.assertTrue(recovered == 1, "login recovery processes exactly one interrupted reservation");
            helper.assertTrue(dao.findOpening(openingId).status() == CaseOpeningStatus.COMMITTED,
                    "recovery completes the SQL opening and ownership transaction");
            helper.assertTrue(dao.ownedAssets(player.getUUID()).size() == 1,
                    "recovery grants exactly one skin asset");
            helper.assertTrue(fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), openingId) == EconomyOperationStatus.COMPLETED,
                    "recovery finalizes the persistent economy operation");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 50_000L
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 10L,
                    "recovery never charges the already-debited operation twice");
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void loginRecoveryFinishesRefundLostAtHardCrash(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 100_000L);
            fake.grant(player, Currency.AZURE, 20L);
            UUID openingId = UUID.randomUUID();
            long now = 1_700_000_000_000L;
            dao.reserve(row(openingId, player.getUUID(), UUID.randomUUID(), now, CaseOpeningStatus.RESERVED));
            helper.assertTrue(fake.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player, openingId, 50_000L, 10L)
                            == EconomyOperationStatus.CHARGED,
                    "fixture persists the debit before the crash window");
            helper.assertTrue(dao.markRefunded(openingId, now + 1),
                    "fixture persists SQL REFUNDED before the SavedData refund is saved");

            CaseOpeningService service = service(dao);
            int recovered = service.recoverFor(player);
            helper.assertTrue(recovered == 1, "login recovery completes one missing SavedData refund");
            helper.assertTrue(fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), openingId) == EconomyOperationStatus.REFUNDED,
                    "the debit operation reaches idempotent REFUNDED state");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 100_000L
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 20L,
                    "both currencies are restored exactly once");
            helper.assertTrue(service.recoverFor(player) == 0,
                    "replaying REFUNDED reconciliation does not mint another refund");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 100_000L
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 20L,
                    "idempotent replay leaves restored balances unchanged");
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void completedRefundConflictIsIsolatedWhileLaterRowsRecover(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 200_000L);
            fake.grant(player, Currency.AZURE, 40L);
            long now = 1_700_000_000_000L;

            UUID conflictedId = UUID.randomUUID();
            dao.reserve(row(conflictedId, player.getUUID(), UUID.randomUUID(), now,
                    CaseOpeningStatus.RESERVED));
            fake.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player, conflictedId, 50_000L, 10L);
            fake.completeBundle(EconomyOperationDomain.CASE_OPENING, player.getUUID(), conflictedId);
            dao.markRefunded(conflictedId, now + 1);

            UUID refundableId = UUID.randomUUID();
            dao.reserve(row(refundableId, player.getUUID(), UUID.randomUUID(), now + 2,
                    CaseOpeningStatus.RESERVED));
            fake.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player, refundableId, 50_000L, 10L);
            dao.markRefunded(refundableId, now + 3);

            // 隔离必须落成一个状态而不是靠抛异常表达: 抛出会让该玩家每次登录都抛, 且抛出点之后的后续恢复
            // 与 enforceMainHand 被整段跳过 —— 一行坏数据瘫掉整条恢复链路。
            service(dao).recoverFor(player);
            helper.assertTrue(dao.findOpening(conflictedId).status() == CaseOpeningStatus.QUARANTINED,
                    "SQL REFUNDED 叠加账本 COMPLETED 必须落成隔离终态, 实为 "
                            + dao.findOpening(conflictedId).status());
            helper.assertTrue(fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), conflictedId)
                            == EconomyOperationStatus.COMPLETED,
                    "the conflicted completed operation is isolated rather than refunded");
            helper.assertTrue(fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), refundableId)
                            == EconomyOperationStatus.REFUNDED,
                    "a later valid REFUNDED row still recovers despite the earlier conflict");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 150_000L
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 30L,
                    "only the valid row is refunded; the completed conflict stays charged");
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void recoveryRechargesCommittedAssetWhenSavedDataDebitVanished(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 100_000L);
            fake.grant(player, Currency.AZURE, 20L);
            UUID openingId = UUID.randomUUID();
            CaseOpeningRow reserved = row(openingId, player.getUUID(), UUID.randomUUID(),
                    1_700_000_000_000L, CaseOpeningStatus.RESERVED);
            dao.reserve(reserved);
            helper.assertTrue(dao.markDebited(openingId, reserved.createdAt() + 1),
                    "fixture advances SQL to DEBITED");
            dao.commitOpening(openingId, asset(reserved), reserved.createdAt() + 2);
            helper.assertTrue(fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), openingId) == EconomyOperationStatus.NONE,
                    "fixture simulates SQLite COMMITTED while the dirty SavedData debit was lost in a hard crash");

            int recovered = service(dao).recoverFor(player);
            helper.assertTrue(recovered == 1, "login recovery detects the unsettled committed asset");
            helper.assertTrue(fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), openingId) == EconomyOperationStatus.COMPLETED,
                    "recovery recreates and completes the missing durable economy operation");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 50_000L
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 10L,
                    "recovery charges the formerly free committed asset exactly once");
            helper.assertTrue(service(dao).ownedAssets(player).size() == 1,
                    "asset becomes visible only after its recreated economy operation is settled");
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    /**
     * F006: 结算锚落在开箱库自身 (economy_settled) 之后, 账本侧的双币幂等凭据即使被
     * {@code EconomySystem} 定期回收 (30 天窗口) 也不得让登录恢复重新扣款、也不得让已发出的皮肤从
     * 面板上消失。此前的判据是查账本里有没有对应的 CHARGED/COMPLETED 行, 而账本行是会被回收的; 回收后
     * COMMITTED 行会被当成"孤儿"重新补扣一次, 相当于同一件皮肤反复收费。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void prunedLedgerDoesNotRechargeCommittedOpenings(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 100_000L);
            fake.grant(player, Currency.AZURE, 20L);
            UUID openingId = UUID.randomUUID();

            CaseOpeningService.OpenResult opened = service(dao).open(player, openingId, CaseCatalog.CASE_ID);
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 50_000L
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 10L,
                    "正常开箱一次后应恰好扣至 50000/10, 实为 " + ledger.balance(player.getUUID(), Currency.CREDIT)
                            + "/" + ledger.balance(player.getUUID(), Currency.AZURE));

            // 模拟 EconomySystem 30 天窗口回收: 用晚 1ms 的截止时间, 该笔 COMPLETED 记录必然创建于此之前。
            int pruned = ledger.pruneTerminalOperations(System.currentTimeMillis() + 1L);
            helper.assertTrue(pruned == 1, "回收必须真的删掉这一笔已结清的账本记录, 实为 " + pruned);
            helper.assertTrue(fake.operationStatus(EconomyOperationDomain.CASE_OPENING, player.getUUID(), openingId)
                            == EconomyOperationStatus.NONE,
                    "账本侧的幂等凭据回收后必须真的查无此笔");

            int recovered = service(dao).recoverFor(player);
            helper.assertTrue(recovered == 0,
                    "已结算的 COMMITTED 行不属于登录恢复的处置对象, 实为处置了 " + recovered + " 条");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 50_000L
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 10L,
                    "账本证据被回收后登录恢复不得再扣一次款, 实为 " + ledger.balance(player.getUUID(), Currency.CREDIT)
                            + "/" + ledger.balance(player.getUUID(), Currency.AZURE));

            List<SkinAssetRow> owned = service(dao).ownedAssets(player);
            helper.assertTrue(owned.size() == 1 && owned.get(0).assetId().equals(opened.asset().assetId()),
                    "账本证据被回收后皮肤仍必须留在面板上且是第一次开出的那件, 实为 " + owned.size() + " 件");
            helper.assertTrue(dao.recoverableOpenings(player.getUUID()).isEmpty(),
                    "已结算的 COMMITTED 行不得再出现在恢复集合里, 实为 "
                            + dao.recoverableOpenings(player.getUUID()).size() + " 条");

            CaseOpeningService.OpenResult replay = service(dao).open(player, openingId, CaseCatalog.CASE_ID);
            helper.assertTrue(replay.replayed(), "账本证据被回收后, 同一 openingId 仍须被识别为重放而非新开");
            helper.assertTrue(replay.asset().assetId().equals(opened.asset().assetId()),
                    "重放必须返回第一次开出的同一件资产");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 50_000L
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 10L,
                    "重放不得产生第二次扣款, 实为 " + ledger.balance(player.getUUID(), Currency.CREDIT)
                            + "/" + ledger.balance(player.getUUID(), Currency.AZURE));
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    /**
     * F107 + F108: {@code settledOwnedAssets}/{@code recoverableOpenings} 的过滤必须发生在 SQL 联表里,
     * 而不是靠 Java 侧再各打一次账本点查。用两件资产分别锁死两侧: 已结算的甲必须出现在拥有列表、绝不出现
     * 在恢复集合; 未结算的乙必须相反 —— 单改一侧的过滤条件都会让另一侧的断言失衡。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void settledFilterSeparatesRecoverableFromOwned(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 100_000L);
            fake.grant(player, Currency.AZURE, 20L);

            // 甲: 经 service.open 正常产出, 结算锚已落, 账本 COMPLETED。
            CaseOpeningService.OpenResult settled = service(dao).open(player, UUID.randomUUID(), CaseCatalog.CASE_ID);

            // 乙: 直接落成 COMMITTED, 不经过 service, 因此结算锚 economy_settled=0 且账本无对应操作。
            UUID unsettledOpeningId = UUID.randomUUID();
            long now = 1_700_000_000_000L;
            CaseOpeningRow proposed = row(unsettledOpeningId, player.getUUID(), UUID.randomUUID(), now,
                    CaseOpeningStatus.RESERVED);
            CaseOpeningRow reserved = dao.reserve(proposed);
            helper.assertTrue(dao.markDebited(unsettledOpeningId, now + 1), "乙应能推进到 DEBITED");
            dao.commitOpening(unsettledOpeningId, asset(reserved), now + 2);

            List<SkinAssetRow> owned = service(dao).ownedAssets(player);
            helper.assertTrue(owned.size() == 1 && owned.get(0).assetId().equals(settled.asset().assetId()),
                    "service.ownedAssets 只应暴露已结算的甲, 实为 " + owned.size() + " 件");
            helper.assertTrue(dao.ownedAssets(player.getUUID()).size() == 2,
                    "资产底表本身应有两件, 证明过滤发生在联表查询而非资产表少行, 实为 "
                            + dao.ownedAssets(player.getUUID()).size() + " 件");

            List<CaseOpeningRow> recoverable = dao.recoverableOpenings(player.getUUID());
            helper.assertTrue(recoverable.size() == 1 && recoverable.get(0).openingId().equals(unsettledOpeningId),
                    "recoverableOpenings 只应捞出未结算的乙, 实为 " + recoverable.size() + " 条");

            int recovered = service(dao).recoverFor(player);
            helper.assertTrue(recovered == 1, "补扣未结算的乙这一件, 实为处置了 " + recovered + " 条");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == 100_000L - 50_000L * 2
                            && ledger.balance(player.getUUID(), Currency.AZURE) == 20L - 10L * 2,
                    "补扣乙之后余额应恰为两箱的合计扣款, 实为 " + ledger.balance(player.getUUID(), Currency.CREDIT)
                            + "/" + ledger.balance(player.getUUID(), Currency.AZURE));
            helper.assertTrue(service(dao).ownedAssets(player).size() == 2,
                    "补扣结算后乙也应出现在拥有列表里, 共两件");
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
        helper.succeed();
    }

    /**
     * 开箱事务的最后一步 (推进货币操作到终态) 失败时, 扣款与皮肤归属必须一并回滚。
     *
     * 这是"扣钥匙 + 扣箱子 + 发皮肤 单个原子事务"的反向验证。此前四步各走 autocommit, 落到这里失败就会
     * 留下"钱扣了、皮肤也发了、但账本停在 CHARGED"的记录, 只能靠登录恢复去猜该补扣还是该退款。
     * 注入点在事务内的最后一步, 被测的是回滚本身, 不是被 mock 掉的业务逻辑。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void failedFinalizeRollsBackChargeAndAsset(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            fake.grant(player, Currency.CREDIT, 100_000L);
            fake.grant(player, Currency.AZURE, 20L);
            long creditBefore = ledger.balance(player.getUUID(), Currency.CREDIT);
            long azureBefore = ledger.balance(player.getUUID(), Currency.AZURE);

            CaseEconomyOperations real = new EconomyCaseOperations();
            CaseOpeningService service = new CaseOpeningService(dao, failingFinalize(real),
                    new CaseRoller(bound -> 0), () -> true, () -> true, () -> true,
                    () -> 50_000L, () -> 10L, () -> CaseWeights.DEFAULT, () -> 20);

            UUID openingId = UUID.randomUUID();
            boolean failed = false;
            try {
                service.open(player, openingId, CaseCatalog.CASE_ID);
            } catch (RuntimeException expected) {
                failed = true;
            }
            helper.assertTrue(failed, "终态推进失败必须让整次开箱失败");
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == creditBefore
                            && ledger.balance(player.getUUID(), Currency.AZURE) == azureBefore,
                    "回滚后两种货币都必须原封不动, 实为 "
                            + ledger.balance(player.getUUID(), Currency.CREDIT) + "/"
                            + ledger.balance(player.getUUID(), Currency.AZURE));
            helper.assertTrue(dao.ownedAssets(player.getUUID()).isEmpty(),
                    "回滚后不得留下任何皮肤资产");
            helper.assertTrue(real.state(player.getUUID(), openingId) == CaseEconomyOperations.State.NONE,
                    "回滚后账本里不得留下这笔操作");
            CaseOpeningRow after = dao.findOpening(openingId);
            helper.assertTrue(after != null && after.status() == CaseOpeningStatus.RESERVED,
                    "回滚后开箱行应停在 RESERVED 等待重试, 实为 "
                            + (after == null ? "无记录" : after.status().name()));
            helper.succeed();
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
    }

    /** 把真实适配器包一层, 只让最后的终态推进抛出; 其余原样委派。 */
    private static CaseEconomyOperations failingFinalize(CaseEconomyOperations delegate) {
        return new CaseEconomyOperations() {
            @Override
            public <T> T inTransaction(java.util.function.Supplier<T> body) {
                return delegate.inTransaction(body);
            }

            @Override
            public long creditBalance(ServerPlayer player) {
                return delegate.creditBalance(player);
            }

            @Override
            public long azureBalance(ServerPlayer player) {
                return delegate.azureBalance(player);
            }

            @Override
            public boolean charge(ServerPlayer player, UUID operationId, long creditCost, long azureCost) {
                return delegate.charge(player, operationId, creditCost, azureCost);
            }

            @Override
            public State state(UUID playerId, UUID operationId) {
                return delegate.state(playerId, operationId);
            }

            @Override
            public State complete(UUID playerId, UUID operationId) {
                throw new IllegalStateException("注入的终态推进失败: " + operationId);
            }

            @Override
            public State refund(UUID playerId, UUID operationId) {
                return delegate.refund(playerId, operationId);
            }
        };
    }

    /**
     * 启动期对账必须能处置从此不再上线的玩家。
     *
     * 登录驱动的恢复捞不到这些人, 他们的未完成开箱行会永久悬挂。本用例刻意【不构造任何 ServerPlayer】,
     * 全程只用 UUID —— 这正是"玩家不在场也能对账"的证明。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void startupReconciliationHandlesOfflineOwners(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService previous = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            UUID offlineOwner = UUID.randomUUID();
            long now = 1_700_000_000_000L;
            ledger.credit(offlineOwner, Currency.CREDIT, 200_000L);
            ledger.credit(offlineOwner, Currency.AZURE, 40L);

            // 甲: 钱已扣但开箱没走完 —— 对账应退款并作废该行。
            UUID chargedId = UUID.randomUUID();
            dao.reserve(row(chargedId, offlineOwner, UUID.randomUUID(), now, CaseOpeningStatus.RESERVED));
            helper.assertTrue(ledger.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, offlineOwner,
                    chargedId, 50_000L, 10L) == EconomyOperationStatus.CHARGED, "预置扣款应成功");

            // 乙: 钱一分没动的悬挂行 (单事务下崩溃只会停在这里) —— 对账应直接作废。
            UUID untouchedId = UUID.randomUUID();
            dao.reserve(row(untouchedId, offlineOwner, UUID.randomUUID(), now + 1, CaseOpeningStatus.RESERVED));

            long creditBefore = ledger.balance(offlineOwner, Currency.CREDIT);
            int handled = service(dao).reconcileAtStartup();

            helper.assertTrue(handled == 2, "两行都应被处置, 实为 " + handled);
            helper.assertTrue(ledger.balance(offlineOwner, Currency.CREDIT) == creditBefore + 50_000L
                            && ledger.balance(offlineOwner, Currency.AZURE) == 40L,
                    "已扣款的悬挂行必须把两种货币都退回离线玩家");
            helper.assertTrue(dao.findOpening(chargedId).status() == CaseOpeningStatus.REFUNDED
                            && dao.findOpening(untouchedId).status() == CaseOpeningStatus.REFUNDED,
                    "两行都应落成 REFUNDED 终态, 不再悬挂");
            helper.assertTrue(dao.ownedAssets(offlineOwner).isEmpty(), "对账不得凭空产出资产");
            helper.succeed();
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
    }

    /**
     * 恢复没通过的玩家不得开新箱。
     *
     * recoveryAuditedPlayers 此前只是个备忘录 —— open 从不查它, 于是挂着未结清资产的玩家可以无限开新箱,
     * 每一箱都在扩大不一致面。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void unreconciledPlayerCannotOpenNewCase(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService fake = new EconomyService(ledger, new AbuseGuard(), newStateResolver());
        IEconomyService previous = swapEconomy(fake);
        CaseDaoSqlite dao = CaseDb.on(ledger.connection());
        try {
            ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            long now = 1_700_000_000_000L;
            // 余额刻意给到足够连开数箱: 这样"被拒"只可能来自恢复闸门, 而不是余额不足这类无关原因。
            fake.grant(player, Currency.CREDIT, 200_000L);
            fake.grant(player, Currency.AZURE, 40L);

            // 已发出皮肤、账本却停在已退款: 补扣款拿到的是终态 REFUNDED, 无论余额多少都无法自愈。
            UUID strandedId = UUID.randomUUID();
            CaseOpeningRow stranded = dao.reserve(row(strandedId, player.getUUID(), UUID.randomUUID(), now,
                    CaseOpeningStatus.RESERVED));
            dao.markDebited(strandedId, now + 1);
            dao.commitOpening(strandedId, asset(stranded), now + 2);
            fake.tryChargeBundle(EconomyOperationDomain.CASE_OPENING, player, strandedId, 50_000L, 10L);
            fake.refundBundle(EconomyOperationDomain.CASE_OPENING, player.getUUID(), strandedId);

            long creditBefore = ledger.balance(player.getUUID(), Currency.CREDIT);
            long azureBefore = ledger.balance(player.getUUID(), Currency.AZURE);

            boolean blocked = false;
            try {
                service(dao).open(player, UUID.randomUUID(), CaseCatalog.CASE_ID);
            } catch (RuntimeException expected) {
                blocked = true;
            }
            helper.assertTrue(blocked, "恢复未通过的玩家开新箱必须被拒, 哪怕余额充足");
            helper.assertTrue(dao.recoverableOpenings(player.getUUID()).size() == 1,
                    "被拒时不得留下新的开箱行, 实为 " + dao.recoverableOpenings(player.getUUID()).size());
            helper.assertTrue(ledger.balance(player.getUUID(), Currency.CREDIT) == creditBefore
                            && ledger.balance(player.getUUID(), Currency.AZURE) == azureBefore,
                    "被拒的开箱不得改动余额");
            helper.succeed();
        } finally {
            CaseDb.close(dao);
            restoreEconomy(previous);
        }
    }

    /**
     * 崩溃并恢复之后, 钱与资产必须满足守恒律。
     *
     * 断言的是守恒关系而不是各项分别相等: 分项断言只要写错一个期望值就会互相掩盖, 而
     * 「初始余额 == 当前余额 + 已结清资产数 x 单价」这一条同时锁死了两侧 —— 少发资产、多扣钱、少扣钱、
     * 凭空发资产, 四种偏差里任何一种都会让它失衡。
     *
     * 崩溃是真的: 用文件库、真的关掉连接再重开。内存库测不出这件事, 它的 journal_mode 实为 memory。
     */
    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void economyConservationHoldsAcrossCrashAndRecovery(GameTestHelper helper) {
        final long creditCost = 50_000L;
        final long azureCost = 10L;
        final long initialCredit = 500_000L;
        final long initialAzure = 100L;

        java.nio.file.Path dir = TempStoreDb.createTempDir();
        java.nio.file.Path dbPath = dir.resolve("cases.db");
        ServerPlayer player = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);

        java.sql.Connection first = TempStoreDb.openUnified(dbPath);
        SqliteEconomyLedger ledger = new SqliteEconomyLedger(first);
        IEconomyService previous = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        try {
            CaseDaoSqlite dao = CaseDb.on(first);
            ledger.credit(player.getUUID(), Currency.CREDIT, initialCredit);
            ledger.credit(player.getUUID(), Currency.AZURE, initialAzure);

            service(dao).open(player, UUID.randomUUID(), CaseCatalog.CASE_ID);
            service(dao).open(player, UUID.randomUUID(), CaseCatalog.CASE_ID);

            // 第三箱在事务的最后一步失败, 紧接着连接被拔掉 —— 这就是一次真实的硬崩溃现场。
            CaseOpeningService failing = new CaseOpeningService(dao,
                    failingFinalize(new EconomyCaseOperations()), new CaseRoller(bound -> 0),
                    () -> true, () -> true, () -> true,
                    () -> creditCost, () -> azureCost, () -> CaseWeights.DEFAULT, () -> 20);
            boolean crashed = false;
            try {
                failing.open(player, UUID.randomUUID(), CaseCatalog.CASE_ID);
            } catch (RuntimeException expected) {
                crashed = true;
            }
            helper.assertTrue(crashed, "注入的终态失败必须让第三箱失败");
            MiningDb.close(first);

            java.sql.Connection second = TempStoreDb.openUnified(dbPath);
            SqliteEconomyLedger reopened = new SqliteEconomyLedger(second);
            swapEconomy(new EconomyService(reopened, new AbuseGuard(), newStateResolver()));
            try {
                CaseDaoSqlite reopenedDao = CaseDb.on(second);
                CaseOpeningService recovered = service(reopenedDao);
                recovered.reconcileAtStartup();
                recovered.recoverFor(player);

                long settled = recovered.ownedAssets(player).size();
                helper.assertTrue(settled == 2,
                        "两箱成功、一箱回滚, 重开后应恰好持有 2 件已结清资产, 实为 " + settled);

                long credit = reopened.balance(player.getUUID(), Currency.CREDIT);
                long azure = reopened.balance(player.getUUID(), Currency.AZURE);
                helper.assertTrue(initialCredit == credit + settled * creditCost,
                        "CREDIT 守恒被打破: 初始 " + initialCredit + " != 当前 " + credit
                                + " + " + settled + " x " + creditCost);
                helper.assertTrue(initialAzure == azure + settled * azureCost,
                        "AZURE 守恒被打破: 初始 " + initialAzure + " != 当前 " + azure
                                + " + " + settled + " x " + azureCost);
            } finally {
                MiningDb.close(second);
            }
            helper.succeed();
        } finally {
            MiningDb.close(first);
            restoreEconomy(previous);
            TempStoreDb.deleteQuietly(dir);
        }
    }

    private static CaseOpeningService service(CaseDaoSqlite dao) {
        return service(dao, true, true);
    }

    private static CaseOpeningService service(CaseDaoSqlite dao, boolean taczLoaded,
                                              boolean caseResourcesAvailable) {
        return new CaseOpeningService(dao, new EconomyCaseOperations(), new CaseRoller(bound -> 0),
                () -> true, () -> taczLoaded, () -> caseResourcesAvailable,
                () -> 50_000L, () -> 10L,
                () -> CaseWeights.DEFAULT, () -> 20);
    }

    private static CaseOpeningRow row(UUID openingId, UUID ownerId, UUID assetId,
                                      long now, CaseOpeningStatus status) {
        CaseSkin skin = CaseCatalog.requireSkin("arctic_grid");
        return new CaseOpeningRow(openingId, ownerId, CaseCatalog.CASE_ID,
                50_000L, 10L, status, assetId, skin.skinId(), skin.rarity(),
                skin.gunId().toString(), skin.displayId().toString(),
                "[\"arctic_grid\"]", 0, now, now);
    }

    private static SkinAssetRow asset(CaseOpeningRow opening) {
        return new SkinAssetRow(opening.assetId(), opening.ownerId(), opening.skinId(), opening.rarity(),
                opening.gunId(), opening.displayId(), opening.openingId(), opening.createdAt(), 0L);
    }

    private static void assertRarity(GameTestHelper helper, int roll, CaseRarity expected) {
        int[] values = {roll, 0};
        int[] cursor = {0};
        CaseSkin result = new CaseRoller(bound -> values[cursor[0]++]).roll(CaseWeights.DEFAULT);
        helper.assertTrue(result.rarity() == expected,
                "roll " + roll + " must select " + expected + ", got " + result.rarity());
    }

    private static void assertInvalidRequest(GameTestHelper helper, Runnable action, String label) {
        boolean rejected = false;
        try {
            action.run();
        } catch (WebUiBusinessException expected) {
            rejected = "INVALID_REQUEST".equals(expected.errorCode())
                    && !expected.retrySameOpeningId();
        }
        helper.assertTrue(rejected, label + " must return terminal INVALID_REQUEST");
    }

    private static IEconomyService swapEconomy(IEconomyService replacement) {
        IEconomyService previous = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(replacement);
        return previous;
    }

    private static void restoreEconomy(IEconomyService previous) {
        if (previous == null) {
            EconomyServices.reset();
        } else {
            EconomyServices.registerEconomyService(previous);
        }
    }

    private static Function<UUID, PlayerAbuseState> newStateResolver() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, ignored -> new PlayerAbuseState());
    }
}
