package com.miningdim.job.farmer.granary;

import com.miningdim.job.JobId;
import com.miningdim.job.JobServices;
import com.miningdim.job.farmer.granary.block.GranaryBinBlockEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * /granary 命令 (Spoilage_Granary_DesignSpec「界面」「/granary 命令」「/granary audit」)。
 *
 * 三条给玩家、一条给管理:
 * <ul>
 *   <li>{@code /granary status} —— 站着的那座粮仓: 档位、状态、启用/待启用, 以及下一步该干什么
 *       (与账台右键读同一批行, 见 {@link GranaryQuery#statusLines});</li>
 *   <li>{@code /granary bins} —— 我的囤: 位置、启用状态、连在哪座粮仓上, 以及额度用了几格;</li>
 *   <li>{@code /granary rescan} —— 立刻复查站着的那座 (复查节流是自动的, 这条是运营与排障的兜底);</li>
 *   <li>{@code /granary audit} (OP) —— 全服盘点: 每座粮仓一行, 外加「验收人 → 房主」与
 *       「成员 → 房主」两种配对。</li>
 * </ul>
 *
 * 规格里 {@code /granary audit} 要列三对配对, 现在「验收人 → 房主」与「成员 → 房主」都是真的 ——
 * 只剩「把囤开放成可取给别人」随开放名单那一批落地, 这里如实写明还缺哪一对, 不假装有。
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
                .then(Commands.literal("deposit").executes(GranaryCommands::deposit))
                .then(Commands.literal("keephotbar")
                        .executes(GranaryCommands::keepHotbarQuery)
                        .then(Commands.argument("value", BoolArgumentType.bool())
                                .executes(GranaryCommands::keepHotbarSet)))
                .then(Commands.literal("claim").executes(GranaryCommands::claim))
                .then(Commands.literal("member")
                        .then(Commands.literal("list").executes(GranaryCommands::memberList))
                        .then(Commands.literal("add")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> memberChange(context, true))))
                        .then(Commands.literal("remove")
                                .then(Commands.argument("player", EntityArgument.player())
                                        .executes(context -> memberChange(context, false))))
                        .then(Commands.literal("leave").executes(GranaryCommands::memberLeave)))
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
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        int pendingBins = data.pendingBins(player.getUUID());
        if (rows.isEmpty()) {
            if (pendingBins > 0) {
                source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.bins_pending",
                        pendingBins), false);
                return 1;
            }
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
        if (pendingBins > 0) {
            source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.bins_pending",
                    pendingBins), false);
        }
        source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.bins_quota",
                GranaryBinGuard.remainingFor(farmerLevel, rows.size() + pendingBins, 0)), false);
        return rows.size();
    }

    // ============================================================
    // 待领粮单
    // ============================================================

    /**
     * 领取待领粮单 (Spoilage_Granary_DesignSpec「待领粮单怎么领」)。
     *
     * 不用站在粮仓边上 (东西本来就是自己的, 被移出的人多半也进不去那间屋子了); 一次领不完的留在单子上,
     * 额度也照占。
     */
    private static int claim(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel overworld = player.getServer().overworld();
        GranarySavedData data = GranarySavedData.get(overworld);
        if (data.pendingBins(player.getUUID()) == 0) {
            source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.claim_empty"), false);
            return 0;
        }
        GranaryPending.ClaimResult result =
                GranaryPending.claim(data, player, GranaryClock.nowMinute(overworld));
        if (result.claimed() == 0) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.claim_full",
                    result.remaining()));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.claim",
                result.claimed(), result.claimedBins(), result.remaining()), false);
        return result.claimed();
    }

    // ============================================================
    // 一键存入 (按钮做出来之前先用命令触发)
    // ============================================================

    /**
     * 一键存入: 把背包里的易腐品按**启用顺序**依次放进自己在这座粮仓里的囤。
     *
     * 与规格里的按钮是同一个入口 ({@link GranaryDeposit#deposit}): 界面做出来之后按钮直接调它,
     * 判定与提示原样复用; 「保留快捷栏」按玩家记住 (persistentData), 这里只读它。
     */
    private static int deposit(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Optional<GranaryQuery.Nearby> nearby = GranaryQuery.nearest(level, player.position());
        if (nearby.isEmpty()) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.none_nearby"));
            return 0;
        }
        List<GranaryQuery.BinRow> mine = new ArrayList<>();
        for (GranaryQuery.BinRow row : GranaryQuery.binsOf(level, nearby.get().gid())) {
            if (player.getUUID().equals(GranarySavedData.get(level.getServer().overworld())
                    .ownerOf(row.dimension(), row.pos()))) {
                mine.add(row);
            }
        }
        if (mine.isEmpty()) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.deposit_no_bins"));
            return 0;
        }
        int items = 0;
        int stacks = 0;
        int exhausted = 0;
        int fresh = 0;
        int rotten = 0;
        boolean blocked = false;
        for (GranaryQuery.BinRow row : mine) {
            if (!(level.getBlockEntity(row.pos()) instanceof GranaryBinBlockEntity bin)) {
                continue;
            }
            GranaryDeposit.Result result = GranaryDeposit.deposit(level, bin, player);
            if (!result.accepted()) {
                blocked = true;
                continue;
            }
            items += result.items();
            stacks += result.stacks();
            exhausted += result.skips().exhausted();
            fresh += result.skips().fresh();
            rotten += result.skips().rotten();
        }
        if (stacks == 0 && blocked) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.deposit_blocked"));
            return 0;
        }
        int finalItems = items;
        int finalStacks = stacks;
        int finalExhausted = exhausted;
        int finalFresh = fresh;
        int finalRotten = rotten;
        source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.deposit",
                finalItems, finalStacks, finalExhausted, finalFresh, finalRotten), false);
        source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.deposit_hint"),
                false);
        return items;
    }

    /** 看「保留快捷栏」现在是开还是关。 */
    private static int keepHotbarQuery(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        boolean keep = GranaryDeposit.keepsHotbar(player);
        context.getSource().sendSuccess(() -> Component.translatable(
                "message.miningdim.granary.command.keephotbar", onOff(keep)), false);
        return keep ? 1 : 0;
    }

    /** 设置「保留快捷栏」(按玩家记住, 规格: 玩家 persistentData)。 */
    private static int keepHotbarSet(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        boolean keep = BoolArgumentType.getBool(context, "value");
        GranaryDeposit.setKeepsHotbar(player, keep);
        context.getSource().sendSuccess(() -> Component.translatable(
                "message.miningdim.granary.command.keephotbar_set", onOff(keep)), false);
        return keep ? 1 : 0;
    }

    private static Component onOff(boolean value) {
        return Component.translatable(value
                ? "message.miningdim.granary.command.on" : "message.miningdim.granary.command.off");
    }

    // ============================================================
    // 成员 (入册 / 移出 / 退出 / 名单)
    // ============================================================

    /** 成员页 (文字版): 每人几个囤、启用几个 —— 对所有成员公开, 不显示里面装了什么。 */
    private static int memberList(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Optional<GranaryQuery.Nearby> nearby = GranaryQuery.nearest(level, player.position());
        if (nearby.isEmpty()) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.none_nearby"));
            return 0;
        }
        GranaryRecord record = nearby.get().record();
        if (record == null) {
            source.sendFailure(Component.translatable("message.miningdim.granary.not_a_granary"));
            return 0;
        }
        source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.member_header",
                record.id(), record.members().size(),
                GranaryQuery.nameOf(source.getServer(), record.owner())), false);
        List<UUID> everyone = new ArrayList<>();
        everyone.add(record.owner());
        everyone.addAll(record.members());
        for (UUID member : everyone) {
            List<GranaryQuery.BinRow> bins = GranaryQuery.binsOf(level, nearby.get().gid()).stream()
                    .filter(row -> member.equals(GranarySavedData.get(level.getServer().overworld())
                            .ownerOf(row.dimension(), row.pos())))
                    .toList();
            long active = bins.stream().filter(row -> row.state() == GranaryBinState.ACTIVE).count();
            source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.member_row",
                    GranaryQuery.nameOf(source.getServer(), member),
                    member.equals(record.owner())
                            ? Component.translatable("granary.member.owner")
                            : Component.translatable("granary.member.member"),
                    bins.size(), active), false);
        }
        return everyone.size();
    }

    /** 入册 / 移出: 只有房主与 OP 能做 (规格的权限表)。 */
    private static int memberChange(CommandContext<CommandSourceStack> context, boolean add)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Optional<GranaryQuery.Nearby> nearby = GranaryQuery.nearest(level, player.position());
        if (nearby.isEmpty()) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.none_nearby"));
            return 0;
        }
        GranaryRecord record = nearby.get().record();
        if (record == null) {
            source.sendFailure(Component.translatable("message.miningdim.granary.not_a_granary"));
            return 0;
        }
        // 权限判在**命令源**上 (与 audit / rescan 的 requires 同一把尺): 玩家的权限等级由服务端按
        // 「在线会话」决定, 而命令源上的那份是这次执行的权威, /op 与权限插件都作用在它上面。
        if (!player.getUUID().equals(record.owner()) && !source.hasPermission(OP_LEVEL)) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.member_not_owner"));
            return 0;
        }
        ServerPlayer target = EntityArgument.getPlayer(context, "player");
        if (target.getUUID().equals(record.owner())) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.member_is_owner"));
            return 0;
        }
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        GranaryRecord updated = add ? record.withMember(target.getUUID())
                : record.withoutMember(target.getUUID());
        data.putGranary(updated);
        // 移出成员: 对方的囤连同里面的东西一起进他自己的待领粮单 (规格「退出、拆走、移出」表),
        // 东西不掉在地上、房主也拿不走; 被移出的那一份在单子里照常停表 7 天 (不是自己造成的)。
        int moved = add ? 0 : takeDownBinsOf(level, updated, target.getUUID(),
                GranaryPending.Reason.REMOVED);
        // 入册/移出之后立刻重算一次: 囤的启用名单跟着成员身份走, 不能让玩家等到下一次复查。
        GranaryInspection.inspect(level, nearby.get().center());
        reportMemberChange(source, add, target.getUUID(), record.owner(), moved, false);
        return add ? 1 : 0;
    }

    /** 成员自己退出 (房主不能退自己的粮仓: 要么转让要么拆掉, 那是另外两条路)。 */
    private static int memberLeave(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level = player.serverLevel();
        Optional<GranaryQuery.Nearby> nearby = GranaryQuery.nearest(level, player.position());
        if (nearby.isEmpty()) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.none_nearby"));
            return 0;
        }
        GranaryRecord record = nearby.get().record();
        if (record == null) {
            source.sendFailure(Component.translatable("message.miningdim.granary.not_a_granary"));
            return 0;
        }
        if (player.getUUID().equals(record.owner())) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.member_owner_cannot_leave"));
            return 0;
        }
        if (!record.isMember(player.getUUID())) {
            source.sendFailure(Component.translatable("message.miningdim.granary.command.member_not_member"));
            return 0;
        }
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        GranaryRecord updated = record.withoutMember(player.getUUID());
        data.putGranary(updated);
        // 自己退出: 东西一样进自己的待领粮单, 但**不停表** —— 是自己选的 (反漏洞 #66)。
        int moved = takeDownBinsOf(level, updated, player.getUUID(), GranaryPending.Reason.LEFT);
        GranaryInspection.inspect(level, nearby.get().center());
        reportMemberChange(source, false, player.getUUID(), record.owner(), moved, true);
        return 1;
    }

    /**
     * 把某人在某座粮仓里的囤全部连东西一起搬进他自己的待领粮单。
     *
     * 方块所在的区块**临时读一次** (规格「远程打包」那一节: /granary leave 与 /granary pack 会一次性加载
     * 囤所在的区块) —— 命令是玩家主动发的, 而且不读就不知道里面有什么, 这时候读一次是应该的。
     *
     * @return 搬走的囤数
     */
    private static int takeDownBinsOf(ServerLevel level, GranaryRecord record, UUID owner,
                                      GranaryPending.Reason reason) {
        GranarySavedData data = GranarySavedData.get(level.getServer().overworld());
        long nowHour = GranaryClock.nowHour(level.getServer().overworld());
        int moved = 0;
        for (GranarySavedData.PosKey key : List.copyOf(data.binsOf(record.id()))) {
            if (!owner.equals(data.ownerOf(key.dimension(), key.pos()))) {
                continue;
            }
            level.getChunk(key.pos());
            if (GranaryTransfer.takeDown(level, key.pos(), reason, nowHour) != null) {
                moved++;
            }
        }
        return moved;
    }

    /** 入册 / 移出的回执 (搬了囤就说清搬了几个、去了哪、还停不停表)。 */
    private static void reportMemberChange(CommandSourceStack source, boolean add, UUID member, UUID owner,
                                           int moved, boolean selfInitiated) {
        if (add) {
            source.sendSuccess(() -> Component.translatable("message.miningdim.granary.command.member_added",
                    GranaryQuery.nameOf(source.getServer(), member),
                    GranaryQuery.nameOf(source.getServer(), owner)), true);
            return;
        }
        if (moved == 0) {
            source.sendSuccess(() -> Component.translatable(selfInitiated
                            ? "message.miningdim.granary.command.member_left"
                            : "message.miningdim.granary.command.member_removed",
                    selfInitiated ? GranaryQuery.nameOf(source.getServer(), owner)
                            : GranaryQuery.nameOf(source.getServer(), member),
                    GranaryQuery.nameOf(source.getServer(), owner)), true);
            return;
        }
        source.sendSuccess(() -> Component.translatable(selfInitiated
                        ? "message.miningdim.granary.command.member_took_away"
                        : "message.miningdim.granary.command.member_moved",
                moved, GranaryQuery.nameOf(source.getServer(), member),
                GranaryGrace.DAMAGE_HOURS / 24L), true);
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
    private static int audit(CommandContext<CommandSourceStack> context) {        CommandSourceStack source = context.getSource();
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
            // 成员配对: 谁在这座粮仓里有囤。房主隐含是成员, 不在这里重复列。
            for (UUID member : record.members()) {
                source.sendSuccess(() -> Component.translatable(
                        "message.miningdim.granary.command.audit_member",
                        GranaryQuery.nameOf(source.getServer(), member),
                        GranaryQuery.nameOf(source.getServer(), record.owner())), false);
            }
        }
        // 规格要的三对配对里, 现在只剩「把囤开放成可取给别人」随开放名单那一批落地。
        source.sendSuccess(() -> Component.translatable(
                "message.miningdim.granary.command.audit_missing_pairs"), false);
        return rows.size();
    }
}
