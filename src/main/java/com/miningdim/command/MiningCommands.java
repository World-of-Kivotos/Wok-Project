package com.miningdim.command;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IInstanceManager;
import com.miningdim.core.IMiningConfig;
import com.miningdim.core.IMiningNetwork;
import com.miningdim.core.IResetService;
import com.miningdim.core.InstanceLimitException;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import com.miningdim.trap.StaticTrapKind;
import com.miningdim.trap.TrapDebugPlacement;
import com.miningdim.trap.TrapDisguise;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * /mining 命令树 (设计文档第十七章)。Brigadier 注册在 RegisterCommandsEvent (17.1)。
 *
 * 权限 (17.2): enter/leave/status 玩家级 (level 0); list/tp 管理 (level 2); kick 管理 (level 3);
 * reset/reset all 破坏性 (level 4) + 二次确认 + 冷却 (17.4)。判定经 MiningPermissions (17.5)。
 *
 * 服务端权威 (C5/17.3): 所有传送/分配/重置仅服务端; enter 不接受客户端坐标, 落点由 SpawnSystem 决定。
 * 异常纪律 (C9): 业务异常自然冒泡, 命令 handler 是入口层 —— 在此捕获 InstanceLimitException 等转玩家失败文案,
 * Brigadier 的 CommandSyntaxException 由 dispatcher 统一处理。难度参数用 StringArgumentType + 校验
 * (17.2 给出的两种方案之一; 选 String 路径避免自定义 ArgumentType 需额外注册 ArgumentTypeInfo 才能客户端同步)。
 */
public final class MiningCommands {

    private MiningCommands() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/MiningCommands");

    private static final int PAGE_SIZE = 8;

    /** 由 CommandSystem 在 RegisterCommandsEvent 内调用一次。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("mining")
                .requires(src -> MiningPermissions.has(src, MiningPermissions.LEVEL_PLAYER));

        // ---- 玩家级 (17.2) ----
        root.then(Commands.literal("enter")
                .then(Commands.argument("difficulty", StringArgumentType.word())
                        .suggests((ctx, sb) -> {
                            for (Difficulty d : Difficulty.values()) {
                                sb.suggest(d.configName());
                            }
                            return sb.buildFuture();
                        })
                        .executes(ctx -> enter(ctx, false))
                        .then(Commands.literal("party")
                                .executes(ctx -> enter(ctx, true)))));

        root.then(Commands.literal("leave")
                .executes(MiningCommands::leave));

        root.then(Commands.literal("status")
                .executes(MiningCommands::status));

        // ---- 管理查询 level 2 (17.2) ----
        root.then(Commands.literal("list")
                .requires(src -> MiningPermissions.has(src, MiningPermissions.LEVEL_ADMIN_QUERY))
                .executes(ctx -> list(ctx, 1))
                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                        .executes(ctx -> list(ctx, IntegerArgumentType.getInteger(ctx, "page")))));

        root.then(Commands.literal("tp")
                .requires(src -> MiningPermissions.has(src, MiningPermissions.LEVEL_ADMIN_QUERY))
                .then(Commands.argument("instanceId", LongArgumentType.longArg(0))
                        .executes(MiningCommands::tp)));

        // ---- 管理操作 level 3 (17.2) ----
        root.then(Commands.literal("kick")
                .requires(src -> MiningPermissions.has(src, MiningPermissions.LEVEL_ADMIN_KICK))
                .then(Commands.argument("instanceId", LongArgumentType.longArg(0))
                        .executes(MiningCommands::kickInstance))
                .then(Commands.argument("player", EntityArgument.player())
                        .executes(MiningCommands::kickPlayer)));

        // ---- 破坏性 level 4 (17.2/17.4) ----
        root.then(Commands.literal("reset")
                .requires(src -> MiningPermissions.has(src, MiningPermissions.LEVEL_RESET))
                .then(Commands.literal("all")
                        .then(Commands.literal("confirm")
                                .executes(MiningCommands::resetAllConfirm)))
                .then(Commands.argument("instanceId", LongArgumentType.longArg(0))
                        .executes(MiningCommands::resetArm)
                        .then(Commands.literal("confirm")
                                .executes(MiningCommands::resetConfirm))));

        // ---- 陷阱调试 (管理操作 level 3, 与 kick 同档; 17.2) ----
        // /mining trap place <kind> [skin]: 在准星指向的方块放一颗静态陷阱 (联调触发/探测/连锁全链路)。
        root.then(Commands.literal("trap")
                .requires(src -> MiningPermissions.has(src, MiningPermissions.LEVEL_ADMIN_KICK))
                .then(Commands.literal("place")
                        .then(Commands.argument("kind", StringArgumentType.word())
                                .suggests((ctx, sb) -> {
                                    for (StaticTrapKind kind : StaticTrapKind.values()) {
                                        sb.suggest(kind.getSerializedName());
                                    }
                                    return sb.buildFuture();
                                })
                                .executes(ctx -> trapPlace(ctx, false))
                                .then(Commands.argument("skin", ResourceLocationArgument.id())
                                        .suggests((ctx, sb) -> {
                                            for (Block block : TrapDisguise.disguiseBlocks()) {
                                                sb.suggest(BuiltInRegistries.BLOCK.getKey(block).toString());
                                            }
                                            return sb.buildFuture();
                                        })
                                        .executes(ctx -> trapPlace(ctx, true))))));

        dispatcher.register(root);
    }

    // ---- trap place (调试) ----

    /**
     * 在玩家准星指向的方块放一颗静态陷阱 (伪装矿石 + {@link com.miningdim.trap.TrapRegistry} 登记)。
     * withSkin=false 时按落点所在难度区随机伪装皮肤; true 时用玩家指定的方块 id (须为合法伪装矿石)。
     * 服务端权威: 落点由准星 raytrace 决定 (不接受客户端坐标), 世界写在命令主线程执行。
     */
    private static int trapPlace(CommandContext<CommandSourceStack> ctx, boolean withSkin) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        if (!player.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.trap.place.not_in_mine"));
            return 0;
        }
        ServerLevel level = player.serverLevel();

