package com.miningdim.reset;

import com.miningdim.core.Difficulty;
import com.miningdim.core.GenState;
import com.miningdim.core.IResetService;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.miningdim.core.RegionBox;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * R6 每难度定时自动重置调度器。开服后由 {@link ResetSystem} 每秒 (20 tick) 检查一次:
 * 对三个固定难度区域, 若 reset.autoResetHours&lt;难度&gt; &gt; 0 且 距上次重置已达间隔, 则进入
 * 预警倒计时 (reset.autoResetWarnSeconds): 每秒向该区域在场玩家广播剩余秒数 -&gt; 到点撤离玩家
 * (IResetService.evacuate) -&gt; 触发 reset(instanceId, NEW_SEED) -&gt; 重置完成回调里更新 lastReset。
 *
 * 每难度独立计时与状态, 互不影响。lastReset 持久化于 {@link AutoResetData} (游戏时间), 重启后续算。
 * 计时时钟统一用矿山维度游戏时间 (mining.getGameTime()): 只在服务端运行时推进, 与 lastReset 同源,
 * 且 vanilla 自身持久化, 故重启后比对正确。
 *
 * 固定实例解析 (模块化铁律 2): 经 core 门面 IInstanceManager 取, 不 import instance 实现类。
 * R1 模型下每难度恰有一个常驻共享实例, 故按 difficulty 在 snapshot 中定位即可。
 *
 * 线程: 全部在服务端主线程 (ResetSystem.onServerTick END 阶段驱动)。
 */
