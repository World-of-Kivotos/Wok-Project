package com.miningdim.district.guard;

import com.miningdim.district.DistrictLimits;
import com.miningdim.district.core.DistrictBounds;
import com.miningdim.district.core.PlotArea;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 按坐标查区与地块的不可变空间索引 (设计文档 22.3): 库的投影, 绝不从 Flan 读。区与地块都是全高的 X/Z 方柱, 只看 X、Z。
 *
 * <p>区域号 (zone, 22.2): 不在任何在用自管区里为 0; 否则 {@code (区序号 << 16) | 地块序号}, 地块序号 0 是公共区域。
 * 序号只在一份快照里有效, 绝不存库、不跨快照比较。禁放区 = 每个在用自管区向 X、Z 四个方向各外扩
 * {@link DistrictLimits#BUFFER_BLOCKS} 格 (方形, 两端都含, 只在该维度)。
 *
 * <p>查询只收 Level (取 {@code level.dimension()}, 驻留的 ResourceKey 按身份比较) 与整数坐标: 不分配、不碰库、不抛异常。
 * 框外 (服务器上绝大多数的格子) 只要一次维度比较与 4 次整数比较。
 */
public final class DistrictZoneSnapshot {

    /** 一块地的输入: plotId、范围、显示用的标签 (与 Flan 里的地块名相同, 例如 阿拜多斯-01)。 */
    public record PlotInput(String plotId, PlotArea area, String label) {

        public PlotInput {
            Objects.requireNonNull(plotId, "plotId");
            Objects.requireNonNull(area, "area");
            Objects.requireNonNull(label, "label");
        }
    }

    /** 一个在用自管区的输入。 */
    public record DistrictInput(String districtId, String displayName, DistrictBounds bounds, List<PlotInput> plots) {

        public DistrictInput {
            Objects.requireNonNull(districtId, "districtId");
            Objects.requireNonNull(displayName, "displayName");
            Objects.requireNonNull(bounds, "bounds");
            plots = List.copyOf(plots);
        }
    }

    /** 一列所在的区与地块 (地块为 null 即公共区域)。 */
    public record Hit(String districtId, String districtName, @Nullable String plotId, @Nullable String plotLabel) {
    }

    /**
     * 碰到的一个禁放区 (22.20 的禁圈区与之相同): 区 id、显示名, 以及禁放区的 X/Z 框 (区的范围向四边各外扩
     * {@link DistrictLimits#BUFFER_BLOCKS} 格, 两端都含)。
     */
    public record BanHit(String districtId, String districtName, PlotArea zone) {
    }

    private static final class PlotBox {
        final String plotId;
        final int ordinal;
        final int minX;
        final int minZ;
        final int maxX;
        final int maxZ;
        final String label;

        PlotBox(String plotId, int ordinal, PlotArea area, String label) {
            this.plotId = plotId;
            this.ordinal = ordinal;
            this.minX = Math.min(area.minX(), area.maxX());
            this.minZ = Math.min(area.minZ(), area.maxZ());
            this.maxX = Math.max(area.minX(), area.maxX());
            this.maxZ = Math.max(area.minZ(), area.maxZ());
            this.label = label;
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }
    }

    private static final class DistrictBox {
        final String districtId;
        final String name;
        final int ordinal;
        final int minX;
        final int minZ;
        final int maxX;
        final int maxZ;
        final Long2ObjectOpenHashMap<PlotBox[]> plotsByChunk;

        DistrictBox(String districtId, String name, int ordinal, DistrictBounds bounds,
                    Long2ObjectOpenHashMap<PlotBox[]> plotsByChunk) {
            this.districtId = districtId;
            this.name = name;
            this.ordinal = ordinal;
            this.minX = Math.min(bounds.minX(), bounds.maxX());
            this.minZ = Math.min(bounds.minZ(), bounds.maxZ());
            this.maxX = Math.max(bounds.minX(), bounds.maxX());
            this.maxZ = Math.max(bounds.minZ(), bounds.maxZ());
            this.plotsByChunk = plotsByChunk;
        }

        boolean contains(int x, int z) {
            return x >= minX && x <= maxX && z >= minZ && z <= maxZ;
        }

        boolean inBuffer(int x, int z, int buffer) {
            return x >= minX - buffer && x <= maxX + buffer && z >= minZ - buffer && z <= maxZ + buffer;
        }

        /** 禁放区的框 (区 ± buffer, 两端都含)。 */
        PlotArea banZone(int buffer) {
            return new PlotArea(minX - buffer, minZ - buffer, maxX + buffer, maxZ + buffer);
        }

        BanHit banHit(int buffer) {
            return new BanHit(districtId, name, banZone(buffer));
        }

        @Nullable
        PlotBox plotAt(int x, int z) {
            PlotBox[] candidates = plotsByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
            if (candidates != null) {
                for (PlotBox plot : candidates) {
                    if (plot.contains(x, z)) {
                        return plot;
                    }
                }
            }
            return null;
        }
    }

    private static final class DimZones {
        final ResourceKey<Level> dimension;
        /** 该维度全部在用自管区的外包框。 */
        final int minX;
        final int minZ;
        final int maxX;
        final int maxZ;
        final DistrictBox[] districts;

        DimZones(ResourceKey<Level> dimension, DistrictBox[] districts) {
            this.dimension = dimension;
            this.districts = districts;
            int x0 = Integer.MAX_VALUE;
            int z0 = Integer.MAX_VALUE;
            int x1 = Integer.MIN_VALUE;
            int z1 = Integer.MIN_VALUE;
            for (DistrictBox box : districts) {
                x0 = Math.min(x0, box.minX);
                z0 = Math.min(z0, box.minZ);
                x1 = Math.max(x1, box.maxX);
                z1 = Math.max(z1, box.maxZ);
            }
            this.minX = x0;
            this.minZ = z0;
            this.maxX = x1;
            this.maxZ = z1;
        }

        boolean outside(int x, int z, int margin) {
            return x < minX - margin || x > maxX + margin || z < minZ - margin || z > maxZ + margin;
        }

        boolean boxOutside(int x0, int z0, int x1, int z1, int margin) {
            return x1 < minX - margin || x0 > maxX + margin || z1 < minZ - margin || z0 > maxZ + margin;
        }
    }

    public static final DistrictZoneSnapshot EMPTY = new DistrictZoneSnapshot(new DimZones[0], 0, 0, List.of());

    private final DimZones[] dims;
    private final int districtCount;
    private final int plotCount;
    /** 维度串解析不出的区 (给 /district status 看)。 */
    private final List<String> skipped;

    private DistrictZoneSnapshot(DimZones[] dims, int districtCount, int plotCount, List<String> skipped) {
        this.dims = dims;
        this.districtCount = districtCount;
        this.plotCount = plotCount;
        this.skipped = List.copyOf(skipped);
    }

    /** 由在用自管区与各自的地块建一份快照。维度串解析不出的区跳过 (记进 {@link #skipped()})。 */
    public static DistrictZoneSnapshot build(List<DistrictInput> districts) {
        if (districts.isEmpty()) {
            return EMPTY;
        }
        Map<ResourceKey<Level>, List<DistrictBox>> byDimension = new LinkedHashMap<>();
        List<String> skipped = new ArrayList<>();
        int districtOrdinal = 0;
        int plots = 0;
        for (DistrictInput input : districts) {
            ResourceLocation dimensionId = ResourceLocation.tryParse(input.bounds().dimension());
            if (dimensionId == null) {
                skipped.add(input.districtId());
                continue;
            }
            districtOrdinal++;
            Long2ObjectOpenHashMap<List<PlotBox>> building = new Long2ObjectOpenHashMap<>();
            int plotOrdinal = 0;
            for (PlotInput plot : input.plots()) {
                plotOrdinal++;
                plots++;
                PlotBox box = new PlotBox(plot.plotId(), plotOrdinal, plot.area(), plot.label());
                for (int cx = box.minX >> 4; cx <= box.maxX >> 4; cx++) {
                    for (int cz = box.minZ >> 4; cz <= box.maxZ >> 4; cz++) {
                        building.computeIfAbsent(ChunkPos.asLong(cx, cz), ignored -> new ArrayList<>()).add(box);
                    }
                }
            }
            Long2ObjectOpenHashMap<PlotBox[]> byChunk = new Long2ObjectOpenHashMap<>(building.size());
            building.long2ObjectEntrySet().fastForEach(entry ->
                    byChunk.put(entry.getLongKey(), entry.getValue().toArray(new PlotBox[0])));
            byChunk.trim();
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, dimensionId);
            byDimension.computeIfAbsent(dimension, ignored -> new ArrayList<>())
                    .add(new DistrictBox(input.districtId(), input.displayName(), districtOrdinal, input.bounds(),
                            byChunk));
        }
        DimZones[] dims = byDimension.entrySet().stream()
                .map(entry -> new DimZones(entry.getKey(), entry.getValue().toArray(new DistrictBox[0])))
                .toArray(DimZones[]::new);
        return new DistrictZoneSnapshot(dims, districtOrdinal, plots, skipped);
    }

    @Nullable
    private DimZones dim(Level level) {
        ResourceKey<Level> dimension = level.dimension();
        for (DimZones zones : dims) {
            if (zones.dimension == dimension) {
                return zones;
            }
        }
        return null;
    }

    @Nullable
    private DimZones dim(ResourceKey<Level> dimension) {
        for (DimZones zones : dims) {
            if (zones.dimension == dimension || zones.dimension.equals(dimension)) {
                return zones;
            }
        }
        return null;
    }

    /** 区域号 (22.2)。 */
    public int zoneAt(Level level, int x, int z) {
        return zoneIn(dim(level), x, z);
    }

    /** 区域号 (按维度键)。 */
    public int zoneAt(ResourceKey<Level> dimension, int x, int z) {
        return zoneIn(dim(dimension), x, z);
    }

    private static int zoneIn(@Nullable DimZones zones, int x, int z) {
        if (zones == null || zones.outside(x, z, 0)) {
            return 0;
        }
        for (DistrictBox district : zones.districts) {
            if (district.contains(x, z)) {
                PlotBox plot = district.plotAt(x, z);
                return (district.ordinal << 16) | (plot == null ? 0 : plot.ordinal);
            }
        }
        return 0;
    }

    /** 这一列在某个在用自管区内 (区域号非 0)。 */
    public boolean inDistrict(Level level, int x, int z) {
        return zoneAt(level, x, z) != 0;
    }

    /** 这一列在某个在用自管区内, 或它的外围 {@link DistrictLimits#BUFFER_BLOCKS} 格内 (禁放区)。 */
    public boolean inBanArea(Level level, int x, int z) {
        return inBanArea(dim(level), x, z);
    }

    public boolean inBanArea(ResourceKey<Level> dimension, int x, int z) {
        return inBanArea(dim(dimension), x, z);
    }

    private static boolean inBanArea(@Nullable DimZones zones, int x, int z) {
        int buffer = DistrictLimits.BUFFER_BLOCKS;
        if (zones == null || zones.outside(x, z, buffer)) {
            return false;
        }
        for (DistrictBox district : zones.districts) {
            if (district.inBuffer(x, z, buffer)) {
                return true;
            }
        }
        return false;
    }

    /** 这个框 (X/Z 闭区间, 两角任意顺序) 碰不碰任何在用自管区。 */
    public boolean touchesDistrict(Level level, int x0, int z0, int x1, int z1) {
        return touches(dim(level), x0, z0, x1, z1, 0);
    }

    /** 这个框碰不碰任何在用自管区的禁放区 (含外围 8 格)。 */
    public boolean touchesBanArea(Level level, int x0, int z0, int x1, int z1) {
        return touches(dim(level), x0, z0, x1, z1, DistrictLimits.BUFFER_BLOCKS);
    }

    private static boolean touches(@Nullable DimZones zones, int x0, int z0, int x1, int z1, int margin) {
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        int minZ = Math.min(z0, z1);
        int maxZ = Math.max(z0, z1);
        if (zones == null || zones.boxOutside(minX, minZ, maxX, maxZ, margin)) {
            return false;
        }
        for (DistrictBox district : zones.districts) {
            if (maxX >= district.minX - margin && minX <= district.maxX + margin
                    && maxZ >= district.minZ - margin && minZ <= district.maxZ + margin) {
                return true;
            }
        }
        return false;
    }

    /**
     * 这个框 (X/Z 闭区间, 两角任意顺序) 碰到的第一个禁放区 (22.20 新圈个人领地的判定): 按快照里区的顺序找, 两个区的外围
     * 重叠时报第一个; 都不碰为 null。全高: 只看 X/Z。
     */
    @Nullable
    public BanHit banHit(Level level, int x0, int z0, int x1, int z1) {
        return banHitIn(dim(level), x0, z0, x1, z1);
    }

    @Nullable
    public BanHit banHit(ResourceKey<Level> dimension, int x0, int z0, int x1, int z1) {
        return banHitIn(dim(dimension), x0, z0, x1, z1);
    }

    @Nullable
    private static BanHit banHitIn(@Nullable DimZones zones, int x0, int z0, int x1, int z1) {
        int buffer = DistrictLimits.BUFFER_BLOCKS;
        int minX = Math.min(x0, x1);
        int maxX = Math.max(x0, x1);
        int minZ = Math.min(z0, z1);
        int maxZ = Math.max(z0, z1);
        if (zones == null || zones.boxOutside(minX, minZ, maxX, maxZ, buffer)) {
            return null;
        }
        for (DistrictBox district : zones.districts) {
            if (maxX >= district.minX - buffer && minX <= district.maxX + buffer
                    && maxZ >= district.minZ - buffer && minZ <= district.maxZ + buffer) {
                return district.banHit(buffer);
            }
        }
        return null;
    }

    /**
     * 改范围 (22.20): 领地从 old 改成 next, 在某个区的禁放区 B 里新占了 old 没有的列时, 返回第一个这样的区; 否则 null。
     * 逐区判定 (与按并集判定等价): next ∩ B 为空与该区无关; old ∩ B 为空、next ∩ B 不为空是从外面扩进来; 两者都不为空时
     * next ∩ B 必须落在 old ∩ B 之内。所以本来就压着外围的老领地可以缩小、可以把远离区的一边往外挪, 但不能多占外围的一列。
     */
    @Nullable
    public BanHit newBanColumns(Level level, PlotArea old, PlotArea next) {
        return newBanColumnsIn(dim(level), old, next);
    }

    @Nullable
    public BanHit newBanColumns(ResourceKey<Level> dimension, PlotArea old, PlotArea next) {
        return newBanColumnsIn(dim(dimension), old, next);
    }

    @Nullable
    private static BanHit newBanColumnsIn(@Nullable DimZones zones, PlotArea old, PlotArea next) {
        int buffer = DistrictLimits.BUFFER_BLOCKS;
        PlotArea o = normalized(old);
        PlotArea n = normalized(next);
        if (zones == null || zones.boxOutside(n.minX(), n.minZ(), n.maxX(), n.maxZ(), buffer)) {
            return null;
        }
        for (DistrictBox district : zones.districts) {
            PlotArea zone = district.banZone(buffer);
            PlotArea newPart = intersection(n, zone);
            if (newPart == null) {
                continue;
            }
            PlotArea oldPart = intersection(o, zone);
            if (oldPart == null || !oldPart.contains(newPart)) {
                return district.banHit(buffer);
            }
        }
        return null;
    }

    /** 两角规整成 min ≤ max。 */
    private static PlotArea normalized(PlotArea area) {
        return new PlotArea(Math.min(area.minX(), area.maxX()), Math.min(area.minZ(), area.maxZ()),
                Math.max(area.minX(), area.maxX()), Math.max(area.minZ(), area.maxZ()));
    }

    /** 两个规整的闭区间框的交集; 不相交为 null。 */
    @Nullable
    private static PlotArea intersection(PlotArea a, PlotArea b) {
        int minX = Math.max(a.minX(), b.minX());
        int maxX = Math.min(a.maxX(), b.maxX());
        int minZ = Math.max(a.minZ(), b.minZ());
        int maxZ = Math.min(a.maxZ(), b.maxZ());
        return minX <= maxX && minZ <= maxZ ? new PlotArea(minX, minZ, maxX, maxZ) : null;
    }

    /** 这一列所在的区与地块; 不在任何在用自管区里为 null。 */
    @Nullable
    public Hit hit(Level level, int x, int z) {
        return hitIn(dim(level), x, z);
    }

    @Nullable
    public Hit hit(ResourceKey<Level> dimension, int x, int z) {
        return hitIn(dim(dimension), x, z);
    }

    @Nullable
    private static Hit hitIn(@Nullable DimZones zones, int x, int z) {
        if (zones == null || zones.outside(x, z, 0)) {
            return null;
        }
        for (DistrictBox district : zones.districts) {
            if (district.contains(x, z)) {
                PlotBox plot = district.plotAt(x, z);
                return new Hit(district.districtId, district.name, plot == null ? null : plot.plotId,
                        plot == null ? null : plot.label);
            }
        }
        return null;
    }

    /** 快照里没有任何在用自管区。 */
    public boolean isEmpty() {
        return dims.length == 0;
    }

    public int districtCount() {
        return districtCount;
    }

    public int plotCount() {
        return plotCount;
    }

    /** 维度串解析不出、没进快照的区。 */
    public List<String> skipped() {
        return skipped;
    }
}