        String kindName = StringArgumentType.getString(ctx, "kind");
        StaticTrapKind kind = StaticTrapKind.byName(kindName);
        if (kind == null) {
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.trap.place.bad_kind", kindName));
            return 0;
        }

        // 落点: 玩家准星指向的方块 (射程 8, 忽略流体; 原版 Entity.pick 途径, hitFluids=false -> ClipContext.Fluid.NONE)。
        HitResult hit = player.pick(8.0D, 1.0F, false);
        if (hit.getType() != HitResult.Type.BLOCK) {
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.trap.place.no_target"));
            return 0;
        }
        BlockPos pos = ((BlockHitResult) hit).getBlockPos();

        BlockState skin;
        String skinLabel;
        if (withSkin) {
            ResourceLocation skinId = ResourceLocationArgument.getId(ctx, "skin");
            skinLabel = skinId.toString();
            skin = BuiltInRegistries.BLOCK.get(skinId).defaultBlockState();
        } else {
            // 缺省: 按落点所在难度区随机 (复用 pickSkin); 深板岩上下文用简化的 y 分层模型 (deepslateByModel)。
            Difficulty difficulty = Difficulty.forBlock(pos.getX(), pos.getZ());
            if (difficulty == null) {
                ctx.getSource().sendFailure(Component.translatable("commands.miningdim.trap.place.no_difficulty"));
                return 0;
            }
            boolean deepslate = TrapDisguise.deepslateByModel(difficulty, pos.getY());
            skin = TrapDisguise.pickSkin(difficulty, deepslate, level.getRandom());
            skinLabel = BuiltInRegistries.BLOCK.getKey(skin.getBlock()).toString();
        }

        // place 核心以 isDisguiseOre 为契约前置校验; 命令是入口层, 在此捕获非法 skin 转失败文案 (异常止步于入口层, 不冒泡成命令内部错)。
        try {
            TrapDebugPlacement.place(level, pos, kind, skin);
        } catch (IllegalArgumentException badSkin) {
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.trap.place.bad_skin", skinLabel));
            return 0;
        }

