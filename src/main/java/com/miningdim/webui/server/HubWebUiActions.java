package com.miningdim.webui.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.webui.server.WebUiServerDispatcher.WebUiAction;

import java.util.List;

/**
 * 平板 hub 的面板可达性 action (hub.panels)。
 *
 * <h2>只下发"能不能进", 不下发"长什么样" (决策 D2)</h2>
 * route / label / iconItemId 一律不发。那三项是纯展示层信息, 改文案、换图标、调路由都是纯前端发版 (不动 mod
 * jar); 服务端一旦也存一份, 就从"前端发版即生效"变成"两端同时发版才不指错路径", 而远端托管 + 浏览器缓存的
 * 部署路线下这种不同步检测不出来。这不是假想: mock 种子里 champion 面板的 route 写成 {@code '/champion'},
 * 而前端真实常量是 {@code ROUTE_CODEX='/codex'} —— 一份没人对得上的副本漂了就漂了。
 *
 * 服务端只权威"这个面板我现在能不能进", 因为它依赖的 OP / 等级 / 婚姻是服务端私有权威数据。
 *
 * <h2>enabled 只影响渲染</h2>
 * 架构铁律 1 不因本 action 放松: 面板背后的每个动作仍各自在自己的 handler 内独立校验权限。把 enabled=false
 * 的面板硬点开, 里面的动作照样一个个拒绝。
 */
public final class HubWebUiActions {

    private static final Gson GSON = new Gson();

    /**
     * 面板 id 全集, 顺序即下发顺序 (决策 D7)。
     *
     * 取值逐条对齐前端 {@code router.ts} 的路由常量与 {@code TabletShell} 的一级导航 id, 与 mock 种子有两处
     * 刻意的差异: quests 剔除 (前端根本没有这条路由, 任务系统零实现, 发一个点不进去的入口只会制造工单),
     * champion 更名 codex (真实路由是 ROUTE_CODEX)。前端的 panelId -&gt; {route,label,icon} 映射表是这份 id 的
     * 唯一消费方; 服务端多发一个前端不认识的 id 时前端应安全跳过。
     */
    private static final List<String> PANEL_IDS = List.of(
            "home", "market", "shop", "jobs", "mining",
            "codex", "marriage", "case", "settings", "admin");

    /** 当前唯一带门的面板。 */
    private static final String PANEL_ADMIN = "admin";

    private HubWebUiActions() {
    }

    /** 把 hub.* action 注册进派发器 (由 {@link WebUiServerSubsystem#register} 调用一次)。 */
    public static void registerAll() {
        WebUiServerDispatcher.register("hub.panels", PANELS);
    }

    /**
     * 发送者此刻各面板的可达性。
     *
     * 当前只有 admin 一条会为 false。刻意<b>没有</b>的两道门:
     *  - 婚姻面板恒开 (D6): 它本身就是未婚玩家的求婚入口, 灰锁掉等于把入口锁在需求后面;
     *  - 职业等级门本批不做 (D7), 故不调 IJobService, 也不得提前造 LEVEL_TOO_LOW 之类还没有判据的锁码。
     */
    static final WebUiAction PANELS = (sender, payload) -> {
        boolean op = WebUiPermissions.isOp(sender);
        JsonArray panels = new JsonArray();
        for (String panelId : PANEL_IDS) {
            boolean enabled = !PANEL_ADMIN.equals(panelId) || op;
            JsonObject panel = new JsonObject();
            panel.addProperty("panelId", panelId);
            panel.addProperty("enabled", enabled);
            if (!enabled) {
                // 缺席键而不是 null: 默认 Gson 无 serializeNulls, 条件性 addProperty 得到的就是"没有这个键"。
                panel.addProperty("lockCode", HubLockCodes.NOT_OP);
            }
            panels.add(panel);
        }
        JsonObject result = new JsonObject();
        result.add("panels", panels);
        return GSON.toJson(result);
    };
}
