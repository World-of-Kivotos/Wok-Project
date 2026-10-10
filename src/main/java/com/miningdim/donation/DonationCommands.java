package com.miningdim.donation;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Item;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.Nullable;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * /donationbox 指令: 界面之外的配套入口, 控制台也能用 (换届交接、远程查账)。
 *
 * 所有子命令都用坐标指定箱子 (BlockPosArgument 在客户端会自动补全准星所指方块的坐标)。权限按箱子判定:
 *  - info / log / coadmin list: 能管理该箱子的人 (箱主、协管、OP);
 *  - coadmin add / remove: 箱主或 OP (协管能进到这一步, 由业务层回"无权调整协管");
 *  - transfer: 仅 OP (指令层 requires 权限等级 2 只是粗筛, 业务层再按玩家本人的 OP 身份判)。
 * "OP" 指玩家本人是 OP, 不看指令来源的等级 (告示牌、任务奖励会把普通玩家的来源抬到 2); 无实体来源只认
 * 真控制台与 RCON (权限等级 4), 命令方块与数据包函数一律按无权处理, 见 {@link #privilegedSource}。
 *
 * 根指令谁都能执行, 所以失败提示本身不能泄露信息: 非 OP 对"区块未加载 / 坐标越界 / 不是捐赠箱 / 无权管理"
 * 只得到同一句提示, 见 {@link #box}。
 */
final class DonationCommands {

    private static final SimpleCommandExceptionType NOT_A_BOX =
            new SimpleCommandExceptionType(Component.translatable("message.miningdim.donation_box.not_a_box"));
    private static final SimpleCommandExceptionType NO_ACCESS =
            new SimpleCommandExceptionType(Component.translatable("message.miningdim.donation_box.no_access"));

    private static final int LOG_PAGE_SIZE = DonationView.PAGE_SIZE;

    /** 服务端控制台与 RCON 的权限等级; 命令方块与数据包函数默认只有 2, 达不到。 */
    private static final int CONSOLE_PERMISSION_LEVEL = 4;

    private DonationCommands() {
    }

    static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("donationbox")
                .then(Commands.literal("info")
                        .then(pos().executes(DonationCommands::info)))
                .then(Commands.literal("log")
                        .then(pos().executes(ctx -> log(ctx, 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> log(ctx, IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.literal("coadmin")
                        .then(Commands.literal("list")
                                .then(pos().executes(DonationCommands::listCoAdmins)))
                        .then(Commands.literal("add")
                                .then(pos().then(Commands.argument("name", StringArgumentType.word())
                                        .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                                ctx.getSource().getOnlinePlayerNames(), builder))
                                        .executes(DonationCommands::addCoAdmin))))
                        .then(Commands.literal("remove")
                                .then(pos().then(Commands.argument("name", StringArgumentType.word())
                                        .executes(DonationCommands::removeCoAdmin)))))
                .then(Commands.literal("transfer")
                        .requires(source -> source.hasPermission(DonationBoxBlockEntity.OPERATOR_PERMISSION_LEVEL))
                        .then(pos().then(Commands.argument("name", StringArgumentType.word())
                                .suggests((ctx, builder) -> SharedSuggestionProvider.suggest(
                                        ctx.getSource().getOnlinePlayerNames(), builder))
                                .executes(DonationCommands::transfer)))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, ?> pos() {
        return Commands.argument("pos", BlockPosArgument.blockPos());
    }

    // ---- 取箱与权限 ----

    /**
     * 取箱并完成访问判定: 返回的箱子调用方一定有权查看 (能管理它)。
     *
     * OP 与控制台 ({@link #privilegedSource}) 拿到细分的错误: 区块未加载、坐标越界、不是捐赠箱。其余来源对"未加载 / 越界 /
     * 不是捐赠箱 / 无权管理"一律只得到同一句 {@link #NO_ACCESS}: 分辨这几种情况等于给了任何人一个远程探针,
     * 按网格扫坐标就能测出别人的活动范围 (区块是否被加载) 和所有捐赠箱的位置。区块是否加载仍要判断, 只是不说
     * 出来, 否则 getBlockEntity 会同步加载区块。
     */
    private static DonationBoxBlockEntity box(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        ServerLevel level = source.getLevel();
        if (privilegedSource(source)) {
            BlockPos pos = BlockPosArgument.getLoadedBlockPos(ctx, "pos");
            if (level.getBlockEntity(pos) instanceof DonationBoxBlockEntity box) {
                return box;
            }
            throw NOT_A_BOX.create();
        }
        ServerPlayer player = actor(source);
        BlockPos pos = BlockPosArgument.getBlockPos(ctx, "pos");
        if (player != null && level.isInWorldBounds(pos) && level.isLoaded(pos)
                && level.getBlockEntity(pos) instanceof DonationBoxBlockEntity box && box.canManage(player)) {
            return box;
        }
        throw NO_ACCESS.create();
    }

    @Nullable
    private static ServerPlayer actor(CommandSourceStack source) {
        return source.getEntity() instanceof ServerPlayer player ? player : null;
    }

    /**
     * 特权来源 = 真控制台 (无实体且权限等级 4: 服务端控制台、RCON) 或者本身就是 OP 的真实玩家。
     *
     * 不能只看 source.hasPermission(2): 告示牌 run_command 以点击者为实体、以固定的权限等级 2 执行, FTB Quests
     * 的 elevate_perms 命令奖励同理; 数据包函数与命令方块没有实体, 默认也是 2。只看来源等级时, 任何玩家点一块
     * 预制告示牌就能对任意坐标查账; 无实体的 2 级来源还会以"控制台"身份增删协管、转让箱主。
     */
    private static boolean privilegedSource(CommandSourceStack source) {
        Entity entity = source.getEntity();
        if (entity == null) {
            return source.hasPermission(CONSOLE_PERMISSION_LEVEL);
        }
        return entity instanceof ServerPlayer player && DonationBoxBlockEntity.isOperator(player);
    }

    // ---- 子命令 ----

    private static int info(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        DonationBoxBlockEntity box = box(ctx);
        Component owner = box.ownerId() == null
                ? Component.translatable("screen.miningdim.donation_box.ledger.no_owner")
                : Component.literal(box.ownerName());
        source.sendSuccess(() -> Component.translatable("message.miningdim.donation_box.info",
                owner, box.coAdmins().size(), DonationBoxBlockEntity.MAX_CO_ADMINS,
                box.storage().usedSlots(), box.storage().getSlots(), box.ledger().size()), false);
        for (DonationLedger.Totals t : box.ledger().totals()) {
            source.sendSuccess(() -> Component.translatable("message.miningdim.donation_box.info.totals",
                    t.playerName(), t.deposited(), t.withdrawn()), false);
        }
        long automation = box.ledger().automationDeposited();
        if (automation > 0L) {
            source.sendSuccess(() -> Component.translatable("message.miningdim.donation_box.info.automation",
                    automation), false);
        }
        return 1;
    }

    private static int log(CommandContext<CommandSourceStack> ctx, int page) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        DonationBoxBlockEntity box = box(ctx);
        List<DonationLedger.Entry> entries = box.ledger().entriesNewestFirst();
        int pages = Math.max(1, (entries.size() + LOG_PAGE_SIZE - 1) / LOG_PAGE_SIZE);
        int shown = Math.min(page, pages);
        source.sendSuccess(() -> Component.translatable("message.miningdim.donation_box.log.header",
                shown, pages, entries.size()), false);
        SimpleDateFormat format = new SimpleDateFormat("MM-dd HH:mm");
        int from = (shown - 1) * LOG_PAGE_SIZE;
        for (int i = from; i < Math.min(entries.size(), from + LOG_PAGE_SIZE); i++) {
            DonationLedger.Entry e = entries.get(i);
            Component actor = e.automation()
                    ? Component.translatable("screen.miningdim.donation_box.ledger.automation")
                    : Component.literal(e.actorName());
            Component action = Component.translatable(e.action() == DonationLedger.Action.DEPOSIT
                    ? "screen.miningdim.donation_box.ledger.deposit"
                    : "screen.miningdim.donation_box.ledger.withdraw");
            Item item = ForgeRegistries.ITEMS.getValue(e.itemId());
            Component itemName = item == null ? Component.literal(e.itemId().toString()) : item.getDescription();
            String time = format.format(new Date(e.timeMillis()));
            source.sendSuccess(() -> Component.translatable("message.miningdim.donation_box.log.entry",
                    time, actor, action, itemName, e.count()), false);
        }
        return entries.size();
    }

    private static int listCoAdmins(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        DonationBoxBlockEntity box = box(ctx);
        String names = box.coAdmins().isEmpty() ? "-" : String.join(", ", box.coAdminNames());
        source.sendSuccess(() -> Component.translatable("message.miningdim.donation_box.coadmin.list",
                box.coAdmins().size(), DonationBoxBlockEntity.MAX_CO_ADMINS, names), false);
        return box.coAdmins().size();
    }

    private static int addCoAdmin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        DonationBoxBlockEntity box = box(ctx);
        return report(source, DonationBoxService.addCoAdmin(box, source.getServer(), actor(source),
                StringArgumentType.getString(ctx, "name")));
    }

    private static int removeCoAdmin(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        DonationBoxBlockEntity box = box(ctx);
        return report(source, DonationBoxService.removeCoAdmin(box, source.getServer(), actor(source),
                StringArgumentType.getString(ctx, "name")));
    }

    private static int transfer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        CommandSourceStack source = ctx.getSource();
        DonationBoxBlockEntity box = box(ctx);
        return report(source, DonationBoxService.transferOwner(box, source.getServer(), actor(source),
                StringArgumentType.getString(ctx, "name")));
    }

    private static int report(CommandSourceStack source, DonationBoxService.Outcome outcome) {
        if (outcome.ok()) {
            source.sendSuccess(outcome::message, true);
            return 1;
        }
        source.sendFailure(outcome.message());
        return 0;
    }
}
