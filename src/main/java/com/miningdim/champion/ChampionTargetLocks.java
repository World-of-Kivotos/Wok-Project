package com.miningdim.champion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 锁定类技能的 per-玩家互斥登记 (ChampionStarAffix spec 7.4/第八章: 命定之死 ⨉ 反击单元 不在同一玩家并行计时;
 * Stage2 批3)。同一冠军身上两词条已由 {@link AffixDef.MutexFlag#DEATH_MARK} 同族互斥挡住; 本类补【跨冠军】维度:
 * 任一玩家同一时刻至多被一个锁定类技能 (命定标记 / 反击锁定) 计时, 两只 8★ BOSS 同场也不得对同一玩家叠锁。
 *
 * 语义: 一玩家一锁 (最严口径, 不区分锁种类 —— 已被反击锁定者命定不可再标, 反之亦然)。锁带过期 tick,
 * 查询/申请时惰性剔除过期项, 技能正常结束应显式 {@link #release} (过期是兜底非常态)。
 *
 * 静态注册表 + 服务端主线程串行读写 (与 BloodPoolRegistry 同纪律); 服务器重启静态态自然清空。跨存档切换的
 * 陈旧项由过期 tick 兜底 (gameTime 回拨极端情形下锁至多多活一个 duration, 可接受, 不为此加世界引用)。
 */
public final class ChampionTargetLocks {

    /** 锁定类技能种类 (诊断/归属用; 互斥判定不区分种类)。 */
    public enum LockKind {
        DEATH_MARK,
        COUNTER_UNIT
    }

    /** 一条在册锁: 持锁冠军 + 种类 + 过期 tick (绝对 gameTime)。 */
    public record Lock(LockKind kind, UUID champion, long expiryTick) {
    }

    /** 玩家 UUID -> 在册锁 (至多一条)。 */
    private static final Map<UUID, Lock> LOCKS = new HashMap<>();

    private ChampionTargetLocks() {
    }

    /**
     * 申请对某玩家上锁: 该玩家当前无未过期锁则登记并返 true; 已被任一锁定类技能占用返 false (调用方跳过本周期)。
     *
     * @param player        目标玩家 UUID
     * @param kind          锁种类
     * @param champion      持锁冠军 UUID
     * @param nowTick       当前 gameTime
     * @param durationTicks 锁时长 (tick, 须 &gt;0)
     * @return 是否成功上锁
     */
    public static boolean tryAcquire(UUID player, LockKind kind, UUID champion, long nowTick, long durationTicks) {
        if (player == null || kind == null || champion == null) {
            throw new IllegalArgumentException("player/kind/champion must not be null");
        }
        if (durationTicks <= 0L) {
            throw new IllegalArgumentException("durationTicks must be > 0, got " + durationTicks);
        }
        Lock existing = LOCKS.get(player);
        if (existing != null && existing.expiryTick() > nowTick) {
            return false; // 玩家已被锁定类技能占用 (红线: 不并行计时)。
        }
        LOCKS.put(player, new Lock(kind, champion, nowTick + durationTicks));
        return true;
    }

    /** 显式释放某冠军对某玩家的锁 (技能正常结束/冠军死亡); 持锁者非该冠军则不动 (防误摘他怪的锁)。 */
    public static void release(UUID player, UUID champion) {
        Lock existing = LOCKS.get(player);
        if (existing != null && existing.champion().equals(champion)) {
            LOCKS.remove(player);
        }
    }

    /** 某玩家当前未过期的锁 (无/已过期返 null; 过期项顺手剔除)。 */
    public static Lock activeLock(UUID player, long nowTick) {
        Lock existing = LOCKS.get(player);
        if (existing == null) {
            return null;
        }
        if (existing.expiryTick() <= nowTick) {
            LOCKS.remove(player);
            return null;
        }
        return existing;
    }

    /** 服务端停止清空 (防跨存档脏引用; 供 ServerStoppingEvent)。 */
    public static void reset() {
        LOCKS.clear();
    }

    /** 在册锁数 (诊断/测试)。 */
    public static int size() {
        return LOCKS.size();
    }
}