final class AutoResetScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/reset");

    /** 检查周期 (tick): 每秒一次, 倒计时也按秒推进, 故周期与秒对齐。 */
    private static final int CHECK_INTERVAL_TICKS = 20;
    /** 1 小时的 tick 数 (3600 秒 * 20 tick/秒)。 */
    private static final long TICKS_PER_HOUR = 3600L * 20L;
    /** 1 秒的 tick 数。 */
    private static final long TICKS_PER_SECOND = 20L;

    /** 单难度自动重置阶段。 */
    private enum Phase {
        /** 空闲: 等待下一次到期。 */
        IDLE,
        /** 倒计时: 每秒广播剩余秒数, 归零后撤离 + 重置。 */
        COUNTDOWN,
        /** 重置进行中: 已触发 IResetService.reset, 等其 future 完成 (防重复触发)。 */
        RESETTING
    }

    /** 单难度的自动重置运行态。 */
    private static final class DifficultyTimer {
        Phase phase = Phase.IDLE;
        /** COUNTDOWN 阶段剩余秒数 (每秒递减)。 */
        int remainingSeconds;
        /** 上次广播的秒数 (避免同一秒重复广播)。 */
        int lastBroadcastSecond = -1;
    }

    private final MinecraftServer server;
    private final ServerLevel miningLevel;
    private final AutoResetData data;
    private final IResetService resetService;

    private final Map<Difficulty, DifficultyTimer> timers = new EnumMap<>(Difficulty.class);

    /** 上次执行检查的游戏时间, 用于按 CHECK_INTERVAL_TICKS 降频。 */
    private long lastCheckTick = Long.MIN_VALUE;

    AutoResetScheduler(MinecraftServer server, ServerLevel miningLevel, IResetService resetService) {
        this.server = server;
        this.miningLevel = miningLevel;
        this.resetService = resetService;
        this.data = AutoResetData.get(miningLevel);

        long now = miningLevel.getGameTime();
        for (Difficulty d : Difficulty.values()) {
            timers.put(d, new DifficultyTimer());
            // 首次开服 (lastReset 从未记录): 以开服当下为基准, 避免开服瞬间立刻判到期触发一轮刷新。
            if (data.lastReset(d) == AutoResetData.NEVER) {
                data.setLastReset(d, now);
            }
        }
    }

    /** 每服务端 tick 调用 (END 阶段)。按秒降频检查到期与推进倒计时。 */
    void tick() {
        long now = miningLevel.getGameTime();
        if (now - lastCheckTick < CHECK_INTERVAL_TICKS) {
            return;
        }
        lastCheckTick = now;
        for (Difficulty d : Difficulty.values()) {
            tickDifficulty(d, now);
        }
    }

    private void tickDifficulty(Difficulty difficulty, long now) {
        DifficultyTimer timer = timers.get(difficulty);
        switch (timer.phase) {
            case IDLE -> maybeArm(difficulty, timer, now);
            case COUNTDOWN -> tickCountdown(difficulty, timer);
            case RESETTING -> {
                // 等 reset future 完成回调把 phase 复位; 此处不动 (防重复触发)。
            }
            default -> throw new IllegalStateException("unknown auto-reset phase: " + timer.phase);
        }
    }

    /** IDLE: 配置开启且到期则起预警 (或无预警时直接撤离重置)。 */
    private void maybeArm(Difficulty difficulty, DifficultyTimer timer, long now) {
        int hours = MiningServices.config().autoResetHours(difficulty);
        if (hours <= 0) {
            return; // 0 = 关闭该难度定时刷新。
        }
        long intervalTicks = hours * TICKS_PER_HOUR;
        long elapsed = now - data.lastReset(difficulty);
        if (elapsed < intervalTicks) {
            return; // 未到期。
        }

        int warnSeconds = MiningServices.config().autoResetWarnSeconds();
        if (warnSeconds <= 0) {
            // 无预警: 直接撤离并重置。
            triggerReset(difficulty, timer);
            return;
        }
        timer.phase = Phase.COUNTDOWN;
        timer.remainingSeconds = warnSeconds;
        timer.lastBroadcastSecond = -1;
        LOGGER.info("[miningdim] auto-reset due for {} region; warning countdown {}s started",
                difficulty.configName(), warnSeconds);
        broadcastCountdown(difficulty, timer);
    }

    /** COUNTDOWN: 每秒递减并广播; 归零则撤离 + 重置。 */
    private void tickCountdown(Difficulty difficulty, DifficultyTimer timer) {
        timer.remainingSeconds--;
        if (timer.remainingSeconds > 0) {
            broadcastCountdown(difficulty, timer);
            return;
        }
        triggerReset(difficulty, timer);
    }

    /** 向该难度区域在场玩家广播剩余秒数 (同一秒只广播一次)。 */
    private void broadcastCountdown(Difficulty difficulty, DifficultyTimer timer) {
        if (timer.lastBroadcastSecond == timer.remainingSeconds) {
            return;
        }
        timer.lastBroadcastSecond = timer.remainingSeconds;
        InstanceState inst = fixedInstanceFor(difficulty);
        if (inst == null) {
            return;
        }
        Component msg = Component.translatable(
                "message.miningdim.reset.auto_warn", difficulty.configName(), timer.remainingSeconds);
        forEachPlayerInRegion(inst.regionBox(), player -> player.sendSystemMessage(msg));
    }

    /** 撤离该难度区域玩家并触发 NEW_SEED 重置; 重置完成回调更新 lastReset 并复位状态。 */
    private void triggerReset(Difficulty difficulty, DifficultyTimer timer) {
        InstanceState inst = fixedInstanceFor(difficulty);
        if (inst == null) {
            // 固定实例尚未就绪 (极早期): 放弃本轮, 回 IDLE, 下个检查周期重判。
            LOGGER.warn("[miningdim] auto-reset for {} aborted: fixed instance not available yet",
                    difficulty.configName());
            timer.phase = Phase.IDLE;
            return;
        }
        long instanceId = inst.instanceId();

        // 进入 RESETTING 阶段防止倒计时/检查重复触发同一难度的重置。
        timer.phase = Phase.RESETTING;

        // 撤离在场玩家回各自进入前坐标 (主线程传送); 撤离后 playerSet 清空, 满足 reset 的 requireEmpty 前置。
        if (!inst.playerSet().isEmpty()) {
            resetService.evacuate(inst, server);
        }

        LOGGER.info("[miningdim] auto-reset triggering NEW_SEED reset of {} region (instance {})",
                difficulty.configName(), instanceId);

        resetService.reset(instanceId, IResetService.ResetMode.NEW_SEED).whenComplete((ignored, error) ->
                // reset future 可能在工作线程兑现; 回主线程更新持久态与复位 (D8)。
                server.execute(() -> onResetComplete(difficulty, timer, instanceId, error)));
    }

    /** 重置完成回调 (主线程): 成功则记 lastReset = 当前游戏时间; 无论成败都复位 IDLE 以便下轮重判。 */
    private void onResetComplete(Difficulty difficulty, DifficultyTimer timer, long instanceId, Throwable error) {
        if (error != null) {
            Throwable cause = (error instanceof java.util.concurrent.CompletionException && error.getCause() != null)
                    ? error.getCause() : error;
            // 失败不更新 lastReset: 下个周期会再次判到期并重试 (不静默吞错, 记 Major)。
            LOGGER.warn("[miningdim] auto-reset of {} region (instance {}) FAILED: {}",
                    difficulty.configName(), instanceId, cause.toString());
        } else {
            data.setLastReset(difficulty, miningLevel.getGameTime());
            LOGGER.info("[miningdim] auto-reset of {} region (instance {}) complete; next cycle armed",
                    difficulty.configName(), instanceId);
        }
        timer.phase = Phase.IDLE;
        timer.lastBroadcastSecond = -1;
    }

    /**
     * 经 core 门面定位某难度的固定常驻实例 (R1 模型下每难度恰一个)。不 import instance 实现类:
     * 用 IInstanceManager 遍历 snapshot, 取 difficulty 匹配且状态存活的实例。
     */
    private InstanceState fixedInstanceFor(Difficulty difficulty) {
        InstanceState[] found = new InstanceState[1];
        MiningServices.instanceManager().forEach(inst -> {
            if (found[0] == null && inst.difficulty() == difficulty && inst.genState() != GenState.RECYCLED) {
                found[0] = inst;
            }
        });
        return found[0];
    }

    /** 对落在该 region XZ 范围内的矿山维度在场玩家执行操作。 */
    private void forEachPlayerInRegion(RegionBox box, java.util.function.Consumer<ServerPlayer> action) {
        List<ServerPlayer> players = miningLevel.players();
        for (ServerPlayer player : players) {
            if (box.contains((int) player.getX(), (int) player.getZ())) {
                action.accept(player);
            }
        }
    }
}
