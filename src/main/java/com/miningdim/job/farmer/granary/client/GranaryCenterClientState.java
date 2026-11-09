package com.miningdim.job.farmer.granary.client;

import com.miningdim.job.farmer.granary.GranaryCenterPage;
import com.miningdim.job.farmer.granary.GranaryCenterPageS2C;

import java.util.List;

/**
 * 账台界面的客户端缓存 (规格「账台的多页签界面」): 只保存**服务端发来的那一页文本**, 不保存任何粮仓数据。
 *
 * 界面每次渲染都读它; 点页签只是发一个请求 ({@link com.miningdim.job.farmer.granary.GranaryCenterPageC2S}),
 * 内容等下一个 {@link GranaryCenterPageS2C} 到达再换 —— 客户端永远不会自己算一页出来。
 */
public final class GranaryCenterClientState {

    private GranaryCenterClientState() {
    }

    private static volatile int windowId = -1;
    private static volatile GranaryCenterPage page = GranaryCenterPage.STRUCTURE;
    private static volatile List<String> lines = List.of();

    /** 收到一页 (客户端线程)。 */
    public static void apply(GranaryCenterPageS2C msg) {
        if (msg == null) {
            return;
        }
        windowId = msg.windowId();
        page = GranaryCenterPage.byIndex(msg.page());
        lines = msg.lines();
    }

    /** 换窗口 / 关界面时清掉 (免得把上一座账台的报告带进下一座)。 */
    public static void clear() {
        windowId = -1;
        page = GranaryCenterPage.STRUCTURE;
        lines = List.of();
    }

    public static int windowId() {
        return windowId;
    }

    public static GranaryCenterPage page() {
        return page;
    }

    public static List<String> lines() {
        return lines;
    }
}