package com.miningdim.job.chef;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 窗口/周期/衰减型厨师效果的 per-player 状态机 (Chef_Job_DesignSpec 第十/十一章: "eat-time 盖章 +
 * tick/事件状态机", 非一次性)。承载: 耐饥 (减饥饿衰减)、披甲 (黄心护盾)、凝脂 (爆炸减伤)、余韵 (延迟再生)、
 * 稳膛 (抗击退)。
 *
 * 设计 (本工程暂无共享 ScheduledEffectManager, 见 foundationGaps): 自持一个 UUID->窗口表的全局 map, 经
 * 服务端 {@link TickEvent.ServerTickEvent} 全局推进; 各窗口记结束 tick (server game time) + 数值快照。
 * 反泄漏 (第十二章测试): 登出 ({@link PlayerEvent.PlayerLoggedOutEvent}) / 死亡 ({@link LivingDeathEvent}) /
 * 换维度 ({@link PlayerEvent.PlayerChangedDimensionEvent}) 清该玩家全部 pending。
 *
 * 稳膛抗击退 (第十章红线): 严禁 AttributeModifier; {@link ChefKnockbackHandler} 在 LivingKnockBackEvent
 * 读 {@link #knockbackResistPerMille(UUID)} 减比, 属性零修饰符。
 *
 * 周期回血 (余韵): tick 内按摊还速率 heal(%最大血量), 进食可打断由 eat-time 入口保证 (吃完才盖章)。
 */
public final class ChefWindowEffectState {

    /**
     * 包级构造: 仅 {@link ChefSystem} new 一个实例订阅 forge 事件 (tick 推进 + 反泄漏清理); 静态窗口表
     * (stamp/active/clearAll 等) 无需实例。混合体 (静态注册表 + 实例事件订阅者) 故构造非 private。
     */
    ChefWindowEffectState() {
    }

    /** 单个窗口效果: 结束 server game tick + 数值快照 (千分比/百分比基点) + 余韵累计已回。 */
    private static final class Window {
        long endTick;
        int magnitude;
        /** 余韵专用: 已回血量累计 (绝对值), 防超过总额。 */
        float regenHealed;
        float regenTotal;
        /** 披甲专用: 本窗口授予的护盾绝对值 (最大血 x 千分比), 过期回收 absorption 时减去这一份。 */
        float shieldGranted;
    }

    /** 每玩家每种窗口效果一个 Window (同种刷新覆盖, 不叠)。仅服务端写, ConcurrentHashMap 防并发读写。 */
    private static final Map<UUID, Map<ChefEffectType, Window>> STATE = new ConcurrentHashMap<>();

    /** 余韵周期回血间隔 (tick): 每秒一次摊还。 */
    private static final int REGEN_INTERVAL_TICKS = 20;

    /**
     * eat-time 盖一个窗口效果 (吃完才调, 保证进食可打断)。同种刷新覆盖 (不叠)。
     *
     * @param player    吃菜玩家 (服务端)
     * @param type      窗口效果种类 (须 isWindowed)
     * @param magnitude 数值快照 (千分比基点)
     * @param windowSeconds 窗口时长秒
     */
    public static void stamp(ServerPlayer player, ChefEffectType type, int magnitude, int windowSeconds) {
        if (!type.isWindowed()) {
            throw new IllegalArgumentException("stamp called for non-windowed effect: " + type);
        }
        long now = player.serverLevel().getGameTime();
        Window w = new Window();
        w.endTick = now + (long) windowSeconds * 20L;
        w.magnitude = magnitude;
        if (type == ChefEffectType.AFTERTASTE_REGEN) {
            // 余韵: magnitude=总回血千分比, 折算成绝对总额 (按当前最大血), 周期摊还。
            w.regenTotal = player.getMaxHealth() * (magnitude / 1000.0F);
            w.regenHealed = 0.0F;
        }
        STATE.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>()).put(type, w);
    }

    /**
     * eat-time 盖披甲 (黄心护盾) 窗口: 立即把 absorption 抬到 max(现值, 目标) 并记录本 mod 实际抬高的份额,
     * 过期由 {@link #onServerTick} 回收 (与其它窗口同生命周期; 解决 "护盾窗口过期后绝不回收 absorption" 的
     * 平衡红线)。max 语义与 TarotEffectEngine#addAbsorption/schedulePeriodicAbsorption 同一约定, 不与之
     * 相加 (F083 复核: 厨师不得凭空叠加外来/塔罗来源的 absorption, 只负责把总量顶到自己的目标)。
     *
     * 刷新不叠 (Chef_Job_DesignSpec 第十一章): 同档/低档在窗口内重复吃, 目标不超过之前已抬到的高度, 不二次
     * 加算。记账取"本次相对施加前 absorption 的差值"并按上一窗口的自留份额累加 (F083 建议: shieldGranted 应
     * 记差值而非整体覆盖) —— 若不累加, 窗口内二次刷新时 delta=0 会把上一次已抬高的份额记丢, 过期永不回收。
     *
     * @param player        吃菜玩家 (服务端)
     * @param perMille      护盾 %最大血量千分比基点 (写进 magnitude 供读)
     * @param windowSeconds 护盾窗口时长秒
     */
    public static void stampShield(ServerPlayer player, int perMille, int windowSeconds) {
        float shield = player.getMaxHealth() * (perMille / 1000.0F);
        if (shield <= 0.0F) {
            return;
        }
        Map<ChefEffectType, Window> existing = STATE.get(player.getUUID());
        Window prev = existing == null ? null : existing.get(ChefEffectType.SHIELD);
        float current = player.getAbsorptionAmount();
        // 旧护盾可能已被伤害吃掉一部分, 账面绝不能超过玩家实际剩余的 absorption。
        float prevOwned = prev == null ? 0.0F : Math.min(prev.shieldGranted, current);
        // 本次相对当前总 absorption (含外来份额) 还能再抬高多少; 若外来来源已经更高, delta=0, 不动它。
        float delta = Math.max(0.0F, shield - current);
        if (delta > 0.0F) {
            player.setAbsorptionAmount(current + delta);
        }
        long now = player.serverLevel().getGameTime();
        Window w = new Window();
        w.endTick = now + (long) windowSeconds * 20L;
        w.magnitude = delta > 0.0F ? perMille : (prev != null ? prev.magnitude : perMille);
        w.shieldGranted = prevOwned + delta;
        STATE.computeIfAbsent(player.getUUID(), k -> new ConcurrentHashMap<>()).put(ChefEffectType.SHIELD, w);
    }

    /** 某玩家某窗口效果是否激活 (未过期)。 */
    public static boolean active(UUID playerId, ChefEffectType type) {
        Map<ChefEffectType, Window> m = STATE.get(playerId);
        return m != null && m.containsKey(type);
    }

    /** 耐饥减饥饿衰减比例 (千分比基点; 0 = 无)。供 {@link ChefHungerHandler} 读。 */
    public static int hungerReducePerMille(UUID playerId) {
        return magnitudeOf(playerId, ChefEffectType.ENDURANCE);
    }

    /** 稳膛抗击退比例 (千分比基点; 0 = 无)。供 {@link ChefKnockbackHandler} 读 (LivingKnockBackEvent)。 */
    public static int knockbackResistPerMille(UUID playerId) {
        return magnitudeOf(playerId, ChefEffectType.STABLE_AIM);
    }

    /** 凝脂爆炸减伤比例 (千分比基点; 0 = 无)。供 {@link ChefGreaseReduction} 读 (玩家减伤单点结算的爆炸源)。 */
    public static int greaseReducePerMille(UUID playerId) {
        return magnitudeOf(playerId, ChefEffectType.GREASE);
    }

    private static int magnitudeOf(UUID playerId, ChefEffectType type) {
        Map<ChefEffectType, Window> m = STATE.get(playerId);
        if (m == null) {
            return 0;
        }
        Window w = m.get(type);
        return w == null ? 0 : w.magnitude;
    }

    /** 清某玩家全部 pending (反泄漏: 登出/死亡/换维度)。 */
    public static void clearAll(UUID playerId) {
        STATE.remove(playerId);
    }

    /**
     * 在线回收: 在清表前, 把该玩家所有披甲窗口已 {@link #stampShield} 授予的 absorption 退还
     * (与 {@link #advancePlayerWindows} 过期分支同口径 setAbsorptionAmount(max(0, current - shieldGranted)))。
     *
     * 红线 (stampShield 注释自称要解决的): changeDimension 复用实体不重置 absorption, 登出实体下线后
     * 该 absorption 也不回收。若仅 STATE.remove 删窗口记录, 已授予的护盾将永不回收 -> 永久护盾。故凡能拿到
     * 在线 ServerPlayer 的清理路径 (登出/换维度), 必须先按各窗口 shieldGranted 累计退还, 再清表。
     *
     * @param player 待回收的在线玩家 (服务端)
     */
    public static void reclaimOnline(ServerPlayer player) {
        Map<ChefEffectType, Window> windows = STATE.get(player.getUUID());
        if (windows != null) {
            float reclaim = 0.0F;
            for (Map.Entry<ChefEffectType, Window> entry : windows.entrySet()) {
                if (entry.getKey() == ChefEffectType.SHIELD && entry.getValue().shieldGranted > 0.0F) {
                    reclaim += entry.getValue().shieldGranted;
                }
            }
            if (reclaim > 0.0F) {
                player.setAbsorptionAmount(Math.max(0.0F, player.getAbsorptionAmount() - reclaim));
            }
        }
        STATE.remove(player.getUUID());
    }

    // ---- 事件: 全局 tick 推进 + 反泄漏清理 (由 ChefSystem 注册到 forgeBus) ----

    /** 服务端 tick: 推进所有玩家窗口, 过期移除, 余韵周期摊还回血。 */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        net.minecraft.server.MinecraftServer server = net.minecraftforge.server.ServerLifecycleHooks.getCurrentServer();
        if (server == null) {
            return;
        }
        long now = server.overworld().getGameTime();
        for (Map.Entry<UUID, Map<ChefEffectType, Window>> e : STATE.entrySet()) {
            UUID id = e.getKey();
            ServerPlayer player = server.getPlayerList().getPlayer(id);
            advancePlayerWindows(id, player, now);
        }
    }

    /**
     * 推进单玩家全部窗口到 now: 过期移除 (披甲过期回收 absorption), 余韵周期摊还回血。空表清出 STATE。
     * 抽出供 {@link #onServerTick} 遍历调用与 GameTest 直接驱动 (测试与生产同一回收代码路径, 不另写副本)。
     */
    static void advancePlayerWindows(UUID id, ServerPlayer player, long now) {
        Map<ChefEffectType, Window> windows = STATE.get(id);
        if (windows == null) {
            return;
        }
        windows.entrySet().removeIf(entry -> {
            Window w = entry.getValue();
            if (now >= w.endTick) {
                // 披甲过期: 回收本窗口授予的护盾 absorption (减去这一份, 钳到 0; 在线才回收)。
                if (entry.getKey() == ChefEffectType.SHIELD && player != null && w.shieldGranted > 0.0F) {
                    float remaining = Math.max(0.0F, player.getAbsorptionAmount() - w.shieldGranted);
                    player.setAbsorptionAmount(remaining);
                }
                return true; // 过期移除。
            }
            if (entry.getKey() == ChefEffectType.AFTERTASTE_REGEN && player != null
                    && now % REGEN_INTERVAL_TICKS == 0L) {
                tickRegen(player, w);
            }
            return false;
        });
        if (windows.isEmpty()) {
            STATE.remove(id);
        }
    }

    /** 余韵周期回血: 按剩余总额与剩余时间摊还, 不超过总额 (战斗向 %最大血量, 进食可打断由盖章入口保证)。 */
    private static void tickRegen(ServerPlayer player, Window w) {
        if (w.regenHealed >= w.regenTotal) {
            return;
        }
        long remainTicks = Math.max(REGEN_INTERVAL_TICKS, w.endTick - player.serverLevel().getGameTime());
        int remainIntervals = (int) Math.max(1L, remainTicks / REGEN_INTERVAL_TICKS);
        float perTick = (w.regenTotal - w.regenHealed) / remainIntervals;
        if (perTick <= 0.0F) {
            return;
        }
        float before = player.getHealth();
        player.heal(perTick);
        w.regenHealed += Math.max(0.0F, player.getHealth() - before);
    }

    @SubscribeEvent
    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        // 玩家仍在线 (本 tick 下线前): 回收披甲 absorption 再清表, 否则护盾随实体下线永不回收。
        if (event.getEntity() instanceof ServerPlayer player) {
            reclaimOnline(player);
        } else {
            clearAll(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        // changeDimension 复用实体不重置 absorption: 必须主动回收本 mod 授予的护盾, 不能只删窗口记录。
        if (event.getEntity() instanceof ServerPlayer player) {
            reclaimOnline(player);
        } else {
            clearAll(event.getEntity().getUUID());
        }
    }

    @SubscribeEvent
    public void onDeath(LivingDeathEvent event) {
        // 死亡: 实体已重置, absorption 已归零, 纯清表即可 (无须回收)。
        if (event.getEntity() instanceof Player player) {
            clearAll(player.getUUID());
        }
    }

    /** 测试钩子: 直接盖一个窗口 (绕过 ServerPlayer 依赖, 供 GameTest 用 mock; 仅同包可见)。 */
    static void stampRaw(UUID playerId, ChefEffectType type, long endTick, int magnitude) {
        Window w = new Window();
        w.endTick = endTick;
        w.magnitude = magnitude;
        STATE.computeIfAbsent(playerId, k -> new ConcurrentHashMap<>()).put(type, w);
    }
}
