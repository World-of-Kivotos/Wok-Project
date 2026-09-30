package com.miningdim.district.service;

import com.miningdim.district.DistrictLimits;
import com.miningdim.district.access.Actor;
import com.miningdim.district.access.DistrictAccess;
import com.miningdim.district.core.Academy;
import com.miningdim.district.core.AreaChange;
import com.miningdim.district.core.DistrictActionNames;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.PlotArea;
import com.miningdim.district.core.PlotGeometry;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.core.TombstoneRecord;
import com.miningdim.district.notice.DistrictNoticeKind;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 划地块 (plot.create)、调范围 (plot.resize)、删地块 (plot.delete): 本区区务长或管理员。区务长只动空置地块; 有户主的地块只有管理员能调范围
 * (代改, 地块记录里通知户主); 删除只删空置的; 冻结中的谁都不能调、不能删。
 */
public final class PlotLayoutService extends ServiceSupport {

    /**
     * 平板读到的范围: 四个坐标都是整数时 area 非 null; 否则 badField 指出哪个字段缺失或不是整数 (报 INVALID_AREA,
     * 排在身份门之后)。
     */
    public record AreaInput(@Nullable PlotArea area, @Nullable String badField) {

        public static AreaInput of(PlotArea area) {
            return new AreaInput(Objects.requireNonNull(area, "area"), null);
        }

        public static AreaInput invalid(String field) {
            return new AreaInput(null, field);
        }
    }

    /** plot.create / plot.resize 的结果: plot 是写入后的地块行, logEntry 是本区那一条。 */
    public record LayoutResult(PlotRecord plot, DistrictLogEntry logEntry, boolean ownerNotified) {
    }

    PlotLayoutService(DistrictContext ctx) {
        super(ctx);
    }

    // ================================================================
    // plot.create 划地块
    // ================================================================

