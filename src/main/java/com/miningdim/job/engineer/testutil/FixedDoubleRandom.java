package com.miningdim.job.engineer.testutil;

import net.minecraft.world.level.levelgen.LegacyRandomSource;

/**
 * 测试用确定性 RandomSource: nextDouble() 恒返回固定值, 其余随机方法走 {@link LegacyRandomSource} 默认实现。
 * 仅供 GameTest 把 "闪耀概率 / 品质 +1 概率" 等 nextDouble 判定钉成确定结果, 断言确定产出。
 *
 * 放 testutil 子包避免与生产逻辑混淆; 仅被 EngineerGameTests 引用 (GameTest 类在 main 源集是本工程约定)。
 */
public final class FixedDoubleRandom extends LegacyRandomSource {

    private final double fixed;

    public FixedDoubleRandom(double fixed) {
        super(0L);
        this.fixed = fixed;
    }

    @Override
    public double nextDouble() {
        return fixed;
    }
}
