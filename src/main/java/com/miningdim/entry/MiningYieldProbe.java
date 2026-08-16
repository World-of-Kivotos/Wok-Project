package com.miningdim.entry;

import com.miningdim.core.Difficulty;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 标定期矿洞单趟产出仪表。状态仅驻留当前服务端进程, 不引入第二份持久化真值。
 * 所有调用均发生在服务端主线程: 成功入场开始、统一离开点结算、卖矿成功后累加。
 */
public final class MiningYieldProbe {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/yield-probe");
    private static final Map<UUID, Accumulator> ACTIVE = new HashMap<>();

    private MiningYieldProbe() {
    }

    static void start(ServerPlayer player, Difficulty difficulty) {
        UUID playerId = player.getUUID();
        Accumulator previous = ACTIVE.putIfAbsent(
                playerId, new Accumulator(difficulty, player.server.getTickCount()));
        if (previous != null) {
            throw new IllegalStateException("yield probe already active for player " + playerId);
        }
    }

    /** 卖矿成功落账后累加一批高价矿产出; 没有活动行程的普通经济调用不属于采样范围。 */
    public static void record(ServerPlayer player, int drops, long gross) {
        if (drops <= 0 || gross < 0L) {
            throw new IllegalArgumentException("yield probe drops must be positive and gross must be non-negative");
        }
        Accumulator accumulator = ACTIVE.get(player.getUUID());
        if (accumulator == null) {
            return;
        }
        accumulator.oreDrops = Math.addExact(accumulator.oreDrops, drops);
        accumulator.creditGross = Math.addExact(accumulator.creditGross, gross);
    }

    /** 统一离开点调用。无活动采样是合法 no-op, 例如重启后恢复的旧 Capability。 */
    static Optional<YieldSample> finish(ServerPlayer player) {
        Accumulator accumulator = ACTIVE.remove(player.getUUID());
        if (accumulator == null) {
            return Optional.empty();
        }
        long dwellTicks = (long) player.server.getTickCount() - accumulator.startedAtTick;
        if (dwellTicks < 0L) {
            throw new IllegalStateException("yield probe server tick moved backwards for player " + player.getUUID());
        }
        YieldSample sample = new YieldSample(
                accumulator.difficulty, dwellTicks, accumulator.oreDrops, accumulator.creditGross);
        LOGGER.info("[miningdim] yield-probe difficulty={} dwellTicks={} oreDrops={} creditGross={}",
                sample.difficulty().configName(), sample.dwellTicks(), sample.oreDrops(), sample.creditGross());
        return Optional.of(sample);
    }

    /** 新服务端启动时清掉同 JVM 上一轮残留的进程内采样。 */
    static void clear() {
        ACTIVE.clear();
    }

    record YieldSample(Difficulty difficulty, long dwellTicks, long oreDrops, long creditGross) {
    }

    private static final class Accumulator {
        private final Difficulty difficulty;
        private final long startedAtTick;
        private long oreDrops;
        private long creditGross;

        private Accumulator(Difficulty difficulty, long startedAtTick) {
            this.difficulty = difficulty;
            this.startedAtTick = startedAtTick;
        }
    }
}
