package com.miningdim.webui.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.miningdim.core.MiningConstants;
import com.miningdim.core.Subsystem;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModList;

/**
 * Web UI 服务端子系统 (Web UI bridge 契约第 7 节)。服务端安全: 不触碰任何 MCEF / 浏览器 / 客户端渲染类,
 * 故无 Dist 守卫需求, 可在专用服务器无条件加载。
 *
 * 职责: register 期向 {@link WebUiServerDispatcher} 注册内置 action。当前注册 "system.echo" (桥接联调回声),
 * 后续业务子系统按同范式各自注册自己的 action (查表派发, 不在此集中堆叠业务逻辑)。
 */
public final class WebUiServerSubsystem implements Subsystem {

    private static final Gson GSON = new Gson();

    @Override
    public void register(IEventBus modBus, IEventBus forgeBus) {
        // 内置回声 action (契约第 6 节响应格式): 回送 {player, echo, serverTick}, 用于桥接通路联调与心跳验证。
        WebUiServerDispatcher.register("system.echo", WebUiServerSubsystem::handleEcho);
        // 契约握手 (架构文档 10.6): 页面启动时比对自身预期的 action 集与服务端实际注册集, 把"远端缓存旧页面
        // 调已删 action"从逐个静默失败变成一次性明确报错。
        WebUiServerDispatcher.register("system.handshake", WebUiServerSubsystem::handleHandshake);
        // 服务器运行状态 (管理后台顶部四项): 在线人数/容量/TPS/MSPT/已运行刻数。纯只读, 不设权限门 ——
        // 这四个数字在任何多人服的 F3 与计分板上都是公开信息, 加门只会让普通玩家的状态栏一片空白。
        WebUiServerDispatcher.register("system.serverStatus", WebUiServerSubsystem::handleServerStatus);
        // 只读 action 的批量聚合 (一次往返跑完一屏的全部查询)。注册点放在 system.* 这一组里, 但实现单独成类:
        // 它是第二个 Gateway 边界 (逐条翻译异常 + 体积计账 + 白名单), 塞进本类会让"注册表"与"派发逻辑"混住。
        WebUiBatchAction.registerAll();
        // 平板 hub 的面板可达性。本类是 hub.* 与 system.* 两组 action 的唯一注册入口 (同一个 register 方法),
        // 避免两组人各改一处注册点后合并时互相覆盖。
        HubWebUiActions.registerAll();
        // 玩家登出清理其 requestId 防重放窗口 (派发器维护, 见红线 6); 防离线玩家窗口驻留内存泄漏。
        // forgeBus 可能为 null (GameTest 纯逻辑路径只验 dispatcher 不订阅事件), 此时跳过订阅。
        if (forgeBus != null) {
            forgeBus.addListener(WebUiServerSubsystem::onPlayerLoggedOut);
        }
    }

    /** 玩家登出 -> 清其 requestId 滑动窗口 (防重放窗口由 {@link WebUiServerDispatcher} 持有)。 */
    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer player) {
            WebUiServerDispatcher.clearPlayer(player.getUUID());
        }
    }

    /**
     * "system.echo" 处理器 (契约第 6 节): resultJson = {"player":<玩家名>,"echo":<payload.msg>,"serverTick":<tick>}。
     * payload 缺 "msg" 字段时 get 返回 null, 对其 getAsString 自然抛 (坏输入冒泡到 dispatcher 的 Gateway 兜底,
     * 不在此吞或填默认值)。serverTick 取发送者所在服务器的全局 tick 计数, 作为服务端权威时基回声。
     */
    private static String handleEcho(net.minecraft.server.level.ServerPlayer sender, JsonObject payload) {
        String echo = payload.get("msg").getAsString();
        JsonObject result = new JsonObject();
        result.addProperty("player", sender.getName().getString());
        result.addProperty("echo", echo);
        result.addProperty("serverTick", sender.server.getTickCount());
        return GSON.toJson(result);
    }

    /**
     * "system.serverStatus" 处理器: resultJson = {"online","maxPlayers","tps","mspt","uptimeSeconds"}。
     *
     * 不吃 payload (纯查询)。刻意<b>没有</b>公告字段: 全库不存在"运营公告"这个业务概念, 唯一贴近的
     * {@code getMotd()} 是 server.properties 里的服务器描述而不是公告。恒回一个空串等于立一个永远为空的死
     * 约定, 不如不发。
     *
     * uptimeSeconds 的口径必须写死: 它是<b>已运行的游戏刻数折算的秒</b>, 服务器掉刻时会慢于真实挂钟时间。
     * 选它是因为原版没有"开机挂钟时刻"的公开 getter, 而为一个状态栏数字新建一份 ServerStartedEvent 时间戳
     * 状态不划算 (YAGNI)。前端文案按此口径写"已运行", 不得宣称是挂钟时长。
     */
    private static String handleServerStatus(ServerPlayer sender, JsonObject payload) {
        MinecraftServer server = sender.server;
        // 原版 averageTickTime 本身就是每 tick 做过 EMA 平滑的毫秒值, 不要在服务端再平均一次 tickTimes 数组。
        float mspt = server.getAverageTickTime();
        JsonObject result = new JsonObject();
        result.addProperty("online", server.getPlayerCount());
        result.addProperty("maxPlayers", server.getMaxPlayers());
        // mspt <= 0 只出现在服务器首 tick 之前 (averageTickTime 的初值就是 0), 那是正常初值不是故障, 故给
        // 设计满速 20 而不是让它除出 Infinity。20 是原版 MS_PER_TICK=50 对应的设计上限, 硬编码。
        result.addProperty("tps", mspt <= 0.0F ? 20.0D : Math.min(20.0D, 1000.0D / mspt));
        result.addProperty("mspt", mspt);
        result.addProperty("uptimeSeconds", server.getTickCount() / 20);
        return GSON.toJson(result);
    }

    /**
     * "system.handshake" 处理器: resultJson = {"modVersion":&lt;版本&gt;,"actions":[&lt;全部已注册 action 名, 字典序&gt;]}。
     *
     * 页面启动时调一次, 与自身构建期记录的 action 集比对: 缺失项即"页面比服务端新"(或服务端少装了子系统),
     * 多出项即"页面是旧缓存"。二者都在启动时一次性暴露, 而不是等玩家点到某个面板才静默失效 (架构文档 10.6)。
     *
     * 不吃 payload: 握手是纯查询, 无入参。版本取自 mod 容器元数据, 与 gradle.properties 的 mod_version 同源。
     */
    private static String handleHandshake(net.minecraft.server.level.ServerPlayer sender, JsonObject payload) {
        JsonObject result = new JsonObject();
        result.addProperty("modVersion", ModList.get().getModContainerById(MiningConstants.MODID)
                .map(c -> c.getModInfo().getVersion().toString())
                .orElse("unknown"));
        JsonArray actions = new JsonArray();
        for (String name : WebUiServerDispatcher.registeredActions()) {
            actions.add(name);
        }
        result.add("actions", actions);
        return GSON.toJson(result);
    }
}
