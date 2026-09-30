package com.miningdim.district.access;

import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.MemberRecord;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.store.DistrictRepository;

import java.util.Optional;

/**
 * 身份判定 (设计文档第七章)。每次请求都从库里现算, 不缓存: 名单、区务长、户主随时会被别人改掉, 缓存一份就会出现
 * "刚被移出的人还能再操作一次"。
 */
public final class AccessResolver {

    private final DistrictRepository repo;

    public AccessResolver(DistrictRepository repo) {
        this.repo = repo;
    }

    /** district.state viewer.role。 */
    public GlobalRole globalRole(Actor actor) {
        if (actor.op()) {
            return GlobalRole.ADMIN;
        }
        if (actor.uuid() == null) {
            return GlobalRole.OUTSIDER;
        }
        boolean member = false;
        Optional<MemberRecord> own = repo.memberByUuid(actor.uuid());
        for (DistrictRecord district : repo.liveDistricts()) {
            if (district.isWarden(actor.uuid())) {
                return GlobalRole.WARDEN;
            }
            if (own.isPresent() && own.get().academyId().equals(district.academyId())) {
                member = true;
            }
        }
        return member ? GlobalRole.RESIDENT : GlobalRole.OUTSIDER;
    }

    /** 对某个自管区的身份。别区的区务长、别区的住户、已解绑学院的成员都是 NONE。 */
    public DistrictAccess access(Actor actor, DistrictRecord district) {
        if (actor.op()) {
            return DistrictAccess.ADMIN;
        }
        if (actor.uuid() == null) {
            return DistrictAccess.NONE;
        }
        if (district.isWarden(actor.uuid())) {
            return DistrictAccess.WARDEN;
        }
        return repo.memberByUuid(actor.uuid())
                .filter(member -> member.academyId().equals(district.academyId()))
                .isPresent() ? DistrictAccess.RESIDENT : DistrictAccess.NONE;
    }

    /** 对某块地的关系: 户主最先判, 所以 OP 管自己的地时按户主算。 */
    public PlotRelation relation(Actor actor, PlotRecord plot) {
        if (plot.isOwner(actor.uuid())) {
            return PlotRelation.OWNER;
        }
        return actor.op() ? PlotRelation.ADMIN : PlotRelation.NONE;
    }
}
