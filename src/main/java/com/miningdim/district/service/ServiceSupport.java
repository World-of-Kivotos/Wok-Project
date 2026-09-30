package com.miningdim.district.service;

import com.miningdim.district.access.AccessResolver;
import com.miningdim.district.access.Actor;
import com.miningdim.district.access.DistrictAccess;
import com.miningdim.district.core.Academy;
import com.miningdim.district.core.DistrictActorRole;
import com.miningdim.district.core.DistrictError;
import com.miningdim.district.core.DistrictLogEntry;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictRuleException;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotActorRole;
import com.miningdim.district.core.PlotLogEntry;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.ResidentSyncStatus;
import com.miningdim.district.store.DistrictRepository;
import com.miningdim.district.store.DistrictStoreException;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * 各服务共用的查找、身份门与记录写法 (设计文档 6.3 / 6.4)。
 *
 * 写动作的固定五步: sweep (本区到期收回, 独立事务) -&gt; main 事务 (重新读取、按契约顺序逐条检查、写业务行、写记录行、
 * 受影响的住户或地块先写"临时失败"、登记提交后推送) -&gt; 提交后按登记顺序推送 -&gt; 推送回写真值 -&gt; 返回。
 * 公开入口不许在调用方已开的事务里运行: 否则提交后推送会被推迟到外层提交, 回执只能看到临时状态。
 */
abstract class ServiceSupport {

    /** 审计日志: 管理员与区务长的每个写动作、买地、到期收回、所有命令各记一行 INFO。 */
    protected static final Logger AUDIT = LoggerFactory.getLogger("miningdim/district");

    protected final DistrictContext ctx;
    protected final DistrictRepository repo;
    protected final AccessResolver access;

    ServiceSupport(DistrictContext ctx) {
        this.ctx = ctx;
        this.repo = ctx.repo();
        this.access = new AccessResolver(ctx.repo());
    }

    protected void requireNoOpenTransaction(String operation) {
        if (repo.inOpenTransaction()) {
            throw new IllegalStateException("district " + operation
                    + " must not run inside the caller's open transaction (its Flan push would be deferred)");
        }
    }

    /** 写动作的第 0 步: 先收本区已到期的冻结地块 (独立事务), 写请求因此看不到已过期的冻结。 */
    protected void sweep(String districtId) {
        new PlotFreezeService(ctx).reclaimExpired(districtId);
    }

    protected DistrictRecord requireLive(String districtId) {
        return repo.liveDistrict(districtId).orElseThrow(() -> new DistrictRuleException(
                DistrictError.DISTRICT_NOT_FOUND, "没有找到这个自管区，它可能刚被管理员解除绑定了",
                DistrictRuleException.params("districtId", DistrictTexts.echo(districtId))));
    }

    protected PlotRecord requirePlot(DistrictRecord district, String plotId) {
        return repo.plot(plotId)
                .filter(plot -> plot.districtId().equals(district.districtId()))
                .orElseThrow(() -> new DistrictRuleException(DistrictError.PLOT_NOT_FOUND,
                        "没有找到这块地，页面可能过期了，请刷新",
                        DistrictRuleException.params("districtId", district.districtId(),
                                "plotId", DistrictTexts.echo(plotId))));
    }

    protected Academy academyOf(DistrictRecord district) {
        return repo.academy(district.academyId()).orElseThrow(() -> new DistrictStoreException(
                "district " + district.districtId() + " points at unknown academy " + district.academyId()));
    }

    /** 本区学院名单上叫这个名字 (不分大小写) 的人。 */
    protected Optional<MemberRecord> memberOfDistrict(DistrictRecord district, @Nullable String typedName) {
        if (typedName == null) {
            return Optional.empty();
        }
        return repo.memberByNameLower(DistrictTexts.lower(typedName.trim()))
                .filter(member -> member.academyId().equals(district.academyId()));
    }

    /** 本区学院名单上的某个 UUID。 */
    protected Optional<MemberRecord> memberOfDistrict(DistrictRecord district, @Nullable java.util.UUID uuid) {
        if (uuid == null) {
            return Optional.empty();
        }
        return repo.memberByUuid(uuid).filter(member -> member.academyId().equals(district.academyId()));
    }

    /**
     * 冻结中的地块的原户主现在在不在本区名单上 (解冻的前提, 也是 canRestore 的算法): 先按冻结时记下的 UUID 找; 按名字
     * (不分大小写) 只认还只有名字的待生效行。已生效的名单行带着真 UUID —— UUID 对不上而名字相同的是另一个账号 (离线
     * UUID 随名字的大小写变), 按名字认就会把地块还给别人。
     */
    protected Optional<MemberRecord> formerOwnerOnRoster(DistrictRecord district, PlotRecord plot) {
        return memberOfDistrict(district, plot.frozenOwnerUuid())
                .or(() -> memberOfDistrict(district, plot.frozenOwnerName())
                        .filter(member -> member.syncStatus() == ResidentSyncStatus.PENDING));
    }

    /** 身份门的拒绝: PERMISSION_DENIED + {action, requires}, message 是契约里的中文句子。 */
    protected static DistrictRuleException denied(String action, String requires, String message) {
        return new DistrictRuleException(DistrictError.PERMISSION_DENIED, message,
                DistrictRuleException.params("action", action, "requires", requires));
    }

    /** admin.* 动作的管理员门 (与 WebUiPermissions.requireOp 同形: params 只有 action)。 */
    protected static void requireOp(Actor actor, String action) {
        if (!actor.op()) {
            throw new DistrictRuleException(DistrictError.PERMISSION_DENIED, "需要 OP 权限",
                    DistrictRuleException.params("action", action));
        }
    }

    /** 管理者 (管理员或区务长) 在本区记录里的身份。 */
    protected static DistrictActorRole managerRole(DistrictAccess access) {
        return access == DistrictAccess.ADMIN ? DistrictActorRole.ADMIN : DistrictActorRole.WARDEN;
    }

    /** 管理者以管理身份写地块记录时的身份。 */
    protected static PlotActorRole managerPlotRole(DistrictAccess access) {
        return access == DistrictAccess.ADMIN ? PlotActorRole.ADMIN : PlotActorRole.WARDEN;
    }

    protected DistrictLogEntry writeDistrictLog(DistrictLogEntry entry) {
        return entry.withId(repo.insertDistrictLog(entry));
    }

    protected PlotLogEntry writePlotLog(PlotLogEntry entry) {
        return entry.withId(repo.insertPlotLog(entry));
    }
}
