package com.miningdim.job.farmer.granary.client;

import com.miningdim.job.farmer.granary.GranaryTrustStateS2C;

import java.util.List;

/**
 * 开放设置面板的客户端缓存: 只保存**服务端发来的那一份名单与结果码**, 不保存任何判定。
 *
 * 界面读它画名单与提示; 点按钮只是发一个请求 ({@link com.miningdim.job.farmer.granary.GranaryTrustActionC2S}),
 * 结果等下一个 {@link GranaryTrustStateS2C} 到达再换 —— 客户端永远不会自己算出"加成功了"。
 */
public final class GranaryTrustClientState {

    private GranaryTrustClientState() {
    }

    private static volatile int windowId = -1;
    private static volatile String outcomeKey = "";
    private static volatile List<String> lines = List.of();
    private static volatile String checkName = "";
    private static volatile String checkKey = "";

    public static void apply(GranaryTrustStateS2C msg) {
        if (msg == null) {
            return;
        }
        windowId = msg.windowId();
        outcomeKey = msg.outcomeKey() == null ? "" : msg.outcomeKey();
        lines = msg.lines();
    }

    /** 换窗口 / 关界面时清掉 (免得把上一个囤的名单带进下一个)。 */
    /** 预检结果 (输入框旁边那一句提示)。 */
    public static void applyCheck(com.miningdim.job.farmer.granary.GranaryTrustCheckS2C msg) {
        if (msg == null) {
            return;
        }
        windowId = msg.windowId();
        checkName = msg.playerName() == null ? "" : msg.playerName();
        checkKey = msg.outcomeKey() == null ? "" : msg.outcomeKey();
    }

    public static void clear() {
        windowId = -1;
        outcomeKey = "";
        lines = List.of();
        checkName = "";
        checkKey = "";
    }

    /** 预检针对的那个名字 (空串表示还没问过)。 */
    public static String checkName() {
        return checkName;
    }

    /** 预检结果码 (语言键)。 */
    public static String checkKey() {
        return checkKey;
    }

    public static int windowId() {
        return windowId;
    }

    /** 上一次操作的结果码 (语言键; 空串表示还没操作过)。 */
    public static String outcomeKey() {
        return outcomeKey;
    }

    public static List<String> lines() {
        return lines;
    }
}