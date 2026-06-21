package com.miningdim.champion;

import com.miningdim.champion.aggregate.PlayerControlAggregator;
import com.miningdim.champion.aggregate.PlayerDotAccumulator;
import com.miningdim.champion.aggregate.RetaliationAggregator;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 精英怪效果聚合器注册表 (ChampionStarAffix spec 红线 2/4/5 + 9.5 聚合器 + 第十四章实现拆分 4 地基)。三块 per-玩家/
 * per-攻击者聚合器的实例管理, 仿 {@link com.miningdim.champion.bloodpool.BloodPoolRegistry} 范式 (静态 UUID->实例
 * Map + 生命周期清理), 供 Effects 阶段 handler 取用:
 *
 *  - per-player DoT: {@link PlayerDotAccumulator} —— 逐 tick 把多源 DoT 名义伤害记入, 每秒经 DotAggregator 衰减
 *    到 15% maxHP 合计封顶 (红线 4)。键 = 受 DoT 玩家 UUID。
 *  - per-player 控制: {@link PlayerControlAggregator} —— 失明/击飞/位移统一进 7s 窗 50% 上限 + ≥2s 自由窗 (红线 5)。
 *    键 = 被控制玩家 UUID。
 *  - per-attacker 反伤: {@link RetaliationAggregator} —— 所有反伤源累加后秒窗 ≤30% + 单窗 ≤40% attacker maxHP
 *    (红线 2)。键 = 攻击者 (玩家) UUID; 构造需 attackerMaxHp, 故按需创建时由调用方给入。
 *
 * 实例管理纪律 (与聚合器纯逻辑分工): 三聚合器本身是纯逻辑 (无世界引用, GameTest 直接断言); 本类只管 UUID->实例
 * 的存活与按需创建 + 清理时机, 不复制任何封顶数学 (单一权威在各聚合器)。控制/反伤聚合器有跨秒/跨窗滚动状态,
 * 故同一玩家须复用同一实例 (新建会丢已累计的窗内额度 = 漏封顶); DoT 缓冲同理 (跨秒累加)。
 *
 * 清理时机 (反泄漏, 仿 ChefWindowEffectState/BloodPoolRegistry): 玩家登出 ({@link PlayerEvent.PlayerLoggedOutEvent})
 * / 死亡 ({@link LivingDeathEvent}) / 换维度 ({@link PlayerEvent.PlayerChangedDimensionEvent}) 清该玩家三聚合器;
 * 服务端停止 {@link #reset} 清全表。死亡/登出后旧滚动窗状态作废 (复活/重连是新战斗周期), 故清理即重置额度。
 *
 * 线程纪律: 聚合器读写只在服务端主线程 (受击/控制/tick 串行); ConcurrentHashMap 仅防跨线程读 (调试快照/卸载)
 * 的可见性, 不替代主线程串行写。本类不 import 任何 top.theillusivec4.champions.* (纯逻辑包), 故 dev GameTest
 * 触达安全; forge 事件订阅者实例由 {@link ChampionSystem} 挂 forgeBus (与血池/奖励 handler 同生命周期)。
 */
public final class ChampionEffectRegistries {

    private ChampionEffectRegistries() {
    }

    /** per-player DoT 秒窗缓冲 (受 DoT 玩家 UUID -> 累加器)。 */
    private static final ConcurrentHashMap<UUID, PlayerDotAccumulator> DOT = new ConcurrentHashMap<>();

    /** per-player 控制聚合 (被控制玩家 UUID -> 聚合器)。 */
    private static final ConcurrentHashMap<UUID, PlayerControlAggregator> CONTROL = new ConcurrentHashMap<>();

    /** per-attacker 反伤聚合 (攻击者玩家 UUID -> 累加器)。 */
    private static final ConcurrentHashMap<UUID, RetaliationAggregator> RETALIATION = new ConcurrentHashMap<>();

    /**
     * 取/建某玩家的 DoT 秒窗缓冲 (按需创建; 复用同一实例保跨秒累加状态)。
     *
     * @param playerId 受 DoT 玩家 UUID
     * @return 该玩家 DoT 累加器
     */
    public static PlayerDotAccumulator dotFor(UUID playerId) {
        requireId(playerId);
        return DOT.computeIfAbsent(playerId, k -> new PlayerDotAccumulator());
    }

    /**
     * 取/建某玩家的控制聚合器 (按需创建; 复用同一实例保 7s 滚动窗已累计受控区间)。
     *
     * @param playerId 被控制玩家 UUID
     * @return 该玩家控制聚合器
     */
    public static PlayerControlAggregator controlFor(UUID playerId) {
        requireId(playerId);
        return CONTROL.computeIfAbsent(playerId, k -> new PlayerControlAggregator());
    }

    /**
     * 取/建某攻击者的反伤累加器 (按需创建; 复用同一实例保秒窗/窗口已累计反伤额度)。
     *
     * {@link RetaliationAggregator} 构造须 attackerMaxHp (反伤 %的基数), 故按需创建时由调用方给入。已存在则
     * 直接复用旧实例 (不因 maxHp 变动重建 —— 重建会丢窗内已累计额度 = 漏封顶; maxHp 变动属罕见且额度按旧基数
     * 钳制更保守, 不破红线)。
     *
     * @param attackerId    攻击者玩家 UUID
     * @param attackerMaxHp 攻击者有效最大血量 (&gt;0; 仅首次创建时用作 %基数)
     * @return 该攻击者反伤累加器
     */
    public static RetaliationAggregator retaliationFor(UUID attackerId, double attackerMaxHp) {
        requireId(attackerId);
        return RETALIATION.computeIfAbsent(attackerId, k -> new RetaliationAggregator(attackerMaxHp));
    }

    /** 某玩家是否已有 DoT 缓冲 (诊断/测试用)。 */
    public static boolean hasDot(UUID playerId) {
        return playerId != null && DOT.containsKey(playerId);
    }

    /** 是否有任意在册 DoT 累加器 (DoT tick handler 早退守卫: 无 DoT 战斗时整 tick no-op, 省全玩家遍历)。 */
    public static boolean hasAnyDot() {
        return !DOT.isEmpty();
    }

    /** 某玩家是否已有控制聚合器 (诊断/测试用)。 */
    public static boolean hasControl(UUID playerId) {
        return playerId != null && CONTROL.containsKey(playerId);
    }

    /** 某攻击者是否已有反伤累加器 (诊断/测试用)。 */
    public static boolean hasRetaliation(UUID attackerId) {
        return attackerId != null && RETALIATION.containsKey(attackerId);
    }

    /**
     * 清某玩家全部三聚合器 (反泄漏: 登出/死亡/换维度)。该玩家既可能是受 DoT/被控制方, 也可能是反伤攻击者,
     * 故三表同 UUID 一并清 (登出/死亡后所有旧滚动窗作废)。
     *
     * @param playerId 玩家 UUID
     */
    public static void clearAll(UUID playerId) {
        if (playerId == null) {
            return;
        }
        DOT.remove(playerId);
        CONTROL.remove(playerId);
        RETALIATION.remove(playerId);
    }

    /** 服务端停止清空全表, 防跨存档脏引用 (供 ServerStoppingEvent, 由 ChampionSystem 调)。 */
    public static void reset() {
        DOT.clear();
        CONTROL.clear();
        RETALIATION.clear();
    }

    /** 三表在册实例总数 (诊断/测试用)。 */
    public static int totalSize() {
        return DOT.size() + CONTROL.size() + RETALIATION.size();
    }

    private static void requireId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException("player/attacker UUID must not be null");
        }
    }

    // ---- forge 事件订阅者: 反泄漏清理 (由 ChampionSystem 挂 forgeBus) ----

    /**
     * forge 事件清理订阅者 (混合体: 静态注册表 + 实例事件订阅, 仿 ChefWindowEffectState)。{@link ChampionSystem}
     * new 一个挂 forgeBus, 在玩家登出/死亡/换维度时清该玩家三聚合器, 防跨战斗周期/跨会话泄漏滚动窗状态。
     */
    public static final class CleanupHandler {

        @SubscribeEvent
        public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
            clearAll(event.getEntity().getUUID());
        }

        @SubscribeEvent
        public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
            clearAll(event.getEntity().getUUID());
        }

        @SubscribeEvent
        public void onDeath(LivingDeathEvent event) {
            if (event.getEntity() instanceof Player player) {
                clearAll(player.getUUID());
            }
        }
    }
}
