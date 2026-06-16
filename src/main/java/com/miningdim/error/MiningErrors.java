package com.miningdim.error;

import com.miningdim.core.IMiningNetwork;
import com.miningdim.core.MiningServices;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 边界场景兜底 helper (设计文档第二十章)。提供各子系统在"最外层"统一使用的两类能力:
 *
 * 1) 顶层兜底包裹 ({@link #guard}): 命令 handler / 网络 packet handler / 进入 Gateway 把业务逻辑放进
 *    一个 {@code Runnable}, 异常自然冒泡到这里被捕获, 记 ERROR 日志并 (可选) 给玩家友好文案,
 *    绝不让异常崩服 (20.1)。业务函数内部严禁本地 try/catch 生吞 (CLAUDE.md 异常纪律 + 20.1)。
 *
 * 2) 确定性降级与玩家提示常量 ({@link #connectivityDegrade} 等返回值 + {@link #notify} 文案下发):
 *    算法"失败"不是异常而是降级 (20.1), 这里给出降级判定阈值与降级路径标识, 供生成/出生/实例
 *    各子系统在其降级分支调用, 保证全库降级口径一致。
 *
 * 全部静态: 无状态, 仅依赖 {@link MiningServices} 取网络门面下发文案。日志用 org.slf4j (CLAUDE.md)。
 */
public final class MiningErrors {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/error");

    private MiningErrors() {
    }

    // ============================================================
    // 1. 顶层兜底包裹 (20.1 最外层 try/catch)
    // ============================================================

    /**
     * 在最外层包裹一段可能抛异常的业务逻辑。异常被捕获、记 ERROR 日志, 不再上抛 (防崩服)。
     * 仅用于命令 / 网络 handler / 进入 Gateway 等真正的最外层; 业务函数内部不得调用此法掩盖异常。
     *
     * @param context 出错时写入日志的上下文标签 (如 "command:/mining enter")
     * @param body    业务逻辑
     * @return true=正常执行完毕; false=捕获到异常 (调用方据此决定是否回滚 / 提示)
     */
    public static boolean guard(String context, Runnable body) {
        try {
            body.run();
            return true;
        } catch (RuntimeException ex) {
            LOGGER.error("[miningdim] unhandled exception at outer boundary: {}", context, ex);
            return false;
        }
    }

    /**
     * 同 {@link #guard(String, Runnable)}, 但捕获异常时额外给玩家下发一条友好文案 (不暴露堆栈, 20.1)。
     * 供"面向单个玩家的交互入口" (进入 / 重置命令) 使用。
     *
     * @param context        日志上下文
     * @param player         出错时要提示的玩家
     * @param playerMessageKey 给玩家的 translation key (见 {@link MiningMessages})
     * @param body           业务逻辑
     * @return true=正常; false=已兜底并已提示玩家
     */
    public static boolean guardForPlayer(String context, ServerPlayer player, String playerMessageKey, Runnable body) {
        try {
            body.run();
            return true;
        } catch (RuntimeException ex) {
            LOGGER.error("[miningdim] unhandled exception at outer boundary: {} (player={})",
                    context, player.getGameProfile().getName(), ex);
            notify(player, playerMessageKey);
            return false;
        }
    }

    // ============================================================
    // 2. 玩家文案下发 (经网络门面, 20.1 可本地化、不暴露堆栈)
    // ============================================================

    /**
     * 给玩家发送一条系统消息 (本地化 translation key)。优先经 {@link IMiningNetwork} 的传送结果通道
     * 不合适 (它专用于传送结果), 故文案直接用原版 {@code ServerPlayer.sendSystemMessage} 下发
     * {@code Component.translatable(key)} —— 客户端按 key 本地化, 服务端不拼接最终文本 (20.1)。
     *
     * @param player 目标玩家
     * @param key    translation key (见 {@link MiningMessages})
     * @param args   可选占位参数 (如排队位次)
     */
    public static void notify(ServerPlayer player, String key, Object... args) {
        player.sendSystemMessage(Component.translatable(key, args));
    }

    /**
     * 实例池满 / 并发上限场景的标准提示 (20.2)。拒绝时 queuePos 传 -1 (不附位次), 排队时传位次。
     * 同步经网络门面发 {@link IMiningNetwork.TeleportResult} 让客户端走传送结果 UI, 并下发文案。
     */
    public static void notifyInstancesFull(ServerPlayer player, int queuePos) {
        IMiningNetwork net = MiningServices.network();
        IMiningNetwork.TeleportResult result =
                queuePos >= 0 ? IMiningNetwork.TeleportResult.QUEUED : IMiningNetwork.TeleportResult.REJECTED_FULL;
        net.sendTeleportResult(player, result, -1L, queuePos, MiningMessages.INSTANCES_FULL);
        notify(player, MiningMessages.INSTANCES_FULL, queuePos);
        if (queuePos > QUEUE_LONG_WARN_THRESHOLD) {
            LOGGER.warn("[miningdim] enter queue is long: player={} pos={}",
                    player.getGameProfile().getName(), queuePos);
        } else {
            LOGGER.info("[miningdim] instances full: player={} queuePos={}",
                    player.getGameProfile().getName(), queuePos);
        }
    }

    // ============================================================
    // 3. 确定性降级路径与阈值 (20.2; 算法"失败"= 降级而非异常)
    // ============================================================

    /** 连通性修复后主连通分量占比合格阈值 (gen.minConnectedRatio, 20.2 建议 0.98)。 */
    public static final double MIN_CONNECTED_RATIO = 0.98D;

    /** 连通性失败后用新子 seed 重跑的最大次数 (gen.maxRetries, 20.2 建议 3)。 */
    public static final int MAX_GEN_RETRIES = 3;

    /** 连通分量被判为"孤岛需填实"的最小体积下限 (gen.minVolume, 20.2; 降级后主分量体积低于此判 BROKEN)。 */
    public static final int MIN_MAIN_VOLUME = 4096;

    /** 找不到安全 spawn 时强制建造的安全平台边长 (20.2: 3x3x3)。 */
    public static final int FALLBACK_PLATFORM_SIZE = 3;

    /** 进入流程等待 genState==READY 的超时 tick (enter.timeoutTicks, 20.2 建议 200)。 */
    public static final int ENTER_TIMEOUT_TICKS = 200;

    /** 排队位次超过此值记 WARN (队列超长, 20.2 队列超长 WARN)。 */
    private static final int QUEUE_LONG_WARN_THRESHOLD = 16;

    /**
     * 连通性降级裁决 (20.2 第一行)。给定当前连通占比与已重试次数, 返回应采取的处置。
     * 这是确定性裁决 (纯函数), 不抛异常: 算法"失败"按 20.1 是降级而非异常。
     *
     * @param connectedRatio 主连通分量 / 总空气体素 ∈ [0,1]
     * @param retryCount     已重试次数 (0 = 首次评估)
     * @return 处置档
     */
    public static ConnectivityVerdict connectivityVerdict(double connectedRatio, int retryCount) {
        if (connectedRatio >= MIN_CONNECTED_RATIO) {
            return ConnectivityVerdict.ACCEPT;
        }
        if (retryCount < MAX_GEN_RETRIES) {
            LOGGER.warn("[miningdim] connectivity below threshold ({} < {}), retry {}/{}",
                    connectedRatio, MIN_CONNECTED_RATIO, retryCount + 1, MAX_GEN_RETRIES);
            return ConnectivityVerdict.RETRY;
        }
        LOGGER.error("[miningdim] connectivity still failing after {} retries (ratio={}), forcing degrade-fill",
                MAX_GEN_RETRIES, connectedRatio);
        return ConnectivityVerdict.DEGRADE_FILL;
    }

    /**
     * 降级填实后主分量体积复核 (20.2 第一行兜底分支): 体积 < {@link #MIN_MAIN_VOLUME} 判该 seed BROKEN,
     * 调用方据此换 instanceId 派生 seed 重生成。
     *
     * @param mainVolume 降级后主连通分量体素数
     * @return true=该 seed 已损坏, 需换 seed 重生成
     */
    public static boolean isSeedBroken(int mainVolume) {
        boolean broken = mainVolume < MIN_MAIN_VOLUME;
        if (broken) {
            LOGGER.error("[miningdim] degraded main component too small ({} < {}), seed marked BROKEN",
                    mainVolume, MIN_MAIN_VOLUME);
        }
        return broken;
    }

    /**
     * 矿山维度未加载的不可恢复错误 (20.2: ServerLevel 为 null)。记 ERROR 并给玩家管理员提示文案。
     * 这是配置/数据包错误, 调用方在拿到 null 的 ServerLevel 时调用本法, 然后拒绝进入。
     */
    public static void reportDimensionMissing(ServerPlayer player) {
        LOGGER.error("[miningdim] mining dimension ServerLevel is null; check datapack dimension/mining.json");
        notify(player, MiningMessages.DIMENSION_MISSING);
    }

    /**
     * 取矿山维度 ServerLevel; 为 null 时记 ERROR 并返回 null (调用方据此拒绝进入, 见 20.2)。
     * 不抛异常 —— 维度缺失是配置错误而非业务异常, 走拒绝路径而非崩服。
     */
    public static ServerLevel miningLevelOrReport(net.minecraft.server.MinecraftServer server, ServerPlayer player) {
        ServerLevel level = server.getLevel(com.miningdim.core.MiningConstants.MINING_LEVEL);
        if (level == null) {
            reportDimensionMissing(player);
        }
        return level;
    }

    /** 连通性裁决档 (20.2)。 */
    public enum ConnectivityVerdict {
        /** 占比达标, 接受当前生成结果。 */
        ACCEPT,
        /** 占比不足且仍有重试额度, 用新子 seed 重跑。 */
        RETRY,
        /** 重试耗尽, 强制把非主分量填实换 100% 连通。 */
        DEGRADE_FILL
    }
}
