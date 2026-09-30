package com.miningdim.webui.server;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * hub.panels 的"受门控"面板 (docs/District_Backend_Design.md 20.9): 这类面板只在有人登记了门、且门此刻返回真时才下发;
 * 没有登记、或门返回假时整条不下发 (不是下发成锁着的 —— 功能没开的服务器上, 玩家根本不该知道有这个页面;
 * enabled=false 的语义是"看得见、进不去")。
 *
 * <p>门由拥有面板的模块在 register 阶段登记 (依赖方向是那个模块 -&gt; webui, 本来就有), webui 不因此反向依赖它。
 * 当前只有自管区 (district) 一个受门控的面板。
 */
public final class HubPanelGates {

    private static final Map<String, BooleanSupplier> GATES = new ConcurrentHashMap<>();

    private HubPanelGates() {
    }

    /** 登记 (同一个 id 再登记会覆盖)。 */
    public static void register(String panelId, BooleanSupplier visible) {
        GATES.put(panelId, visible);
    }

    /** 这个受门控的面板此刻该不该下发: 没有登记的一律不下发; 门抛异常也按不下发处理。 */
    static boolean visible(String panelId) {
        BooleanSupplier gate = GATES.get(panelId);
        if (gate == null) {
            return false;
        }
        try {
            return gate.getAsBoolean();
        } catch (RuntimeException failure) {
            return false;
        }
    }
}