        final String label = skinLabel;
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.miningdim.trap.place.success",
                kind.getSerializedName(), label, pos.getX(), pos.getY(), pos.getZ()), true);
        return Command.SINGLE_SUCCESS;
    }

    // ---- enter ----

    private static int enter(CommandContext<CommandSourceStack> ctx, boolean party) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        String raw = StringArgumentType.getString(ctx, "difficulty");
        Difficulty difficulty;
        try {
            difficulty = Difficulty.byConfigName(raw);
        } catch (IllegalArgumentException badName) {
            // 入口层兜底: 非法难度名转明确失败文案, 不让 IAE 冒泡成命令内部错。
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.enter.bad_difficulty", raw));
            return 0;
        }

        IInstanceManager manager = MiningServices.instanceManager();
        IMiningNetwork network = MiningServices.network();

        ctx.getSource().sendSuccess(
                () -> Component.translatable("commands.miningdim.enter.requested", difficulty.configName()), false);

        // allocate 异步: 生成可能在进行中。结果回到主线程后发 TeleportResultS2C; 实际传送由进入流程子系统
        // 在实例 READY 后执行 (14.2), 命令层只发起请求并回执背压/错误 (17.3)。
        manager.allocate(player, difficulty).whenComplete((instance, error) ->
                player.server.execute(() -> {
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        IMiningNetwork.TeleportResult result;
                        String key;
                        if (cause instanceof InstanceLimitException limit) {
                            result = IMiningNetwork.TeleportResult.REJECTED_FULL;
                            key = switch (limit.reason()) {
                                case GLOBAL_CAP -> "commands.miningdim.enter.full";
                                case QUEUE_TIMEOUT -> "commands.miningdim.enter.queue_timeout";
                            };
                        } else {
                            result = IMiningNetwork.TeleportResult.ERROR;
                            key = "commands.miningdim.enter.error";
                        }
                        network.sendTeleportResult(player, result, -1L, -1, key);
                        return;
                    }
                    network.sendInstanceStatus(player, instance, 1.0f);
                    network.sendTeleportResult(player, IMiningNetwork.TeleportResult.SUCCESS,
                            instance.instanceId(), -1, "commands.miningdim.enter.success");
                }));
        return Command.SINGLE_SUCCESS;
    }

    // ---- leave ----

    private static int leave(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        IInstanceManager manager = MiningServices.instanceManager();

        InstanceState current = manager.regionAt((int) player.getX(), (int) player.getZ());
        if (current == null || !player.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.leave.not_in_instance"));
            return 0;
        }
        // 离开汇聚点 (12.6): playerSet 移除、active/lastEmptyTick 维护、唤醒排队。还原坐标 (读 Capability)
        // 由进入流程/撤离子系统在 onPlayerLeave 路径内执行 (D5); 命令层只触发统一汇聚点, 不直接传送 (17.3)。
        long id = current.instanceId();
        manager.onPlayerLeave(id, player);
        ctx.getSource().sendSuccess(
                () -> Component.translatable("commands.miningdim.leave.success", id), false);
        return Command.SINGLE_SUCCESS;
    }

    // ---- status ----

    private static int status(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer player = ctx.getSource().getPlayerOrException();
        IInstanceManager manager = MiningServices.instanceManager();

        InstanceState current = manager.regionAt((int) player.getX(), (int) player.getZ());
        if (current == null || !player.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            ctx.getSource().sendSuccess(
                    () -> Component.translatable("commands.miningdim.status.none"), false);
            return Command.SINGLE_SUCCESS;
        }
        RegionBox box = current.regionBox();
        long id = current.instanceId();
        String diff = current.difficulty().configName();
        String state = current.genState().name();
        int ox = box.originX();
        int oz = box.originZ();
        ctx.getSource().sendSuccess(() -> Component.translatable("commands.miningdim.status.line",
                id, diff, state, ox, oz), false);
        return Command.SINGLE_SUCCESS;
    }

    // ---- list ----

    private static int list(CommandContext<CommandSourceStack> ctx, int page) {
        IInstanceManager manager = MiningServices.instanceManager();
        List<InstanceState> all = new ArrayList<>(manager.snapshot());
        all.sort(Comparator.comparingLong(InstanceState::instanceId));

        int total = all.size();
        int pages = Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
        int clampedPage = Math.min(page, pages);
        int from = (clampedPage - 1) * PAGE_SIZE;
        int to = Math.min(from + PAGE_SIZE, total);

        final int p = clampedPage;
        ctx.getSource().sendSuccess(
                () -> Component.translatable("commands.miningdim.list.header", total, p, pages), false);
        for (int i = from; i < to; i++) {
            InstanceState st = all.get(i);
            long id = st.instanceId();
            String diff = st.difficulty().configName();
            int refs = st.refCount();
            String state = st.genState().name();
            long created = st.createdTick();
            ctx.getSource().sendSuccess(() -> Component.translatable("commands.miningdim.list.entry",
                    id, diff, refs, state, created), false);
        }
        return total;
    }

    // ---- tp (管理巡查) ----

    private static int tp(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer admin = ctx.getSource().getPlayerOrException();
        long instanceId = LongArgumentType.getLong(ctx, "instanceId");
        IInstanceManager manager = MiningServices.instanceManager();
        Optional<InstanceState> opt = manager.byId(instanceId);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.notfound", instanceId));
            return 0;
        }
        InstanceState instance = opt.get();
        MinecraftServer server = ctx.getSource().getServer();
        ServerLevel mining = server.getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.no_dimension"));
            return 0;
        }
        // 巡查落点: region 水平中心、全高中点 (非玩家出生点; 出生点需体素视图, 巡查用几何中心即可)。
        RegionBox box = instance.regionBox();
        double cx = box.originX() + box.sizeX() / 2.0;
        double cz = box.originZ() + box.sizeZ() / 2.0;
        double cy = (MiningConstants.REGION_FULL_MIN_WORLD_Y + MiningConstants.REGION_FULL_MAX_WORLD_Y) / 2.0;
        // 世界写 (传送) 在服务端主线程; 命令本就在主线程执行, 直接 teleportTo 安全。
        admin.teleportTo(mining, cx, cy, cz, admin.getYRot(), admin.getXRot());
        ctx.getSource().sendSuccess(
                () -> Component.translatable("commands.miningdim.tp.success", instanceId), true);
        return Command.SINGLE_SUCCESS;
    }

    // ---- kick ----

    private static int kickInstance(CommandContext<CommandSourceStack> ctx) {
        long instanceId = LongArgumentType.getLong(ctx, "instanceId");
        IInstanceManager manager = MiningServices.instanceManager();
        IResetService reset = MiningServices.resetService();
        Optional<InstanceState> opt = manager.byId(instanceId);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.notfound", instanceId));
            return 0;
        }
        InstanceState instance = opt.get();
        int count = instance.refCount();
        // 撤离全部玩家回各自进入前坐标 (读 Capability, 14.6/D5); evacuate 在主线程传送。
        reset.evacuate(instance, ctx.getSource().getServer());
        ctx.getSource().sendSuccess(
                () -> Component.translatable("commands.miningdim.kick.instance", count, instanceId), true);
        return count;
    }

    private static int kickPlayer(CommandContext<CommandSourceStack> ctx) throws CommandSyntaxException {
        ServerPlayer target = EntityArgument.getPlayer(ctx, "player");
        IInstanceManager manager = MiningServices.instanceManager();

        InstanceState instance = manager.regionAt((int) target.getX(), (int) target.getZ());
        if (instance == null || !target.level().dimension().equals(MiningConstants.MINING_LEVEL)) {
            ctx.getSource().sendFailure(
                    Component.translatable("commands.miningdim.kick.player_not_in", target.getGameProfile().getName()));
            return 0;
        }
        // 单玩家踢出走统一离开汇聚点 (12.6); 坐标还原在 onPlayerLeave 路径内执行 (D5)。
        long id = instance.instanceId();
        String name = target.getGameProfile().getName();
        manager.onPlayerLeave(id, target);
        ctx.getSource().sendSuccess(
                () -> Component.translatable("commands.miningdim.kick.player", name, id), true);
        return Command.SINGLE_SUCCESS;
    }

    // ---- reset (二次确认 + 冷却, 17.4) ----

    private static int resetArm(CommandContext<CommandSourceStack> ctx) {
        long instanceId = LongArgumentType.getLong(ctx, "instanceId");
        IInstanceManager manager = MiningServices.instanceManager();
        IMiningConfig config = MiningServices.config();

        Optional<InstanceState> opt = manager.byId(instanceId);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.notfound", instanceId));
            return 0;
        }
        InstanceState instance = opt.get();
        if (config.resetRequireEmpty() && instance.refCount() > 0 && !config.resetKickOnForce()) {
            // requireEmpty 且不允许强制踢人: 有人则拒绝 (17.4 在场保护)。
            int refs = instance.refCount();
            ctx.getSource().sendFailure(
                    Component.translatable("commands.miningdim.reset.occupied", instanceId, refs));
            return 0;
        }
        long now = ctx.getSource().getServer().getTickCount();
        ResetConfirmations.arm(instanceId, now);
        int window = config.resetConfirmationWindowSeconds();
        ctx.getSource().sendSuccess(() -> Component.translatable(
                "commands.miningdim.reset.armed", instanceId, window), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int resetConfirm(CommandContext<CommandSourceStack> ctx) {
        long instanceId = LongArgumentType.getLong(ctx, "instanceId");
        IInstanceManager manager = MiningServices.instanceManager();
        IResetService reset = MiningServices.resetService();
        IMiningConfig config = MiningServices.config();

        Optional<InstanceState> opt = manager.byId(instanceId);
        if (opt.isEmpty()) {
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.notfound", instanceId));
            return 0;
        }
        long now = ctx.getSource().getServer().getTickCount();
        int window = config.resetConfirmationWindowSeconds();
        if (!ResetConfirmations.confirm(instanceId, now, window)) {
            // 无未超窗意图: 提示需先发起 (17.4 超时作废)。
            ctx.getSource().sendFailure(Component.translatable("commands.miningdim.reset.no_pending", instanceId));
            return 0;
        }

        InstanceState instance = opt.get();
        // kickOnForceReset: confirm 阶段先清场再重置 (17.4 在场保护)。
        if (instance.refCount() > 0 && config.resetKickOnForce()) {
            reset.evacuate(instance, ctx.getSource().getServer());
        }

        // 审计日志 (17.4): 记录执行者、instanceId、tick。
        String who = ctx.getSource().getTextName();
        LOGGER.info("[miningdim] reset confirmed by {} on instance {} at tick {}", who, instanceId, now);

        ctx.getSource().sendSuccess(
                () -> Component.translatable("commands.miningdim.reset.start", instanceId), true);
        // NEW_SEED: 命令触发的重置默认换图 (运维清场重排布局); SAME_SEED 留给确定性验收/自动化。
        CommandSourceStack source = ctx.getSource();
        reset.reset(instanceId, IResetService.ResetMode.NEW_SEED).whenComplete((ignored, error) ->
                source.getServer().execute(() -> {
                    if (error != null) {
                        Throwable cause = unwrap(error);
                        String detail = cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
                        source.sendFailure(Component.translatable(
                                "commands.miningdim.reset.failed", instanceId, detail));
                        return;
                    }
                    source.sendSuccess(
                            () -> Component.translatable("commands.miningdim.reset.done", instanceId), true);
                }));
        return Command.SINGLE_SUCCESS;
    }

    private static int resetAllConfirm(CommandContext<CommandSourceStack> ctx) {
        IInstanceManager manager = MiningServices.instanceManager();
        IResetService reset = MiningServices.resetService();

        List<Long> ids = new ArrayList<>();
        manager.forEach(st -> ids.add(st.instanceId()));

        long now = ctx.getSource().getServer().getTickCount();
        String who = ctx.getSource().getTextName();
        LOGGER.info("[miningdim] reset ALL confirmed by {} ({} instances) at tick {}", who, ids.size(), now);

        ctx.getSource().sendSuccess(
                () -> Component.translatable("commands.miningdim.reset.all_start", ids.size()), true);

        // 逐实例串行 (13/18.2): 同一限速队列天然串行, 不并发卸载导致 IO 风暴。先清场再重置。
        MinecraftServer server = ctx.getSource().getServer();
        for (long id : ids) {
            manager.byId(id).ifPresent(st -> {
                if (st.refCount() > 0) {
                    reset.evacuate(st, server);
                }
            });
            reset.reset(id, IResetService.ResetMode.NEW_SEED);
        }
        return ids.size();
    }

    // ---- helpers ----

    /** CompletableFuture 把异常包成 CompletionException; 取真实因以分类 (不掩盖, 仅解壳)。 */
    private static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }
}
