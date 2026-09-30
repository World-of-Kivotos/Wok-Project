package com.miningdim.district.service;

import com.miningdim.district.DistrictLimits;
import com.miningdim.district.access.Abilities;
import com.miningdim.district.access.Actor;
import com.miningdim.district.access.DistrictAccess;
import com.miningdim.district.access.GlobalRole;
import com.miningdim.district.access.PlotRelation;
import com.miningdim.district.core.Academy;
import com.miningdim.district.core.DistrictActionNames;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PermissionCatalog;
import com.miningdim.district.core.PermissionCells;
import com.miningdim.district.core.PermissionItemDef;
import com.miningdim.district.core.PlotAudience;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.TombstoneRecord;
import com.miningdim.district.store.DistrictRepository;
import com.miningdim.economy.IEconomyService;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 只读快照 (district.state / district.detail / district.permissions / district.plots / plot.detail /
 * admin.district.archive)。读动作一律不写库, 也不做到期收回: 到期但还没来得及收回的地块照实给出冻结状态,
 * 界面已有"冻结期已满，等服务器收回"的写法。
 *
 * 快照不裁剪: 按身份裁剪 (住户列、flanIds、风险提示、syncError、名单与记录的可见性) 只在平板层的 DistrictJson 一处做,
 * 快照带上它需要的身份 (access / relation)。列表按设计文档 14.2 的顺序给出, 上限在这里生效 (多取一条判断是否截断)。
 */
public final class DistrictQueryService extends ServiceSupport {

    // ---- district.state ----

    public record DistrictSummaryView(DistrictRecord district, Academy academy, int residentCount, int plotCount,
                                      int vacantPlotCount, DistrictRepository.SyncCounts syncIssues) {
    }

    public record ResidencyView(DistrictRecord district, Academy academy, MemberRecord member, boolean isWarden,
                                @Nullable PlotRecord plot) {
    }

    public record FriendshipView(DistrictRecord district, PlotRecord plot, FriendRecord friend) {
    }

    public record StateView(Actor viewer, GlobalRole role, @Nullable ResidencyView residency,
                            List<DistrictSummaryView> districts, List<FriendshipView> friendOf,
                            boolean friendOfTruncated) {
    }

    // ---- district.detail ----

    public record ResidentView(MemberRecord member, @Nullable Long lastSeenAt, boolean isWarden,
                               @Nullable PlotRecord plot, int friendOfPlotCount) {
    }

    /** residents / log 只给管理员与本区区务长; 住户拿到的是 null (必须和空列表区分开)。 */
    public record DetailView(DistrictSummaryView summary, DistrictAccess access, Abilities abilities,
                             @Nullable List<ResidentView> residents, @Nullable List<DistrictLogEntry> log,
                             boolean logTruncated) {
    }

    // ---- district.permissions ----

    public record PermissionsView(DistrictRecord district, DistrictAccess access, PermissionCells cells) {
    }

    // ---- district.plots ----

    /**
     * canRestore = 原户主在本区名单上 (按 UUID; 名字只认待生效行, 见 ServiceSupport.formerOwnerOnRoster), 且没有别的地块;
     * 由平板层只给管理者。
     */
    public record FreezeView(String formerOwnerName, long frozenAt, long reclaimAt, boolean canRestore) {
    }

    /**
     * @param price                   只有空置地块才有 (面积 × 单价)
     * @param openToResidents         住户列为真的 member 项的名称 (目录顺序); 冻结中为空, 空置地块用默认值
     * @param residentColumnIsDefault 未冻结且住户列每一格都等于默认值
     */
    public record PlotSummaryView(PlotRecord plot, int friendCount, @Nullable FreezeView freeze, long area,
                                  @Nullable Long price, List<String> openToResidents,
                                  boolean residentColumnIsDefault) {
    }

    /** viewerBalance 只在 viewerBlock 为 null 且经济门面在线时给出。 */
    public record MarketView(int unitPrice, boolean open, @Nullable DistrictError viewerBlock,
                             @Nullable Long viewerBalance) {
    }

    public record TombstoneView(TombstoneRecord tombstone, List<PlotLogEntry> log, boolean logTruncated) {
    }

    /** tombstones 只给管理员, 其余人为 null。 */
    public record PlotsView(DistrictRecord district, DistrictAccess access, @Nullable PlotRecord myPlot,
                            List<String> residentDefaults, List<PlotSummaryView> plots, MarketView market,
                            @Nullable List<TombstoneView> tombstones, boolean tombstonesTruncated) {
    }

    // ---- plot.detail ----

    public record FriendView(FriendRecord friend, boolean isResident) {
    }

