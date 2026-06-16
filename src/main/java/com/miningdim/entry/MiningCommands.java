package com.miningdim.entry;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IResetService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * /mining 命令树 (设计文档 14.1, Brigadier)。玩家级: enter/leave/info; OP 级 (level 2): reset/reset all。
 * 命令是入口层之一: 在此兜底捕获难度解析等用户输入错误并提示 (C9), 业务异常仍自然冒泡到 Brigadier。
 *
 * 命令处理只做参数解析 + 委派给 {@link EntryGateway} / {@link IResetService}; 不内联业务逻辑。
 * 由 {@link EntrySystem} 在 RegisterCommandsEvent 调 {@link #register} 接线; EntrySystem 注入 gateway 引用。
 */
public final class MiningCommands {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/entry");

    /** OP 权限等级 (14.1: reset 需 level 2)。 */
    private static final int OP_LEVEL = 2;

    private final EntrySystem entrySystem;

    MiningCommands(EntrySystem entrySystem) {
        this.entrySystem = entrySystem;
    }

    void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mining")
                // enter <difficulty> [reseed]
                .then(Commands.literal("enter")
                        .then(Commands.argument("difficulty", StringArgumentType.word())
                                .executes(ctx -> enter(ctx, false))
                                .then(Commands.literal("reseed")
                                        .executes(ctx -> enter(ctx, true)))))
                // leave
                .then(Commands.literal("leave")
                        .executes(this::leave))
                // info [instanceId]
                .then(Commands.literal("info")
                        .executes(ctx -> infoSelf(ctx))
                        .then(Commands.argument("instanceId", LongArgumentType.longArg())
                                .executes(ctx -> infoById(ctx, LongArgumentType.getLong(ctx, "instanceId")))))
                // reset <instanceId> [reseed]  (OP)
                .then(Commands.literal("reset")
                        .requires(src -> src.hasPermission(OP_LEVEL))
                        .then(Commands.literal("all")
                                .executes(this::resetAll))
                        .then(Commands.argument("instanceId", LongArgumentType.longArg())
                                .executes(ctx -> reset(ctx, IResetService.ResetMode.SAME_SEED))
                                .then(Commands.literal("reseed")
                                        .executes(ctx -> reset(ctx, IResetService.ResetMode.NEW_SEED)))));

        dispatcher.register(root);
    }

    // ---- 玩家命令 ----

    private int enter(CommandContext<CommandSourceStack> ctx, boolean reseed) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String raw = StringArgumentType.getString(ctx, "difficulty");
        Difficulty difficulty;
        try {
            difficulty = Difficulty.byConfigName(raw);
        } catch (IllegalArgumentException badName) {
            // 用户输入错误: 入口层兜底提示, 不让 IAE 冒泡成红字堆栈。
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.enter.bad_difficulty", raw));
            return 0;
        }
        entrySystem.gateway().requestEnter(player, difficulty, reseed);
        ctx.getSource().sendSuccess(
                () -> Component.translatable("message.miningdim.enter.requested", difficulty.configName()), false);
        return 1;
    }

    private int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        boolean left = entrySystem.leaveToFallback(player);
        if (!left) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.leave.not_inside"));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("message.miningdim.leave.done"), false);
        return 1;
    }

    private int infoSelf(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        long id = MiningCapabilities.get(player)
                .map(IMiningPlayerData::currentInstanceId)
                .orElse(IMiningPlayerData.NO_INSTANCE);
        if (id == IMiningPlayerData.NO_INSTANCE) {
            ctx.getSource().sendSuccess(() -> Component.translatable("message.miningdim.info.not_inside"), false);
            return 0;
        }
        return infoById(ctx, id);
    }

    private int infoById(CommandContext<CommandSourceStack> ctx, long instanceId) {
        InstanceState inst = MiningServices.instanceManager().byId(instanceId).orElse(null);
        if (inst == null) {
            ctx.getSource().sendFailure(Component.translatable("message.miningdim.info.unknown", instanceId));
            return 0;
        }
        ctx.getSource().sendSuccess(() -> Component.translatable("message.miningdim.info.line",
                inst.instanceId(), inst.difficulty().configName(),
                inst.genState().name(), inst.refCount()), false);
        return 1;
    }

    // ---- OP 命令 ----

    private int reset(CommandContext<CommandSourceStack> ctx, IResetService.ResetMode mode) {
        long instanceId = LongArgumentType.getLong(ctx, "instanceId");
        CommandSourceStack src = ctx.getSource();
        // FORCE 路径: 命令重置先撤离在场玩家, 再 reset (13.2 FORCE / 14.1 OP)。
        InstanceState inst = MiningServices.instanceManager().byId(instanceId).orElse(null);
        if (inst == null) {
            src.sendFailure(Component.translatable("message.miningdim.reset.unknown", instanceId));
            return 0;
        }
        if (MiningServices.config().resetKickOnForce() && !inst.playerSet().isEmpty()) {
            MiningServices.resetService().evacuate(inst, src.getServer());
        }
        MiningServices.resetService().reset(instanceId, mode).whenComplete((v, err) -> {
            if (err != null) {
                src.sendFailure(Component.translatable("message.miningdim.reset.failed",
                        instanceId, err.getMessage()));
            } else {
                src.sendSuccess(() -> Component.translatable("message.miningdim.reset.done", instanceId), true);
            }
        });
        src.sendSuccess(() -> Component.translatable("message.miningdim.reset.enqueued", instanceId, mode.name()), true);
        return 1;
    }

    private int resetAll(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        int[] count = {0};
        // 13.6 全局重置: 逐实例串行 (同一限速队列天然串行化, 不并发卸载)。
        MiningServices.instanceManager().snapshot().forEach(inst -> {
            if (!inst.genState().isEnterable()) {
                return;
            }
            if (!inst.playerSet().isEmpty()) {
                MiningServices.resetService().evacuate(inst, src.getServer());
            }
            MiningServices.resetService().reset(inst.instanceId(), IResetService.ResetMode.NEW_SEED);
            count[0]++;
        });
        int total = count[0];
        src.sendSuccess(() -> Component.translatable("message.miningdim.reset.all", total), true);
        LOGGER.info("[miningdim] /mining reset all enqueued {} instance(s)", total);
        return total;
    }
}
