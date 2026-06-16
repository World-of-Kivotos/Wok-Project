package com.miningdim.core;

/**
 * 基材令牌 (R3)。core 不得 import worldgen / net.minecraft.block, 故难度调色板用本枚举描述"该填哪类基材",
 * 由 worldgen (MiningChunkGenerator) 把令牌映射到具体 BlockState。这样难度调色板的"选择逻辑"留在 core
 * (确定性、与绝对 Y 无关、可单测), 而"令牌 -> 方块"的渲染映射留在 worldgen, 满足模块化铁律 (core 无 worldgen 依赖)。
 *
 * 令牌覆盖三难度调色板所需的全部基材类别: 主基材 (石头/深板岩) + 各难度点缀。worldgen 负责把每个令牌
 * 解析为对应 vanilla BlockState (STONE/DEEPSLATE/ANDESITE/DIORITE/TUFF/BLACKSTONE)。
 */
public enum BaseMaterial {
    /** 石头 (Easy 主基材 / Medium 浅层主基材)。 */
    STONE,
    /** 深板岩 (Hard 主基材 / Medium 深层混入)。 */
    DEEPSLATE,
    /** 安山岩 (Easy 点缀)。 */
    ANDESITE,
    /** 闪长岩 (Easy 点缀)。 */
    DIORITE,
    /** 凝灰岩 (Hard 点缀)。 */
    TUFF,
    /** 黑石 (Hard 点缀)。 */
    BLACKSTONE
}
