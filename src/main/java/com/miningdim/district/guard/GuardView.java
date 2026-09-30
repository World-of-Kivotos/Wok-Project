package com.miningdim.district.guard;

import com.miningdim.district.guard.create.CreateBlockPolicy;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 守卫此刻看到的一切 (设计文档 22.3): 设置、空间索引快照、机械动力分类, 不可变。门面 {@link DistrictWorldGuards} 持有
 * volatile 的当前 view; 快照每重建一次就发布一个新的 view。{@link #OFF} 什么都不拦 (没有区域、开关都关)。
 *
 * @param installed 是不是由开服 install (或 GameTest 的测试区域) 装上的; OFF 为假
 */
public record GuardView(GuardSettings settings, DistrictZoneSnapshot zones, CreateBlockPolicy policy,
                        boolean installed) {

    public static final GuardView OFF;

    static {
        GuardSettings off = new GuardSettings(false, false, false, Set.of(), Set.of(), Set.of(), false, List.of());
        OFF = new GuardView(off, DistrictZoneSnapshot.EMPTY, new CreateBlockPolicy(off, null), false);
    }

    public GuardView {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(zones, "zones");
        Objects.requireNonNull(policy, "policy");
    }

    /** 同一份设置与分类, 换一份快照。 */
    public GuardView withZones(DistrictZoneSnapshot next) {
        return new GuardView(settings, next, policy, installed);
    }

    /** 地块边界守卫生效 (装上了、开关开着、有区域)。 */
    public boolean crossPlotActive() {
        return installed && settings.crossPlot() && !zones.isEmpty();
    }

    /** 机器拦截生效。 */
    public boolean machineryActive() {
        return installed && settings.createMachinery() && !zones.isEmpty();
    }

    /** 放置禁令与 denyUse 生效 (不看急停开关)。 */
    public boolean banActive() {
        return installed && !zones.isEmpty();
    }

    /** 个人圈地限制生效 (22.20: 装上了、急停开关开着、有区域)。 */
    public boolean personalClaimsActive() {
        return installed && settings.personalClaims() && !zones.isEmpty();
    }
}
