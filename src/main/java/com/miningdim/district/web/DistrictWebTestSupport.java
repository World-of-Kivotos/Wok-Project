package com.miningdim.district.web;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.miningdim.district.DistrictTestEnv;
import com.miningdim.webui.server.WebUiBusinessException;
import com.miningdim.webui.server.WebUiServerDispatcher;
import com.mojang.authlib.GameProfile;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;

import java.util.ArrayList;
import java.util.List;

/**
 * 平板动作 GameTest 的共用工具 (设计文档 18.1): 一律经派发器里真实登记的 handler 调用 —— 测试代码里不注册 action,
 * 生产接线漏了测试就应该失败。
 */
final class DistrictWebTestSupport {

    private DistrictWebTestSupport() {
    }

    /** 调一条动作并解析回执; 动作没登记或抛了业务拒绝都让 GameTest 当场失败。 */
    static JsonObject call(GameTestHelper helper, String action, ServerPlayer sender, JsonObject payload) {
        WebUiServerDispatcher.WebUiAction handler = handler(helper, action);
        String json;
        try {
            json = handler.handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            helper.fail(action + " 本应成功, 实际被拒: " + rejected.errorCode() + " " + rejected.params() + " ("
                    + rejected.getMessage() + ")");
            throw new IllegalStateException("unreachable");
        }
        return JsonParser.parseString(json).getAsJsonObject();
    }

    /** 调一条动作的原始回执 (量体积用)。 */
    static String raw(GameTestHelper helper, String action, ServerPlayer sender, JsonObject payload) {
        return handler(helper, action).handle(sender, payload);
    }

    /** 调一条动作并要求它以指定码拒绝, 返回该拒绝。 */
    static WebUiBusinessException reject(GameTestHelper helper, String action, ServerPlayer sender,
                                         JsonObject payload, String code) {
        try {
            handler(helper, action).handle(sender, payload);
        } catch (WebUiBusinessException rejected) {
            helper.assertTrue(code.equals(rejected.errorCode()), action + " 应报 " + code + ", 实得 "
                    + rejected.errorCode() + " " + rejected.params() + " (" + rejected.getMessage() + ")");
            return rejected;
        }
        helper.fail(action + " 应报 " + code + ", 实际成功了: " + payload);
        throw new IllegalStateException("unreachable");
    }

    private static WebUiServerDispatcher.WebUiAction handler(GameTestHelper helper, String action) {
        WebUiServerDispatcher.WebUiAction handler = WebUiServerDispatcher.resolve(action);
        if (handler == null) {
            helper.fail("action " + action + " 没有登记进派发器 (DistrictSystem.register 没有调用 registerAll?)");
            throw new IllegalStateException("unreachable");
        }
        return handler;
    }

    /** 按键值对造 payload: 值可以是 String / Number / Boolean / JsonElement / null (写成 JSON null)。 */
    static JsonObject payload(Object... keyValues) {
        JsonObject payload = new JsonObject();
        for (int i = 0; i < keyValues.length; i += 2) {
            String key = (String) keyValues[i];
            Object value = keyValues[i + 1];
            if (value == null) {
                payload.add(key, JsonNull.INSTANCE);
            } else if (value instanceof String text) {
                payload.addProperty(key, text);
            } else if (value instanceof Number number) {
                payload.addProperty(key, number);
            } else if (value instanceof Boolean flag) {
                payload.addProperty(key, flag);
            } else if (value instanceof JsonElement element) {
                payload.add(key, element);
            } else {
                throw new IllegalArgumentException("unsupported payload value " + value);
            }
        }
        return payload;
    }

    static JsonObject area(int minX, int minZ, int maxX, int maxZ) {
        return payload("minX", minX, "minZ", minZ, "maxX", maxX, "maxZ", maxZ);
    }

    /** 某个键存在且值是 JSON null (serializeNulls: 可空字段写 null, 不许省略键)。 */
    static boolean nullKey(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonNull();
    }

    static String str(JsonObject object, String key) {
        JsonElement value = object.get(key);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    static JsonArray array(JsonObject object, String key) {
        return object.getAsJsonArray(key);
    }

    /** 列表里第一个 key == value 的对象。 */
    static JsonObject find(JsonArray array, String key, String value) {
        for (JsonElement element : array) {
            JsonObject object = element.getAsJsonObject();
            if (value.equals(str(object, key))) {
                return object;
            }
        }
        return null;
    }

    /** 公共区域开关表里的一项 (按 permissionId 找)。 */
    static JsonObject permissionItem(JsonObject permissions, String permissionId) {
        for (JsonElement group : permissions.getAsJsonArray("groups")) {
            JsonObject found = find(group.getAsJsonObject().getAsJsonArray("items"), "permissionId", permissionId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * 一组在线的 mock 玩家 (名字即 UUID 的来源, 与 {@link DistrictTestEnv#uuidOf} 一致)。close 时撤销 OP 并把玩家移出
     * PlayerList。必须在测试夹具 (DistrictTestEnv) 之后打开、之前关闭: 登录与登出事件要落在测试库上。
     */
    static final class Cast implements AutoCloseable {

        private final GameTestHelper helper;
        private final List<ServerPlayer> players = new ArrayList<>();
        private final List<GameProfile> ops = new ArrayList<>();

        Cast(GameTestHelper helper) {
            this.helper = helper;
        }

        ServerPlayer player(String name) {
            ServerPlayer player = DistrictTestEnv.onlinePlayer(helper, name);
            players.add(player);
            helper.assertTrue(!playerList().isOp(player.getGameProfile()), "前提: 新建的 mock 玩家 " + name + " 不是 OP");
            return player;
        }

        ServerPlayer op(String name) {
            ServerPlayer player = player(name);
            playerList().op(player.getGameProfile());
            ops.add(player.getGameProfile());
            return player;
        }

        private PlayerList playerList() {
            return helper.getLevel().getServer().getPlayerList();
        }

        @Override
        public void close() {
            for (GameProfile profile : ops) {
                playerList().deop(profile);
            }
            for (ServerPlayer player : players) {
                DistrictTestEnv.removePlayer(helper, player);
            }
        }
    }
}
