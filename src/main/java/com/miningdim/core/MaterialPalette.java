package com.miningdim.core;

/**
 * 难度基材调色板 (R3, 修旧 bug)。每个难度一份: 描述"整块 384 高的实心基材该按什么规则选", 与绝对 worldY 无关
 * (旧 bug: 按 worldY<0 选深板岩, 导致 Medium 在 y0 以上整段变纯石头)。选择确定性: 同一 (seed, x, y, z) 必产出
 * 同一令牌, 经 {@link SeedUtil#hash} 派生, 不依赖任何可变 Random 推进顺序 (D3)。
 *
 * core 只产出 {@link BaseMaterial} 令牌, 具体 BlockState 由 worldgen 映射 (见 BaseMaterial 注释)。
 *
 * 三调色板 (R3 用户拍板):
 *  - Easy:   STONE 为主, 偶尔 ANDESITE/DIORITE 点缀 (accentChance, 与深度无关)。
 *  - Medium: STONE+DEEPSLATE 噪声混合, 越深深板岩概率越高 (真正的夹杂, 非按绝对 Y 一刀切);
 *            深板岩概率 = lerp(deepslateMinChance, deepslateMaxChance) 随 localY 从顶到底线性升高。
 *  - Hard:   DEEPSLATE 为主, 偶尔 TUFF/BLACKSTONE 点缀。
 *
 * 字段语义:
 * @param primary           主基材令牌 (Medium 取 STONE 作浅层基底, 深处由 deepslate* 概率混入 DEEPSLATE)。
 * @param deepslateMinChance Medium 在 region 顶部 (localY=REGION_HEIGHT-1) 的深板岩混入概率; Easy/Hard 取 0。
 * @param deepslateMaxChance Medium 在 region 底部 (localY=0) 的深板岩混入概率; Easy/Hard 取 0。
 * @param accentChance      点缀概率 (Easy 安山/闪长, Hard 凝灰/黑石); 与深度无关。
 * @param accentA           点缀候选 A (accent 命中且二选一为 A)。
 * @param accentB           点缀候选 B。
 */
public record MaterialPalette(
        BaseMaterial primary,
        double deepslateMinChance,
        double deepslateMaxChance,
        double accentChance,
        BaseMaterial accentA,
        BaseMaterial accentB) {

    /** 基材选择用的 featureId (与骨架/矿物/陷阱的 featureId 区分, 避免撞同一随机流)。 */
    private static final int FEATURE_BASE_MATERIAL = 0x0BA5E;

    /** 点缀二选一用的 featureId。 */
    private static final int FEATURE_ACCENT_PICK = 0x0ACCE;

    /**
     * 为给定实例 seed 与世界坐标确定性地选出该实心格的基材令牌 (R3 核心)。
     * regionHeight 用于把 localY 归一化成深度比例 (Medium 深度相关混合)。
     *
     * @param seed         实例确定性种子 (InstanceState.seed)
     * @param worldX       世界 X
     * @param worldY       世界 Y (仅作 hash 输入, 不参与"按绝对 Y 选深板岩"的旧逻辑)
     * @param worldZ       世界 Z
     * @param localY       本地 Y (= worldY - REGION_MIN_Y), [0, regionHeight)
     * @param regionHeight region 高度 (REGION_HEIGHT)
     * @return 该格应填的基材令牌
     */
    public BaseMaterial select(long seed, int worldX, int worldY, int worldZ, int localY, int regionHeight) {
        // 1) 点缀优先判定 (Easy/Hard 用; Medium accentChance=0 时恒跳过)。
        if (accentChance > 0.0) {
            double accentRoll = unitHash(seed, worldX, worldY, worldZ, FEATURE_BASE_MATERIAL);
            if (accentRoll < accentChance) {
                double pick = unitHash(seed, worldX, worldY, worldZ, FEATURE_ACCENT_PICK);
                return pick < 0.5 ? accentA : accentB;
            }
        }
        // 2) Medium 深度相关深板岩混入 (越深概率越高)。Easy/Hard 两概率都为 0, 恒返回 primary。
        if (deepslateMaxChance > 0.0) {
            // depthRatio: 顶部(localY=regionHeight-1)=0, 底部(localY=0)=1。
            double depthRatio = regionHeight <= 1
                    ? 1.0
                    : 1.0 - ((double) localY / (double) (regionHeight - 1));
            double deepslateChance = deepslateMinChance + (deepslateMaxChance - deepslateMinChance) * depthRatio;
            double deepRoll = unitHash(seed, worldX, worldY, worldZ, FEATURE_BASE_MATERIAL + 1);
            if (deepRoll < deepslateChance) {
                return BaseMaterial.DEEPSLATE;
            }
        }
        return primary;
    }

    /** 把 SeedUtil.hash 的 long 归一化到 [0,1) (取低 53 位避免符号/精度问题)。 */
    private static double unitHash(long seed, int x, int y, int z, int featureId) {
        // SeedUtil.hash 不含 y 维, 这里把 y 折进 z 维以让同列不同高度也独立 (基材逐格独立, 非逐列)。
        long h = SeedUtil.hash(seed, x, z * 31 + y, featureId);
        return (h >>> 11) * 0x1.0p-53;
    }
}
