package com.miningdim.donation;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.server.players.PlayerList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 按玩家名解析 GameProfile: 先找在线玩家, 再查服务器 profile cache (usercache.json 对应的内存表)。
 *
 * 只查缓存, 不走原版 {@code GameProfileCache.get(String)}: 那个方法缓存未命中时会在服务端主线程上同步请求
 * Mojang 接口 (离线模式下则凭名字捏一个离线 UUID)。协管增删可以由任意箱主从界面发起, 把主线程网络请求的
 * 开关交给普通玩家, 等于给了一个按一下卡一下服务器的按钮。缓存里有的都是进过本服的玩家 —— 协管本来就该是
 * 进过服的人; 查不到就明确提示失败。
 *
 * 也不走 {@code get(UUID)}: 它命中时会改写条目的最近访问序号 (lastAccess), 而 usercache.json 落盘时只按这个
 * 序号保留最近 1000 人。拿它逐个比对名字, 任何箱主发一个查无此人的名字就能把整张表的最近使用顺序按哈希顺序
 * 重排, 缓存过千人后真正最近来过的玩家会被挤出 usercache.json。
 *
 * 所以这里只读: 直接查按名字索引的那张表 (键为小写名, 与原版一致), 一次命中后从条目里读 GameProfile,
 * 不碰任何访问记录。条目类型是包级私有的, 字段与方法都按签名反射定位, 不写死开发环境或 SRG 名:
 * 名字索引表是"键为 String、值类型与按 UUID 索引那张表相同"的 Map (以区分同为 String 键的在途请求表),
 * 取 profile 的方法是条目类上唯一无参且返回 GameProfile 的方法。定位失败只降级为"仅在线玩家", 并记一次警告。
 */
final class DonationProfiles {

    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/donation");

    /** 玩家名长度上限 (原版 16)。超长或含空白的输入直接判为查无此人, 不去翻缓存。 */
    static final int MAX_NAME_LENGTH = 16;

    /** 反射定位结果: 名字索引表字段 + 条目上读 GameProfile 的方法。 */
    private record Accessors(Field profilesByName, Method profileOf) {
    }

    private static volatile Accessors accessors;
    private static volatile boolean reflectionFailed;

    private DonationProfiles() {
    }

    /** 名字 -> GameProfile 的解析函数。业务入口按服务器构造; 测试可换成指向独立缓存实例的解析。 */
    @FunctionalInterface
    interface Resolver {
        Optional<GameProfile> resolve(String name);
    }

    static Resolver forServer(MinecraftServer server) {
        return name -> resolve(server.getPlayerList(), server.getProfileCache(), name);
    }

    static Optional<GameProfile> resolve(PlayerList players, @Nullable GameProfileCache cache, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        if (!isPlausibleName(name)) {
            return Optional.empty();
        }
        ServerPlayer online = players.getPlayerByName(name);
        if (online != null) {
            return Optional.of(online.getGameProfile());
        }
        return cache == null ? Optional.empty() : cachedProfile(cache, name);
    }

    /**
     * 只保证长度与无空白, 不套原版 {@code [A-Za-z0-9_]{3,16}}: 离线模式与 Floodgate 这类代理的玩家名不守那条规则,
     * 而查找本身是一次哈希命中, 不需要靠预过滤省开销。
     */
    static boolean isPlausibleName(String name) {
        if (name.isEmpty() || name.length() > MAX_NAME_LENGTH) {
            return false;
        }
        for (int i = 0; i < name.length(); i++) {
            if (Character.isWhitespace(name.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** 只读查找: 名字索引表一次命中, 不经过任何会改写访问记录的公开方法。 */
    private static Optional<GameProfile> cachedProfile(GameProfileCache cache, String name) {
        Accessors found = accessors();
        if (found == null) {
            return Optional.empty();
        }
        try {
            if (!(found.profilesByName().get(cache) instanceof Map<?, ?> byName)) {
                return Optional.empty();
            }
            Object info = byName.get(name.toLowerCase(Locale.ROOT));
            if (info == null) {
                return Optional.empty();
            }
            return found.profileOf().invoke(info) instanceof GameProfile profile && profile.getId() != null
                    ? Optional.of(profile)
                    : Optional.empty();
        } catch (ReflectiveOperationException | RuntimeException e) {
            markFailed(e);
            return Optional.empty();
        }
    }

    @Nullable
    private static Accessors accessors() {
        if (reflectionFailed) {
            return null;
        }
        Accessors cached = accessors;
        if (cached != null) {
            return cached;
        }
        try {
            Type entryType = null;
            for (Field field : GameProfileCache.class.getDeclaredFields()) {
                Type[] args = mapTypeArguments(field);
                if (args != null && args[0] == UUID.class) {
                    entryType = args[1];
                    break;
                }
            }
            if (!(entryType instanceof Class<?> entryClass)) {
                markFailed(new NoSuchFieldException("Map<UUID, ?> in GameProfileCache"));
                return null;
            }
            Field byName = null;
            for (Field field : GameProfileCache.class.getDeclaredFields()) {
                Type[] args = mapTypeArguments(field);
                if (args != null && args[0] == String.class && args[1] == entryClass) {
                    byName = field;
                    break;
                }
            }
            Method profileOf = null;
            for (Method method : entryClass.getDeclaredMethods()) {
                if (method.getParameterCount() == 0 && method.getReturnType() == GameProfile.class) {
                    profileOf = method;
                    break;
                }
            }
            if (byName == null || profileOf == null) {
                markFailed(new NoSuchFieldException("name index or profile accessor in GameProfileCache"));
                return null;
            }
            byName.setAccessible(true);
            profileOf.setAccessible(true);
            Accessors located = new Accessors(byName, profileOf);
            accessors = located;
            return located;
        } catch (RuntimeException e) {
            markFailed(e);
            return null;
        }
    }

    /** Map 字段的两个泛型实参; 不是带完整泛型签名的 Map 时为 null。 */
    @Nullable
    private static Type[] mapTypeArguments(Field field) {
        if (!Map.class.isAssignableFrom(field.getType())) {
            return null;
        }
        if (field.getGenericType() instanceof ParameterizedType parameterized
                && parameterized.getActualTypeArguments().length == 2) {
            return parameterized.getActualTypeArguments();
        }
        return null;
    }

    private static void markFailed(Exception cause) {
        if (!reflectionFailed) {
            reflectionFailed = true;
            LOGGER.warn("[donation] cannot read the server profile cache; co-admin names resolve against online players only", cause);
        }
    }
}
