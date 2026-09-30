package com.miningdim.district.command;

import com.miningdim.district.DistrictFeature;
import com.miningdim.district.DistrictLimits;
import com.miningdim.district.DistrictServices;
import com.miningdim.district.access.Actor;
import com.miningdim.district.core.Academy;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.SeenPlayer;
import com.miningdim.district.flan.BufferClaimReport;
import com.miningdim.district.flan.DistrictReconciler;
import com.miningdim.district.flan.FlanPermissionPolicy;
import com.miningdim.district.flan.PersonalClaim;
import com.miningdim.district.flan.ReconcileScheduler;
import com.miningdim.district.flan.real.FlanCompat;
import com.miningdim.district.flan.real.FlanHookTargets;
import com.miningdim.district.guard.DistrictWorldGuards;
import com.miningdim.district.guard.DistrictZoneSnapshot;
import com.miningdim.district.guard.GuardMixinStatus;
import com.miningdim.district.guard.GuardSettings;
import com.miningdim.district.guard.PersonalClaimGuard;
import com.miningdim.district.guard.create.CreateBlockPolicy;
import com.miningdim.district.guard.create.CreateHookTargets;
import com.miningdim.district.guard.create.CreateMachineScan;
import com.miningdim.district.service.DistrictAdminService;
import com.miningdim.district.service.DistrictContext;
import com.miningdim.district.service.ResidentService;
import com.miningdim.district.store.DistrictRepository;
import com.miningdim.district.store.DistrictStoreException;
import com.miningdim.store.MiningStoreException;
import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.coordinates.ColumnPosArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ColumnPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * /district OP 引导命令 (设计文档第十六章): 建区、改范围、区规、任免区务长、批量加住户、列表与排障。与平板调同一批服务方法,
 * 同一套规则与记录。
 *
 * <ul>
 *   <li>根节点要求 {@code hasPermission(2)}, 玩家与控制台都能用。</li>
 *   <li>玩家名一律用 {@link StringArgumentType#word()} 读入, 由服务层按"在线 -> 见过的玩家 -> 旧存档"解析; 不用
 *       GameProfileArgument (它会走阻塞的名字查询, GameTest 服上还会空指针)。</li>
 *   <li>坐标用 {@link ColumnPosArgument} (支持 {@code ~}); 维度默认取执行者所在的世界。</li>
 *   <li>业务拒绝 ({@link DistrictRuleException}) 原文转成 sendFailure; 数据库失败统一回"本次操作没有生效"。</li>
 *   <li>解绑、定价、开放购买、冻结处置只在平板上做 (那里有确认框); 命令只负责引导和排障。</li>
 * </ul>
 * 每条会改动数据的命令写一行审计 INFO (服务层另有自己的审计行)。
 */
public final class DistrictCommands {

    private static final Logger AUDIT = LoggerFactory.getLogger("miningdim/district");

    private static final int OP_LEVEL = 2;
    private static final String KEY = "district.miningdim.command.";
    private static final DateTimeFormatter LOG_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.ROOT)
            .withZone(ZoneId.systemDefault());

    /** /district info 显示的记录条数。 */
    static final int INFO_LOG_LINES = 10;

    /** /district inspect 每类差异列几条。 */
    static final int INSPECT_LINES = 10;

    /** /district personalclaims 至多列几块 (22.20)。 */
    static final int PERSONAL_CLAIM_LINES = 20;

    private static final SuggestionProvider<CommandSourceStack> ACADEMIES = (context, builder) ->
            DistrictServices.isRegistered()
                    ? SharedSuggestionProvider.suggest(DistrictServices.context().repo().academies().stream()
                    .map(Academy::academyId), builder)
                    : builder.buildFuture();

    private static final SuggestionProvider<CommandSourceStack> LIVE_DISTRICTS = (context, builder) ->
            DistrictServices.isRegistered()
                    ? SharedSuggestionProvider.suggest(DistrictServices.context().repo().liveDistricts().stream()
                    .map(DistrictRecord::districtId), builder)
                    : builder.buildFuture();

    private DistrictCommands() {
    }

    /** 由 DistrictSystem 在 RegisterCommandsEvent 中注册。 */
    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(root());
    }

    static LiteralArgumentBuilder<CommandSourceStack> root() {
        return Commands.literal("district")
                .requires(source -> source.hasPermission(OP_LEVEL))
                .then(Commands.literal("academy")
                        .then(Commands.literal("list").executes(DistrictCommands::academyList))
                        .then(Commands.literal("members")
                                .then(Commands.argument("academyId", StringArgumentType.word())
                                        .suggests(ACADEMIES)
                                        .executes(DistrictCommands::academyMembers)))
                        .then(Commands.literal("kick")
                                .then(Commands.argument("academyId", StringArgumentType.word())
                                        .suggests(ACADEMIES)
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(DistrictCommands::academyKick)))))
                .then(Commands.literal("create")
                        .then(Commands.argument("academyId", StringArgumentType.word())
                                .suggests(ACADEMIES)
                                .then(Commands.argument("from", ColumnPosArgument.columnPos())
                                        .then(Commands.argument("to", ColumnPosArgument.columnPos())
                                                .executes(context -> create(context, false, false))
                                                .then(Commands.argument("dimension", DimensionArgument.dimension())
                                                        .executes(context -> create(context, true, false))
                                                        .then(Commands.argument("unitPrice", IntegerArgumentType
                                                                        .integer(1, DistrictLimits.MAX_UNIT_PRICE))
                                                                .executes(context -> create(context, true,
                                                                        true))))))))
                .then(Commands.literal("bounds")
                        .then(Commands.argument("districtId", StringArgumentType.word())
                                .suggests(LIVE_DISTRICTS)
                                .then(Commands.literal("sync").executes(DistrictCommands::boundsSync))
                                .then(Commands.argument("from", ColumnPosArgument.columnPos())
                                        .then(Commands.argument("to", ColumnPosArgument.columnPos())
                                                .executes(DistrictCommands::bounds)))))
                .then(Commands.literal("bind")
                        .then(Commands.argument("academyId", StringArgumentType.word())
                                .suggests(ACADEMIES)
                                .then(Commands.argument("pos", ColumnPosArgument.columnPos())
                                        .executes(context -> bind(context, false))
                                        .then(Commands.literal("confirm").executes(context -> bind(context, true))))))
                .then(Commands.literal("claim")
                        .then(Commands.argument("districtId", StringArgumentType.word())
                                .suggests(LIVE_DISTRICTS)
                                .then(Commands.literal("recreate").executes(DistrictCommands::recreate))
                                .then(Commands.literal("relink")
                                        .then(Commands.argument("pos", ColumnPosArgument.columnPos())
                                                .executes(context -> relink(context, false))
                                                .then(Commands.literal("confirm")
                                                        .executes(context -> relink(context, true)))))))
                .then(Commands.literal("status").executes(DistrictCommands::status))
                .then(Commands.literal("inspect")
                        .then(Commands.argument("districtId", StringArgumentType.word())
                                .suggests(LIVE_DISTRICTS)
                                .executes(DistrictCommands::inspect)))
                .then(Commands.literal("rules")
                        .then(Commands.argument("districtId", StringArgumentType.word())
                                .suggests(LIVE_DISTRICTS)
                                .then(Commands.literal("add")
                                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                                .executes(DistrictCommands::rulesAdd)))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("index", IntegerArgumentType.integer(1))
                                                .executes(DistrictCommands::rulesRemove)))
                                .then(Commands.literal("clear").executes(DistrictCommands::rulesClear))))
                .then(Commands.literal("warden")
                        .then(Commands.argument("districtId", StringArgumentType.word())
                                .suggests(LIVE_DISTRICTS)
                                .then(Commands.literal("set")
                                        .then(Commands.argument("name", StringArgumentType.word())
                                                .executes(context -> warden(context,
                                                        StringArgumentType.getString(context, "name")))))
                                .then(Commands.literal("clear").executes(context -> warden(context, null)))))
                .then(Commands.literal("residents")
                        .then(Commands.argument("districtId", StringArgumentType.word())
                                .suggests(LIVE_DISTRICTS)
                                .then(Commands.literal("add")
                                        .then(Commands.argument("names", StringArgumentType.greedyString())
                                                .executes(context -> residentsAdd(context, false))))
                                .then(Commands.literal("addUnseen")
                                        .then(Commands.argument("names", StringArgumentType.greedyString())
                                                .executes(context -> residentsAdd(context, true))))))
                .then(Commands.literal("list").executes(DistrictCommands::list))
                .then(Commands.literal("info")
                        .then(Commands.argument("districtId", StringArgumentType.word())
                                .suggests(LIVE_DISTRICTS)
                                .executes(DistrictCommands::info)))
                .then(Commands.literal("plots")
                        .then(Commands.argument("districtId", StringArgumentType.word())
                                .suggests(LIVE_DISTRICTS)
                                .executes(DistrictCommands::plots)))
                .then(Commands.literal("machines")
                        .then(Commands.argument("districtId", StringArgumentType.word())
                                .suggests(LIVE_DISTRICTS)
                                .executes(DistrictCommands::machines)))
                .then(Commands.literal("personalclaims")
                        .then(Commands.argument("districtId", StringArgumentType.word())
                                .suggests(LIVE_DISTRICTS)
                                .executes(DistrictCommands::personalClaims)))
                .then(Commands.literal("sweep").executes(DistrictCommands::sweep))
                .then(Commands.literal("resync")
                        .then(Commands.argument("districtId", StringArgumentType.word())
                                .suggests(LIVE_DISTRICTS)
                                .executes(DistrictCommands::resync)));
    }

    // ================================================================
    // 学院
    // ================================================================

    private static int academyList(CommandContext<CommandSourceStack> context) {
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            DistrictRepository repo = ctx.repo();
            List<Academy> academies = repo.academies();
            source.sendSuccess(() -> Component.translatable(KEY + "academy.list.header", academies.size()), false);
            for (Academy academy : academies) {
                Component bound = repo.liveDistrictOfAcademy(academy.academyId())
                        .<Component>map(d -> Component.literal(d.districtId()))
                        .orElseGet(() -> Component.translatable(KEY + "academy.list.unbound"));
                int members = repo.countMembers(academy.academyId());
                source.sendSuccess(() -> Component.translatable(KEY + "academy.list.entry", academy.academyId(),
                        academy.shortName(), academy.fullName(), bound, members), false);
            }
            return Math.max(1, academies.size());
        });
    }

    private static int academyMembers(CommandContext<CommandSourceStack> context) {
        String academyId = StringArgumentType.getString(context, "academyId");
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            Optional<Academy> academy = ctx.repo().academy(academyId);
            if (academy.isEmpty()) {
                return unknownAcademy(source, ctx, academyId);
            }
            List<MemberRecord> members = ctx.repo().membersOf(academyId);
            source.sendSuccess(() -> Component.translatable(KEY + "academy.members.header",
                    academy.get().fullName(), members.size()), false);
            if (ctx.repo().liveDistrictOfAcademy(academyId).isEmpty()) {
                source.sendSuccess(() -> Component.translatable(KEY + "academy.members.unbound"), false);
            }
            for (MemberRecord member : members) {
                source.sendSuccess(() -> Component.translatable(KEY + "academy.members.entry", member.name(),
                        syncLabel(member.syncStatus().wire()), member.addedByName(),
                        LOG_TIME.format(Instant.ofEpochMilli(member.joinedAt()))), false);
            }
            return Math.max(1, members.size());
        });
    }

    private static int academyKick(CommandContext<CommandSourceStack> context) {
        String academyId = StringArgumentType.getString(context, "academyId");
        String name = StringArgumentType.getString(context, "name");
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            DistrictAdminService.CommandResult result = ctx.admin().kickFromUnboundAcademy(actorOf(source),
                    academyId, name);
            audit(source, "academy kick " + academyId + " " + name, result.outcome().name());
            return switch (result.outcome()) {
                case OK -> success(source, Component.translatable(KEY + "academy.kick.done", first(result, name),
                        academyId));
                case ACADEMY_UNKNOWN -> unknownAcademy(source, ctx, academyId);
                case ACADEMY_STILL_BOUND -> failure(source, Component.translatable(KEY + "academy.kick.still_bound",
                        academyId, first(result, "?")));
                default -> failure(source, Component.translatable(KEY + "academy.kick.not_member", name, academyId));
            };
        });
    }

    // ================================================================
    // 建区、改范围、区规
    // ================================================================

    private static int create(CommandContext<CommandSourceStack> context, boolean withDimension, boolean withPrice)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String academyId = StringArgumentType.getString(context, "academyId");
        ColumnPos from = ColumnPosArgument.getColumnPos(context, "from");
        ColumnPos to = ColumnPosArgument.getColumnPos(context, "to");
        String dimension = withDimension
                ? DimensionArgument.getDimension(context, "dimension").dimension().location().toString()
                : source.getLevel().dimension().location().toString();
        Integer unitPrice = withPrice ? IntegerArgumentType.getInteger(context, "unitPrice") : null;
        DistrictBounds bounds = DistrictBounds.ofCorners(dimension, from.x(), from.z(), to.x(), to.z());
        return run(context, ctx -> {
            DistrictAdminService.CommandResult result = ctx.admin().createDistrict(actorOf(source), academyId,
                    bounds, unitPrice);
            audit(source, "create " + academyId + " " + boundsText(bounds), result.outcome().name());
            return switch (result.outcome()) {
                case OK -> {
                    DistrictRecord district = result.district();
                    if (district == null) {
                        throw new IllegalStateException("createDistrict reported OK without a district row");
                    }
                    int done = success(source, Component.translatable(KEY + "create.done", academyId,
                            district.districtId(), district.displayName(), boundsText(district.bounds()),
                            district.unitPrice()));
                    machineSummary(source, ctx, district);
                    personalClaimSummary(source, ctx, district);
                    yield done;
                }
                case ACADEMY_UNKNOWN -> unknownAcademy(source, ctx, academyId);
                case ACADEMY_ALREADY_BOUND -> failure(source, Component.translatable(KEY + "create.already_bound",
                        academyId, first(result, "?")));
                case OVERLAPS_DISTRICT -> failure(source, Component.translatable(KEY + "overlaps_district",
                        first(result, "?")));
                case FLAN_CLAIM_EXISTS -> failure(source, Component.translatable(KEY + "flan_claim_exists",
                        String.join("、", result.detail())));
                default -> failure(source, Component.translatable(KEY + "bounds_invalid"));
            };
        });
    }

    private static int bounds(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String districtId = StringArgumentType.getString(context, "districtId");
        ColumnPos from = ColumnPosArgument.getColumnPos(context, "from");
        ColumnPos to = ColumnPosArgument.getColumnPos(context, "to");
        return run(context, ctx -> {
            Optional<DistrictRecord> district = ctx.repo().liveDistrict(districtId);
            if (district.isEmpty()) {
                return unknownDistrict(source, districtId);
            }
            // 改范围不改维度: 自管区换维度等于重新建区。
            DistrictBounds bounds = DistrictBounds.ofCorners(district.get().bounds().dimension(), from.x(), from.z(),
                    to.x(), to.z());
            DistrictAdminService.CommandResult result = ctx.admin().setBounds(actorOf(source), districtId, bounds);
            audit(source, "bounds " + districtId + " " + boundsText(bounds), result.outcome().name());
            return switch (result.outcome()) {
                case OK -> success(source, Component.translatable(KEY + "bounds.done", districtId,
                        boundsText(bounds)));
                case DISTRICT_NOT_FOUND -> unknownDistrict(source, districtId);
                case OVERLAPS_DISTRICT -> failure(source, Component.translatable(KEY + "overlaps_district",
                        first(result, "?")));
                case PLOTS_OUTSIDE -> failure(source, Component.translatable(KEY + "bounds.plots_outside",
                        result.detail().size(), DistrictLimits.EDGE_GAP, String.join("、", result.detail())));
                case BOUNDS_FOLLOW_CLAIM -> failure(source, Component.translatable(KEY + "bounds.follow_claim",
                        districtId, districtId));
                default -> failure(source, Component.translatable(KEY + "bounds_invalid"));
            };
        });
    }

    /** /district bounds &lt;id&gt; sync: 读父领地的范围, 校验后写库 (20.6)。 */
    private static int boundsSync(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String districtId = StringArgumentType.getString(context, "districtId");
        return run(context, ctx -> {
            DistrictAdminService.CommandResult result = ctx.admin().syncBoundsFromClaim(actorOf(source), districtId);
            audit(source, "bounds " + districtId + " sync", result.outcome().name());
            return switch (result.outcome()) {
                case OK -> {
                    DistrictRecord district = result.district();
                    String bounds = district == null ? "?" : boundsText(district.bounds());
                    int done = result.detail().contains("unchanged")
                            ? success(source, Component.translatable(KEY + "bounds.sync.unchanged", districtId, bounds))
                            : success(source, Component.translatable(KEY + "bounds.sync.done", districtId, bounds));
                    if (district != null) {
                        machineSummary(source, ctx, district);
                        personalClaimSummary(source, ctx, district);
                    }
                    yield done;
                }
                case DISTRICT_NOT_FOUND -> unknownDistrict(source, districtId);
                case FLAN_UNAVAILABLE -> flanUnavailable(source, ctx);
                case CLAIM_NOT_FOUND -> failure(source, Component.translatable(KEY + "claim_not_found",
                        first(result, "?")));
                case OVERLAPS_DISTRICT -> failure(source, Component.translatable(KEY + "overlaps_district",
                        first(result, "?")));
                case PLOTS_OUTSIDE -> failure(source, Component.translatable(KEY + "bounds.sync.plots_outside",
                        result.detail().size(), DistrictLimits.EDGE_GAP, String.join("、", result.detail())));
                default -> failure(source, Component.translatable(KEY + "bounds_invalid"));
            };
        });
    }

    // ================================================================
    // 绑定已有领地、重建父领地
    // ================================================================

    /**
     * /district bind &lt;academyId&gt; &lt;pos&gt; [confirm] (20.6): 维度取执行者所在的世界 (控制台为主世界; 别的维度用
     * /execute in &lt;维度&gt; run district bind …)。不带 confirm 只预览, 返回 1; 带 confirm 绑定并收编。
     */
    private static int bind(CommandContext<CommandSourceStack> context, boolean confirm) throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String academyId = StringArgumentType.getString(context, "academyId");
        ColumnPos pos = ColumnPosArgument.getColumnPos(context, "pos");
        String dimension = source.getLevel().dimension().location().toString();
        return run(context, ctx -> {
            DistrictAdminService.CommandResult result = ctx.admin().bindDistrict(actorOf(source), academyId,
                    dimension, pos.x(), pos.z(), confirm);
            audit(source, "bind " + academyId + " " + dimension + " " + pos.x() + " " + pos.z()
                    + (confirm ? " confirm" : ""), result.outcome().name());
            return switch (result.outcome()) {
                case PREVIEW -> {
                    List<String> lines = result.detail();
                    source.sendSuccess(() -> Component.translatable(KEY + "bind.preview.header", academyId), false);
                    for (String line : lines.subList(0, Math.max(0, lines.size() - 1))) {
                        source.sendSuccess(() -> Component.translatable(KEY + "bind.preview.line", line), false);
                    }
                    personalClaimPreview(source, ctx, result.previewBounds());
                    String subclaims = lines.isEmpty() ? "?" : lines.get(lines.size() - 1);
                    source.sendSuccess(() -> Component.translatable(KEY + "bind.preview.confirm", subclaims), false);
                    yield 1;
                }
                case OK -> {
                    DistrictRecord district = result.district();
                    if (district == null) {
                        throw new IllegalStateException("bindDistrict reported OK without a district row");
                    }
                    int done = success(source, Component.translatable(KEY + "bind.done",
                            String.valueOf(district.flanClaimId()), academyId, district.districtId(),
                            boundsText(district.bounds())));
                    machineSummary(source, ctx, district);
                    personalClaimSummary(source, ctx, district);
                    yield done;
                }
                case ACADEMY_UNKNOWN -> unknownAcademy(source, ctx, academyId);
                case ACADEMY_ALREADY_BOUND -> failure(source, Component.translatable(KEY + "create.already_bound",
                        academyId, first(result, "?")));
                case FLAN_UNAVAILABLE -> flanUnavailable(source, ctx);
                case CLAIM_NOT_FOUND -> failure(source, Component.translatable(KEY + "claim_not_found",
                        first(result, "?")));
                case CLAIM_NOT_ADMIN -> failure(source, Component.translatable(KEY + "claim_not_admin",
                        first(result, "?")));
                case CLAIM_NOT_FULL_HEIGHT -> failure(source, Component.translatable(KEY + "claim_not_full_height",
                        first(result, "?")));
                case CLAIM_ALREADY_BOUND -> failure(source, Component.translatable(KEY + "claim_already_bound",
                        first(result, "?")));
                case OVERLAPS_DISTRICT -> failure(source, Component.translatable(KEY + "overlaps_district",
                        first(result, "?")));
                default -> failure(source, Component.translatable(KEY + "bounds_invalid"));
            };
        });
    }

    /**
     * /district claim &lt;id&gt; relink &lt;pos&gt; [confirm] (20.6): 本区的父领地在 Flan 里找不到了、那片地上却有一块管理员
     * 领地时, 改认那一列上的这块 (位置取本区所在的维度)。不带 confirm 只预览, 返回 1; 带 confirm 写库并 resync。
     */
    private static int relink(CommandContext<CommandSourceStack> context, boolean confirm)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        String districtId = StringArgumentType.getString(context, "districtId");
        ColumnPos pos = ColumnPosArgument.getColumnPos(context, "pos");
        return run(context, ctx -> {
            DistrictAdminService.CommandResult result = ctx.admin().relinkDistrictClaim(actorOf(source), districtId,
                    pos.x(), pos.z(), confirm);
            audit(source, "claim " + districtId + " relink " + pos.x() + " " + pos.z() + (confirm ? " confirm" : ""),
                    result.outcome().name());
            return switch (result.outcome()) {
                case PREVIEW -> {
                    List<String> lines = result.detail();
                    source.sendSuccess(() -> Component.translatable(KEY + "claim.relink.preview.header", districtId),
                            false);
                    for (String line : lines.subList(0, Math.max(0, lines.size() - 1))) {
                        source.sendSuccess(() -> Component.translatable(KEY + "bind.preview.line", line), false);
                    }
                    personalClaimPreview(source, ctx, result.previewBounds());
                    String claim = lines.isEmpty() ? "?" : lines.get(lines.size() - 1);
                    source.sendSuccess(() -> Component.translatable(KEY + "claim.relink.preview.confirm", claim),
                            false);
                    yield 1;
                }
                case OK -> {
                    DistrictRecord district = result.district();
                    int done = success(source, Component.translatable(KEY + "claim.relink.done", districtId,
                            first(result, "?"), district == null ? "?" : boundsText(district.bounds()),
                            result.detail().size() > 1 ? result.detail().get(1) : "?",
                            result.detail().size() > 2 ? result.detail().get(2) : "?"));
                    if (district != null) {
                        personalClaimSummary(source, ctx, district);
                    }
                    yield done;
                }
                case DISTRICT_NOT_FOUND -> unknownDistrict(source, districtId);
                case FLAN_UNAVAILABLE -> flanUnavailable(source, ctx);
                case CLAIM_PRESENT -> failure(source, Component.translatable(KEY + "claim_present", districtId,
                        first(result, "?")));
                case CLAIM_NOT_FOUND -> failure(source, Component.translatable(KEY + "claim_not_found",
                        first(result, "?")));
                case CLAIM_NOT_ADMIN -> failure(source, Component.translatable(KEY + "claim_not_admin",
                        first(result, "?")));
                case CLAIM_NOT_FULL_HEIGHT -> failure(source, Component.translatable(KEY + "claim_not_full_height",
                        first(result, "?")));
                case CLAIM_ALREADY_BOUND -> failure(source, Component.translatable(KEY + "claim_already_bound",
                        first(result, "?")));
                case OVERLAPS_DISTRICT -> failure(source, Component.translatable(KEY + "overlaps_district",
                        first(result, "?")));
                case PLOTS_OUTSIDE -> failure(source, Component.translatable(KEY + "bounds.sync.plots_outside",
                        result.detail().size(), DistrictLimits.EDGE_GAP, String.join("、", result.detail())));
                default -> failure(source, Component.translatable(KEY + "bounds_invalid"));
            };
        });
    }

    /** /district claim &lt;id&gt; recreate: 父领地丢了时按库里的范围重建, 再 resync (20.6)。 */
    private static int recreate(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        String districtId = StringArgumentType.getString(context, "districtId");
        return run(context, ctx -> {
            DistrictAdminService.CommandResult result = ctx.admin().recreateDistrictClaim(actorOf(source), districtId);
            audit(source, "claim " + districtId + " recreate", result.outcome().name());
            return switch (result.outcome()) {
                case OK -> success(source, Component.translatable(KEY + "claim.recreate.done", districtId,
                        first(result, "?"), result.detail().size() > 1 ? result.detail().get(1) : "?",
                        result.detail().size() > 2 ? result.detail().get(2) : "?"));
                case DISTRICT_NOT_FOUND -> unknownDistrict(source, districtId);
                case FLAN_UNAVAILABLE -> flanUnavailable(source, ctx);
                case CLAIM_PRESENT -> failure(source, Component.translatable(KEY + "claim_present", districtId,
                        first(result, "?")));
                case FLAN_CLAIM_EXISTS -> failure(source, Component.translatable(KEY + "flan_claim_exists",
                        String.join("、", result.detail())));
                case FLAN_FAILED -> failure(source, Component.translatable(KEY + "flan_failed", first(result, "?")));
                default -> failure(source, Component.translatable(KEY + "bounds_invalid"));
            };
        });
    }

    private static int flanUnavailable(CommandSourceStack source, DistrictContext ctx) {
        String reason = DistrictFeature.status().reason();
        if (reason == null) {
            reason = ctx.gateway().getClass().getSimpleName();
        }
        return failure(source, Component.translatable(KEY + "flan_unavailable", reason));
    }

    private static int rulesAdd(CommandContext<CommandSourceStack> context) {
        String districtId = StringArgumentType.getString(context, "districtId");
        String text = StringArgumentType.getString(context, "text").trim();
        return editRules(context, districtId, "rules add", rules -> {
            if (text.isEmpty() || text.length() > DistrictLimits.MAX_RULE_CHARS) {
                return Component.translatable(KEY + "rules.too_long", DistrictLimits.MAX_RULE_CHARS);
            }
            if (rules.size() >= DistrictLimits.MAX_RULES) {
                return Component.translatable(KEY + "rules.too_many", DistrictLimits.MAX_RULES);
            }
            rules.add(text);
            return null;
        }, rules -> Component.translatable(KEY + "rules.added", districtId, rules.size()));
    }

    private static int rulesRemove(CommandContext<CommandSourceStack> context) {
        String districtId = StringArgumentType.getString(context, "districtId");
        int index = IntegerArgumentType.getInteger(context, "index");
        return editRules(context, districtId, "rules remove " + index, rules -> {
            if (index > rules.size()) {
                return Component.translatable(KEY + "rules.bad_index", districtId, rules.size());
            }
            rules.remove(index - 1);
            return null;
        }, rules -> Component.translatable(KEY + "rules.removed", districtId, index));
    }

    private static int rulesClear(CommandContext<CommandSourceStack> context) {
        String districtId = StringArgumentType.getString(context, "districtId");
        return editRules(context, districtId, "rules clear", rules -> {
            rules.clear();
            return null;
        }, rules -> Component.translatable(KEY + "rules.cleared", districtId));
    }

    /** 区规整张替换: 读当前区规 -> 改 -> 写回。edit 返回非 null 即拒绝 (不写库)。 */
    private static int editRules(CommandContext<CommandSourceStack> context, String districtId, String command,
                                 java.util.function.Function<List<String>, Component> edit,
                                 java.util.function.Function<List<String>, Component> done) {
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            Optional<DistrictRecord> district = ctx.repo().liveDistrict(districtId);
            if (district.isEmpty()) {
                return unknownDistrict(source, districtId);
            }
            List<String> rules = new ArrayList<>(district.get().rules());
            Component problem = edit.apply(rules);
            if (problem != null) {
                return failure(source, problem);
            }
            DistrictAdminService.CommandResult result = ctx.admin().setRules(actorOf(source), districtId, rules);
            audit(source, command + " (" + districtId + ")", result.outcome().name());
            if (!result.ok()) {
                return unknownDistrict(source, districtId);
            }
            return success(source, done.apply(rules));
        });
    }

    // ================================================================
    // 区务长与住户
    // ================================================================

    private static int warden(CommandContext<CommandSourceStack> context, @Nullable String name) {
        String districtId = StringArgumentType.getString(context, "districtId");
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            DistrictAdminService.WardenResult result = ctx.admin().setWarden(actorOf(source), districtId, name);
            audit(source, "warden " + districtId + " " + (name == null ? "clear" : "set " + name), "OK");
            return success(source, result.wardenName() == null
                    ? Component.translatable(KEY + "warden.cleared", districtId)
                    : Component.translatable(KEY + "warden.set", result.wardenName(), districtId));
        });
    }

    /**
     * 批量加住户: 名字用空格或逗号分隔, 逐人走加住户的规则 (与平板同一个服务方法), 逐人回报结果; add 拒绝从没进过服的人,
     * addUnseen 允许并以待生效记入。返回加入的人数。
     */
    private static int residentsAdd(CommandContext<CommandSourceStack> context, boolean allowNeverJoined) {
        String districtId = StringArgumentType.getString(context, "districtId");
        List<String> names = Arrays.stream(StringArgumentType.getString(context, "names").split("[\\s,，]+"))
                .map(String::trim)
                .filter(name -> !name.isEmpty())
                .distinct()
                .toList();
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            if (names.isEmpty()) {
                return failure(source, Component.translatable(KEY + "residents.empty"));
            }
            if (names.size() > DistrictLimits.MAX_BULK_NAMES) {
                return failure(source, Component.translatable(KEY + "residents.too_many",
                        DistrictLimits.MAX_BULK_NAMES));
            }
            Actor actor = actorOf(source);
            int added = 0;
            for (String name : names) {
                try {
                    ResidentService.AddResult result = ctx.residents().addResident(actor, districtId, name,
                            allowNeverJoined);
                    added++;
                    MemberRecord member = result.resident();
                    source.sendSuccess(() -> Component.translatable(KEY + "residents.added", member.name(),
                            districtId, syncLabel(member.syncStatus().wire())), true);
                } catch (DistrictRuleException rejection) {
                    source.sendFailure(Component.translatable(KEY + "residents.rejected", name,
                            rejection.getMessage()));
                } catch (DistrictStoreException | MiningStoreException failure) {
                    AUDIT.error("[miningdim] /district residents add failed on the database for {} in {}", name,
                            districtId, failure);
                    source.sendFailure(Component.translatable(KEY + "residents.rejected", name,
                            Component.translatable(KEY + "store_failed")));
                }
            }
            int total = added;
            audit(source, "residents " + districtId + (allowNeverJoined ? " addUnseen " : " add ") + names,
                    added + "/" + names.size() + " added");
            source.sendSuccess(() -> Component.translatable(KEY + "residents.summary", names.size(), total), false);
            return total;
        });
    }

    // ================================================================
    // 查询与排障
    // ================================================================

    private static int list(CommandContext<CommandSourceStack> context) {
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            DistrictRepository repo = ctx.repo();
            List<DistrictRecord> districts = repo.liveDistricts();
            if (districts.isEmpty()) {
                // 列出 0 条也是执行成功: 查询类返回列出的条数, 至少 1 (设计文档第十六章); 0 只留给拒绝。
                source.sendSuccess(() -> Component.translatable(KEY + "list.none"), false);
                return 1;
            }
            source.sendSuccess(() -> Component.translatable(KEY + "list.header", districts.size()), false);
            for (DistrictRecord district : districts) {
                int residents = repo.countMembers(district.academyId());
                int plots = repo.plotsOf(district.districtId()).size();
                source.sendSuccess(() -> Component.translatable(KEY + "list.entry", district.districtId(),
                        district.displayName(), boundsText(district.bounds()), wardenText(district), residents,
                        plots, purchaseLabel(district.purchaseOpen())), false);
            }
            return districts.size();
        });
    }

    private static int info(CommandContext<CommandSourceStack> context) {
        String districtId = StringArgumentType.getString(context, "districtId");
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            DistrictRepository repo = ctx.repo();
            Optional<DistrictRecord> found = repo.liveDistrict(districtId);
            if (found.isEmpty()) {
                return unknownDistrict(source, districtId);
            }
            DistrictRecord district = found.get();
            String academy = repo.academy(district.academyId()).map(Academy::fullName).orElse(district.academyId());
            DistrictRepository.SyncCounts issues = repo.countMembersBySync(district.academyId());
            int residents = repo.countMembers(district.academyId());
            source.sendSuccess(() -> Component.translatable(KEY + "info.header", district.displayName(),
                    district.districtId(), academy), false);
            source.sendSuccess(() -> Component.translatable(KEY + "info.bounds", boundsText(district.bounds()),
                    district.bounds().area()), false);
            source.sendSuccess(() -> Component.translatable(KEY + "info.warden", wardenText(district)), false);
            source.sendSuccess(() -> Component.translatable(KEY + "info.pricing", district.unitPrice(),
                    district.minSide(), district.maxSide(), purchaseLabel(district.purchaseOpen())), false);
            source.sendSuccess(() -> Component.translatable(KEY + "info.roster", residents, issues.pending(),
                    issues.failed()), false);
            if (district.needsReconcile()) {
                source.sendSuccess(() -> Component.translatable(KEY + "info.reconcile"), false);
            }
            source.sendSuccess(() -> Component.translatable(KEY + "info.rules_header", district.rules().size()),
                    false);
            for (int i = 0; i < district.rules().size(); i++) {
                int number = i + 1;
                String rule = district.rules().get(i);
                source.sendSuccess(() -> Component.translatable(KEY + "info.rule", number, rule), false);
            }
            List<DistrictLogEntry> log = repo.districtLog(district.districtId(), INFO_LOG_LINES);
            source.sendSuccess(() -> Component.translatable(KEY + "info.log_header", log.size()), false);
            for (DistrictLogEntry entry : log) {
                source.sendSuccess(() -> Component.translatable(KEY + "info.log_entry",
                        LOG_TIME.format(Instant.ofEpochMilli(entry.at())), entry.actorName(), entry.actorRole().wire(),
                        entry.action().wire(), entry.targetName() == null ? "-" : entry.targetName(),
                        logDetail(entry)), false);
            }
            return 1;
        });
    }

    /** 全部地块 (不受回执预算限制): 编号、状态、户主或原户主、范围、生效状态。 */
    private static int plots(CommandContext<CommandSourceStack> context) {
        String districtId = StringArgumentType.getString(context, "districtId");
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            Optional<DistrictRecord> found = ctx.repo().liveDistrict(districtId);
            if (found.isEmpty()) {
                return unknownDistrict(source, districtId);
            }
            List<PlotRecord> plots = ctx.repo().plotsOf(districtId);
            source.sendSuccess(() -> Component.translatable(KEY + "plots.header", districtId, plots.size()), false);
            for (PlotRecord plot : plots) {
                String who = plot.owned() ? String.valueOf(plot.ownerName())
                        : plot.frozen() ? String.valueOf(plot.frozenOwnerName()) : "-";
                source.sendSuccess(() -> Component.translatable(KEY + "plots.entry", plot.code(), plot.plotId(),
                        Component.translatable("district.miningdim.plot_status." + plot.status().wire()), who,
                        plot.area().sideText() + " @ (" + plot.area().minX() + ", " + plot.area().minZ() + ")",
                        syncLabel(plot.syncStatus().wire())), false);
            }
            return Math.max(1, plots.size());
        });
    }

    /**
     * /district machines &lt;districtId&gt; (22.8): 该区及外围 8 格内已加载区块里的机械动力"机器" (方块实体), 按方块 id 计数,
     * 每种列前 10 个坐标, 并报告没加载的区块数。拆不拆由 OP 决定。返回找到的个数 (至少 1)。
     */
    private static int machines(CommandContext<CommandSourceStack> context) {
        String districtId = StringArgumentType.getString(context, "districtId");
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            Optional<DistrictRecord> found = ctx.repo().liveDistrict(districtId);
            if (found.isEmpty()) {
                return unknownDistrict(source, districtId);
            }
            DistrictRecord district = found.get();
            ServerLevel level = levelOf(source.getServer(), district.bounds().dimension());
            if (level == null) {
                return failure(source, Component.translatable(KEY + "machines.no_level",
                        district.bounds().dimension()));
            }
            CreateMachineScan.Result result = CreateMachineScan.scan(level, district.bounds(),
                    CreateBlockPolicy.of(ctx.guards()));
            source.sendSuccess(() -> Component.translatable(KEY + "machines.header", districtId,
                    DistrictLimits.BUFFER_BLOCKS, result.total(), result.entries().size(), result.unloadedChunks()),
                    false);
            for (CreateMachineScan.Entry entry : result.entries()) {
                String coords = String.join(" ", entry.first().stream()
                        .map(pos -> "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")").toList());
                String more = entry.count() > entry.first().size() ? " …" : "";
                source.sendSuccess(() -> Component.translatable(KEY + "machines.entry", entry.block().toString(),
                        entry.count(), coords + more), false);
            }
            return Math.max(1, result.total());
        });
    }

    /**
     * create / bind confirm / bounds sync 成功之后的摘要 (22.8): 区内及外围 8 格的已加载区块里有 N 个机械动力方块 (N 为 0
     * 不显示)。扫描出错只记日志, 不影响已经成功的命令。
     */
    private static void machineSummary(CommandSourceStack source, DistrictContext ctx, DistrictRecord district) {
        try {
            ServerLevel level = levelOf(source.getServer(), district.bounds().dimension());
            if (level == null) {
                return;
            }
            CreateMachineScan.Result result = CreateMachineScan.scan(level, district.bounds(),
                    CreateBlockPolicy.of(ctx.guards()));
            if (result.total() > 0) {
                source.sendSuccess(() -> Component.translatable(KEY + "machines.summary", DistrictLimits.BUFFER_BLOCKS,
                        result.total(), district.districtId()), false);
            }
        } catch (RuntimeException failure) {
            AUDIT.error("[miningdim] /district: scanning {} for Create machines failed", district.districtId(),
                    failure);
        }
    }

    /**
     * /district personalclaims &lt;districtId&gt; (22.20, P40): 与本区禁圈区 (区 ± 8 格) 相交的全部个人领地 —— 主人、X/Z 范围、
     * 2D 或 3D、位置 (压进区内 / 外围离区边几格)、领地 id 前 8 位。压进区内的在前, 其余由近到远, 至多
     * {@value #PERSONAL_CLAIM_LINES} 行。只读, 不删、不改。领地对接没有生效时回"领地对接没有生效"。返回找到的块数 (至少 1)。
     */
    private static int personalClaims(CommandContext<CommandSourceStack> context) {
        String districtId = StringArgumentType.getString(context, "districtId");
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            Optional<DistrictRecord> found = ctx.repo().liveDistrict(districtId);
            if (found.isEmpty()) {
                return unknownDistrict(source, districtId);
            }
            if (!ctx.gateway().available()) {
                return flanUnavailable(source, ctx);
            }
            DistrictRecord district = found.get();
            List<BufferClaimReport.Entry> entries = BufferClaimReport.list(ctx.gateway(), district);
            if (entries.isEmpty()) {
                source.sendSuccess(() -> Component.translatable(KEY + "personalclaims.none", districtId,
                        DistrictLimits.BUFFER_BLOCKS), false);
                return 1;
            }
            source.sendSuccess(() -> Component.translatable(KEY + "personalclaims.header", districtId,
                    DistrictLimits.BUFFER_BLOCKS, entries.size()), false);
            MinecraftServer server = source.getServer();
            for (BufferClaimReport.Entry entry : entries.subList(0, Math.min(PERSONAL_CLAIM_LINES, entries.size()))) {
                PersonalClaim claim = entry.claim();
                String owner = ownerName(ctx, server, claim.owner());
                String range = "(" + claim.area().minX() + ", " + claim.area().minZ() + ") ~ (" + claim.area().maxX()
                        + ", " + claim.area().maxZ() + ")";
                Component shape = claim.flat()
                        ? Component.translatable(KEY + "personalclaims.flat")
                        : Component.translatable(KEY + "personalclaims.three_d", claim.minY(), claim.maxY());
                Component where = entry.parentClaim()
                        ? Component.translatable(KEY + "personalclaims.parent")
                        : entry.inside()
                        ? Component.translatable(KEY + "personalclaims.inside")
                        : Component.translatable(KEY + "personalclaims.buffer", entry.distance());
                String id = claim.handle().claimId().toString().substring(0, 8);
                source.sendSuccess(() -> Component.translatable(KEY + "personalclaims.entry", owner, range, shape,
                        where, id), false);
            }
            if (entries.size() > PERSONAL_CLAIM_LINES) {
                int more = entries.size() - PERSONAL_CLAIM_LINES;
                source.sendSuccess(() -> Component.translatable(KEY + "personalclaims.more", more), false);
            }
            source.sendSuccess(() -> Component.translatable(KEY + "personalclaims.advice"), false);
            return entries.size();
        });
    }

    /** 主人的名字: 见过的玩家表 (10.1) → 服务器的玩家名缓存 → UUID 前 8 位。 */
    private static String ownerName(DistrictContext ctx, MinecraftServer server, UUID owner) {
        Optional<SeenPlayer> seen = ctx.repo().seenByUuid(owner);
        if (seen.isPresent()) {
            return seen.get().name();
        }
        GameProfileCache cache = server.getProfileCache();
        Optional<String> cached = cache == null ? Optional.empty() : cache.get(owner).map(GameProfile::getName);
        return cached.orElseGet(() -> owner.toString().substring(0, 8));
    }

    /**
     * create / bind confirm / claim relink confirm / bounds sync 成功之后的摘要 (22.20): 外围 8 格内 (含区内) 有 N 块个人
     * 领地, 不会删除, 也不能再扩进来 (N 为 0 或领地对接没有生效时不显示)。出错只记日志, 不影响已经成功的命令。
     */
    private static void personalClaimSummary(CommandSourceStack source, DistrictContext ctx, DistrictRecord district) {
        try {
            if (!ctx.gateway().available()) {
                return;
            }
            int count = BufferClaimReport.count(ctx.gateway(), district.bounds());
            if (count > 0) {
                source.sendSuccess(() -> Component.translatable(KEY + "personalclaims.summary",
                        DistrictLimits.BUFFER_BLOCKS, count, district.districtId()), false);
            }
        } catch (RuntimeException failure) {
            AUDIT.error("[miningdim] /district: counting the personal claims around {} failed", district.districtId(),
                    failure);
        }
    }

    /** bind / relink 预览多一行 (22.20): 外围 8 格内有 N 块个人领地, 绑定后原样保留 (N 为 0 不显示)。 */
    private static void personalClaimPreview(CommandSourceStack source, DistrictContext ctx,
                                             @Nullable DistrictBounds bounds) {
        if (bounds == null) {
            return;
        }
        try {
            int count = BufferClaimReport.count(ctx.gateway(), bounds);
            if (count > 0) {
                source.sendSuccess(() -> Component.translatable(KEY + "personalclaims.preview",
                        DistrictLimits.BUFFER_BLOCKS, count), false);
            }
        } catch (RuntimeException failure) {
            AUDIT.error("[miningdim] /district: counting the personal claims around {} failed", bounds, failure);
        }
    }

    @Nullable
    private static ServerLevel levelOf(MinecraftServer server, String dimension) {
        ResourceLocation id = ResourceLocation.tryParse(dimension);
        return id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
    }

    private static int sweep(CommandContext<CommandSourceStack> context) {
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            int reclaimed = ctx.freezes().reclaimExpired(null);
            audit(source, "sweep", reclaimed + " reclaimed");
            source.sendSuccess(() -> Component.translatable(KEY + "sweep.done", reclaimed), true);
            return Math.max(1, reclaimed);
        });
    }

    /**
     * /district resync &lt;id&gt; (20.4): 立即对账并写回, 先强制备份; 外来子领地、没有墓碑的本模块子领地也删。返回成功的
     * 项数 (至少 1)。
     */
    private static int resync(CommandContext<CommandSourceStack> context) {
        String districtId = StringArgumentType.getString(context, "districtId");
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            if (ctx.repo().liveDistrict(districtId).isEmpty()) {
                return unknownDistrict(source, districtId);
            }
            DistrictReconciler.DistrictProgress report = ctx.reconciler().reconcileDistrict(districtId,
                    DistrictReconciler.Mode.EXPLICIT);
            audit(source, "resync " + districtId, report.succeeded() + " ok / " + report.failed() + " failed / "
                    + report.reverted() + " reverted");
            source.sendSuccess(() -> Component.translatable(KEY + "resync.done", districtId, report.succeeded(),
                    report.failed()), true);
            source.sendSuccess(() -> Component.translatable(KEY + "resync.detail", report.reverted(),
                    report.removedSubclaims().size(), report.unfixable().size()), false);
            for (String line : report.unfixable()) {
                source.sendSuccess(() -> Component.translatable(KEY + "inspect.line", line), false);
            }
            return Math.max(1, report.succeeded());
        });
    }

    /** /district inspect &lt;id&gt; (20.4): 对账预演, 每类差异列前 10 条并计数, 不写。 */
    private static int inspect(CommandContext<CommandSourceStack> context) {
        String districtId = StringArgumentType.getString(context, "districtId");
        return run(context, ctx -> {
            CommandSourceStack source = context.getSource();
            if (ctx.repo().liveDistrict(districtId).isEmpty()) {
                return unknownDistrict(source, districtId);
            }
            DistrictReconciler.DistrictProgress report = ctx.reconciler().reconcileDistrict(districtId,
                    DistrictReconciler.Mode.DRY_RUN);
            source.sendSuccess(() -> Component.translatable(KEY + "inspect.header", districtId,
                    report.changes().size(), report.unfixable().size(), report.removedSubclaims().size(),
                    report.reportedSubclaims().size()), false);
            for (List<String> kind : List.of(report.unfixable(), report.changes(), report.removedSubclaims(),
                    report.reportedSubclaims())) {
                for (String line : kind.subList(0, Math.min(INSPECT_LINES, kind.size()))) {
                    source.sendSuccess(() -> Component.translatable(KEY + "inspect.line", line), false);
                }
                if (kind.size() > INSPECT_LINES) {
                    int more = kind.size() - INSPECT_LINES;
                    source.sendSuccess(() -> Component.translatable(KEY + "inspect.more", more), false);
                }
            }
            return 1;
        });
    }

    /**
     * /district status (20.8): 功能状态、Flan 版本与自检问题、网关、权限表、上一轮对账、各维度的备份。功能关着时也能用
     * (不经 {@link #run}), 返回 1。
     */
    private static int status(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        DistrictFeature.Status status = DistrictFeature.status();
        Component state = switch (status.state()) {
            case OFF -> Component.translatable(KEY + "status.state.off");
            case DEGRADED -> Component.translatable(KEY + "status.state.degraded", String.valueOf(status.reason()));
            case LIVE -> Component.translatable(KEY + "status.state.live");
        };
        source.sendSuccess(() -> Component.translatable(KEY + "status.state", state), false);
        source.sendSuccess(() -> Component.translatable(KEY + "status.flan",
                status.flanVersion() == null ? "-" : status.flanVersion(), status.gateway()), false);
        for (String problem : status.selfCheckProblems()) {
            source.sendSuccess(() -> Component.translatable(KEY + "status.problem", problem), false);
        }
        guardStatus(source);
        if (!DistrictServices.isRegistered()) {
            return 1;
        }
        try {
            DistrictContext ctx = DistrictServices.context();
            FlanPermissionPolicy policy = ctx.flanSync().policy();
            long version = ctx.gateway().permissionTableVersion();
            source.sendSuccess(() -> Component.translatable(KEY + "status.table", version, policy.table().size(),
                    policy.unknownIds().isEmpty() ? "-" : String.join(", ", policy.unknownIds())), false);
            ReconcileScheduler scheduler = DistrictFeature.scheduler();
            ReconcileScheduler.RoundStats round = scheduler == null ? null : scheduler.lastRound();
            if (round == null) {
                source.sendSuccess(() -> Component.translatable(KEY + "status.round_none"), false);
            } else {
                Component aborted = !round.aborted()
                        ? Component.literal("")
                        : ReconcileScheduler.ABORT_DATABASE.equals(round.abortReason())
                        ? Component.translatable(KEY + "status.round_aborted_database")
                        : Component.translatable(KEY + "status.round_aborted");
                source.sendSuccess(() -> Component.translatable(KEY + "status.round",
                        LOG_TIME.format(Instant.ofEpochMilli(round.startedAt())), round.durationMs(), round.slices(),
                        round.districts(), round.reverted(), round.unfixable(), round.failures(), aborted), false);
            }
            Set<String> dimensions = new LinkedHashSet<>();
            ctx.repo().liveDistricts().forEach(district -> dimensions.add(district.bounds().dimension()));
            for (String dimension : dimensions) {
                ctx.gateway().backupStatus(dimension).ifPresent(text -> source.sendSuccess(() ->
                        Component.translatable(KEY + "status.backup", dimension, text), false));
            }
            bufferClaimStatus(source, ctx);
        } catch (DistrictStoreException | MiningStoreException failure) {
            AUDIT.error("[miningdim] /district status failed on the database", failure);
            return failure(source, Component.translatable(KEY + "store_failed"));
        }
        return 1;
    }

    /**
     * status 的守卫一节 (22.7、22.14): 空间索引、三个开关、算作机械动力的命名空间、名单问题 (功能打开时), 以及 mixin
     * 的情况与机械动力版本 (与功能开关无关)。
     */
    private static void guardStatus(CommandSourceStack source) {
        if (DistrictServices.isRegistered()) {
            DistrictContext ctx = DistrictServices.context();
            GuardSettings guards = ctx.guards();
            DistrictZoneSnapshot zones = ctx.zones().current();
            boolean installed = DistrictWorldGuards.installedIndex() == ctx.zones();
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.index",
                    Component.translatable(KEY + (installed ? "status.guard.installed" : "status.guard.not_installed")),
                    zones.districtCount(), zones.plotCount(), onOff(guards.crossPlot()),
                    onOff(guards.createMachinery()), onOff(guards.denyUse()), onOff(guards.personalClaims())), false);
            String namespaces = guards.namespacesText();
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.namespaces", namespaces), false);
            if (ctx.zones().stale()) {
                source.sendSuccess(() -> Component.translatable(KEY + "status.guard.stale"), false);
            }
            for (String problem : guards.problems()) {
                source.sendSuccess(() -> Component.translatable(KEY + "status.guard.list_problem", problem), false);
            }
            long failOpens = PersonalClaimGuard.failOpenCount();
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.claims_fail_open", failOpens), false);
        } else {
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.off"), false);
        }
        GuardMixinStatus.Status mixins = GuardMixinStatus.last();
        if (mixins == null) {
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.unchecked"), false);
            return;
        }
        source.sendSuccess(() -> Component.translatable(KEY + "status.guard.world", mixins.worldApplied(),
                GuardMixinStatus.WORLD_TARGETS.size(),
                mixins.worldMissing().isEmpty() ? "-" : String.join(", ", mixins.worldMissing())), false);
        createMixinStatus(source, mixins);
        flanMixinStatus(source, mixins);
    }

    private static void createMixinStatus(CommandSourceStack source, GuardMixinStatus.Status mixins) {
        if (!mixins.createInstalled()) {
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.create_absent"), false);
            return;
        }
        if (mixins.createComplete()) {
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.create_ok", mixins.createApplied(),
                    CreateHookTargets.HOOKS.size(), String.valueOf(mixins.createVersion())), false);
        } else {
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.create_incomplete",
                    mixins.missingIds(), mixins.createApplied(), CreateHookTargets.HOOKS.size(),
                    String.valueOf(mixins.createVersion())), false);
            for (CreateHookTargets.Hook hook : mixins.createMissing()) {
                source.sendSuccess(() -> Component.translatable(KEY + "status.guard.create_missing", hook.id(),
                        hook.covers()), false);
            }
        }
        if (mixins.createVersionUnverified()) {
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.create_unverified",
                    String.valueOf(mixins.createVersion()), GuardMixinStatus.VERIFIED_CREATE_VERSION), false);
        }
    }

    /**
     * 个人圈地限制的 mixin (22.20): 没装 Flan / F1、F2 都织进去了 / 缺哪个、各自覆盖什么; 版本未经核对; Flan 的
     * permissionLevel 低于 2 时注明 1 级 OP 也能用 Flan 的管理命令绕过。
     */
    private static void flanMixinStatus(CommandSourceStack source, GuardMixinStatus.Status mixins) {
        if (!mixins.flanInstalled()) {
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.claims_absent"), false);
            return;
        }
        if (mixins.flanComplete()) {
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.claims_ok", mixins.flanApplied(),
                    FlanHookTargets.HOOKS.size(), String.valueOf(mixins.flanVersion())), false);
        } else {
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.claims_incomplete",
                    mixins.flanMissingIds(), mixins.flanApplied(), FlanHookTargets.HOOKS.size(),
                    String.valueOf(mixins.flanVersion())), false);
            for (FlanHookTargets.Hook hook : mixins.flanMissing()) {
                source.sendSuccess(() -> Component.translatable(KEY + "status.guard.create_missing", hook.id(),
                        hook.covers()), false);
            }
        }
        if (mixins.flanVersionUnverified()) {
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.claims_unverified",
                    String.valueOf(mixins.flanVersion()), GuardMixinStatus.VERIFIED_FLAN_VERSION), false);
        }
        Integer permissionLevel = FlanCompat.permissionLevel(DistrictCommands.class.getClassLoader());
        if (permissionLevel != null && permissionLevel < 2) {
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.claims_permission_level",
                    permissionLevel), false);
        }
    }

    /**
     * 外围个人领地的计数 (22.20): 每个在用自管区的禁圈区里有几块个人领地, 如"abydos 0、millennium 2"; 领地对接没有生效时
     * 数不了。读库失败由调用方接住。
     */
    private static void bufferClaimStatus(CommandSourceStack source, DistrictContext ctx) {
        if (!ctx.gateway().available()) {
            String reason = ctx.gateway().unavailableReason();
            source.sendSuccess(() -> Component.translatable(KEY + "status.guard.buffer_claims_unavailable", reason),
                    false);
            return;
        }
        List<String> counts = new ArrayList<>();
        for (DistrictRecord district : ctx.repo().liveDistricts()) {
            counts.add(district.districtId() + " " + BufferClaimReport.count(ctx.gateway(), district.bounds()));
        }
        Component text = counts.isEmpty() ? Component.translatable(KEY + "none")
                : Component.literal(String.join("、", counts));
        source.sendSuccess(() -> Component.translatable(KEY + "status.guard.buffer_claims", text), false);
    }

    private static Component onOff(boolean value) {
        return Component.translatable(KEY + (value ? "status.guard.on" : "status.guard.off_switch"));
    }

    // ================================================================
    // 公共
    // ================================================================

    @FunctionalInterface
    private interface Body {
        int run(DistrictContext ctx) throws CommandSyntaxException;
    }

    /**
     * 执行一条子命令: 门面没绑定时回"尚未就绪"; 业务拒绝把原文转成 sendFailure; 数据库失败 (事务已回滚) 记 ERROR 后回
     * "本次操作没有生效"。
     */
    private static int run(CommandContext<CommandSourceStack> context, Body body) {
        CommandSourceStack source = context.getSource();
        if (!DistrictServices.isRegistered()) {
            // 功能关着 (OFF) 时服务本来就不绑定: 除 status 外一律回"没有开启" (20.8)。
            source.sendFailure(Component.translatable(KEY + (DistrictFeature.state() == DistrictFeature.State.OFF
                    ? "disabled" : "not_ready")));
            return 0;
        }
        try {
            return body.run(DistrictServices.context());
        } catch (DistrictRuleException rejection) {
            return failure(source, Component.translatable(KEY + "rejected", rejection.getMessage()));
        } catch (DistrictStoreException | MiningStoreException failure) {
            AUDIT.error("[miningdim] /district {} failed on the database (issuer {})", context.getInput(),
                    source.getTextName(), failure);
            return failure(source, Component.translatable(KEY + "store_failed"));
        } catch (CommandSyntaxException syntax) {
            source.sendFailure(Component.literal(syntax.getMessage()));
            return 0;
        }
    }

    /** 命令的操作人: 玩家执行时用玩家本人 (命令根已要求 2 级权限, 按管理员算); 控制台 / RCON 为"控制台"。 */
    static Actor actorOf(CommandSourceStack source) {
        if (source.getEntity() instanceof ServerPlayer player) {
            return Actor.player(player.getUUID(), player.getGameProfile().getName(), true);
        }
        return Actor.console();
    }

    private static int success(CommandSourceStack source, Component message) {
        source.sendSuccess(() -> message, true);
        return 1;
    }

    private static int failure(CommandSourceStack source, Component message) {
        source.sendFailure(message);
        return 0;
    }

    private static int unknownDistrict(CommandSourceStack source, String districtId) {
        return failure(source, Component.translatable(KEY + "district_unknown", districtId));
    }

    private static int unknownAcademy(CommandSourceStack source, DistrictContext ctx, String academyId) {
        String known = String.join(", ", ctx.repo().academies().stream().map(Academy::academyId).toList());
        return failure(source, Component.translatable(KEY + "academy.unknown", academyId, known));
    }

    private static void audit(CommandSourceStack source, String command, String outcome) {
        AUDIT.info("[miningdim] /district {} by {}: {}", command, source.getTextName(), outcome);
    }

    private static String first(DistrictAdminService.CommandResult result, String fallback) {
        return result.detail().isEmpty() ? fallback : result.detail().get(0);
    }

    static String boundsText(DistrictBounds bounds) {
        return bounds.dimension() + " (" + bounds.minX() + ", " + bounds.minZ() + ") ~ (" + bounds.maxX() + ", "
                + bounds.maxZ() + ")";
    }

    private static Component wardenText(DistrictRecord district) {
        return district.wardenName() == null
                ? Component.translatable(KEY + "none")
                : Component.literal(district.wardenName());
    }

    private static Component purchaseLabel(boolean open) {
        return Component.translatable(KEY + (open ? "purchase.open" : "purchase.closed"));
    }

    private static Component syncLabel(String wire) {
        return Component.translatable("district.miningdim.sync_status." + wire);
    }

    private static String logDetail(DistrictLogEntry entry) {
        if (entry.permission() != null) {
            return entry.permission().label() + " / " + entry.permission().audience() + ": "
                    + entry.permission().from() + " -> " + entry.permission().to();
        }
        return entry.reason() == null ? "" : entry.reason();
    }
}