    /** 户主只拿到本任期的记录; 管理员另外拿到历任的归档, 排在本任期之后。 */
    public record PlotDetailView(DistrictRecord district, PlotRecord plot, PlotRelation relation, boolean editable,
                                 List<FriendView> friends, PermissionCells cells, @Nullable FreezeView freeze,
                                 List<PlotLogEntry> log, boolean logTruncated) {
    }

    // ---- admin.district.archive ----

    public record ArchivedDistrictView(DistrictRecord district, Academy academy, List<DistrictLogEntry> log,
                                       boolean logTruncated) {
    }

    public record ArchiveView(List<ArchivedDistrictView> districts, boolean truncated) {
    }

    DistrictQueryService(DistrictContext ctx) {
        super(ctx);
    }

    // ================================================================
    // district.state
    // ================================================================

    public StateView state(Actor viewer) {
        GlobalRole role = access.globalRole(viewer);
        List<DistrictRecord> live = repo.liveDistricts();
        List<DistrictSummaryView> summaries = new ArrayList<>();
        Map<String, Integer> order = new HashMap<>();
        Map<String, DistrictRecord> byId = new HashMap<>();
        for (DistrictRecord district : live) {
            order.put(district.districtId(), order.size());
            byId.put(district.districtId(), district);
            summaries.add(summary(district));
        }

        ResidencyView residency = null;
        if (viewer.uuid() != null) {
            Optional<MemberRecord> own = repo.memberByUuid(viewer.uuid());
            if (own.isPresent()) {
                Optional<DistrictRecord> home = repo.liveDistrictOfAcademy(own.get().academyId());
                if (home.isPresent()) {
                    PlotRecord plot = repo.plotOwnedBy(viewer.uuid())
                            .filter(p -> p.districtId().equals(home.get().districtId()))
                            .orElse(null);
                    residency = new ResidencyView(home.get(), academyOf(home.get()), own.get(),
                            home.get().isWarden(viewer.uuid()), plot);
                }
            }
        }

        List<FriendshipView> friendOf = new ArrayList<>();
        if (viewer.uuid() != null) {
            for (FriendRecord friend : repo.friendshipsOf(viewer.uuid(), DistrictTexts.lower(viewer.name()))) {
                Optional<PlotRecord> plot = repo.plot(friend.plotId());
                if (plot.isEmpty() || !plot.get().owned()) {
                    // 只列有户主的地块: 空置地块没有朋友, 冻结中的地块没有生效的朋友 (契约的刻意口径)。
                    continue;
                }
                DistrictRecord district = byId.get(plot.get().districtId());
                if (district != null) {
                    friendOf.add(new FriendshipView(district, plot.get(), friend));
                }
            }
        }
        friendOf.sort(Comparator.<FriendshipView>comparingInt(v -> order.get(v.district().districtId()))
                .thenComparingInt(v -> v.plot().plotNo()));
        boolean truncated = friendOf.size() > DistrictLimits.FRIEND_OF_LIMIT;
        List<FriendshipView> capped = truncated ? friendOf.subList(0, DistrictLimits.FRIEND_OF_LIMIT) : friendOf;
        return new StateView(viewer, role, residency, List.copyOf(summaries), List.copyOf(capped), truncated);
    }

    /** 一个自管区的摘要: 住户数含待生效与同步失败, 地块数含冻结中的, 空置数只数空置的。 */
    public DistrictSummaryView summary(DistrictRecord district) {
        List<PlotRecord> plots = repo.plotsOf(district.districtId());
        int vacant = (int) plots.stream().filter(PlotRecord::vacant).count();
        return new DistrictSummaryView(district, academyOf(district), repo.countMembers(district.academyId()),
                plots.size(), vacant, repo.countMembersBySync(district.academyId()));
    }

    // ================================================================
    // district.detail
    // ================================================================

    public DetailView detail(Actor viewer, String districtId) {
        DistrictRecord district = requireLive(districtId);
        DistrictAccess viewerAccess = access.access(viewer, district);
        if (viewerAccess == DistrictAccess.NONE) {
            throw denied(DistrictActionNames.DETAIL, "member", "你不是这个自管区的住户，只能看公开信息");
        }
        MemberRecord own = viewer.uuid() == null ? null : memberOfDistrict(district, viewer.uuid()).orElse(null);
        Abilities abilities = Abilities.of(viewerAccess, own);
        if (!viewerAccess.manager()) {
            return new DetailView(summary(district), viewerAccess, abilities, null, null, false);
        }
        List<ResidentView> residents = residentViews(district);
        List<DistrictLogEntry> log = repo.districtLog(district.districtId(), DistrictLimits.DISTRICT_LOG_LIMIT + 1);
        boolean truncated = log.size() > DistrictLimits.DISTRICT_LOG_LIMIT;
        return new DetailView(summary(district), viewerAccess, abilities, residents,
                truncated ? List.copyOf(log.subList(0, DistrictLimits.DISTRICT_LOG_LIMIT)) : log, truncated);
    }

