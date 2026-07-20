package com.miningdim.job.tarot;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.AABB;

import java.util.EnumMap;
import java.util.HashMap;
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

    public enum Restriction {
        ATTACK_LOCK,
        HEALING_BLOCK,
        UNTARGETABLE
    }

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

    /**
     * 累计反击窗 (正义闪耀): 窗口内逐攻击者累计其造成的伤害; 窗口结束时对仍在 radius 格内的每个攻击者回击
     * 其累计伤害的 percent (单次封顶 perHitCap)。结算由 {@link #drainReflectAccum} 抽出 (调度器到点调)。
     */
    private static final class ReflectAccum {
        long endTick;
        double percent;
        double perHitCap;
        double radius;
        /** attackerUUID -> 该攻击者在窗口内对持有者累计造成的伤害。 */
        final Map<UUID, Double> perAttacker = new HashMap<>();
    }

    /**
     * 延迟记账冻死窗 (倒吊人闪耀): 窗口内对持有者的伤害累加进 pendingDamage 且致命伤被冻结 (不死); 窗口结束时
     * 由 {@link #drainLedger} 结算 pendingDamage 的 settlePercent, 存活则额外回 surviveHeal。
     */
    private static final class Ledger {
        long endTick;
        double settlePercent;
        double surviveHeal;
        double pendingDamage;
    }

    /**
     * 生死绑定 (恋人闪耀): 与 partner 双向绑定至 endTick; 距离 > unbindDistance 解绑; 一方死则另一方
     * deathDelayTicks 后同死。两侧各存一份 (互为 partner), 任一侧解绑/到期都清两侧由 {@link #clearBond}。
     */
    private static final class Bond {
        UUID partner;
        long endTick;
        double unbindDistance;
        int deathDelayTicks;
    }

    /**
     * 免疫窗 (太阳闪耀/世界闪耀/力量闪耀/恶魔闪耀的 IMMUNITY op): endTick 前对持窗玩家拒绝施加 effectIds 列出的
     * MobEffect ({@link TarotCombatHandlers#onMobEffectApplicable}), 并在 immuneVulnerability=true 时跳过易伤放大
     * ({@link com.miningdim.effect.VulnerabilityHurtHandler})。effectIds 为 MobEffect 注册名字符串集 (引擎已解析校验)。
     */
    private static final class Immunity {
        long endTick;
        java.util.Set<String> effectIds;
        boolean immuneVulnerability;
    }

    private static final class DamageShare {
        long endTick;
        double percent;
        java.util.Set<UUID> members;
    }

    public record DamageShareSnapshot(double percent, java.util.Set<UUID> members) {
    }

    private static final Map<UUID, Map<WindowKind, Window>> WINDOWS = new ConcurrentHashMap<>();
    private static final Map<UUID, Contract> CONTRACTS = new ConcurrentHashMap<>();
    private static final Map<UUID, ReflectAccum> REFLECT_ACCUMS = new ConcurrentHashMap<>();
    private static final Map<UUID, Ledger> LEDGERS = new ConcurrentHashMap<>();
    private static final Map<UUID, Bond> BONDS = new ConcurrentHashMap<>();
    private static final Map<UUID, Immunity> IMMUNITIES = new ConcurrentHashMap<>();
    private static final Map<UUID, Map<Restriction, Long>> RESTRICTIONS = new ConcurrentHashMap<>();
    private static final Map<UUID, DamageShare> DAMAGE_SHARES = new ConcurrentHashMap<>();

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

    /** 开一个累计反击窗 (正义闪耀): durationTicks 内逐攻击者累计伤害, 结束对 radius 格内各攻击者回击 percent (封顶 perHitCap)。 */
    public static void openReflectAccum(ServerPlayer player, int durationTicks, double percent,
                                        double perHitCap, double radius) {
        ReflectAccum a = new ReflectAccum();
        a.endTick = now(player) + durationTicks;
        a.percent = percent;
        a.perHitCap = perHitCap;
        a.radius = radius;
        REFLECT_ACCUMS.put(player.getUUID(), a);
    }

    /** 开一个延迟记账冻死窗 (倒吊人闪耀): durationTicks 内伤害挂账且致命伤冻结, 结束结算 settlePercent, 存活回 surviveHeal。 */
    public static void openLedger(ServerPlayer player, int durationTicks, double settlePercent, double surviveHeal) {
        Ledger l = new Ledger();
        l.endTick = now(player) + durationTicks;
        l.settlePercent = settlePercent;
        l.surviveHeal = surviveHeal;
        l.pendingDamage = 0.0D;
        LEDGERS.put(player.getUUID(), l);
    }

    /** 开一对生死绑定 (恋人闪耀): a/b 双向绑定 durationTicks, 距离 > unbindDistance 解绑, 一方死则另一方 deathDelayTicks 后同死。 */
    public static void openLifeBond(ServerPlayer a, ServerPlayer b, int durationTicks,
                                    double unbindDistance, int deathDelayTicks) {
        long end = now(a) + durationTicks;
        putBond(a.getUUID(), b.getUUID(), end, unbindDistance, deathDelayTicks);
        putBond(b.getUUID(), a.getUUID(), end, unbindDistance, deathDelayTicks);
    }

    private static void putBond(UUID self, UUID partner, long endTick, double unbindDistance, int deathDelayTicks) {
        Bond b = new Bond();
        b.partner = partner;
        b.endTick = endTick;
        b.unbindDistance = unbindDistance;
        b.deathDelayTicks = deathDelayTicks;
        BONDS.put(self, b);
    }

    /**
     * 开一个免疫窗 (太阳/世界/力量/恶魔闪耀等的 IMMUNITY op): durationTicks 内免疫 effectIds 列出的 MobEffect,
     * immuneVulnerability=true 时同时免易伤放大。effectIds 拷一份不可变集 (引擎传入已解析校验的注册名)。
     */
    public static void openImmunity(ServerPlayer player, int durationTicks,
                                    java.util.Set<String> effectIds, boolean immuneVulnerability) {
        Immunity im = new Immunity();
        im.endTick = now(player) + durationTicks;
        im.effectIds = java.util.Set.copyOf(effectIds);
        im.immuneVulnerability = immuneVulnerability;
        IMMUNITIES.put(player.getUUID(), im);
    }

    public static void openRestriction(ServerPlayer player, Restriction restriction, int durationTicks) {
        RESTRICTIONS.computeIfAbsent(player.getUUID(), ignored -> new EnumMap<>(Restriction.class))
                .put(restriction, now(player) + durationTicks);
    }

    public static void openHermitRestrictions(ServerPlayer player, int durationTicks) {
        openRestriction(player, Restriction.ATTACK_LOCK, durationTicks);
        openRestriction(player, Restriction.UNTARGETABLE, durationTicks);
    }

    public static void openDamageShare(ServerPlayer owner, java.util.Set<UUID> members,
                                       int durationTicks, double percent) {
        if (members.isEmpty()) {
            return;
        }
        DamageShare share = new DamageShare();
        share.endTick = now(owner) + durationTicks;
        share.percent = percent;
        share.members = java.util.Set.copyOf(members);
        for (UUID member : share.members) {
            DAMAGE_SHARES.put(member, share);
        }
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

    // ---- 免疫窗 (太阳/世界/力量/恶魔闪耀) ----

    /** 该玩家当前是否对名为 effectId 的 MobEffect 免疫 (有未过期免疫窗且其 effectIds 含此名)。 */
    public static boolean immuneToEffect(UUID playerId, String effectId, long now) {
        Immunity im = IMMUNITIES.get(playerId);
        return im != null && now < im.endTick && im.effectIds.contains(effectId);
    }

    /** 该玩家当前是否免疫易伤放大 (有未过期免疫窗且其 immuneVulnerability=true)。 */
    public static boolean immuneToVulnerability(UUID playerId, long now) {
        Immunity im = IMMUNITIES.get(playerId);
        return im != null && now < im.endTick && im.immuneVulnerability;
    }

    public static boolean restricted(UUID playerId, Restriction restriction, long now) {
        Map<Restriction, Long> restrictions = RESTRICTIONS.get(playerId);
        if (restrictions == null) {
            return false;
        }
        Long end = restrictions.get(restriction);
        return end != null && now < end;
    }

    public static DamageShareSnapshot damageShare(UUID playerId, long now) {
        DamageShare share = DAMAGE_SHARES.get(playerId);
        if (share == null || now >= share.endTick) {
            return null;
        }
        return new DamageShareSnapshot(share.percent, share.members);
    }

    // ---- 累计反击窗 (正义闪耀) ----

    /** 是否有未过期累计反击窗。 */
    public static boolean hasReflectAccum(UUID playerId, long now) {
        ReflectAccum a = REFLECT_ACCUMS.get(playerId);
        return a != null && now < a.endTick;
    }

    /** 在累计反击窗内累计某攻击者对持有者造成的伤害 (无窗则 no-op)。 */
    public static void recordReflectAccum(UUID playerId, UUID attacker, double amount, long now) {
        ReflectAccum a = REFLECT_ACCUMS.get(playerId);
        if (a == null || now >= a.endTick || amount <= 0.0D) {
            return;
        }
        a.perAttacker.merge(attacker, amount, Double::sum);
    }

    /**
     * 抽干并移除累计反击窗: 返回 attacker -> 应回击的伤害 (= 累计伤害 * percent, 单次封顶 perHitCap)。无窗返回空表。
     * 结算后窗口移除 (一次性结算; 由调度器在窗口结束 tick 调)。
     */
    public static Map<UUID, Double> drainReflectAccum(UUID playerId) {
        ReflectAccum a = REFLECT_ACCUMS.remove(playerId);
        if (a == null) {
            return Map.of();
        }
        Map<UUID, Double> out = new HashMap<>();
        for (Map.Entry<UUID, Double> e : a.perAttacker.entrySet()) {
            double retaliate = Math.min(e.getValue() * a.percent, a.perHitCap);
            if (retaliate > 0.0D) {
                out.put(e.getKey(), retaliate);
            }
        }
        return out;
    }

    // ---- 延迟记账冻死窗 (倒吊人闪耀) ----

    /** 是否有未过期延迟记账窗。 */
    public static boolean hasLedger(UUID playerId, long now) {
        Ledger l = LEDGERS.get(playerId);
        return l != null && now < l.endTick;
    }

    /** 在延迟记账窗内累加挂起伤害 (无窗则 no-op)。 */
    public static void recordLedgerDamage(UUID playerId, double amount, long now) {
        Ledger l = LEDGERS.get(playerId);
        if (l == null || now >= l.endTick || amount <= 0.0D) {
            return;
        }
        l.pendingDamage += amount;
    }

    /**
     * 抽干并移除延迟记账窗: 返回 [结算应扣伤害 = pendingDamage * settlePercent, 存活回血 surviveHeal]。
     * 无窗返回 null。结算逻辑 (扣血/判存活/回血) 由调用方 (调度器到点) 执行。
     */
    public static double[] drainLedger(UUID playerId) {
        Ledger l = LEDGERS.remove(playerId);
        if (l == null) {
            return null;
        }
        return new double[]{l.pendingDamage * l.settlePercent, l.surviveHeal};
    }

    // ---- 生死绑定 (恋人闪耀) ----

    /** 该玩家当前绑定的 partner UUID (无未过期绑定返回 null)。 */
    public static UUID bondPartner(UUID playerId, long now) {
        Bond b = BONDS.get(playerId);
        return (b != null && now < b.endTick) ? b.partner : null;
    }

    /** 该玩家绑定的解绑距离 (无绑定返回 -1)。 */
    public static double bondUnbindDistance(UUID playerId) {
        Bond b = BONDS.get(playerId);
        return b == null ? -1.0D : b.unbindDistance;
    }

    /** 该玩家绑定的一方死亡后另一方延迟死亡 ticks (无绑定返回 -1)。 */
    public static int bondDeathDelay(UUID playerId) {
        Bond b = BONDS.get(playerId);
        return b == null ? -1 : b.deathDelayTicks;
    }

    /** 是否有未过期绑定。 */
    public static boolean hasBond(UUID playerId, long now) {
        Bond b = BONDS.get(playerId);
        return b != null && now < b.endTick;
    }

    /** 解绑某玩家 (含其 partner 的反向绑定; 双向清除幂等)。 */
    public static void clearBond(UUID playerId) {
        Bond b = BONDS.remove(playerId);
        if (b != null) {
            Bond reverse = BONDS.get(b.partner);
            if (reverse != null && playerId.equals(reverse.partner)) {
                BONDS.remove(b.partner);
            }
        }
    }

    /** 清某玩家全部窗口/契约 (登出/死亡/换维度防泄漏)。绑定经 {@link #clearBond} 双向清。 */
    public static void clearAll(UUID playerId) {
        WINDOWS.remove(playerId);
        CONTRACTS.remove(playerId);
        REFLECT_ACCUMS.remove(playerId);
        LEDGERS.remove(playerId);
        IMMUNITIES.remove(playerId);
        RESTRICTIONS.remove(playerId);
        clearDamageShare(playerId);
        clearBond(playerId);
    }

    private static void clearDamageShare(UUID playerId) {
        DamageShare share = DAMAGE_SHARES.remove(playerId);
        if (share != null) {
            for (UUID member : share.members) {
                DAMAGE_SHARES.remove(member, share);
            }
        }
    }

    /**
     * 服务端 tick: 过期窗口/契约移除 (由 {@link TarotSystem} 在 ServerTickEvent.END 调用)。
     * 累计反击窗 / 延迟记账窗的过期由各自 scheduleOnce 结算任务到点 drain 移除 (结算后窗口即除); 此处不重复扫
     * (避免与结算任务竞争把未结算的伤害账本丢弃)。{@link #clearAll} 在登出/死亡/换维度兜底防泄漏。
     * 绑定无结算任务, 故在此扫过期 (双向解绑); 距离解绑由 {@link TarotSystem} 在 tick 内按在线实体坐标判定后调
     * {@link #clearBond} (本类无实体引用)。
     */
    public static void tick(MinecraftServer server) {
        long now = server.getTickCount();
        WINDOWS.values().forEach(m -> m.values().removeIf(w -> now >= w.endTick));
        WINDOWS.entrySet().removeIf(e -> e.getValue().isEmpty());
        CONTRACTS.entrySet().removeIf(e -> now >= e.getValue().endTick);
        IMMUNITIES.entrySet().removeIf(e -> now >= e.getValue().endTick);
        RESTRICTIONS.values().forEach(m -> m.entrySet().removeIf(e -> now >= e.getValue()));
        RESTRICTIONS.entrySet().removeIf(e -> e.getValue().isEmpty());
        DAMAGE_SHARES.entrySet().removeIf(e -> now >= e.getValue().endTick);
        clearMobTargets(server, now);
        // 过期绑定双向解绑 (拷贝键集后再清, 避免遍历中改 map)。
        for (UUID id : java.util.List.copyOf(BONDS.keySet())) {
            Bond b = BONDS.get(id);
            if (b != null && now >= b.endTick) {
                clearBond(id);
            }
        }
    }

    private static void clearMobTargets(MinecraftServer server, long now) {
        for (Map.Entry<UUID, Map<Restriction, Long>> entry : RESTRICTIONS.entrySet()) {
            Long end = entry.getValue().get(Restriction.UNTARGETABLE);
            if (end == null || now >= end) {
                continue;
            }
            ServerPlayer player = server.getPlayerList().getPlayer(entry.getKey());
            if (player == null) {
                continue;
            }
            AABB area = player.getBoundingBox().inflate(64.0D);
            for (Mob mob : player.serverLevel().getEntitiesOfClass(Mob.class, area,
                    candidate -> candidate.getTarget() == player)) {
                mob.setTarget(null);
            }
        }
    }

    /** 当前在场绑定的玩家 UUID 快照 (供 {@link TarotSystem} 按坐标做距离解绑遍历)。 */
    public static java.util.Set<UUID> bondedPlayers() {
        return java.util.Set.copyOf(BONDS.keySet());
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

    static void openReflectAccumRaw(UUID playerId, long endTick, double percent, double perHitCap, double radius) {
        ReflectAccum a = new ReflectAccum();
        a.endTick = endTick;
        a.percent = percent;
        a.perHitCap = perHitCap;
        a.radius = radius;
        REFLECT_ACCUMS.put(playerId, a);
    }

    static void openLedgerRaw(UUID playerId, long endTick, double settlePercent, double surviveHeal) {
        Ledger l = new Ledger();
        l.endTick = endTick;
        l.settlePercent = settlePercent;
        l.surviveHeal = surviveHeal;
        LEDGERS.put(playerId, l);
    }

    static void openBondRaw(UUID a, UUID b, long endTick, double unbindDistance, int deathDelayTicks) {
        putBond(a, b, endTick, unbindDistance, deathDelayTicks);
        putBond(b, a, endTick, unbindDistance, deathDelayTicks);
    }

    static void openImmunityRaw(UUID playerId, long endTick, java.util.Set<String> effectIds, boolean immuneVulnerability) {
        Immunity im = new Immunity();
        im.endTick = endTick;
        im.effectIds = java.util.Set.copyOf(effectIds);
        im.immuneVulnerability = immuneVulnerability;
        IMMUNITIES.put(playerId, im);
    }
}
