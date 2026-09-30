package com.miningdim.district.guard;

import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.PlotRecord;
import com.miningdim.district.flan.LogThrottle;
import com.miningdim.district.store.DistrictRepository;
import com.miningdim.district.store.DistrictStoreException;
import com.miningdim.store.MiningStoreException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 每个 DistrictContext 一个的空间索引 (设计文档 22.3): 从库重建 {@link DistrictZoneSnapshot}, 整体替换。热路径只读
 * volatile 的当前快照, 永远不碰库。
 *
 * <p>什么时候重建: 建索引时一次; 改几何的六个仓储写方法提交之后 ({@link DistrictRepository#onLayoutChanged});
 * 定时收回的节拍顺带一次 (兜底)。户主、冻结、朋友、开关的变化不改几何, 不重建。重建读库失败时保留旧快照, 置"索引过期"
 * 标记给 /district status 看, 每小时至多记一次 ERROR。
 */
public final class DistrictZoneIndex {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    private final DistrictRepository repo;
    private final LogThrottle throttle;
    private volatile DistrictZoneSnapshot current = DistrictZoneSnapshot.EMPTY;
    private volatile boolean stale;
    private volatile Runnable onRebuilt = () -> {
    };

    public DistrictZoneIndex(DistrictRepository repo, LogThrottle throttle) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.throttle = Objects.requireNonNull(throttle, "throttle");
    }

    /** 当前快照。 */
    public DistrictZoneSnapshot current() {
        return current;
    }

    /** 上一次重建读库失败, 守卫仍按更早的快照工作。 */
    public boolean stale() {
        return stale;
    }

    /** 每次重建成功之后调 (门面借它发布新的 GuardView)。 */
    public void onRebuilt(Runnable listener) {
        this.onRebuilt = Objects.requireNonNull(listener, "listener");
    }

    /** 从库重建。返回是否成功; 失败 (DistrictStoreException 等) 时保留旧快照。 */
    public boolean rebuild() {
        DistrictZoneSnapshot next;
        try {
            List<DistrictZoneSnapshot.DistrictInput> inputs = new ArrayList<>();
            for (DistrictRecord district : repo.liveDistricts()) {
                List<DistrictZoneSnapshot.PlotInput> plots = new ArrayList<>();
                for (PlotRecord plot : repo.plotsOf(district.districtId())) {
                    plots.add(new DistrictZoneSnapshot.PlotInput(plot.plotId(), plot.area(), plot.code()));
                }
                inputs.add(new DistrictZoneSnapshot.DistrictInput(district.districtId(), district.displayName(),
                        district.bounds(), plots));
            }
            next = DistrictZoneSnapshot.build(inputs);
        } catch (DistrictStoreException | MiningStoreException failure) {
            stale = true;
            if (throttle.loud("district-zone-index-rebuild")) {
                LOGGER.error("[miningdim] district zone index rebuild failed; guards keep the previous snapshot "
                        + "until the next successful rebuild", failure);
            }
            return false;
        }
        current = next;
        stale = false;
        if (!next.skipped().isEmpty() && throttle.loud("district-zone-index-skipped")) {
            LOGGER.warn("[miningdim] district zone index skipped district(s) with an unparsable dimension: {}",
                    next.skipped());
        }
        onRebuilt.run();
        return true;
    }
}
