package com.miningdim.economy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.core.MiningConstants;
import com.miningdim.testutil.MockGameTestPlayers;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * W10 经济管理台两条 admin.economy.* action 的 GameTest。
 *
 * 四条主线:
 *  1. OP 门: 非 OP 拿到的是<b>带 errorCode 的业务拒绝</b>而不是裸异常 (裸异常在前端只是一句无法本地化的文本),
 *     且被拒的 set 一分钱都不许改;
 *  2. 名字解析走服务端 PlayerList (大小写不敏感), 回执发的是解析到的真名与 uuid, 不是回显入参; 解析不到必须
 *     明确报错, 严禁返回空钱包冒充成功 —— 那会让操作者照着一个假的 0 去调账;
 *  3. set 是真的经账本改钱 (查账本行本身, 不只看回执), 且只动指定的那一种货币;
 *  4. 回执必带 before: 真服无调账流水表, 面板查不到历史, 改前值只有这一次机会被看见。
 */
@GameTestHolder(MiningConstants.MODID)
@PrefixGameTestTemplate(false)
public final class EconomyAdminWebUiGameTests {

    private static final String EMPTY = "empty";
    private static final String BATCH = "webui_economy_admin";

    private static final String BALANCE_ACTION = "admin.economy.balance";
    private static final String SET_ACTION = "admin.economy.set";