    /** 本区名单 (入学顺序), 每人带最后在线时间、是否区务长、现有地块与朋友身份块数。 */
    public List<ResidentView> residentViews(DistrictRecord district) {
        List<PlotRecord> plots = repo.plotsOf(district.districtId());
        List<FriendRecord> friends = repo.friendsInDistrict(district.districtId());
        List<ResidentView> views = new ArrayList<>();
        for (MemberRecord member : repo.membersOf(district.academyId())) {
            views.add(residentView(district, member, plots, friends));
        }
        return List.copyOf(views);
    }

    /** 单个住户的视图 (district.addResident / admin.district.retrySync 的回执也用它)。 */
    public ResidentView residentView(DistrictRecord district, MemberRecord member) {
        return residentView(district, member, repo.plotsOf(district.districtId()),
                repo.friendsInDistrict(district.districtId()));
    }

    private ResidentView residentView(DistrictRecord district, MemberRecord member, List<PlotRecord> plots,
                                      List<FriendRecord> friends) {
        PlotRecord owned = null;
        for (PlotRecord plot : plots) {
            if (plot.isOwner(member.uuid())) {
                owned = plot;
            }
        }
        Long lastSeen = repo.seenByUuid(member.uuid()).map(seen -> seen.lastSeenAt()).orElse(null);
        int friendOf = ResidentService.activeFriendshipsIn(plots, friends, member).size();
        return new ResidentView(member, lastSeen, district.isWarden(member.uuid()), owned, friendOf);
    }

    // ================================================================
    // district.permissions
    // ================================================================

    /** 不拒绝外人: 外人拿到的是外人列与全区列, 本来就是公开信息 (裁剪在平板层)。 */
    public PermissionsView permissions(Actor viewer, String districtId) {
        DistrictRecord district = requireLive(districtId);
        return new PermissionsView(district, access.access(viewer, district), repo.districtCells(districtId));
    }

    // ================================================================
    // district.plots
    // ================================================================

    /**
     * @param wallet 查看者的在线玩家对象, 用来读余额 (命令等没有玩家对象的场合传 null, 余额为 null)
     */
    public PlotsView plots(Actor viewer, @Nullable ServerPlayer wallet, String districtId) {
        DistrictRecord district = requireLive(districtId);
        DistrictAccess viewerAccess = access.access(viewer, district);
        if (viewerAccess == DistrictAccess.NONE) {
            throw denied(DistrictActionNames.PLOTS, "member", "只有本区住户能看本区的地块和户主");
        }
        List<PlotRecord> plots = repo.plotsOf(district.districtId());
        List<PlotSummaryView> summaries = new ArrayList<>();
        PlotRecord myPlot = null;
        for (PlotRecord plot : plots) {
            summaries.add(plotSummary(district, plot));
            if (plot.isOwner(viewer.uuid())) {
                myPlot = plot;
            }
        }

        DistrictError block = new PlotMarketService(ctx).buyBlock(district, viewer);
        Long balance = null;
        if (block == null && wallet != null) {
            IEconomyService economy = ctx.economyOrNull();
            balance = economy == null ? null : economy.creditBalance(wallet);
        }
        MarketView market = new MarketView(district.unitPrice(), district.purchaseOpen(), block, balance);

        List<TombstoneView> tombstones = null;
        boolean tombstonesTruncated = false;
        if (viewerAccess == DistrictAccess.ADMIN) {
            List<TombstoneRecord> rows = repo.tombstones(district.districtId(), DistrictLimits.TOMBSTONE_LIMIT + 1);
            tombstonesTruncated = rows.size() > DistrictLimits.TOMBSTONE_LIMIT;
            List<TombstoneView> views = new ArrayList<>();
            for (TombstoneRecord row : tombstonesTruncated ? rows.subList(0, DistrictLimits.TOMBSTONE_LIMIT) : rows) {
                List<PlotLogEntry> log = repo.plotLog(row.plotId(), 1, Integer.MAX_VALUE,
                        DistrictLimits.TOMBSTONE_LOG_LIMIT + 1);
                boolean logTruncated = log.size() > DistrictLimits.TOMBSTONE_LOG_LIMIT;
                views.add(new TombstoneView(row,
                        logTruncated ? List.copyOf(log.subList(0, DistrictLimits.TOMBSTONE_LOG_LIMIT)) : log,
                        logTruncated));
            }
            tombstones = List.copyOf(views);
        }
        return new PlotsView(district, viewerAccess, myPlot, PermissionCatalog.residentDefaultLabels(),
                List.copyOf(summaries), market, tombstones, tombstonesTruncated);
    }

