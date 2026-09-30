package com.miningdim.district.service;

import com.miningdim.district.DistrictLimits;
import com.miningdim.district.access.Actor;
import com.miningdim.district.access.DistrictAccess;
import com.miningdim.district.core.DistrictActionNames;
import com.miningdim.district.core.DistrictActorRole;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogAction;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotLogAction;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.PlotSyncStatus;
import com.miningdim.district.notice.DistrictNoticeKind;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Optional;

/**
 * 冻结与收回 (设计文档第十一章): 移出户主时的冻结 (由 {@link ResidentService} 在自己的事务里调用)、管理员解冻 (admin.plot.unfreeze)、
 * 立即收回 (admin.plot.reclaimNow) 与到期收回。
 *
 * 到期检查的三个入口都调 {@link #reclaimExpired}: 定时 (每 60 秒, 全服)、开服 (补停服期间到期的, 全服)、写动作前
 * (本区)。每块地一个事务, 一块地出错只记 ERROR、不影响其他地块; 跳过已解绑的自管区; 记录的 at 取实际处理的时刻,
 * 不回填成 frozenAt + 7 天 (否则记录会写成"发生在过去", 与自增 id 的顺序打架)。读动作不做到期收回。
 */
public final class PlotFreezeService extends ServiceSupport {

    public record UnfreezeResult(PlotRecord plot, String ownerName, DistrictLogEntry logEntry) {
    }

    PlotFreezeService(DistrictContext ctx) {
        super(ctx);
    }

