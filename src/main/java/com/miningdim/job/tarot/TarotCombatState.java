package com.miningdim.job.tarot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 塔罗师战斗窗口状态机 (TarotReader spec 第六/十二章有状态机制). 仿厨师 {@link com.miningdim.job.chef.ChefWindowEffectState}:
 * per-player 窗口表, 经服务端 tick 推进, 登出/死亡/换维度清理 (防泄漏). 承载下列有状态机制 —— 它们无法用一次性
 * MobEffect 表达, 必须挂事件读窗口快照:
 *  - 免疫击退 (KNOCKBACK_IMMUNITY): {@link TarotCombatHandlers#onKnockback} 读, 严禁 AttributeModifier (零泄漏);
 *  - 吸血 (LIFESTEAL): {@link TarotCombatHandlers#onLivingHurtSource} 在使用者对敌出伤后回血 percent;
 *  - 反伤 (REFLECT): {@link TarotCombatHandlers#onLivingHurtVictim} 受伤回击攻击者, 单次封顶;
 *  - 无敌窗 (INVULNERABLE): 受伤伤害归零 (真免疫, 非抗性);
 *  - 复活契约 (DEATH_CONTRACT): {@link TarotCombatHandlers#onLivingDeath} 拦截一次致死并复活 (一次性).
 *
 * 时钟用 {@link MinecraftServer#getTickCount()} (spec 红线: 全局时钟跨维度一致). 仅服务端写, ConcurrentHashMap
 * 防 tick/事件并发读写. 纯静态状态: tick 推进由 {@link TarotSystem#onServerTick} 调 {@link #tick(MinecraftServer)},
 * 反泄漏清理由 {@link TarotSystem} 的登出/死亡/换维度事件调 {@link #clearAll(UUID)}; 事件读取由 {@link TarotCombatHandlers}.
 */
public final class TarotCombatState {

    /** 窗口类机制的种类 (一玩家每种至多一个激活窗口; 同种刷新覆盖)。 */
    public enum WindowKind {
        KNOCKBACK_IMMUNITY,
        LIFESTEAL,
        REFLECT,
        INVULNERABLE
    }

    /** 一个窗口: 结束 tick + 数值快照 (吸血/反伤百分比基点 + 反伤单次封顶)。 */
    private static final class Window {
        long endTick;
        double percent;
        double perHitCap;
    }

    /** 复活契约: 结束 tick + 复活血量 (一次性: 拦截后立即清除)。 */
    private static final class Contract {
        long endTick;
        double reviveHealth;
    }

    private static final Map<UUID, Map<WindowKind, Window>> WINDOWS = new ConcurrentHashMap<>();
    private static final Map<UUID, Contract> CONTRACTS = new ConcurrentHashMap<>();

    private TarotCombatState() {
    }

    // ---- 写入 (TarotEffectEngine 调用) ----

    /** 开一个免疫击退窗 (倒吊人逆位/力量闪耀)。 */
    public static void openKnockbackImmunity(ServerPlayer player, int durationTicks) {
        putWindow(player, WindowKind.KNOCKBACK_IMMUNITY, durationTicks, 0.0D, 0.0D);
    }

    /** 开一个吸血窗 (倒吊人逆位/恶魔): percent 0-1。 */
    public static void openLifesteal(ServerPlayer player, int durationTicks, double percent) {
        putWindow(player, WindowKind.LIFESTEAL, durationTicks, percent, 0.0D);
    }

    /** 开一个反伤窗 (正义正位): percent 0-1, 单次封顶 perHitCap (绝对 HP)。 */
    public static void openReflect(ServerPlayer player, int durationTicks, double percent, double perHitCap) {
        putWindow(player, WindowKind.REFLECT, durationTicks, percent, perHitCap);
    }

    /** 开一个无敌窗 (愚者闪耀): durationTicks 内对使用者伤害归零。 */
    public static void openInvulnerable(ServerPlayer player, int durationTicks) {
        putWindow(player, WindowKind.INVULNERABLE, durationTicks, 0.0D, 0.0D);
    }

    /** 开一个复活契约 (死神逆位): durationTicks 内拦截 1 次致死, 复活回 reviveHealth 血 (一次性)。 */
    public static void openDeathContract(ServerPlayer player, int durationTicks, double reviveHealth) {
        Contract c = new Contract();
        c.endTick = now(player) + durationTicks;
        c.reviveHealth = reviveHealth;
        CONTRACTS.put(player.getUUID(), c);
    }

    private static void putWindow(ServerPlayer player, WindowKind kind, int durationTicks,
                                  double percent, double perHitCap) {
        Window w = new Window();
        w.endTick = now(player) + durationTicks;
        w.percent = percent;
        w.perHitCap = perHitCap;
        WINDOWS.computeIfAbsent(player.getUUID(), k -> new EnumMap<>(WindowKind.class)).put(kind, w);
    }

    // ---- 读取 (事件 handler 调用) ----

    public static boolean hasWindow(UUID playerId, WindowKind kind, long now) {
        Map<WindowKind, Window> m = WINDOWS.get(playerId);
        if (m == null) {
            return false;
        }
        Window w = m.get(kind);
        return w != null && now < w.endTick;
    }

    /** 反伤百分比 (0 = 无窗); 仅 REFLECT。 */
    public static double reflectPercent(UUID playerId, long now) {
        Window w = windowIfActive(playerId, WindowKind.REFLECT, now);
        return w == null ? 0.0D : w.percent;
    }

    /** 反伤单次封顶 (绝对 HP); 仅 REFLECT 有窗时有意义。 */
    public static double reflectPerHitCap(UUID playerId, long now) {
        Window w = windowIfActive(playerId, WindowKind.REFLECT, now);
        return w == null ? 0.0D : w.perHitCap;
    }

    /** 吸血百分比 (0 = 无窗); 仅 LIFESTEAL。 */
    public static double lifestealPercent(UUID playerId, long now) {
        Window w = windowIfActive(playerId, WindowKind.LIFESTEAL, now);
        return w == null ? 0.0D : w.percent;
    }

    private static Window windowIfActive(UUID playerId, WindowKind kind, long now) {
        Map<WindowKind, Window> m = WINDOWS.get(playerId);
        if (m == null) {
            return null;
        }
        Window w = m.get(kind);
        return (w != null && now < w.endTick) ? w : null;
    }

    /**
     * 消费复活契约: 若该玩家有未过期契约则返回其复活血量并清除契约 (一次性: 拦截后第二次不再拦截), 否则返回 -1。
     */
    public static double consumeDeathContract(UUID playerId, long now) {
        Contract c = CONTRACTS.get(playerId);
        if (c == null || now >= c.endTick) {
            return -1.0D;
        }
        CONTRACTS.remove(playerId);
        return c.reviveHealth;
    }

    /** 清某玩家全部窗口/契约 (登出/死亡/换维度防泄漏)。 */
    public static void clearAll(UUID playerId) {
        WINDOWS.remove(playerId);
        CONTRACTS.remove(playerId);
    }

    /** 服务端 tick: 过期窗口/契约移除 (由 {@link TarotSystem} 在 ServerTickEvent.END 调用)。 */
    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        WINDOWS.values().forEach(m -> m.values().removeIf(w -> now >= w.endTick));
        WINDOWS.entrySet().removeIf(e -> e.getValue().isEmpty());
        CONTRACTS.entrySet().removeIf(e -> now >= e.getValue().endTick);
    }

    private static long now(ServerPlayer player) {
        MinecraftServer server = player.getServer();
        if (server == null) {
            throw new IllegalStateException("ServerPlayer has no MinecraftServer (combat-state clock unavailable)");
        }
        return server.getTickCount();
    }

    // ---- 测试钩子 (绕过 ServerPlayer 依赖, 仅同包可见) ----

    static void openWindowRaw(UUID playerId, WindowKind kind, long endTick, double percent, double perHitCap) {
        Window w = new Window();
        w.endTick = endTick;
        w.percent = percent;
        w.perHitCap = perHitCap;
        WINDOWS.computeIfAbsent(playerId, k -> new EnumMap<>(WindowKind.class)).put(kind, w);
    }

    static void openContractRaw(UUID playerId, long endTick, double reviveHealth) {
        Contract c = new Contract();
        c.endTick = endTick;
        c.reviveHealth = reviveHealth;
        CONTRACTS.put(playerId, c);
    }
}
