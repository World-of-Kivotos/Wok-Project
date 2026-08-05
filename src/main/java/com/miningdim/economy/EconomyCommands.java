package com.miningdim.economy;

import com.miningdim.core.MiningConstants;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;

/**
 * /economy 命令树 (OP 级运营调账; 由 {@link EconomySystem} 在 RegisterCommandsEvent 调用)。独立根,
 * 范式对齐 {@link com.miningdim.champion.ChampionCommands} (静态工具类 + 根级 requires 权限门)。
 *
 * 当前只提供 {@code /economy set <target> <currency> <amount>}: 把目标玩家某货币余额直接设为指定值。
 * 用途是运营调账与联调 (箱子商店 / 市场等下游消费方需要能凭空造出一个已知余额来验证扣款路径),
 * 不是玩家可见功能 —— 故权限门下在根节点, 非 OP 连补全都看不到。
 *
 * 为何不挂在 {@link com.miningdim.job.JobCommands} 的 /job 下: 货币不是职业框架的职责, /job wallet 是
 * 顶栏余额上线前的临时查询入口, 而调账属经济子系统自身的运维面 (模块化铁律 3: 子系统自注册自己的命令树)。
 *
 * 为何直连账本而不经 {@link IEconomyService}: 门面是业务通道 (grant/tryCharge 受衰减主闸与每日计数约束),
 * 若把 setBalance 加进门面, 任何业务代码都能绕开主闸直接设值 = 印钞后门。调账是独立的运维通道,
 * 故经 {@link EconomyWalletData#setBalance} 直落账本。
 */
public final class EconomyCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/economy");

    private static final int OP_LEVEL = 2;

    /** 补全候选从枚举派生, 新增货币时无需改本类 (硬编码字面量会在加币种时静默漏补全)。 */
    private static final String[] CURRENCY_NAMES = Arrays.stream(Currency.values())
            .map(c -> c.name().toLowerCase(Locale.ROOT))
            .toArray(String[]::new);

    private EconomyCommands() {
    }

    /** 注册 /economy 命令树 (由 {@link EconomySystem} 在 RegisterCommandsEvent 调用)。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("economy")
                .requires(src -> src.hasPermission(OP_LEVEL))
                .then(Commands.literal("set")
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("currency", StringArgumentType.word())
                                        .suggests((ctx, builder) ->
                                                SharedSuggestionProvider.suggest(CURRENCY_NAMES, builder))
                                        // 下界 0 由 Brigadier 拦截 (负余额破坏 PlayerWallet 非负不变量),
                                        // 上界留 Long.MAX_VALUE: 调账本就是运维口, 溢出在 overwriteBalance 侧无风险 (直接设值不做加法)。
                                        .then(Commands.argument("amount", LongArgumentType.longArg(0L))
                                                .executes(EconomyCommands::set)))));
        dispatcher.register(root);
    }

    private static int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        String rawCurrency = StringArgumentType.getString(ctx, "currency");
        Currency currency = parseCurrency(rawCurrency);
        if (currency == null) {
            ctx.getSource().sendFailure(Component.translatable(
                    "message.miningdim.economy.bad_currency", rawCurrency, String.join(" / ", CURRENCY_NAMES)));
            return 0;
        }

        EconomyWalletData ledger = ledgerOf(ctx.getSource());
        if (ledger == null) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.economy.no_ledger"));
            return 0;
        }

        long amount = LongArgumentType.getLong(ctx, "amount");
        long before = ledger.balance(target.getUUID(), currency);
        ledger.setBalance(target.getUUID(), currency, amount);

        // 调账必须留痕: 全库当前无任何资金流水 (见 docs/Economy_Completeness_Audit.md 缺口 5), 而本命令是唯一能
        // 凭空改余额的通道。至少落一条 INFO 记下"谁在何时把谁的余额从多少改到多少", 否则事后无从区分
        // 调账与刷钱 bug。流水表建成后本行应改为写流水。
        LOGGER.info("[miningdim] economy admin set: operator={} target={} currency={} before={} after={}",
                ctx.getSource().getTextName(), target.getGameProfile().getName(), currency, before, amount);

        ctx.getSource().sendSuccess(() -> Component.translatable(
                "message.miningdim.economy.set_done",
                target.getGameProfile().getName(),
                currency.name().toLowerCase(Locale.ROOT),
                before, amount), true);
        return 1;
    }

    /** 大小写不敏感解析货币名; 非法名返回 null 交调用方出用户可读失败 (用户输入错误不该抛异常刷栈)。 */
    static Currency parseCurrency(String raw) {
        for (Currency c : Currency.values()) {
            if (c.name().equalsIgnoreCase(raw)) {
                return c;
            }
        }
        return null;
    }

    /**
     * 取矿山维度账本 (与 {@link EconomySystem#onServerStarted} 同一 computeIfAbsent 实例, 非新建副本)。
     * 矿山维度缺失时返回 null: 那是配置错误 (20.2), 账本无处落盘, 调账必须失败而非落进一个不会持久化的临时账本
     * (静默成功但重启即丢, 比直接失败更难排查)。
     */
    private static EconomyWalletData ledgerOf(CommandSourceStack source) {
        ServerLevel mining = source.getServer().getLevel(MiningConstants.MINING_LEVEL);
        return mining == null ? null : EconomyWalletData.get(mining);
    }
}
