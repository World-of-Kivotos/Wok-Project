package com.miningdim.command;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * reset 命令的二次确认登记表 (设计文档 17.4)。首次 /mining reset <id> 仅登记一个带时间戳的待确认意图,
 * 提示在 reset.confirmationWindowSeconds 内重发 /mining reset <id> confirm; 超时作废。
 *
 * 仅服务端命令线程访问 (RegisterCommandsEvent 派发的命令执行在服务端主线程); ConcurrentHashMap 是对
 * "命令线程与可能的清理巡检"的防御。时间以 server game time (tick) 为基准, 由调用方传入 (不在此读时钟,
 * 保持纯函数式可测)。
 */
public final class ResetConfirmations {

    private ResetConfirmations() {
    }

    /** instanceId -> 首次发起 reset 的 tick。 */
    private static final Map<Long, Long> PENDING = new ConcurrentHashMap<>();

    /** 登记一次待确认意图 (覆盖旧的, 刷新时间戳)。 */
    public static void arm(long instanceId, long nowTick) {
        PENDING.put(instanceId, nowTick);
    }

    /**
     * 校验并消费确认: 若存在未超窗的待确认意图则消费并返回 true; 否则 (无意图或已超窗) 返回 false。
     * 超窗的陈旧意图顺手清除。窗口长度以 tick 计 (windowSeconds * 20)。
     */
    public static boolean confirm(long instanceId, long nowTick, int windowSeconds) {
        Long armedAt = PENDING.get(instanceId);
        if (armedAt == null) {
            return false;
        }
        long windowTicks = (long) windowSeconds * 20L;
        if (nowTick - armedAt > windowTicks) {
            PENDING.remove(instanceId);
            return false;
        }
        PENDING.remove(instanceId);
        return true;
    }

    /** 是否存在未超窗的待确认意图 (供反馈文案判断, 不消费)。 */
    public static boolean isArmed(long instanceId, long nowTick, int windowSeconds) {
        Long armedAt = PENDING.get(instanceId);
        if (armedAt == null) {
            return false;
        }
        long windowTicks = (long) windowSeconds * 20L;
        return nowTick - armedAt <= windowTicks;
    }

    /** 清除指定意图 (取消/已执行后)。 */
    public static void clear(long instanceId) {
        PENDING.remove(instanceId);
    }
}
