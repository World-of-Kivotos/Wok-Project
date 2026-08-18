package com.miningdim.testutil;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * GameTest 配置基线归位。
 *
 * 存在理由 (2026-08-19 连续三次假红后加): 一批用例为了验"配置是实时读的而不是快照", 会把某个键临时改成
 * 探针值再在 finally 还原。问题出在 ForgeConfigSpec 的写入是<b>落盘</b>的, 而 runGameTestServer 复用
 * run/world 存档 —— 探针值一旦在还原落盘之前被 Forge 保存, 就会留在 run/world/serverconfig/ 里跨轮存活。
 * 下一轮读到的"原值"已经是污染值, 于是:
 *  - 断言绝对数值的用例 (如 munitions 的 capacityCurveLookups 断言 L1 台数恒为 1) 直接判红;
 *  - 更糟的是探针常写成"原值 + N", 于是每跑一轮涨一次, 污染自我放大 (实测 tableCountL1 反复涨到 6)。
 *
 * 因此每个会改配置的批次都在 {@code @BeforeBatch} 里先把自己要动的键归位到 spec 默认值, 使该批次的起点
 * 与上一轮残留无关。用例内部原有的 "读原值 -> 设探针 -> finally 还原" 结构保持不变 (它保证的是批次内部
 * 用例之间不互相污染, 与本类解决的跨轮污染是两件事)。
 */
public final class ConfigBaseline {

    private ConfigBaseline() {
    }

    /** 把给定配置项全部写回其 spec 声明的默认值。 */
    public static void resetToDefaults(ForgeConfigSpec.ConfigValue<?>... values) {
        for (ForgeConfigSpec.ConfigValue<?> value : values) {
            resetOne(value);
        }
    }

    /** 单独一项: 需要独立的类型参数, 让 getDefault() 的返回类型与 set() 的入参类型在捕获后对齐。 */
    private static <T> void resetOne(ForgeConfigSpec.ConfigValue<T> value) {
        value.set(value.getDefault());
    }
}