    /** admin.plot.unfreeze 解冻: 原户主已重新成为本区住户、且在别处没有地块时, 朋友与三列照原样还回去。 */
    public UnfreezeResult unfreeze(Actor actor, String districtId, String plotId) {
        requireOp(actor, DistrictActionNames.PLOT_UNFREEZE);
        requireNoOpenTransaction("unfreeze");
        sweep(districtId);
        long at = ctx.now();
        UnfreezeResult result = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            PlotRecord plot = requirePlot(district, plotId);
            if (!plot.frozen()) {
                throw notFrozen(plot, plot.code() + " 没有冻结");
            }
            MemberRecord back = formerOwnerOnRoster(district, plot)
                    .orElseThrow(() -> new DistrictRuleException(DistrictError.FORMER_OWNER_NOT_RESIDENT,
                            "原户主 " + plot.frozenOwnerName() + " 现在不是本区住户，先把 TA 加回本区才能解除冻结",
                            DistrictRuleException.params("playerName", String.valueOf(plot.frozenOwnerName()))));
            Optional<PlotRecord> other = repo.plotOwnedBy(back.uuid());
            if (other.isPresent()) {
                throw new DistrictRuleException(DistrictError.ALREADY_OWNS_PLOT,
                        back.name() + " 回来后已经有了 " + other.get().code() + "，一人最多一块",
                        DistrictRuleException.params("plotId", other.get().plotId(), "code", other.get().code()));
            }
            repo.unfreeze(plot.plotId(), back.uuid(), back.name());
            repo.setPlotSync(plot.plotId(), PlotSyncStatus.FAILED, DistrictTexts.SYNC_INTERRUPTED);
            DistrictLogEntry log = writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, actor.uuid(),
                    actor.name(), DistrictActorRole.ADMIN, DistrictLogAction.UNFREEZE_PLOT, plot.code(),
                    DistrictTexts.unfreezeDistrictReason(back.name()), null, null));
            writePlotLog(new PlotLogEntry(0, plot.plotId(), district.districtId(), plot.tenure(), at, actor.uuid(),
                    actor.name(), PlotActorRole.ADMIN, PlotLogAction.UNFREEZE, back.name(),
                    DistrictTexts.PLOT_NOTE_UNFREEZE, null, null, false));
            repo.afterCommit(() -> ctx.flanSync().writePlotState(plot.plotId()));
            ctx.notices().enqueueUnlessSelf(actor, back.uuid(), DistrictNoticeKind.PLOT_UNFROZEN,
                    List.of(plot.code()), district.districtId(), plot.plotId());
            return new UnfreezeResult(plot, back.name(), log);
        });
        AUDIT.info("[miningdim] {} ({}) unfroze plot {} back to {}", actor.name(), actor.uuid(), plotId,
                result.ownerName());
        return result;
    }

    /** admin.plot.reclaimNow 立即收回: 只有冻结中的地块能收回 (有户主的地块不能直接收回)。返回本区那条记录。 */
    public DistrictLogEntry reclaimNow(Actor actor, String districtId, String plotId) {
        requireOp(actor, DistrictActionNames.PLOT_RECLAIM_NOW);
        requireNoOpenTransaction("reclaimNow");
        sweep(districtId);
        long at = ctx.now();
        DistrictLogEntry log = repo.inTransaction(() -> {
            DistrictRecord district = requireLive(districtId);
            PlotRecord plot = requirePlot(district, plotId);
            if (!plot.frozen()) {
                throw notFrozen(plot, plot.code() + " 没有冻结；只有冻结中的地块能立即收回");
            }
            // 收件人要在收回清掉冻结信息之前读 (22.11): plot 是收回之前读到的行。
            ctx.notices().enqueueUnlessSelf(actor, plot.frozenOwnerUuid(), DistrictNoticeKind.PLOT_RECLAIMED_NOW,
                    List.of(plot.code()), district.districtId(), plot.plotId());
            return vacateInTransaction(district, plot, actor, DistrictActorRole.ADMIN, PlotActorRole.ADMIN,
                    DistrictTexts.reclaimNowDistrictReason(String.valueOf(plot.frozenOwnerName())),
                    DistrictTexts.PLOT_NOTE_RECLAIM_NOW, at);
        });
        AUDIT.info("[miningdim] {} ({}) reclaimed frozen plot {} now", actor.name(), actor.uuid(), plotId);
        return log;
    }

    /**
     * 收回已到期的冻结地块。
     *
     * @param districtId 只处理这个自管区; null 为全服
     * @return 收回了几块
     */
    public int reclaimExpired(@Nullable String districtId) {
        requireNoOpenTransaction("reclaimExpired");
        long now = ctx.now();
        long cutoff = now - DistrictLimits.FREEZE_MS;
        List<PlotRecord> expired;
        try {
            expired = repo.expiredFrozenPlots(cutoff, districtId);
        } catch (RuntimeException failure) {
            AUDIT.error("[miningdim] listing expired frozen plots failed", failure);
            return 0;
        }
        int reclaimed = 0;
        for (PlotRecord candidate : expired) {
            try {
                boolean done = repo.inTransaction(() -> {
                    Optional<PlotRecord> fresh = repo.plot(candidate.plotId());
                    if (fresh.isEmpty() || !fresh.get().frozen() || fresh.get().frozenAt() == null
                            || fresh.get().frozenAt() > cutoff) {
                        return false;
                    }
                    Optional<DistrictRecord> district = repo.liveDistrict(fresh.get().districtId());
                    if (district.isEmpty()) {
                        return false;
                    }
                    ctx.notices().enqueueUnlessSelf(Actor.system(), fresh.get().frozenOwnerUuid(),
                            DistrictNoticeKind.PLOT_RECLAIMED_EXPIRED, List.of(fresh.get().code()),
                            district.get().districtId(), fresh.get().plotId());
                    vacateInTransaction(district.get(), fresh.get(), Actor.system(), DistrictActorRole.SYSTEM,
                            PlotActorRole.SYSTEM, DistrictTexts.DISTRICT_NOTE_RECLAIM_EXPIRED,
                            DistrictTexts.PLOT_NOTE_RECLAIM_EXPIRED, now);
                    return true;
                });
                if (done) {
                    reclaimed++;
                    AUDIT.info("[miningdim] reclaimed expired frozen plot {} (former owner {}, frozen at {})",
                            candidate.plotId(), candidate.frozenOwnerName(), candidate.frozenAt());
                }
            } catch (RuntimeException failure) {
                AUDIT.error("[miningdim] reclaiming expired frozen plot {} failed", candidate.plotId(), failure);
            }
        }
        return reclaimed;
    }

    /**
     * 冻结 (district.removeResident 连带触发, 在调用方的事务里): 户主移到冻结字段, 朋友与三列不动; 写一条地块记录 freeze (固定缘由, 绝不抄
     * 移出原因); 地块先写临时失败, 由调用方登记的提交后推送整块写成全关。
     */
    void freezeInTransaction(DistrictRecord district, PlotRecord plot, MemberRecord formerOwner, Actor actor,
                             DistrictAccess actorAccess, long at) {
        repo.freeze(plot.plotId(), formerOwner.uuid(), formerOwner.name(), at);
        repo.setPlotSync(plot.plotId(), PlotSyncStatus.FAILED, DistrictTexts.SYNC_INTERRUPTED);
        writePlotLog(new PlotLogEntry(0, plot.plotId(), district.districtId(), plot.tenure(), at, actor.uuid(),
                actor.name(), managerPlotRole(actorAccess), PlotLogAction.FREEZE, formerOwner.name(),
                DistrictTexts.PLOT_NOTE_FREEZE, null, null, false));
    }

    /**
     * 收回例程 (admin.plot.reclaimNow 与到期收回共用, 在调用方的事务里): 任期 +1 (此前的记录由此归档, 只有管理员能看); 清空户主与冻结
     * 信息、删掉全部朋友、三列回到地块默认值、状态改为临时失败; 在新任期写 vacate (它成为新一任记录的第一行);
     * 写本区记录 vacatePlot; 提交后整块重写成空置。返回本区那条记录。
     */
    DistrictLogEntry vacateInTransaction(DistrictRecord district, PlotRecord plot, Actor actor,
                                         DistrictActorRole districtRole, PlotActorRole plotRole,
                                         String districtReason, String plotReason, long at) {
        repo.vacate(plot.plotId());
        repo.deleteFriends(plot.plotId());
        repo.resetPlotCells(plot.plotId());
        repo.setPlotSync(plot.plotId(), PlotSyncStatus.FAILED, DistrictTexts.SYNC_INTERRUPTED);
        writePlotLog(new PlotLogEntry(0, plot.plotId(), district.districtId(), plot.tenure() + 1, at, actor.uuid(),
                actor.name(), plotRole, PlotLogAction.VACATE, plot.frozenOwnerName(), plotReason, null, null,
                false));
        DistrictLogEntry log = writeDistrictLog(new DistrictLogEntry(0, district.districtId(), at, actor.uuid(),
                actor.name(), districtRole, DistrictLogAction.VACATE_PLOT, plot.code(), districtReason, null, null));
        repo.afterCommit(() -> ctx.flanSync().writePlotState(plot.plotId()));
        return log;
    }

    private static DistrictRuleException notFrozen(PlotRecord plot, String message) {
        return new DistrictRuleException(DistrictError.PLOT_NOT_FROZEN, message,
                DistrictRuleException.params("plotId", plot.plotId(), "code", plot.code()));
    }
}
