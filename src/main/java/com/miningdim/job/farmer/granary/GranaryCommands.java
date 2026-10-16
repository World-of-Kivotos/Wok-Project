package com.miningdim.job.farmer.granary;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.List;
import java.util.Optional;

/**
 * /granary 命令 (Spoilage_Granary_DesignSpec「界面」「/granary 命令」「/granary audit」)。
 *
 * 三条给玩家、一条给管理:
 * <ul>
 *   <li>{@code /granary status} —— 站着的那座粮仓: 档位、状态、启用/待启用, 以及下一步该干什么
 *       (与账台右键读同一批行, 见 {@link GranaryQuery#statusLines});</li>
 *   <li>{@code /granary bins} —— 我的囤: 位置、启用状态、连在哪座粮仓上, 以及额度用了几格;</li>
 *   <li>{@code /granary rescan} —— 立刻复查站着的那座 (复查节流是自动的, 这条是运营与排障的兜底);</li>
 *   <li>{@code /granary audit} (OP) —— 全服盘点: 每座粮仓一行, 外加「验收人 → 房主」配对。</li>
 * </ul>
 *
 * 规格里 {@code /granary audit} 要列三对配对, 现在只有「验收人 → 房主」这一对是真的 —— 成员表与
 * 开放取用随合建那一批落地, 这里如实写明还缺哪两对, 不假装有。
 */
public final class GranaryCommands {

    /** /granary audit 与 /granary rescan 的 OP 门槛 (与 /farmer admin 一致的管理操作等级)。 */
    private static final int OP_LEVEL = 2;

    private GranaryCommands() {
    }

    /** 农夫模块装配时调用。 */
    public static void register(IEventBus forgeBus) {
        forgeBus.addListener(GranaryCommands::onRegisterCommands);
    }

    public static void onRegisterCommands(RegisterCommandsEvent event) {
        CommandDispatcher<CommandSourceStack> dispatcher = event.getDispatcher();
        dispatcher.register(Commands.literal("granary")
                .then(Commands.literal("status").executes(GranaryCommands::status))
                .then(Commands.literal("bins").executes(GranaryCommands::bins))
                .then(Commands.literal("rescan")
                        .requires(source -> source.hasPermission(OP_LEVEL))
                        .executes(GranaryCommands::rescan))
                .then(Commands.literal("audit")
                        .requires(source -> source.hasPermission(OP_LEVEL))
                        .executes(GranaryCommands::audit)));
    }

    // ============================================================
    // 玩家命令
    // ============================================================

    /** 站着的那座粮仓: 先整体重算一遍再报 (命令与右键账台读的是同一次判定)。 */
    private static int status(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Optional<GranaryQuery.Nearby> nearby = GranaryQuery.nearest(level, player.position());
        if (nearby.isEmpty()) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.none_nearby"));
            return 0;
        }
        GranaryInspection.Report report = GranaryInspection.inspect(level, nearby.get().center());
        for (Component line : GranaryQuery.statusLines(level, report, player)) {
            source.sendSuccess(() -> line, false);
        }
        return 1;
    }

    /** 我的囤: 位置、状态、连在哪座粮仓, 以及额度还剩几个。 */
    private static int bins(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        List<GranaryQuery.BinRow> rows = GranaryQuery.binsOf(level, player.getUUID());
        int farmerLevel = JobServices.jobService().level(player, JobId.FARMER);
        int cap = GranaryBinGuard.capForLevel(farmerLevel);
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.bins_empty"), false);
            return 1;
        }
        source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.bins_header",
                rows.size(), cap, farmerLevel), false);
        for (GranaryQuery.BinRow row : rows) {
            source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.bin_row",
                    GranaryQuery.where(row.pos()),
                    Component.translatable(row.state().translationKey()),
                    row.gid() == 0L
                            ? Component.translatable("granary.tier.none")
                            : Component.literal("#" + row.gid())), false);
        }
        source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.bins_quota",
                GranaryBinGuard.remainingFor(farmerLevel, rows.size(), 0)), false);
        return rows.size();
    }

    // ============================================================
    // 管理命令
    // ============================================================

    /** 立刻复查站着的那座 (不走队列与冷却)。 */
    private static int rescan(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Optional<GranaryQuery.Nearby> nearby = GranaryQuery.nearest(level, player.position());
        if (nearby.isEmpty()) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.none_nearby"));
            return 0;
        }
        GranaryInspection.Report report = GranaryInspection.inspect(level, nearby.get().center());
        source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.rescan",
                report.gid(), GranaryQuery.tierName(report.record().tier()),
                Component.translatable(report.record().status().translationKey())), true);
        return 1;
    }

    /** 全服盘点: 每座粮仓一行 + 「验收人 → 房主」配对。 */
    private static int audit(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getServer().overworld();
        List<GranaryQuery.AuditRow> rows = GranaryQuery.audit(level);
        if (rows.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.audit_none"), false);
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.audit_header",
                rows.size()), false);
        for (GranaryQuery.AuditRow row : rows) {
            GranaryRecord record = row.record();
            source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.audit_row",
                    record.id(),
                    GranaryQuery.tierName(record.tier()),
                    Component.translatable(record.status().translationKey()),
                    row.bins(), row.active(), row.stopped(), row.pending(),
                    GranaryQuery.nameOf(source.getServer(), record.owner())), false);
            if (record.acceptedBy() != null) {
                source.sendSuccess(() -> Component.translatable(
                        "message.miningdim.granary.command.audit_accepted",
                        GranaryQuery.nameOf(source.getServer(), record.acceptedBy()),
                        GranaryQuery.nameOf(source.getServer(), record.owner()),
                        record.acceptedAtHour()), false);
            }
        }
        // 规格要的三对配对里, 现在只有「验收人 → 房主」是真的; 另外两对随合建那一批落地。
        source.sendSuccess(() -> Component.translatable(
                "message.miningdim.granary.command.audit_missing_pairs"), false);
        return rows.size();
    }
}
