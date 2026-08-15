package com.miningdim.core;

/**
 * 三块难度 region 当前所在世界坐标的进程级快照 (13.4/D3 滑动重置)。
 *
 * 为什么需要它: worldgen/MiningBiomeSource.java 用编译期固定单元几何 (Difficulty.regionCellX +
 * REGION_STRIDE_X) 判难度 biome, region 外一律 mining_wall (surface_rule 填纯基岩)。region 一旦滑到新
 * 坐标, 新坐标按编译期固定几何会被判成 wall, 整块新区就会生成成实心基岩。本类持有"当前三块 region 在哪"
 * 的运行期真相, 取代编译期固定几何。
 *
 * 必须放在 core 且不依赖 MiningServices/InstanceManager: MiningBiomeSource 的 Codec 双端注册 (客户端
 * 也会反序列化并调用 getNoiseBiome 渲染群系), 不能引入只在服务端存在的单例, 否则客户端 NPE 崩溃。
 *
 * 默认快照 (静态初始化) 按编译期几何构造, 只保证服务端重建前与客户端 codec 反序列化时不 NPE;
 * 权威值由 InstanceManager 在启动重建与每次滑动后写入 (set)。
 */
public final class RegionLayout {

    private RegionLayout() {
    }

    /** 三块难度 region 当前世界坐标的不可变快照。 */
    public record Snapshot(RegionBox easy, RegionBox medium, RegionBox hard) {

        /** 按难度取对应 region 包围盒; 无 default 兜底, 新增难度档必须同步扩这个 switch。 */
        public RegionBox boxOf(Difficulty d) {
            return switch (d) {
                case EASY -> easy;
                case MEDIUM -> medium;
                case HARD -> hard;
            };
        }

        /** 世界坐标命中哪块 region 就返回哪个难度; 三块都不含 (缓冲带/网格外) 返回 null, 调用方据此填基岩墙。 */
        public Difficulty difficultyAt(int worldX, int worldZ) {
            for (Difficulty d : Difficulty.values()) {
                if (boxOf(d).contains(worldX, worldZ)) {
                    return d;
                }
            }
            return null;
        }

        /** 值语义替换一块 region, 返回新快照 (原快照不变)。 */
        public Snapshot with(Difficulty d, RegionBox box) {
            return switch (d) {
                case EASY -> new Snapshot(box, medium, hard);
                case MEDIUM -> new Snapshot(easy, box, hard);
                case HARD -> new Snapshot(easy, medium, box);
            };
        }
    }

    private static volatile Snapshot current = defaultSnapshot();

    private static Snapshot defaultSnapshot() {
        return new Snapshot(
                defaultBox(Difficulty.EASY),
                defaultBox(Difficulty.MEDIUM),
                defaultBox(Difficulty.HARD));
    }

    private static RegionBox defaultBox(Difficulty d) {
        int originX = MiningConstants.REGION_ORIGIN_X + d.regionCellX() * MiningConstants.REGION_STRIDE_X;
        int originZ = MiningConstants.REGION_ORIGIN_Z
                + MiningConstants.FIXED_REGION_CELL_Z * MiningConstants.REGION_STRIDE_Z;
        return RegionBox.ofDefault(originX, originZ);
    }

    /** 当前快照 (volatile 读)。 */
    public static Snapshot current() {
        return current;
    }

    /**
     * 写入新快照 (主线程: InstanceManager 在启动重建与每次滑动后调用)。
     * @throws IllegalArgumentException snapshot 为 null
     */
    public static void set(Snapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("RegionLayout.set: snapshot must not be null");
        }
        current = snapshot;
    }
}
