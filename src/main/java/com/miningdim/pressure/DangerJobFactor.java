package com.miningdim.pressure;

import net.minecraft.server.level.ServerPlayer;

/**
 * 压力子系统的 "职业 danger 时间项系数" 接线点 (seam holder, 同 {@code entrance.EntranceHooks} 范式)。
 *
 * Major 缺陷四 (耐压死特性) 的跨子系统接线: danger 时间项 tWin 的累积/衰减须按玩家职业 (当前为矿工耐压技能)
 * 缩放, 但缩放系数的逐级曲线归属各职业子系统 (矿工: {@code MinerSkills.dangerTimeFactor}, 封底 0.6, 不动 zoneTerm、不钳 0)。
 * 压力子系统不得硬 import 职业实现类 (模块化铁律 2 / Miner_Job_DesignSpec 第十一章 "跨子系统经门面, 不硬 import"),
 * 故在此暴露一个绑定点: 职业子系统在服务端启动期 {@link #bind} 注入按玩家求系数的 provider, 压力 tick 经 {@link #factorFor}
 * 取值喂给 {@link Danger#evaluate}。
 *
 * 未接线时 (provider 为空 / 极早期) {@link #factorFor} 返回 1.0 (无缩放, 等价无耐压技能), 不抛异常打断刷怪评估。
 * 绑定/解绑只在主线程的启动/停止事件发生, 运行期只读, 故无需加锁 (volatile 防可见性)。
 */
public final class DangerJobFactor {

    private DangerJobFactor() {
    }

    /**
     * 按玩家求 danger 时间项累积/衰减系数的 provider。返回值语义: tWin 累积/衰减量乘以此系数,
     * 取值越小压力累积越慢 (矿工耐压: 1.0 未解锁 -> 0.60 满级封底)。实现方须保证 > 0 (不钳 0, 防实质免疫压力系统)。
     */
    @FunctionalInterface
    public interface Provider {
        float timeAccrueFactor(ServerPlayer player);
    }

    private static volatile Provider provider;

    /** 职业子系统启动期注入 provider (null 抛 IllegalArgumentException)。 */
    public static void bind(Provider impl) {
        if (impl == null) {
            throw new IllegalArgumentException("DangerJobFactor.Provider must not be null");
        }
        provider = impl;
    }

    /** 服务端停止时清空, 防跨存档/跨重启脏引用。 */
    public static void unbind() {
        provider = null;
    }

    /**
     * 取某玩家的 danger 时间项系数。未接线返回 1.0 (无缩放)。返回值在此硬钳 (0, 1] 防 provider 漂移到 0 或 >1:
     * <=0 会让压力时间项永不累积 (实质免疫压力系统, 破护栏), >1 会让累积超原始速率 (耐压本意是减压不是加压)。
     */
    public static float factorFor(ServerPlayer player) {
        Provider p = provider;
        if (p == null) {
            return 1.0f;
        }
        float f = p.timeAccrueFactor(player);
        if (f <= 0.0f) {
            return Float.MIN_VALUE; // 不钳 0: 极慢累积也比零累积守护栏 (provider 应自封底, 此为防御性兜底)。
        }
        return Math.min(1.0f, f);
    }
}
