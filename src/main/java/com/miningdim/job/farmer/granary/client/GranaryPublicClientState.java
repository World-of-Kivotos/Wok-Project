package com.miningdim.job.farmer.granary.client;

import com.miningdim.job.farmer.granary.GranaryPublicStateS2C;

/**
 * 公开模式面板的客户端缓存: 只保存服务端发来的那一份状态与结果码, 自己不算任何判定。
 *
 * <p>与开放名单那个缓存同一条纪律: 点按钮只是发一个请求, 结果等下一个状态包到达再换 ——
 * 官仓能不能开、开了之后配额多少, 客户端说了不算。</p>
 */
public final class GranaryPublicClientState {

    private static volatile int windowId = -1;
    private static volatile String outcomeKey = "";
    private static volatile boolean donate;
    private static volatile int donateGroups;
    private static volatile boolean take;
    private static volatile int takeGroups;
    private static volatile boolean stateTier;
    private static volatile boolean known;

    private GranaryPublicClientState() {
    }

    public static void apply(GranaryPublicStateS2C msg) {
        if (msg == null) {
            return;
        }
        windowId = msg.windowId();
        outcomeKey = msg.outcomeKey() == null ? "" : msg.outcomeKey();
        donate = msg.donate();
        donateGroups = msg.donateGroups();
        take = msg.take();
        takeGroups = msg.takeGroups();
        stateTier = msg.stateTier();
        known = true;
    }

    /** 换窗口 / 关界面时清掉 (免得把上一个囤的状态带进下一个)。 */
    public static void clear() {
        windowId = -1;
        outcomeKey = "";
        donate = false;
        donateGroups = 0;
        take = false;
        takeGroups = 0;
        stateTier = false;
        known = false;
    }

    public static int windowId() {
        return windowId;
    }

    /** 上一次操作的结果码 (语言键; 空串表示还没操作过)。 */
    public static String outcomeKey() {
        return outcomeKey;
    }

    /** 服务端说过这个囤的公开模式状态吗 (没有就不画按钮, 免得画一个假开关)。 */
    public static boolean known() {
        return known;
    }

    public static boolean donate() {
        return donate;
    }

    public static int donateGroups() {
        return donateGroups;
    }

    public static boolean take() {
        return take;
    }

    public static int takeGroups() {
        return takeGroups;
    }

    /** 这座粮仓是官仓吗 (不是的话两个开关是灰的)。 */
    public static boolean stateTier() {
        return stateTier;
    }
}
