package com.miningdim.job;

import java.util.EnumMap;
import java.util.Map;

/**
 * 客户端职业进度镜像 (实现手册登录同步范式; 对齐 network.ClientDangerState)。仅客户端逻辑端加载
 * (经 DistExecutor 隔离, 见 {@link com.miningdim.network.JobSyncS2C#handle})。
 *
 * 服务端权威: 本类只缓存 S2C 携带的等级/经验, 供 /job HUD 或客户端界面读取, 绝不自算升级。
 * 字段以 EnumMap 持每职业 level/xp; 网络主线程写、客户端读 (单写多读, 用 volatile 引用整体替换保证可见性)。
 */
public final class ClientJobState {

    private ClientJobState() {
    }

    /** 每职业 [level, xp] 的不可变快照引用 (整体替换式更新, 读端无需逐字段同步)。 */
    private static volatile Map<JobId, long[]> snapshot = new EnumMap<>(JobId.class);

    /** 由 JobSyncS2C 客户端 handler 在客户端主线程调用: 整体替换镜像快照。 */
    public static void accept(Map<JobId, long[]> levels) {
        Map<JobId, long[]> copy = new EnumMap<>(JobId.class);
        copy.putAll(levels);
        snapshot = copy;
    }

    /** 某职业客户端镜像等级 (无数据返回 1 级新人默认)。 */
    public static int level(JobId job) {
        long[] lv = snapshot.get(job);
        return lv == null ? JobXpCurve.MIN_LEVEL : (int) lv[0];
    }

    /** 某职业客户端镜像累计有效经验 (无数据返回 0)。 */
    public static long xp(JobId job) {
        long[] lv = snapshot.get(job);
        return lv == null ? 0L : lv[1];
    }
}
