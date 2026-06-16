package com.miningdim.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.DimensionType;

/**
 * 全 mod 级别的不可变常量。所有子系统从这里取 modid、维度键、region 网格几何与三固定难度区域槽位,
 * 杜绝跨子系统各写一份导致漂移。region 几何为设计文档第四章建议初值 (PENDING待校验),
 * 真正权威以维度 JSON 的 generator.settings 为准 (4.4), 本类常量作为 Java 默认值与 datapack 对齐。
 *
 * 难度模型 (R1/R2): 三个固定、独立、共享、常驻的区域在 XZ 平面并排, 每块 256x384x256, 一整块就是一个难度
 * (Easy/Medium/Hard)。难度由"玩家在哪一块 region"决定, 不再按 worldY 分带; 旧的难度子盒 Y 常量已删除。
 */
public final class MiningConstants {

    private MiningConstants() {
    }

    /** mod 命名空间, 所有 ResourceLocation 的 namespace 恒为此值。 */
    public static final String MODID = "miningdim";

    /** 唯一矿山维度的 Level 键 = miningdim:mining (单一静态维度, C1)。 */
    public static final ResourceKey<Level> MINING_LEVEL =
            ResourceKey.create(Registries.DIMENSION, new ResourceLocation(MODID, "mining"));

    /** 矿山维度类型键, 指向 data/miningdim/dimension_type/mining.json。 */
    public static final ResourceKey<DimensionType> MINING_DIM_TYPE =
            ResourceKey.create(Registries.DIMENSION_TYPE, new ResourceLocation(MODID, "mining"));

    /** 自定义 ChunkGenerator 的注册 id, 必须与 dimension/mining.json 的 generator.type 一致 (4.4/5.3)。 */
    public static final ResourceLocation CHUNK_GENERATOR_ID =
            new ResourceLocation(MODID, "mining_chunk_generator");

    /** 自定义 BiomeSource 的注册 id, 必须与 dimension/mining.json 的 biome_source.type 一致 (4.4/5.3)。 */
    public static final ResourceLocation BIOME_SOURCE_ID =
            new ResourceLocation(MODID, "mining_biome_source");

    // ---- region 网格几何 (设计文档 4.2, 建议初值 PENDING待校验) ----

    /** region 在 X 方向格数, 必须为 16 的整数倍 (利于区块对齐)。 */
    public static final int REGION_SIZE_X = 256;

    /** region 在 Z 方向格数, 必须为 16 的整数倍。 */
    public static final int REGION_SIZE_Z = 256;

    /** region 垂直高度, 等于维度 height, 必须为 16 的倍数。 */
    public static final int REGION_HEIGHT = 384;

    /** region 底部世界 Y, 等于维度 min_y。 */
    public static final int REGION_MIN_Y = -64;

    /** region 顶部世界 Y (开区间上界等价值: 体素 localY 范围 [0, REGION_HEIGHT))。 */
    public static final int REGION_MAX_Y_EXCLUSIVE = REGION_MIN_Y + REGION_HEIGHT; // 320

    /** 相邻 region 之间缓冲带宽度 (区块数), >=1, 实心填充 (D1/C2)。 */
    public static final int BUFFER_CHUNKS = 1;

    /** 相邻 region 之间缓冲带格数 = BUFFER_CHUNKS * 16 (设计文档 REGION_GAP=32 时取 2 区块; 此处以 BUFFER_CHUNKS 派生)。 */
    public static final int REGION_GAP = 32;

    /** XZ 网格步长 = SIZE + GAP (派生量, 4.2)。 */
    public static final int REGION_STRIDE_X = REGION_SIZE_X + REGION_GAP; // 288 (按 GAP=32; 文档建议 544 对应 GAP=288, 取 JSON 为权威时由 settings 覆盖)
    public static final int REGION_STRIDE_Z = REGION_SIZE_Z + REGION_GAP;

    /** 网格原点世界坐标 (4.2)。 */
    public static final int REGION_ORIGIN_X = 0;
    public static final int REGION_ORIGIN_Z = 0;

    // ---- 三固定难度区域的 XZ 槽位 (R1: 三块并排、常驻、共享, 一整块就是一个难度) ----
    // 新模型不再按 worldY 分带, 也不再动态分配/GC 这三块。三块在 X 轴并排 (Z 槽位同列 0),
    // 沿用 RegionGrid 的螺旋槽位映射保持几何与持久化位图自洽: Easy=slot0(原点), Medium=slot1, Hard=...,
    // 但本类只给出"难度 -> 网格单元 X 列"的稳定映射, 实际 RegionBox 由 RegionGrid.fixedRegionFor 据 stride 派生。
    // 单元 X 列彼此差 1, 经 stride(=SIZE+GAP) 平移后天然带 REGION_GAP 缓冲带, 满足 C2 不相交 + D1 实心隔离。

    /** Easy 区域所在网格单元 X 列 (Z 列恒为 0)。 */
    public static final int EASY_CELL_X = 0;
    /** Medium 区域所在网格单元 X 列。 */
    public static final int MEDIUM_CELL_X = 1;
    /** Hard 区域所在网格单元 X 列。 */
    public static final int HARD_CELL_X = 2;
    /** 三固定难度区域共用的网格单元 Z 列 (并排只在 X 轴展开)。 */
    public static final int FIXED_REGION_CELL_Z = 0;

    // ---- 全区域 localY 区间 (R2: 难度=区域, 不再有难度子盒; 整块 384 高都属同一难度) ----
    // 旧的 EASY/MEDIUM/HARD 子盒 worldY/localY 常量已删除 (难度不再由 worldY 决定)。可雕刻/出生扫描
    // 的 Y 范围现在是整块 region 的全高, 由下列全区间常量给出, 与具体难度无关。

    /** 全区域可用 localY 下界 (含) = 0。 */
    public static final int REGION_FULL_MIN_LOCAL_Y = 0;
    /** 全区域可用 localY 上界 (含) = REGION_HEIGHT - 1。 */
    public static final int REGION_FULL_MAX_LOCAL_Y = REGION_HEIGHT - 1; // 383
    /** 全区域可用 worldY 下界 (含) = REGION_MIN_Y。 */
    public static final int REGION_FULL_MIN_WORLD_Y = REGION_MIN_Y;            // -64
    /** 全区域可用 worldY 上界 (含) = REGION_MAX_Y_EXCLUSIVE - 1。 */
    public static final int REGION_FULL_MAX_WORLD_Y = REGION_MAX_Y_EXCLUSIVE - 1; // 319
}