    /** 一块地的摘要 (plot.create / plot.resize 的回执也用它)。 */
    public PlotSummaryView plotSummary(DistrictRecord district, PlotRecord plot) {
        int friendCount = repo.friendsOf(plot.plotId()).size();
        List<String> open = new ArrayList<>();
        boolean allDefault = true;
        if (!plot.frozen()) {
            PermissionCells cells = plot.vacant() ? null : repo.plotCells(plot.plotId());
            for (PermissionItemDef item : PermissionCatalog.memberItems()) {
                boolean fallback = item.plotDefault(PlotAudience.RESIDENT);
                boolean value = cells == null ? fallback : cells.plot(item, PlotAudience.RESIDENT);
                if (value) {
                    open.add(item.label());
                }
                if (value != fallback) {
                    allDefault = false;
                }
            }
        }
        long area = plot.area().area();
        Long price = plot.vacant() ? district.priceOf(plot.area()) : null;
        return new PlotSummaryView(plot, friendCount, freezeView(district, plot), area, price, List.copyOf(open),
                !plot.frozen() && allDefault);
    }

    @Nullable
    private FreezeView freezeView(DistrictRecord district, PlotRecord plot) {
        if (!plot.frozen() || plot.frozenAt() == null) {
            return null;
        }
        Optional<MemberRecord> back = formerOwnerOnRoster(district, plot);
        boolean canRestore = back.isPresent() && repo.plotOwnedBy(back.get().uuid()).isEmpty();
        return new FreezeView(String.valueOf(plot.frozenOwnerName()), plot.frozenAt(),
                plot.frozenAt() + DistrictLimits.FREEZE_MS, canRestore);
    }

    // ================================================================
    // plot.detail
    // ================================================================

    public PlotDetailView plotDetail(Actor viewer, String districtId, String plotId) {
        DistrictRecord district = requireLive(districtId);
        PlotRecord plot = requirePlot(district, plotId);
        PlotRelation relation = access.relation(viewer, plot);
        if (relation == PlotRelation.NONE) {
            if (access.access(viewer, district) == DistrictAccess.WARDEN) {
                throw denied(DistrictActionNames.PLOT_DETAIL, "owner",
                        "别人的地块由户主做主，区务长只能看地块列表，不能看或改朋友和权限");
            }
            throw denied(DistrictActionNames.PLOT_DETAIL, "owner", "只有户主本人和管理员能看这块地的朋友和权限");
        }
        PlotOwnerService owners = new PlotOwnerService(ctx);
        List<FriendView> friends = new ArrayList<>();
        for (FriendRecord friend : repo.friendsOf(plot.plotId())) {
            friends.add(new FriendView(friend, owners.isResident(district, friend)));
        }
        int minTenure = relation == PlotRelation.ADMIN ? 1 : plot.tenure();
        List<PlotLogEntry> log = repo.plotLog(plot.plotId(), minTenure, plot.tenure(),
                DistrictLimits.PLOT_LOG_LIMIT + 1);
        boolean truncated = log.size() > DistrictLimits.PLOT_LOG_LIMIT;
        return new PlotDetailView(district, plot, relation, plot.owned(), List.copyOf(friends),
                repo.plotCells(plot.plotId()), freezeView(district, plot),
                truncated ? List.copyOf(log.subList(0, DistrictLimits.PLOT_LOG_LIMIT)) : log, truncated);
    }

    // ================================================================
    // admin.district.archive
    // ================================================================

    public ArchiveView archive(Actor viewer) {
        requireOp(viewer, DistrictActionNames.ARCHIVE);
        List<DistrictRecord> rows = repo.archivedDistricts(DistrictLimits.ARCHIVE_LIMIT + 1);
        boolean truncated = rows.size() > DistrictLimits.ARCHIVE_LIMIT;
        List<ArchivedDistrictView> views = new ArrayList<>();
        for (DistrictRecord district : truncated ? rows.subList(0, DistrictLimits.ARCHIVE_LIMIT) : rows) {
            List<DistrictLogEntry> log = repo.districtLog(district.districtId(), DistrictLimits.ARCHIVE_LOG_LIMIT + 1);
            boolean logTruncated = log.size() > DistrictLimits.ARCHIVE_LOG_LIMIT;
            views.add(new ArchivedDistrictView(district, academyOf(district),
                    logTruncated ? List.copyOf(log.subList(0, DistrictLimits.ARCHIVE_LOG_LIMIT)) : log,
                    logTruncated));
        }
        return new ArchiveView(List.copyOf(views), truncated);
    }
}
