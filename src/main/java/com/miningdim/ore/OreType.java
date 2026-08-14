package com.miningdim.ore;

import com.miningdim.core.Difficulty;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * 矿种枚举 (设计文档 8.2 权重表 + 8.5 矿脉尺寸表)。每个矿种携带:
 *  - 石质 / 深板岩质两套方块变体 (按落点 worldY 是否 < DEEPSLATE_Y_THRESHOLD 二选一, 与第四章分层一致);
 *  - baseWeight: 加权轮盘的相对权重基准 (8.2);
 *  - mult[]: 难度乘子, 索引 = Difficulty.ordinal() (8.2 表方向);
 *  - densityPerK[]: 每 1000 可铺壁面体素的目标矿块数, 索引 = ordinal (8.4 配额);
 *  - maxCount[]: 每实例硬上限, 索引 = ordinal (8.4, OG-1 评审硬约束);
 *  - veinSizeMin/Max: 矿脉成簇 BFS 尺寸 (8.5)。
 *
 * 数值照抄设计文档 8.2/8.4/8.5 的 PENDING 初值; ancient_debris 无深板岩变体, 两槽同填 ANCIENT_DEBRIS。
 * 矿石方块均引用原版 net.minecraft.world.level.block.Blocks 真实字段 (8.2 末注), 本 mod 不另注册矿石方块。
 */
public enum OreType {

    COAL(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
            100, new float[]{1.30f, 1.00f, 0.70f},
            new float[]{28f, 22f, 14f}, new int[]{900, 700, 480},
            4, 12),

    COPPER(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
            60, new float[]{1.10f, 1.00f, 0.80f},
            new float[]{16f, 16f, 13f}, new int[]{520, 520, 440},
            3, 8),

    IRON(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
            70, new float[]{1.20f, 1.10f, 0.90f},
            new float[]{20f, 20f, 18f}, new int[]{640, 640, 600},
            3, 8),

    GOLD(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
            25, new float[]{0.40f, 1.00f, 1.60f},
            new float[]{3f, 7f, 13f}, new int[]{110, 230, 420},
            2, 5),

    REDSTONE(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
            40, new float[]{0.60f, 1.00f, 1.20f},
            new float[]{8f, 12f, 16f}, new int[]{260, 380, 520},
            4, 9),

    LAPIS(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
            18, new float[]{0.80f, 1.00f, 1.10f},
            new float[]{4f, 5f, 6f}, new int[]{140, 170, 210},
            3, 6),

    EMERALD(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
            6, new float[]{0.20f, 0.60f, 1.40f},
            new float[]{0.3f, 1.0f, 3.0f}, new int[]{12, 36, 96},
            1, 2),

    DIAMOND(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
            8, new float[]{0.15f, 0.70f, 2.20f},
            new float[]{0.5f, 1.6f, 4.5f}, new int[]{18, 56, 150},
            1, 4),

    ANCIENT_DEBRIS(Blocks.ANCIENT_DEBRIS, Blocks.ANCIENT_DEBRIS,
            2, new float[]{0.00f, 0.10f, 1.00f},
            new float[]{0f, 0.15f, 0.6f}, new int[]{0, 6, 20},
            1, 2);

    /**
     * 深板岩变体阈值 (8.2 末): 落点 worldY < 该值用深板岩矿, 否则用石质矿。
     * PENDING 初值 0; region 高度重定时可调, 但调整属阶段2 数值校验范畴, 此处取文档给定 0。
     */
    public static final int DEEPSLATE_Y_THRESHOLD = 0;

    /**
     * 方块 -> 矿种反查表 (石质 + 深板岩两套变体均映射回同一 OreType)。供矿工探矿在真实世界扫块时把
     * 命中的矿石方块还原为矿种 (OreScanService 改扫真实世界路径), 取代死体素表 cachedPlacement。
     * ANCIENT_DEBRIS 两变体同方块, put 同键幂等。本表在枚举值全部构造完成后初始化 (static 块晚于枚举常量)。
     */
    private static final Map<Block, OreType> BY_BLOCK = buildBlockIndex();

    private static Map<Block, OreType> buildBlockIndex() {
        Map<Block, OreType> index = new HashMap<>();
        for (OreType ore : values()) {
            index.put(ore.stoneVariant, ore);
            index.put(ore.deepslateVariant, ore);
        }
        return Map.copyOf(index);
    }

    /**
     * 由矿石方块反查矿种; 非本 mod 关心的矿石方块返回 null (调用方据此跳过)。
     * 石质与深板岩两变体均能命中 (如 IRON_ORE 与 DEEPSLATE_IRON_ORE 同映射 IRON)。
     */
    public static OreType fromBlock(Block block) {
        return BY_BLOCK.get(block);
    }

    private final Block stoneVariant;
    private final Block deepslateVariant;
    private final int baseWeight;
    private final float[] mult;
    private final float[] densityPerK;
    private final int[] maxCount;
    private final int veinSizeMin;
    private final int veinSizeMax;

    OreType(Block stoneVariant, Block deepslateVariant, int baseWeight, float[] mult,
            float[] densityPerK, int[] maxCount, int veinSizeMin, int veinSizeMax) {
        this.stoneVariant = stoneVariant;
        this.deepslateVariant = deepslateVariant;
        this.baseWeight = baseWeight;
        this.mult = mult;
        this.densityPerK = densityPerK;
        this.maxCount = maxCount;
        this.veinSizeMin = veinSizeMin;
        this.veinSizeMax = veinSizeMax;
    }

    /**
     * 本矿种的代表物品 (固定取石质变体的方块物品)。
     *
     * 存在的理由: {@link #stoneVariant}/{@link #deepslateVariant} 是 private 且只有反向的 {@link #fromBlock},
     * 没有正向 OreType -&gt; Item 通路; 探矿回执要告诉玩家"这次探到的是什么矿"就必须有一个 itemId 与翻译键。
     * 固定取石质变体而不是按玩家所在 Y 二选一: 一次探测的命中坐标可能横跨深板岩阈值, 让展示用的 id 随坐标漂移
     * 只会让同一矿种在面板上时而叫铁矿石、时而叫深层铁矿石。
     */
    public Item representativeItem() {
        return stoneVariant.asItem();
    }

    /** 按落点世界 Y 选石质 / 深板岩质方块状态 (8.2)。 */
    public BlockState blockStateAt(int worldY) {
        return (worldY < DEEPSLATE_Y_THRESHOLD ? deepslateVariant : stoneVariant).defaultBlockState();
    }

    /** 加权轮盘相对权重基准 (8.2)。 */
    public int baseWeight() {
        return baseWeight;
    }

    /** effectiveWeight = baseWeight * difficultyMultiplier (8.2 公式)。 */
    public float effectiveWeight(Difficulty difficulty) {
        return baseWeight * mult[difficulty.ordinal()];
    }

    /** 每 1000 可铺壁面体素目标矿块数 (8.4)。 */
    public float densityPerK(Difficulty difficulty) {
        return densityPerK[difficulty.ordinal()];
    }

    /** 每实例硬上限 maxCount (8.4, OG-1)。 */
    public int maxCount(Difficulty difficulty) {
        return maxCount[difficulty.ordinal()];
    }

    /** 矿脉最小体素数 (8.5)。 */
    public int veinSizeMin() {
        return veinSizeMin;
    }

    /** 矿脉最大体素数 (8.5)。 */
    public int veinSizeMax() {
        return veinSizeMax;
    }
}
