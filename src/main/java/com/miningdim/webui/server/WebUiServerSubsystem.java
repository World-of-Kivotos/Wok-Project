package com.miningdim.webui.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.miningdim.core.Subsystem;
import net.minecraftforge.eventbus.api.IEventBus;

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
}
