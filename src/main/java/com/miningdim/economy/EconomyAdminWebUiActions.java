package com.miningdim.economy;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiErrorCodes;
import com.miningdim.webui.server.WebUiPayloads;
import com.miningdim.webui.server.WebUiPermissions;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * 经济管理台的 admin.economy.* WebUiAction (查某玩家余额 / 把某币余额改成某个绝对值)。
 *
 * 权限 (架构铁律 1): 每条动作各自先过 {@link WebUiPermissions#isOp} —— 派发器这个 Gateway 不做任何权限兜底,
 * hub 面板的 admin 锁只是渲染决策。非 OP 抛 {@link WebUiBusinessException} 而不是裸 IllegalStateException:
 * 后者会落进 Gateway 无 errorCode 的通用分支, 前端只能拿到一句裸文本, 分不清"没权限"与"服务端炸了"。
 *
 * 改钱一律经账本门面 ({@link IEconomyService#grant} / {@link IEconomyService#tryCharge}), 不碰 SQL 也不新开
 * 写入口: 溢出检出、非法金额、先校验后扣这些货币不变量全在账本那一侧, 绕过去等于让它们对管理台失效。
 * "设成绝对值"因此实现为"在同一个事务里读当前值 -&gt; 只补差额", 而不是新加一个 setBalance 写口。
 *
 * 回执带 before (改前双币余额) 是刻意的: 真服没有调账流水表, 面板做出来也查不到历史, 至少让操作者当场看见
 * 改前改后。同一笔调账另落一行服务端日志 (与 {@code /economy grant} 同一个 logger), 那是事后唯一的追溯来源。
 *
 * 目标玩家只按名字解析在线玩家: 名字是会变的快照, 解析不到必须明确报错, 严禁退化成"返回一个空钱包"——
 * 那会让操作者以为对方真有 0 信用点, 进而照着这个假数字调账。
 */
public final class EconomyAdminWebUiActions {

    /** 无 null 字段, 用默认 Gson (回执形状与存量 admin.* 一致)。 */
    private static final Gson GSON = new Gson();

    /** 与 {@code /economy grant} 共用同一个审计 logger: 同一类事实 (谁给谁改了多少钱) 不该散在两个日志名下。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/economy/admin");

    /**
     * 入参金额上界 = JS Number 的无损整数上限 (2^53-1)。
     *
     * 页面送来的金额是 double: 超过这个值的"整数"在到达服务端之前就已经不是操作者输入的那个数了, 照收等于把
     * 一个已经变形的金额写进账本。账本本身的上界是 long, 这里更紧是入参通道的真实限制, 不是业务口径。
     */
    private static final long MAX_SAFE_JSON_INTEGER = 9_007_199_254_740_991L;

    private EconomyAdminWebUiActions() {
    }

    /** 把两条 admin.economy.* action 注册进派发器 (由 {@link EconomySystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("admin.economy.balance", BALANCE);
        WebUiServerDispatcher.register("admin.economy.set", SET);
    }

    // ============================================================
    // admin.economy.balance: {playerName} -> {playerName,playerUuid,wallet}
    // ============================================================

    /** 查某在线玩家的双币余额 (只读, 不动账)。 */
    static final WebUiAction BALANCE = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, "admin.economy.balance");
        ServerPlayer target = resolveOnlineTarget(sender, WebUiPayloads.requiredString(payload, "playerName"));
        IEconomyService economy = EconomyServices.economyService();

        JsonObject result = new JsonObject();
        appendIdentity(result, target);
        result.add("wallet", walletJson(economy, target));
        return GSON.toJson(result);
    };

    // ============================================================
    // admin.economy.set: {playerName,currency,amount} -> {…,currency,before,wallet}
    // ============================================================

    /**
     * 把某玩家某币的余额改成 amount 这个绝对值。
     *
     * 读当前值与补差额必须在同一个事务里: 分开做的话, 中间任何一次别的写入都会让"补的差额"对着一个已经过期的
     * 余额算, 结果是一个谁也没要求过的数字。差额为 0 时一次账本写都不发生 (账本的 grant/tryCharge 都要求金额
     * 严格为正, 硬凑一次 0 元写入只会抛 ILLEGAL_AMOUNT)。
     */
    static final WebUiAction SET = (sender, payload) -> {
        WebUiPermissions.requireOp(sender, "admin.economy.set");
        ServerPlayer target = resolveOnlineTarget(sender, WebUiPayloads.requiredString(payload, "playerName"));
        Currency currency = requiredCurrency(payload, "currency");
        long amount = requiredNonNegativeLong(payload, "amount");
        IEconomyService economy = EconomyServices.economyService();

        // [改前信用点, 改前青辉石, 改后信用点, 改后青辉石]: 事务内一次取齐, 出来再拼 JSON 与写日志,
        // 免得为了一句日志把 IO 塞进事务体。
        long[] balances = economy.inTransaction(() -> {
            long beforeCredit = economy.creditBalance(target);
            long beforeAzure = economy.heartstoneBalance(target);
            long current = currency == Currency.CREDIT ? beforeCredit : beforeAzure;
            long delta = amount - current;
            if (delta > 0L) {
                economy.grant(target, currency, delta);
            } else if (delta < 0L && !economy.tryCharge(target, currency, -delta)) {
                // 同一事务内刚读到的余额扣不动同样大小的差额: 账本在读与写之间被人改了。服务端逻辑单线程单写者下
                // 这不可能发生, 真发生了就是不变量已破, 必须炸出来而不是留下一笔改了一半的调账。
                throw new IllegalStateException("balance changed inside the admin set transaction for "
                        + target.getUUID() + " (" + currency + ")");
            }
            return new long[] {beforeCredit, beforeAzure,
                    economy.creditBalance(target), economy.heartstoneBalance(target)};
        });

        LOGGER.info("[miningdim] economy admin set: issuer={} target={} targetUuid={} currency={} amount={} "
                        + "beforeCredit={} beforeAzure={} afterCredit={} afterAzure={}",
                sender.getGameProfile().getName(), target.getGameProfile().getName(), target.getUUID(),
                currency, amount, balances[0], balances[1], balances[2], balances[3]);

        JsonObject result = new JsonObject();
        appendIdentity(result, target);
        result.addProperty("currency", currency.name());
        result.add("before", walletJson(balances[0], balances[1]));
        result.add("wallet", walletJson(balances[2], balances[3]));
        return GSON.toJson(result);
    };

    // ============================================================
    // 权限 / 目标解析 / 入参
    // ============================================================

    /**
     * OP 门 (每条 admin.* 各自校验, 不依赖任何上游兜底)。
     *
     * errorCode 暂用 {@link WebUiErrorCodes#INVALID_REQUEST}: 错误码表是跨组共享契约, 本组不得擅自加码。
     * 它语义上并不贴切 (这是权限拒绝不是入参非法), 已在交付报告的 blockers 里申请专用的权限拒绝码。
     */

    /**
     * 按名字解析在线玩家。
     *
     * 只认在线玩家: 账本以 UUID 为键, 而离线玩家的"名字 -&gt; UUID"要么查 usercache 的过期快照 (改过名的玩家
     * 会指向别人), 要么打 Mojang API (主线程网络 IO)。两条都不能在这里做, 故解析不到就明确拒绝。
     *
     * 拒绝文案里不回显入参: 名字是客户端可控的超长标量, 原样拼进 message 会把回执撑过下行上限;
     * {@link WebUiPayloads#illegalValue} 只把截断后的值放进 params, 前端据此定位输入框。
     */
    private static ServerPlayer resolveOnlineTarget(ServerPlayer sender, String playerName) {
        ServerPlayer target = sender.getServer().getPlayerList().getPlayerByName(playerName);
        if (target == null) {
            throw WebUiPayloads.illegalValue("playerName", playerName, "找不到该名字对应的在线玩家");
        }
        return target;
    }

    /** 货币枚举入参 (不用 valueOf + catch: 那是拿异常做流程控制, 且拿不到被拒的原值填 params)。 */
    private static Currency requiredCurrency(JsonObject payload, String field) {
        String raw = WebUiPayloads.requiredString(payload, field);
        for (Currency currency : Currency.values()) {
            if (currency.name().equals(raw)) {
                return currency;
            }
        }
        throw WebUiPayloads.illegalValue(field, raw, field + " 只能是 CREDIT 或 AZURE");
    }

    /** 非负整数金额入参 (上界见 {@link #MAX_SAFE_JSON_INTEGER}; 负数是"欠款"语义, 账本没有这个概念故直接拒)。 */
    private static long requiredNonNegativeLong(JsonObject payload, String field) {
        JsonElement raw = WebUiPayloads.requiredField(payload, field);
        if (!raw.isJsonPrimitive() || !raw.getAsJsonPrimitive().isNumber()) {
            throw WebUiPayloads.wrongType(field, "整数");
        }
        double value = raw.getAsDouble();
        // NaN 自动落进第一个条件 (NaN != NaN); 无穷大与超无损域的值落进后两个。
        if (value != Math.floor(value) || value < 0.0D || value > MAX_SAFE_JSON_INTEGER) {
            throw WebUiPayloads.illegalValue(field, raw.getAsJsonPrimitive().getAsString(),
                    field + " 必须是 0 到 " + MAX_SAFE_JSON_INTEGER + " 之间的整数");
        }
        return (long) value;
    }

    // ============================================================
    // JSON helper
    // ============================================================

    /**
     * 身份两栏。playerName 回的是解析到的 GameProfile 真名而不是回显入参: 名字解析大小写不敏感,
     * 原样回显会让面板把 "Steve" 显示成操作者随手敲的 "steve"。uuid 是账本真正的键, 必须一并发。
     */
    private static void appendIdentity(JsonObject result, ServerPlayer target) {
        result.addProperty("playerName", target.getGameProfile().getName());
        result.addProperty("playerUuid", target.getUUID().toString());
    }

    private static JsonObject walletJson(IEconomyService economy, ServerPlayer target) {
        return walletJson(economy.creditBalance(target), economy.heartstoneBalance(target));
    }

    private static JsonObject walletJson(long credit, long azure) {
        JsonObject wallet = new JsonObject();
        wallet.addProperty("credit", credit);
        wallet.addProperty("azure", azure);
        return wallet;
    }
}
