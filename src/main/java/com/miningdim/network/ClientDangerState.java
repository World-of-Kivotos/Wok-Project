package com.miningdim.network;

import com.miningdim.core.IMiningNetwork;

/**
 * 客户端 danger 渲染态单例 (设计文档 15.4.2)。仅客户端逻辑端加载 (经 DistExecutor 隔离)。
 *
 * 服务端权威 (N2): 本类只缓存 DangerSyncS2C 携带的服务端计算结果, 供 HUD 与屏幕压暗 overlay 读取,
 * 绝不自算 danger。字段 volatile: 网络主线程写、渲染线程读 (1.20.1 渲染与客户端逻辑可不同帧读)。
 *
 * 边界用例 (15.6 PASS 判据): 停发 DangerSyncS2C 后, 这些字段停在旧值不变, 证明客户端不自算。
 */
public final class ClientDangerState {

    private ClientDangerState() {
    }

    private static volatile long instanceId = -1L;
    private static volatile float danger = 0.0f;
    private static volatile float dangerMax = 1.0f;
    private static volatile IMiningNetwork.DangerTier tier = IMiningNetwork.DangerTier.SAFE;
    private static volatile float lightDimFactor = 0.0f;

    /** 由 DangerSyncS2C 客户端 handler 在客户端主线程调用 (15.4.2)。 */
    public static void accept(DangerSyncS2C msg) {
        instanceId = msg.instanceId();
        danger = msg.danger();
        dangerMax = msg.dangerMax();
        tier = msg.tierEnum();
        lightDimFactor = msg.lightDimFactor();
    }

    public static long instanceId() {
        return instanceId;
    }

    public static float danger() {
        return danger;
    }

    public static float dangerMax() {
        return dangerMax;
    }

    public static IMiningNetwork.DangerTier tier() {
        return tier;
    }

    /** 屏幕压暗系数 [0,1], 供渲染滤镜 overlay 使用 (HUD 子系统在后续阶段消费)。 */
    public static float lightDimFactor() {
        return lightDimFactor;
    }
}
