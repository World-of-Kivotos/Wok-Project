package com.miningdim.district.flan;

import com.miningdim.district.DistrictLimits;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.DistrictRecord;
import com.miningdim.district.core.PlotArea;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * 自管区外围的个人领地 (设计文档 22.20, P40): 建区、绑定、改认、bounds sync 之前就压着外围 (极少数情况下压进区里) 的
 * 个人领地不删、不冻结、不改, 只报告。这里算清单、摘要与计数, 不引用 Flan (经 {@link FlanGateway}); 命令层只排版。
 *
 * <p>禁圈区与守卫同一口径: 区 (按库里的范围) 向四边各外扩 {@link DistrictLimits#BUFFER_BLOCKS} 格, 两端都含, 全高,
 * 只在该区的维度。
 */
public final class BufferClaimReport {

    private BufferClaimReport() {
    }

    /**
     * 清单里的一块。
     *
     * @param claim       那块个人领地
     * @param inside      压进区内 (X/Z 与区的范围相交)
     * @param distance    离区边多少格 (切比雪夫距离; 压进区内为 0)
     * @param parentClaim 领地 id 恰好是本区父领地 (有人在父领地上执行了 setAdminClaim false, 对账报 ERROR)
     */
    public record Entry(PersonalClaim claim, boolean inside, int distance, boolean parentClaim) {

        public Entry {
            Objects.requireNonNull(claim, "claim");
        }
    }

    /** 区的禁圈区 (区 ± 外围格数)。 */
    public static PlotArea zoneOf(DistrictBounds bounds) {
        int buffer = DistrictLimits.BUFFER_BLOCKS;
        return new PlotArea(bounds.minX() - buffer, bounds.minZ() - buffer, bounds.maxX() + buffer,
                bounds.maxZ() + buffer);
    }

    /**
     * 与区的禁圈区相交的全部个人领地, 排好序: 压进区内的在前, 其余按离区边由近到远 (同距离按领地 id)。
     * 领地对接没有生效时为空 (调用方先查 {@link FlanGateway#available()})。
     */
    public static List<Entry> list(FlanGateway gateway, DistrictRecord district) {
        return list(gateway, district.bounds(), district.flanClaimId());
    }

    /** 同上, 按一片范围算 (bind、relink 的预览用: 那时还没有自管区行)。 */
    public static List<Entry> list(FlanGateway gateway, DistrictBounds bounds, @Nullable UUID parentClaimId) {
        PlotArea district = bounds.asArea();
        List<Entry> entries = new ArrayList<>();
        for (PersonalClaim claim : gateway.personalClaimsIntersecting(bounds.dimension(), zoneOf(bounds))) {
            boolean inside = claim.area().overlaps(district);
            entries.add(new Entry(claim, inside, inside ? 0 : distance(claim.area(), district),
                    claim.handle().claimId().equals(parentClaimId)));
        }
        entries.sort(Comparator.comparing((Entry entry) -> !entry.inside())
                .thenComparingInt(Entry::distance)
                .thenComparing(entry -> entry.claim().handle().claimId()));
        return entries;
    }

    /** 块数 (摘要、预览与 /district status 用)。 */
    public static int count(FlanGateway gateway, DistrictBounds bounds) {
        return gateway.personalClaimsIntersecting(bounds.dimension(), zoneOf(bounds)).size();
    }

    /** 两个框之间的切比雪夫距离 (相交为 0; 紧挨着的一格为 1)。 */
    static int distance(PlotArea claim, PlotArea district) {
        int dx = Math.max(0, Math.max(district.minX() - claim.maxX(), claim.minX() - district.maxX()));
        int dz = Math.max(0, Math.max(district.minZ() - claim.maxZ(), claim.minZ() - district.maxZ()));
        return Math.max(dx, dz);
    }
}