    public LayoutResult create(Actor actor, String districtId, AreaInput input) {
        requireNoOpenTransaction("plot.create");
        sweep(districtId);
        long at = ctx.now();
        LayoutResult draft = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            DistrictAccess actorAccess = requirePlotManager(actor, district, DistrictActionNames.PLOT_CREATE);
            PlotArea area = requireArea(input);
            PlotGeometry.validate(district.bounds(), area, DistrictLimits.EDGE_GAP, district.minSide(),
                    district.maxSide(), repo.plotsOf(district.districtId()));

            Academy academy = academyOf(district);
            int number = repo.takeNextPlotNo(district.districtId());
            String plotId = district.districtId() + "-" + DistrictTexts.pad2(number);
            String code = academy.shortName() + "-" + DistrictTexts.pad2(number);
            PlotRecord plot = new PlotRecord(plotId, district.districtId(), number, code, area, null, null, null, null,
                    null, 1, PlotSyncStatus.FAILED, DistrictTexts.SYNC_INTERRUPTED, null, at);
            repo.insertPlot(plot);
            repo.insertPlotDefaults(plotId);
            AreaChange change = new AreaChange(null, area);
            DistrictLogEntry log = writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, actor.uuid(),
                    actor.name(), managerRole(actorAccess), DistrictLogAction.CREATE_PLOT, code,
                    DistrictTexts.createPlotReason(area), null, change));
            writePlotLog(new PlotLogEntry(0, plotId, district.districtId(), 1, at, actor.uuid(), actor.name(),
                    managerPlotRole(actorAccess), PlotLogAction.CREATE, null, null, null, change, false));
            // 提交后在自管区领地下代建子领地, 立刻把每一格写成明确值, 组名是这块地独有的。
            repo.afterCommit(() -> ctx.flanSync().writePlotState(plotId));
            return new LayoutResult(plot, log, false);
        });
        AUDIT.info("[miningdim] {} ({}) created plot {} in {} at {}", actor.name(), actor.uuid(),
                draft.plot().plotId(), districtId, draft.plot().area());
        return new LayoutResult(repo.plot(draft.plot().plotId()).orElse(draft.plot()), draft.logEntry(), false);
    }

    // ================================================================
    // plot.resize 调范围
    // ================================================================

    public LayoutResult resize(Actor actor, String districtId, String plotId, AreaInput input) {
        requireNoOpenTransaction("plot.resize");
        sweep(districtId);
        long at = ctx.now();
        LayoutResult draft = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            DistrictAccess actorAccess = requirePlotManager(actor, district, DistrictActionNames.PLOT_RESIZE);
            PlotRecord plot = requirePlot(district, plotId);
            if (plot.frozen()) {
                throw new DistrictRuleException(DistrictError.PLOT_FROZEN,
                        plot.code() + " 冻结中，不能改范围；要先解除冻结或收回",
                        DistrictRuleException.params("plotId", plot.plotId(), "code", plot.code()));
            }
            boolean owned = plot.owned();
            if (owned && actorAccess != DistrictAccess.ADMIN) {
                throw occupied(plot, plot.code() + " 是 " + plot.ownerName() + " 的家，区务长不能改有户主的地块范围");
            }
            PlotArea area = requireArea(input);
            if (area.equals(plot.area())) {
                throw new DistrictRuleException(DistrictError.PLOT_AREA_UNCHANGED, "范围和原来一样，没有改动",
                        DistrictRuleException.params("plotId", plot.plotId()));
            }
            List<PlotRecord> others = new ArrayList<>();
            for (PlotRecord other : repo.plotsOf(district.districtId())) {
                if (!other.plotId().equals(plot.plotId())) {
                    others.add(other);
                }
            }
            PlotGeometry.validate(district.bounds(), area, DistrictLimits.EDGE_GAP, district.minSide(),
                    district.maxSide(), others);

            repo.setPlotBounds(plot.plotId(), area);
            repo.setPlotSync(plot.plotId(), PlotSyncStatus.FAILED, DistrictTexts.SYNC_INTERRUPTED);
            AreaChange change = new AreaChange(plot.area(), area);
            String reason = owned
                    ? DistrictTexts.resizeOwnedReason(String.valueOf(plot.ownerName()))
                    : DistrictTexts.resizeVacantReason(plot.area(), area);
            DistrictLogEntry log = writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, actor.uuid(),
                    actor.name(), managerRole(actorAccess), DistrictLogAction.RESIZE_PLOT, plot.code(), reason, null,
                    change));
            // 有户主时这条就是给户主的通知 (标"管理员代改")。
            writePlotLog(new PlotLogEntry(0, plot.plotId(), district.districtId(), plot.tenure(), at, actor.uuid(),
                    actor.name(), managerPlotRole(actorAccess), PlotLogAction.RESIZE, null, null, null, change,
                    owned));
            if (owned) {
                // 记录里的"已通知户主"从此名副其实 (22.10 plot_admin.resized)。
                ctx.notices().enqueueUnlessSelf(actor, plot.ownerUuid(), DistrictNoticeKind.PLOT_ADMIN_RESIZED,
                        List.of(actor.name(), plot.code(), plot.area().sideText(), area.sideText()),
                        district.districtId(), plot.plotId());
            }
            repo.afterCommit(() -> ctx.flanSync().writePlotState(plot.plotId()));
            return new LayoutResult(plot, log, owned);
        });
        AUDIT.info("[miningdim] {} ({}) resized plot {} in {} to {}{}", actor.name(), actor.uuid(), plotId,
                districtId, input.area(), draft.ownerNotified() ? " on behalf of the owner" : "");
        return new LayoutResult(repo.plot(plotId).orElse(draft.plot()), draft.logEntry(), draft.ownerNotified());
    }

    // ================================================================
    // plot.delete 删地块
    // ================================================================

    /**
     * 只删空置地块: 写墓碑, 删地块行 (三列与朋友随之级联删除); 地块记录不删, 墓碑按 plot_id 读到它们。
     * 本区记录 deletePlot, 不写地块记录。提交后删子领地。返回本区那条记录。
     */
    public DistrictLogEntry delete(Actor actor, String districtId, String plotId) {
        requireNoOpenTransaction("plot.delete");
        sweep(districtId);
        long at = ctx.now();
        DistrictLogEntry log = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            DistrictAccess actorAccess = requirePlotManager(actor, district, DistrictActionNames.PLOT_DELETE);
            PlotRecord plot = requirePlot(district, plotId);
            if (plot.frozen()) {
                throw new DistrictRuleException(DistrictError.PLOT_FROZEN, plot.code() + " 冻结中，不能删；要先解除冻结或收回",
                        DistrictRuleException.params("plotId", plot.plotId(), "code", plot.code()));
            }
            if (plot.owned()) {
                throw occupied(plot, plot.code() + " 是 " + plot.ownerName() + " 的家，有户主的地块不能删");
            }
            repo.insertTombstone(new TombstoneRecord(plot.plotId(), district.districtId(), plot.code(), plot.area(),
                    at, actor.uuid(), actor.name()));
            repo.deletePlot(plot.plotId());
            DistrictLogEntry entry = writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, actor.uuid(),
                    actor.name(), managerRole(actorAccess), DistrictLogAction.DELETE_PLOT, plot.code(),
                    DistrictTexts.DISTRICT_NOTE_DELETE_PLOT, null, new AreaChange(plot.area(), null)));
            UUID claimId = plot.flanClaimId();
            repo.afterCommit(() -> ctx.flanSync().deletePlotClaim(district.districtId(), claimId));
            return entry;
        });
        AUDIT.info("[miningdim] {} ({}) deleted vacant plot {} in {}", actor.name(), actor.uuid(), plotId,
                districtId);
        return log;
    }

    private DistrictAccess requirePlotManager(Actor actor, DistrictRecord district, String action) {
        DistrictAccess actorAccess = access.access(actor, district);
        if (!actorAccess.manager()) {
            throw denied(action, "manager", "只有本区区务长和管理员可以划地块");
        }
        return actorAccess;
    }

    private static PlotArea requireArea(AreaInput input) {
        if (input.area() == null) {
            String field = input.badField() == null ? "area" : input.badField();
            throw new DistrictRuleException(DistrictError.INVALID_AREA, "坐标必须是整数",
                    DistrictRuleException.params("field", field));
        }
        return input.area();
    }

    private static DistrictRuleException occupied(PlotRecord plot, String message) {
        return new DistrictRuleException(DistrictError.PLOT_OCCUPIED, message,
                DistrictRuleException.params("plotId", plot.plotId(), "code", plot.code(),
                        "ownerName", String.valueOf(plot.ownerName())));
    }
}
