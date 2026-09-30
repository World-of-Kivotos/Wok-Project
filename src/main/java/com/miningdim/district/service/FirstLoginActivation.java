package com.miningdim.district.service;

import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.DistrictTexts;
import com.miningdim.district.core.FriendRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.core.ResidentSyncStatus;

import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 登录与登出 (设计文档 10.1 / 10.4): 记见过的玩家表, 并对从没进过服、以待生效记入的住户与朋友补写 Flan。
 *
 * <ol>
 *   <li>一个事务里重新对齐身份: 按 UUID 找名单行, 找不到时按小写名找待生效的名单行; UUID 不同就换键 (区务长一并改,
 *       先改名单行再改自管区, 保证触发器通过)。名字相同的待生效朋友行也换键并改为已生效。凡是换键的地方, 按旧 UUID
 *       排着的聊天通知一并换到新 UUID (设计文档 22.11), 登录确认之后补发。</li>
 *   <li>提交后推送: 名单行原本待生效 -&gt; 写成员资格并回写 synced 或 failed; 每块有朋友被激活的地 -&gt; 整块重写。</li>
 *   <li>只处理未解绑的自管区; 这一步不写任何记录行 (契约没有对应的记录动作)。</li>
 * </ol>
 * 大小写与账号唯一依赖 AccessHub 保证"不分大小写的一个名字只对应一个账号" (PENDING P1)。
 */
public final class FirstLoginActivation extends ServiceSupport {

    /** 这次登录激活了什么 (供测试与日志)。 */
    public record Activation(boolean memberActivated, boolean rekeyed, Set<String> plotsRewritten) {
    }

    FirstLoginActivation(DistrictContext ctx) {
        super(ctx);
    }

    /** 登录: 先记见过的玩家表, 再补写待生效的住户与朋友。 */
    public Activation onLogin(UUID uuid, String name) {
        requireNoOpenTransaction("onLogin");
        long at = ctx.now();
        repo.upsertSeenOnLogin(uuid, name, at);
        String nameLower = DistrictTexts.lower(name);

        record Plan(boolean memberActivated, boolean rekeyed, String districtId, Set<String> plots) {
        }
        Plan plan = repo.inTransaction(() -> {
            boolean activated = false;
            boolean rekeyed = false;
            String districtId = null;
            Optional<MemberRecord> byUuid = repo.memberByUuid(uuid);
            Optional<MemberRecord> member = byUuid.isPresent()
                    ? byUuid
                    : repo.memberByNameLower(nameLower).filter(m -> m.syncStatus() == ResidentSyncStatus.PENDING);
            if (member.isPresent()) {
                MemberRecord row = member.get();
                Optional<DistrictRecord> home = repo.liveDistrictOfAcademy(row.academyId());
                if (home.isPresent()) {
                    if (!row.uuid().equals(uuid)) {
                        repo.rekeyMember(row.id(), uuid, name);
                        repo.rekeyPlotOwner(row.uuid(), uuid, name);
                        if (home.get().isWarden(row.uuid())) {
                            repo.setWarden(home.get().districtId(), uuid, name);
                        }
                        // 按输入名字算的离线 UUID 入队的通知跟着换键 (22.11), 本次登录之后就能收到。
                        ctx.notices().rekey(row.uuid(), uuid);
                        rekeyed = true;
                    }
                    if (row.syncStatus() == ResidentSyncStatus.PENDING) {
                        repo.setMemberSync(uuid, ResidentSyncStatus.FAILED, DistrictTexts.SYNC_INTERRUPTED);
                        activated = true;
                        districtId = home.get().districtId();
                    }
                }
            }
            Set<String> plots = new LinkedHashSet<>();
            for (FriendRecord friend : repo.pendingFriendsFor(uuid, nameLower)) {
                Optional<PlotRecord> plot = repo.plot(friend.plotId());
                if (plot.isEmpty() || repo.liveDistrict(plot.get().districtId()).isEmpty()) {
                    continue;
                }
                repo.activateFriend(friend.id(), uuid, name);
                if (!friend.uuid().equals(uuid)) {
                    ctx.notices().rekey(friend.uuid(), uuid);
                }
                plots.add(plot.get().plotId());
            }
            return new Plan(activated, rekeyed, districtId, plots);
        });

        if (plan.memberActivated() && plan.districtId() != null) {
            ctx.flanSync().syncResidentMembership(plan.districtId(), uuid);
        }
        for (String plotId : plan.plots()) {
            ctx.flanSync().writePlotState(plotId);
        }
        if (plan.memberActivated() || plan.rekeyed() || !plan.plots().isEmpty()) {
            AUDIT.info("[miningdim] first login of {} ({}): member activated {}, rekeyed {}, {} friend plot(s)",
                    name, uuid, plan.memberActivated(), plan.rekeyed(), plan.plots().size());
        }
        return new Activation(plan.memberActivated(), plan.rekeyed(), Set.copyOf(plan.plots()));
    }

    /** 登出: 更新最后在线时间。 */
    public void onLogout(UUID uuid) {
        repo.touchLastSeen(uuid, ctx.now());
    }
}