    // ============================================================
    // 1. OP 门: 业务拒绝 + 零副作用
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminEconomyActionsRejectNonOpWithBusinessError(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        try {
            ServerPlayer sender = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
            ServerPlayer target = resolveTarget(helper, sender);
            EconomyServices.economyService().grant(target, Currency.CREDIT, 800L);
            helper.assertTrue(!helper.getLevel().getServer().getPlayerList().isOp(sender.getGameProfile()),
                    "前提校验: 新建的 mock 玩家不是 OP");

            // rejection() 只接住 WebUiBusinessException: 实现若照抄 MarketAdminActions 抛 IllegalStateException,
            // 异常会穿过本用例直接把它判失败 —— 这正是本条要锁的差别。
            WebUiBusinessException balanceRejected =
                    rejection(helper, BALANCE_ACTION, sender, namePayload(target));
            helper.assertTrue(!balanceRejected.errorCode().isBlank(),
                    "非 OP 调 admin.economy.balance 必须拿到带 errorCode 的业务拒绝");
            WebUiBusinessException setRejected =
                    rejection(helper, SET_ACTION, sender, setPayload(target, Currency.CREDIT, 1L));
            helper.assertTrue(!setRejected.errorCode().isBlank(),
                    "非 OP 调 admin.economy.set 必须拿到带 errorCode 的业务拒绝");
            helper.assertTrue(ledger.balance(target.getUUID(), Currency.CREDIT) == 800L,
                    "被 OP 门拒的 set 一分钱都不许改, 实得 " + ledger.balance(target.getUUID(), Currency.CREDIT));
            helper.succeed();
        } finally {
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 2. balance: 按名字解析 + 读真实账本行
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void balanceResolvesByNameAndReadsTheLedgerRow(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        ServerPlayer sender = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            helper.getLevel().getServer().getPlayerList().op(sender.getGameProfile());
            ServerPlayer target = resolveTarget(helper, sender);
            EconomyServices.economyService().grant(target, Currency.CREDIT, 12_345L);
            EconomyServices.economyService().grant(target, Currency.AZURE, 7L);

            // 故意用全大写送: 解析走服务端 PlayerList (大小写不敏感), 回执必须发解析到的真名。
            JsonObject payload = new JsonObject();
            payload.addProperty("playerName", target.getGameProfile().getName().toUpperCase(java.util.Locale.ROOT));
            JsonObject result = handle(helper, BALANCE_ACTION, sender, payload);

            helper.assertTrue(target.getGameProfile().getName().equals(result.get("playerName").getAsString()),
                    "playerName 必须回解析到的 GameProfile 真名而不是回显入参, 实得 "
                            + result.get("playerName").getAsString());
            helper.assertTrue(target.getUUID().toString().equals(result.get("playerUuid").getAsString()),
                    "playerUuid 是账本真正的键, 必须一并发");
            JsonObject wallet = result.getAsJsonObject("wallet");
            helper.assertTrue(wallet.get("credit").getAsLong() == 12_345L,
                    "credit 必须是账本真实余额 12345, 实得 " + wallet.get("credit").getAsLong());
            helper.assertTrue(wallet.get("azure").getAsLong() == 7L,
                    "azure 必须是账本真实余额 7, 实得 " + wallet.get("azure").getAsLong());
            helper.succeed();
        } finally {
            helper.getLevel().getServer().getPlayerList().deop(sender.getGameProfile());
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 3. set: 真落账本 + before/after + 只动一种货币
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void setWritesThroughTheLedgerAndReportsBefore(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        ServerPlayer sender = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            helper.getLevel().getServer().getPlayerList().op(sender.getGameProfile());
            ServerPlayer target = resolveTarget(helper, sender);
            EconomyServices.economyService().grant(target, Currency.CREDIT, 1_000L);
            EconomyServices.economyService().grant(target, Currency.AZURE, 9L);

            // 调低: 1000 -> 250 (走扣费侧补差额)。
            JsonObject down = handle(helper, SET_ACTION, sender, setPayload(target, Currency.CREDIT, 250L));
            helper.assertTrue(down.getAsJsonObject("before").get("credit").getAsLong() == 1_000L,
                    "before.credit 必须是改前值 1000, 实得 " + down.getAsJsonObject("before").get("credit").getAsLong());
            helper.assertTrue(down.getAsJsonObject("before").get("azure").getAsLong() == 9L,
                    "before 是完整双币快照, azure 也要发改前值 9");
            helper.assertTrue(down.getAsJsonObject("wallet").get("credit").getAsLong() == 250L,
                    "改后余额必须正好是入参的绝对值 250, 实得 "
                            + down.getAsJsonObject("wallet").get("credit").getAsLong());
            helper.assertTrue(down.getAsJsonObject("wallet").get("azure").getAsLong() == 9L,
                    "只动指定的那一种货币, 青辉石必须原封不动 (9)");
            helper.assertTrue("CREDIT".equals(down.get("currency").getAsString()),
                    "回执必须回显被改的币种");
            helper.assertTrue(ledger.balance(target.getUUID(), Currency.CREDIT) == 250L,
                    "改动必须真落账本 (不是只改回执), 账本实得 "
                            + ledger.balance(target.getUUID(), Currency.CREDIT));

            // 调高: 250 -> 5000 (走入账侧补差额)。
            JsonObject up = handle(helper, SET_ACTION, sender, setPayload(target, Currency.CREDIT, 5_000L));
            helper.assertTrue(up.getAsJsonObject("before").get("credit").getAsLong() == 250L,
                    "第二次调账的 before 必须是上一次的结果 250, 实得 "
                            + up.getAsJsonObject("before").get("credit").getAsLong());
            helper.assertTrue(up.getAsJsonObject("wallet").get("credit").getAsLong() == 5_000L
                            && ledger.balance(target.getUUID(), Currency.CREDIT) == 5_000L,
                    "调高同样落账本 (5000), 账本实得 " + ledger.balance(target.getUUID(), Currency.CREDIT));

            // 清零青辉石: 边界值 0 必须能设 (扣到刚好 0), 且不碰信用点。
            JsonObject zero = handle(helper, SET_ACTION, sender, setPayload(target, Currency.AZURE, 0L));
            helper.assertTrue(zero.getAsJsonObject("wallet").get("azure").getAsLong() == 0L
                            && ledger.balance(target.getUUID(), Currency.AZURE) == 0L,
                    "青辉石可被设成 0, 账本实得 " + ledger.balance(target.getUUID(), Currency.AZURE));
            helper.assertTrue(zero.getAsJsonObject("wallet").get("credit").getAsLong() == 5_000L,
                    "改青辉石不得动信用点 (仍是 5000)");

            // 设成与当前值相同: 不改任何东西, 且 before 与 wallet 同值 (差额为 0 时一次账本写都不发生)。
            JsonObject same = handle(helper, SET_ACTION, sender, setPayload(target, Currency.CREDIT, 5_000L));
            helper.assertTrue(same.getAsJsonObject("before").get("credit").getAsLong() == 5_000L
                            && same.getAsJsonObject("wallet").get("credit").getAsLong() == 5_000L,
                    "设成同值是合法的空操作, before 与 wallet 都应是 5000");
            helper.succeed();
        } finally {
            helper.getLevel().getServer().getPlayerList().deop(sender.getGameProfile());
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 4. 坏入参: 解析不到的名字 / 负数 / 非法币种 一律拒且零副作用
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void setRejectsUnknownPlayerNegativeAmountAndBadCurrency(GameTestHelper helper) {
        SqliteEconomyLedger ledger = SqliteEconomyLedger.openInMemory();
        IEconomyService prev = swapEconomy(new EconomyService(ledger, new AbuseGuard(), newStateResolver()));
        ServerPlayer sender = MockGameTestPlayers.makeMockServerPlayerWithChannel(helper);
        try {
            helper.getLevel().getServer().getPlayerList().op(sender.getGameProfile());
            ServerPlayer target = resolveTarget(helper, sender);
            EconomyServices.economyService().grant(target, Currency.CREDIT, 640L);

            // 改过名的玩家在快照里就是这个样子: 名字解析不到必须报错, 不许返回空钱包冒充成功。
            JsonObject unknown = new JsonObject();
            unknown.addProperty("playerName", "definitely-offline-player");
            unknown.addProperty("currency", "CREDIT");
            unknown.addProperty("amount", 1L);
            WebUiBusinessException noSuchPlayer = rejection(helper, SET_ACTION, sender, unknown);
            helper.assertTrue("playerName".equals(noSuchPlayer.params().get("field")),
                    "解析不到玩家时必须用 params.field=playerName 指出是哪个输入被拒, 实得 "
                            + noSuchPlayer.params().get("field"));
            helper.assertTrue(rejection(helper, BALANCE_ACTION, sender, unknown).params().get("field") != null,
                    "admin.economy.balance 对同一个解析不到的名字也必须拒");

            JsonObject negative = setPayload(target, Currency.CREDIT, -1L);
            helper.assertTrue("amount".equals(rejection(helper, SET_ACTION, sender, negative).params().get("field")),
                    "负数金额必须被拒并指明 field=amount (账本没有欠款语义)");

            JsonObject badCurrency = setPayload(target, Currency.CREDIT, 10L);
            badCurrency.addProperty("currency", "COPPER");
            helper.assertTrue("currency".equals(
                            rejection(helper, SET_ACTION, sender, badCurrency).params().get("field")),
                    "非法币种必须被拒并指明 field=currency");

            helper.assertTrue(ledger.balance(target.getUUID(), Currency.CREDIT) == 640L,
                    "三次被拒的请求一分钱都不许改, 实得 " + ledger.balance(target.getUUID(), Currency.CREDIT));
            helper.succeed();
        } finally {
            helper.getLevel().getServer().getPlayerList().deop(sender.getGameProfile());
            restoreEconomy(prev);
        }
    }

    // ============================================================
    // 5. 注册名
    // ============================================================

    @GameTest(templateNamespace = MiningConstants.MODID, template = EMPTY, batch = BATCH)
    public static void adminEconomyActionsAreRegisteredUnderContractNames(GameTestHelper helper) {
        ensureAdminActionsRegistered();
        for (String name : new String[] {BALANCE_ACTION, SET_ACTION}) {
            helper.assertTrue(WebUiServerDispatcher.resolve(name) != null,
                    name + " 必须由 EconomyAdminWebUiActions.registerAll 注册进派发器");
        }
        helper.assertTrue(WebUiServerDispatcher.resolve("admin.economy.grant") == null,
                "管理台只有 balance/set 两条 action, 没有 grant (发放走 /economy grant 命令)");
        helper.succeed();
    }

    // ============================================================
    // 工具
    // ============================================================

    /**
     * 注册守卫。<b>刻意不在测试侧补注册</b>: 补上了, "EconomySystem.register 忘了调 EconomyAdminWebUiActions.registerAll"
     * 这一类装配缺陷就永远测不出来 —— 把生产侧那一行删掉, 本文件全绿, 而真服上前端调 admin.economy.* action 只会拿到
     * 派发器的 "unknown Web UI action" 失败回执, 整个面板全黑。
     *
     * 没注册就是 EconomySystem.register 的接线掉了, 直接炸。
     */
    private static void ensureAdminActionsRegistered() {
        if (WebUiServerDispatcher.resolve(BALANCE_ACTION) == null) {
            throw new IllegalStateException(
                    "admin.economy.* action 未注册: EconomySystem.register 没有调用 EconomyAdminWebUiActions.registerAll");
        }
    }

    private static JsonObject handle(GameTestHelper helper, String action, ServerPlayer sender, JsonObject payload) {
        return JsonParser.parseString(resolveAction(helper, action).handle(sender, payload)).getAsJsonObject();
    }

    /** 调 action 并要求它抛业务拒绝; 没抛就地判失败 (返回值必非 null, 调用方可直接取 params)。 */
    private static WebUiBusinessException rejection(GameTestHelper helper, String action,
                                                    ServerPlayer sender, JsonObject payload) {
        try {
            resolveAction(helper, action).handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            return rejected;
        }
        helper.fail("该请求本应被业务拒绝, 实际却成功返回了: " + payload);
        throw new IllegalStateException("unreachable: helper.fail already threw");
    }

    private static WebUiAction resolveAction(GameTestHelper helper, String action) {
        ensureAdminActionsRegistered();
        WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 未注册进派发器");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return handler;
    }

    /**
     * 服务端按名字会解析到的那个在线玩家。
     *
     * mock 玩家的 GameProfile 名字是固定的 (MockGameTestPlayers 写死 test-mock-player), 同名玩家可能不止一个,
     * 故断言的期望值必须取 PlayerList 自己会解析到的那一个, 而不是想当然地用 sender。
     */
    private static ServerPlayer resolveTarget(GameTestHelper helper, ServerPlayer sender) {
        ServerPlayer target = helper.getLevel().getServer().getPlayerList()
                .getPlayerByName(sender.getGameProfile().getName());
        if (target == null) {
            helper.fail("前提校验失败: PlayerList 里找不到刚建好的 mock 玩家");
            throw new IllegalStateException("unreachable: helper.fail already threw");
        }
        return target;
    }

    private static JsonObject namePayload(ServerPlayer target) {
        JsonObject payload = new JsonObject();
        payload.addProperty("playerName", target.getGameProfile().getName());
        return payload;
    }

    private static JsonObject setPayload(ServerPlayer target, Currency currency, long amount) {
        JsonObject payload = namePayload(target);
        payload.addProperty("currency", currency.name());
        payload.addProperty("amount", amount);
        return payload;
    }

    private static IEconomyService swapEconomy(IEconomyService fake) {
        IEconomyService prev = EconomyServices.isRegistered() ? EconomyServices.economyService() : null;
        EconomyServices.registerEconomyService(fake);
        return prev;
    }

    private static void restoreEconomy(IEconomyService prev) {
        if (prev != null) {
            EconomyServices.registerEconomyService(prev);
        } else {
            EconomyServices.reset();
        }
    }

    /** 测试用玩家态解析器 (与 EconomySystem.playerState 同纪律: 未知 UUID 惰性建态)。 */
    private static Function<UUID, PlayerAbuseState> newStateResolver() {
        Map<UUID, PlayerAbuseState> states = new HashMap<>();
        return id -> states.computeIfAbsent(id, k -> new PlayerAbuseState());
    }
}
