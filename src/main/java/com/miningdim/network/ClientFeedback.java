package com.miningdim.network;

import com.miningdim.core.GenState;
import com.miningdim.core.IMiningNetwork;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * 客户端反馈分发 (设计文档 15.4.3 / 15.4.4)。仅客户端逻辑端加载 (经 DistExecutor 隔离)。
 *
 * 职责: 把 TeleportResultS2C / InstanceStatusS2C 落到客户端可见反馈。当前阶段无自定义 HUD/Screen,
 * 故传送结果以 actionbar (聊天上方提示条) 显示本地化文案; 实例状态缓存到本类静态字段供后续 GUI 子系统读取
 * (本包不画界面, 只持态)。所有访问均在客户端主线程 (handler enqueueWork 内)。
 */
public final class ClientFeedback {

    private ClientFeedback() {
    }

    // 最近一次收到的实例状态快照 (后续 GUI 列表/进度条消费; 单订阅模型够用, 多订阅时由 GUI 子系统扩展为 map)。
    private static volatile long lastInstanceId = -1L;
    private static volatile GenState lastGenState = GenState.PENDING;
    private static volatile float lastGenProgress = 0.0f;
    private static volatile int lastPlayerCount = 0;

    /**
     * 传送结果反馈 (15.4.3): 用 reasonKey 本地化, QUEUED 追加排队位次。以 actionbar 显示, 不弹独立窗口。
     * reasonKey 为空时回退到按 result 枚举推导的默认 key, 避免空串导致无文案。
     */
    public static void acceptTeleportResult(TeleportResultS2C msg) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        IMiningNetwork.TeleportResult result = msg.resultEnum();
        String key = msg.reasonKey();
        if (key == null || key.isEmpty()) {
            key = defaultKey(result);
        }
        Component text;
        if (result == IMiningNetwork.TeleportResult.QUEUED && msg.queuePos() >= 0) {
            text = Component.translatable(key, msg.queuePos());
        } else {
            text = Component.translatable(key);
        }
        // actionbar (overlay=true): 轻量提示, 不刷屏聊天框。
        player.displayClientMessage(text, true);
    }

    /** 实例状态缓存 (15.4.4): 更新单订阅快照供 GUI 读取。 */
    public static void acceptInstanceStatus(InstanceStatusS2C msg) {
        lastInstanceId = msg.instanceId();
        lastGenState = msg.genStateEnum();
        lastGenProgress = msg.genProgress();
        lastPlayerCount = msg.playerCount();
    }

    public static long lastInstanceId() {
        return lastInstanceId;
    }

    public static GenState lastGenState() {
        return lastGenState;
    }

    public static float lastGenProgress() {
        return lastGenProgress;
    }

    public static int lastPlayerCount() {
        return lastPlayerCount;
    }

    private static String defaultKey(IMiningNetwork.TeleportResult result) {
        return switch (result) {
            case SUCCESS -> "commands.miningdim.enter.success";
            case QUEUED -> "commands.miningdim.enter.queued";
            case REJECTED_FULL -> "commands.miningdim.enter.full";
            case REJECTED_GENERATING -> "commands.miningdim.enter.generating";
            case ERROR -> "commands.miningdim.enter.error";
        };
    }
}
