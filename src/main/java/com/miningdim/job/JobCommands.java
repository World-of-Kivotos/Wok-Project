package com.miningdim.job;

import com.miningdim.economy.EconomyServices;
import com.miningdim.economy.IEconomyService;
import com.miningdim.entry.IMiningPlayerData;
import com.miningdim.entry.MiningCapabilities;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * /job 命令树 (JobFramework_Shared_Foundation_DesignSpec 第九章, Brigadier)。独立根 (不挂 /mining 下,
 * 避免 Brigadier 双根冲突 —— 框架 spec 第九章明确)。
 *
 * 玩家级: /job list (各职业等级/经验/当日剩余衰减额度)、/job info &lt;job&gt;。
 * OP 级 (level 2): /job set &lt;player&gt; &lt;job&gt; &lt;level&gt;。权限沿用 OP_LEVEL=2 (与 entry.MiningCommands 一致)。
 *
 * 命令只做参数解析 + 委派 {@link IJobService} / 直接读 entry 唯一权威 capability ({@link MiningCapabilities},
 * 第 2.3 节并入); 业务异常自然冒泡 (用户输入错误在此兜底 sendFailure)。
 */
public final class JobCommands {

    private static final int OP_LEVEL = 2;

    private final JobFrameworkSystem system;

    JobCommands(JobFrameworkSystem system) {
        this.system = system;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("job")
                .then(Commands.literal("list")
                        .executes(this::list))
                .then(Commands.literal("info")
                        .then(Commands.argument("job", StringArgumentType.word())
                                .executes(this::info)))
                .then(Commands.literal("wallet")
                        .executes(this::wallet))
                .then(Commands.literal("set")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .then(Commands.argument("target", EntityArgument.player())
                                .then(Commands.argument("job", StringArgumentType.word())
                                        .then(Commands.argument("level",
                                                        IntegerArgumentType.integer(JobXpCurve.MIN_LEVEL, JobXpCurve.MAX_LEVEL))
                                                .executes(this::set)))));
        dispatcher.register(root);
    }

    private int list(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        IMiningPlayerData data = MiningCapabilities.get(player).orElse(null);
        if (data == null) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.job.no_data"));
            return 0;
        }
        // 日戳只取一次, 八个职业同一口径 (每职业各取一次会让跨午夜的那一行与上一行差一整天)。带日戳的只读
        // 重载: 无日戳版本直接读字段, 玩家昨天吃满额度、今天开工前查询会被告知"额度已用尽"。查询不翻日,
        // 清零权仍独归入账路径。
        long todayStamp = JobServiceImpl.currentUtcDayStamp();
        for (JobId job : JobId.values()) {
            JobProgress p = data.jobProgress(job);
            ctx.getSource().sendSuccess(() -> Component.translatable(
                    "message.miningdim.job.list_line",
                    job.displayName(), p.level(), p.xp(job), p.dailyRemaining(job, todayStamp)), false);
        }
        return JobId.values().length;
    }

    private int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String raw = StringArgumentType.getString(ctx, "job");
        JobId job = JobId.byId(raw);
        if (job == null) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.job.bad_job", raw));
            return 0;
        }
        JobProgress p = MiningCapabilities.get(player).map(d -> d.jobProgress(job)).orElse(null);
        if (p == null) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.job.no_data"));
            return 0;
        }
        JobProgress shown = p;
        // 与 /job list 同一条: 当日已结算经验必须按当前日戳读, 否则跨日后显示的是昨天的量。
        long todayStamp = JobServiceImpl.currentUtcDayStamp();
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "message.miningdim.job.info_line",
                job.displayName(), shown.level(), shown.xp(job),
                JobXpCurve.cumulativeXpForLevel(Math.min(shown.level() + 1, JobXpCurve.MAX_LEVEL)),
                shown.dailyXp(job, todayStamp)), false);
        return 1;
    }

    private int wallet(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        // 临时调试命令: 无前端 Web UI 时让玩家在游戏内看自己的双货币余额 (顶栏余额上线后可移除)。仅查自己, 不收 target。
        if (!EconomyServices.isRegistered()) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.job.no_economy"));
            return 0;
        }
        IEconomyService eco = EconomyServices.economyService();
        long credit = eco.creditBalance(player);
        long azure = eco.heartstoneBalance(player);
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "message.miningdim.job.wallet_line", credit, azure), false);
        return 1;
    }

    private int set(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "target");
        String raw = StringArgumentType.getString(ctx, "job");
        JobId job = JobId.byId(raw);
        if (job == null) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.job.bad_job", raw));
            return 0;
        }
        int level = IntegerArgumentType.getInteger(ctx, "level");
        IMiningPlayerData data = MiningCapabilities.get(target).orElse(null);
        if (data == null) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.job.no_data"));
            return 0;
        }
        data.jobProgress(job).setLevel(level);
        system.syncTo(target); // 改级后立即同步客户端镜像。
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "message.miningdim.job.set_done",
                target.getGameProfile().getName(), job.displayName(), level), true);
        return 1;
    }
}
