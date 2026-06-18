package com.miningdim.job.tarot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 周期/延迟效果调度 (TarotReader spec 第十二章)。愚者每 30s 治疗、世界每 5s 补黄心、最大生命到期归还等都是
 * "未来某 tick 执行" 或 "每 N tick 重复 M 次"。本类持内存态任务队列, 挂全局服务器 tick 推进。
 *
 * 时钟用 {@link MinecraftServer#getTickCount()} (spec 红线: 全局时钟, 跨维度一致)。任务绑定 ownerUUID,
 * 登出/死亡时按 owner 清队列 ({@link #cancelFor(UUID)}), 防离线/重生后仍触发 (spec 第十二章: 登出/死亡清队列)。
 *
 * 任务回调接收 owner 当前 {@link ServerPlayer} (调度时按 UUID 解析在线玩家); 玩家离线则该次跳过且任务取消。
 * 全部主线程 (ServerTickEvent.END) 访问, 无并发保护。
 */
public final class ScheduledEffectManager {

    /** 一个调度任务: 在 nextRunTick 执行 action, 剩 remaining 次, 每次间隔 periodTicks。 */
    private static final class Task {
        final UUID owner;
        final Consumer<ServerPlayer> action;
        final int periodTicks;
        int remaining;
        long nextRunTick;

        Task(UUID owner, Consumer<ServerPlayer> action, int periodTicks, int count, long firstRunTick) {
            this.owner = owner;
            this.action = action;
            this.periodTicks = periodTicks;
            this.remaining = count;
            this.nextRunTick = firstRunTick;
        }
    }

    private final List<Task> tasks = new ArrayList<>();

    /**
     * 排一个周期任务: 从 now + delayTicks 起, 每 periodTicks 执行 action 一次, 共 count 次。
     * delayTicks 即首次执行的延迟 (愚者 "每 30s" 用 delay=period=600; 倒吊人 "18s 延迟记账" 用 count=1)。
     */
    public void schedule(ServerPlayer owner, int delayTicks, int periodTicks, int count, Consumer<ServerPlayer> action) {
        if (count <= 0) {
            return;
        }
        long now = owner.getServer().getTickCount();
        tasks.add(new Task(owner.getUUID(), action, periodTicks, count, now + delayTicks));
    }

    /** 排一个单次延迟任务 (倒吊人闪耀 18s 后结算、世界闪耀单次等)。 */
    public void scheduleOnce(ServerPlayer owner, int delayTicks, Consumer<ServerPlayer> action) {
        schedule(owner, delayTicks, 1, 1, action);
    }

    /** 取消某玩家全部待执行任务 (登出/死亡; spec 第十二章清队列防泄漏)。 */
    public void cancelFor(UUID owner) {
        tasks.removeIf(t -> t.owner.equals(owner));
    }

    /** 当前某玩家的待执行任务数 (测试/诊断: 验证清队列)。 */
    public int pendingCountFor(UUID owner) {
        int n = 0;
        for (Task t : tasks) {
            if (t.owner.equals(owner)) {
                n++;
            }
        }
        return n;
    }

    /**
     * 全局 tick 推进: 执行所有到点任务。每个任务执行后若仍有剩余次数则重排下一次, 否则移除。
     * owner 离线时该任务取消 (不执行剩余次数)。在 ServerTickEvent.END 主线程调用。
     */
    public void tick(MinecraftServer server) {
        if (tasks.isEmpty()) {
            return;
        }
        long now = server.getTickCount();
        Iterator<Task> it = tasks.iterator();
        // 收集本 tick 要执行的任务后再回调 (回调可能再 schedule, 避免遍历中改集合)。
        List<Runnable> due = new ArrayList<>();
        while (it.hasNext()) {
            Task t = it.next();
            if (now < t.nextRunTick) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(t.owner);
            if (player == null) {
                // owner 离线: 取消该任务 (spec: 登出清队列)。
                it.remove();
                continue;
            }
            final ServerPlayer ref = player;
            final Task task = t;
            due.add(() -> task.action.accept(ref));
            t.remaining--;
            if (t.remaining <= 0) {
                it.remove();
            } else {
                t.nextRunTick = now + t.periodTicks;
            }
        }
        for (Runnable r : due) {
            r.run();
        }
    }
}
