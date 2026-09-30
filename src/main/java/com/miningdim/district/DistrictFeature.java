package com.miningdim.district;

import com.miningdim.district.flan.ReconcileScheduler;
import net.minecraft.gametest.framework.GameTestServer;
import net.minecraftforge.server.ServerLifecycleHooks;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 自管区的功能状态 (设计文档 20.2): 关闭 / 降级 (附原因) / 生效。平板导航入口 (hub.panels 的门) 与 /district status 读它。
 *
 * <table>
 *   <tr><th>状态</th><th>条件</th><th>服务</th><th>网关</th><th>开服备份、对账</th><th>导航入口</th></tr>
 *   <tr><td>OFF</td><td>enabled = false</td><td>不绑定</td><td>—</td><td>不做</td><td>不显示</td></tr>
 *   <tr><td>DEGRADED</td><td>开着但 Flan 没装、版本不符、自检不过或运行中熔断</td><td>绑定</td><td>Disabled (带原因)</td>
 *       <td>不做</td><td>不显示</td></tr>
 *   <tr><td>LIVE</td><td>开着且自检通过</td><td>绑定</td><td>真网关</td><td>做</td><td>显示</td></tr>
 * </table>
 * 进程级的静态状态, 由 {@link DistrictSystem} 在 ServerStarting 设置、ServerStopping 复位; 真网关熔断时转为 DEGRADED。
 */
public final class DistrictFeature {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/district");

    public enum State {
        OFF,
        DEGRADED,
        LIVE
    }

    /** 一次状态: 降级原因、Flan 版本 (没装为 null)、自检问题清单、网关的类名。 */
    public record Status(State state, @Nullable String reason, @Nullable String flanVersion,
                         List<String> selfCheckProblems, String gateway) {

        public Status {
            selfCheckProblems = List.copyOf(selfCheckProblems);
        }
    }

    private static final Status OFF_STATUS = new Status(State.OFF, null, null, List.of(), "-");

    private static volatile Status status = OFF_STATUS;
    @Nullable
    private static volatile ReconcileScheduler scheduler;

    private DistrictFeature() {
    }

    public static Status status() {
        return status;
    }

    public static State state() {
        return status.state();
    }

    /** 生效: 平板显示自管区入口, 开服备份与对账照常跑。 */
    public static boolean live() {
        return status.state() == State.LIVE;
    }

    @Nullable
    public static ReconcileScheduler scheduler() {
        return scheduler;
    }

    /** 开服选定之后 (DistrictSystem)。 */
    static void set(Status next, @Nullable ReconcileScheduler nextScheduler) {
        status = next;
        scheduler = nextScheduler;
    }

    /** 停服 (DistrictSystem)。 */
    static void reset() {
        status = OFF_STATUS;
        scheduler = null;
    }

    /**
     * 真网关熔断 (运行中出了 LinkageError, 20.2 第 3 条): 转为 DEGRADED, 导航入口随之隐藏, 对账停下; 重启才恢复。
     * 只转一次 (LIVE 才转)。
     */
    public static void fuse(String reason) {
        Status current = status;
        if (current.state() != State.LIVE) {
            return;
        }
        status = new Status(State.DEGRADED, reason, current.flanVersion(), current.selfCheckProblems(),
                current.gateway());
        LOGGER.error("[miningdim] district feature degraded until restart: {}", reason);
    }

    /**
     * 只供 GameTest: 把状态翻成指定值, 返回复原用的句柄。只在 GameTest 服务端上可用 (门同登录门的
     * forceVerdictForTest): 专用服务器与单人存档里调用直接抛 IllegalStateException。
     */
    public static AutoCloseable forceForTest(State state, @Nullable String reason) {
        if (!(ServerLifecycleHooks.getCurrentServer() instanceof GameTestServer)) {
            throw new IllegalStateException("DistrictFeature.forceForTest is only available on the GameTest server");
        }
        Status previous = status;
        ReconcileScheduler previousScheduler = scheduler;
        status = new Status(state, reason, previous.flanVersion(), previous.selfCheckProblems(), previous.gateway());
        return () -> {
            status = previous;
            scheduler = previousScheduler;
        };
    }
}
