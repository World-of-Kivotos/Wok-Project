package com.miningdim.champion.integration;

import com.miningdim.champion.ChampionDamageTypes;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ExpiryLedger;
import com.miningdim.champion.MiningChampions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * 大额 AOE 2s 伤害免疫缓冲 (精英怪批4 波0 基建; ChampionStarAffix spec 红线 3)。红线 3 原文: 大额 AOE/核弹命中后
 * 给被击玩家 2s 伤害免疫缓冲 (其他冠军来源衰减至 0, DoT 按时间走), 由【缓冲而非压低数值】防多来源叠杀 —— 一次
 * 电磁/天雷/小男孩已足够压制, 2s 内其它冠军直接伤害不得再叠加致死。
 *
 * 触发方 (波2, 尚未落地): 电磁/天雷/小男孩等大额 AOE 命中玩家【结算完自身伤害后】调 {@link #grant(ServerPlayer)}
 * 开窗。波0 只落地缓冲基建 (账本 + 拦截 + 清扫), 无调用方属计划内 —— 波2 接线时直接调 grant 即可, 拦截语义已就位。
 *
 * 拦截 (红线 3): {@link #onLivingHurt} 挂 {@link EventPriority#HIGHEST} —— 早于攻击 on-hit rider (HIGH) 与血池
 * 净减伤 (LOWEST) 等一切下游 handler, 缓冲是硬闸, 命中缓冲中玩家的【冠军直接伤害且非豁免类型】直接
 * setAmount(0) + setCanceled, 下游整体被跳过 (不落 DoT 刷层/损甲/易伤副作用)。绝大多数受击 (未缓冲 / 非冠军来源)
 * 在此早退零副作用。冠军直接伤害判定: {@code source.getEntity()} 是带 {@link com.miningdim.champion.MiningChampionData}
 * capability 的冠军 (与 {@link ChampionAttackHandler} 的攻击者判定同口径; 冠军近战不另造 DamageSource, 是把额外伤
 * 叠进 vanilla mob_attack 事件, 故来源实体仍是冠军)。
 *
 * 豁免 (缓冲中仍照常结算, 见 {@link #isExemptDamageType}): DoT (magic) / 命定处决 (champion_execution) / 反震
 * (champion_thorns) 三类逐项裁定不受缓冲拦截。
 *
 * 设计 (静态账本 + 实例 handler): 缓冲态是全局单例服务, 存于静态 {@link #LEDGER} (代理 A 冻结契约
 * {@link ExpiryLedger} 提供的到期账本), 故 {@link #grant}/{@link #isBuffered}/{@link #reset} 皆静态;
 * @SubscribeEvent 拦截/清扫为实例方法, 由 {@code ChampionSystem#register} 以 {@code forgeBus.register(new AoeImmunityBuffer())}
 * 挂载 (与包内既有 handler 同注册范式)。本 handler 只读事件 + 掐伤, 无实体位移写操作, 故无需 server.execute 回主线程
 * (LivingHurtEvent 本就在服务端主线程结算)。
 */
public final class AoeImmunityBuffer {

    /** 诊断日志: 缓冲拦截真服首验用 (拦截本就低频 = 仅 2s 窗内被冠军再打, 再经 trace 门控只打眼前的怪)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/aoebuffer");

    /** 缓冲时长 (tick): 2s = 40tick (红线 3 定值; 命中大额 AOE 起算的免疫窗)。 */
    public static final long BUFFER_TICKS = 40L;

    /**
     * 缓冲账本清扫周期 (tick): 缓冲条目仅 {@value #BUFFER_TICKS}tick 短命且读判 {@link #isBuffered} 已按 tick 判到期,
     * sweep 只回收已过期条目的内存残留 (登出/掉线玩家等), 故低频清扫即可 (与 {@code ChampionSelfEffectHandler}
     * TTL 清扫同范式, END 相位每 5s 一次)。
     */
    private static final int SWEEP_INTERVAL_TICKS = 100;

    /**
     * per-玩家缓冲到期账本 (玩家 UUID -> 到期 gameTime tick; 纯逻辑, 由代理 A 冻结契约 {@link ExpiryLedger} 提供)。
     * 静态单例: 缓冲态全局唯一, 静态 API 与实例 handler 共读同一账本。同一玩家重复 grant 由账本按 UUID 覆盖到期 tick
     * (续窗), 故账本大小受在线玩家数天然封顶。
     */
    private static final ExpiryLedger LEDGER = new ExpiryLedger();

    /**
     * 授予某玩家 2s 大额 AOE 免疫缓冲: 到期 tick = 当前 gameTime + {@value #BUFFER_TICKS}。波2 的电磁/天雷/小男孩等
     * 大额 AOE 命中玩家【结算完自身伤害后】调用 —— 之后 2s 内该玩家受到的其它冠军直接伤害经 {@link #onLivingHurt}
     * 衰减至 0, 由缓冲防多来源叠杀 (红线 3)。
     *
     * @param player 获缓冲的服务端玩家 (被大额 AOE 命中者)
     */
    public static void grant(ServerPlayer player) {
        long nowTick = player.level().getGameTime();
        LEDGER.grant(player.getUUID(), nowTick + BUFFER_TICKS);
    }

    /**
     * 该玩家是否仍在 AOE 免疫缓冲窗内 (读账本按当前 gameTime 判到期)。过期条目由周期 {@link #onServerTick} 清扫回收,
     * 但读判独立于清扫 —— 账本 {@link ExpiryLedger#isActive} 以 nowTick 判活, 过期未清扫的条目亦返 false。
     *
     * @param player 待查玩家
     * @return 是否在缓冲窗内
     */
    public static boolean isBuffered(ServerPlayer player) {
        return LEDGER.isActive(player.getUUID(), player.level().getGameTime());
    }

    /**
     * 大额 AOE 命中后的开窗裁决 (纯逻辑, 与 {@link PlayerLandingProtection#shouldCancelKnockback} 同为测试可见缝):
     * 仅当受击玩家在【本次命中之前】不在缓冲窗内, 这一发才算一次真实命中, 才开窗。窗内的那一发已被
     * {@link #onLivingHurt} 在 HIGHEST 掐 0, 不构成新的压制, 不得续窗 —— 否则玩家只要待在落点圈里, 每 &lt;=2s 一发
     * 的零伤 AOE 就能把免疫窗无限推到 now+2s, 站在爆点不动反而成为最优解, 与这些技能"锁定落点可躲"的设计意图相反
     * (F102)。
     *
     * @param playerId 受击玩家 UUID
     * @param nowTick  本次命中时的 gameTime tick
     * @param ledger   查询的到期账本
     * @return 是否应在本次命中后开窗
     */
    public static boolean shouldGrantAfterAoeHit(UUID playerId, long nowTick, ExpiryLedger ledger) {
        return !ledger.isActive(playerId, nowTick);
    }

    /**
     * 大额 AOE 结算完自身伤害后的统一开窗入口 (电磁蓄力/天雷/小男孩共用, 各 handler 不再自行判断): 本次命中前已在
     * 窗内则不续窗, 否则照常开 2s 窗。
     *
     * @param player 结算完伤害的服务端玩家
     */
    public static void grantIfNotBuffered(ServerPlayer player) {
        if (!shouldGrantAfterAoeHit(player.getUUID(), player.level().getGameTime(), LEDGER)) {
            return;
        }
        grant(player);
    }

    /**
     * 清空缓冲账本 (服务端停止清理 + GameTest 隔离共用; 审查修复: 换存档后 gameTime 从小值重计, 残留的旧到期 tick
     * 会让同 UUID 玩家在新存档被误判仍在缓冲窗, 故 {@code ChampionSystem#onServerStopping} 必须清账)。
     * {@link ExpiryLedger} 冻结契约无 clear 方法, 故以 {@code sweep(Long.MAX_VALUE)} 令一切条目到期回收 ——
     * 任何真实到期 tick (gameTime + 40) 皆 &lt; Long.MAX_VALUE, 无论 sweep 到期判定含界与否均全清。
     */
    public static void reset() {
        LEDGER.sweep(Long.MAX_VALUE);
    }

    /**
     * 缓冲拦截 (红线 3): 受害者为缓冲中的 {@link ServerPlayer} 且伤害属【冠军直接伤害】且非豁免类型 -> 掐为 0 并取消。
     * HIGHEST 优先级 (审查修复): 必须早于同事件 HIGH 的 {@link ChampionAttackHandler} —— 后者在同一 LivingHurtEvent
     * 里落 DoT 刷层/损甲/易伤等即时副作用, 同 HIGH 时按注册序 FIFO 它先跑, 事后 cancel 只回滚 event.amount 回滚不了
     * 这些副作用, "免疫窗内仍被叠 DoT/磨甲"恰是红线 3 要防的叠杀向量。HIGHEST + cancel 后 receiveCanceled=false 的
     * 下游 (含 AttackHandler/血池 LOWEST) 整体被跳过, 硬闸才成立。未缓冲 / 非玩家受击在此早退 (绝大多数受击零副作用)。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer victim)) {
            return; // 仅拦服务端玩家受击 (缓冲是给玩家的红线 3 保护; 冠军间/环境受击不涉)。
        }
        boolean victimBuffered = isBuffered(victim);
        if (!victimBuffered) {
            return; // 未在缓冲窗: 常规结算 (绝大多数受击早退, 不做后续判定)。
        }
        DamageSource source = event.getSource();
        LivingEntity champion = championAttacker(source);
        boolean exempt = isExemptDamageType(source);
        if (!shouldBlockDamage(victimBuffered, champion != null, exempt)) {
            return; // 非冠军来源 (环境/玩家互伤/原版怪) 或豁免类型 (DoT/处决/反震): 不拦, 照常结算。
        }
        event.setAmount(0.0F);
        event.setCanceled(true);

        // 诊断 (真服首验, 仅 10 格内有玩家的冠军; 拦截本就低频=仅 2s 窗内): 打一行被掐掉的冠军伤害来源。
        if (ChampionDiagnostics.shouldTrace(champion)) {
            LOGGER.info("aoe-buffer-block victim={} champion={} src={}",
                    victim.getName().getString(), champion.getType().getDescriptionId(), source.getMsgId());
        }
    }

    /**
     * 周期回收过期缓冲条目 (纯内存卫生): END 相位每 {@value #SWEEP_INTERVAL_TICKS}tick 一次, 按 overworld gameTime
     * 清账本已到期条目。读判 {@link #isBuffered} 已按 tick 判到期, 清扫不影响正确性, 仅回收登出/掉线玩家残留条目。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SWEEP_INTERVAL_TICKS != 0) {
            return;
        }
        LEDGER.sweep(server.overworld().getGameTime());
    }

    /**
     * 缓冲拦截真值判定 (纯逻辑, 供 GameTest 精确断言真值表): 仅当【受害者在缓冲窗 且 攻击者为冠军直接伤害 且 非豁免
     * 类型】三者同真才拦 (掐 0 + 取消)。删任一条件都会翻转某组合的真值 —— 删 victimBuffered 则未缓冲受击被误拦;
     * 删 attackerIsChampion 则环境/玩家互伤被误拦; 删 !isExemptDamageType 则 DoT/处决/反震被误拦。
     *
     * public static 为测试可见缝 (GameTest 在 champion 根包, 与本 integration 包跨包, 故非 package-private)。
     *
     * @param victimBuffered     受害者是否在 2s 缓冲窗内
     * @param attackerIsChampion 伤害来源是否本工程冠军直接伤害
     * @param isExemptDamageType 是否豁免类型 (DoT magic / 命定处决 / 反震)
     * @return 是否应把本次伤害掐为 0 并取消
     */
    public static boolean shouldBlockDamage(boolean victimBuffered, boolean attackerIsChampion,
                                            boolean isExemptDamageType) {
        return victimBuffered && attackerIsChampion && !isExemptDamageType;
    }

    /**
     * 该伤害来源的攻击者若为本工程冠军则返回之, 否则 null (冠军直接伤害判定; spec 红线 3 口径: getEntity 是带冠军
     * capability 的怪)。取 {@code source.getEntity()} (弹射物取射手 = 冠军本体, 与 {@link ChampionAttackHandler}
     * 攻击者判定同口径); 冠军近战不另造 DamageSource 而是把额外伤叠进 vanilla mob_attack 事件, 故来源实体仍是冠军。
     * 非生物来源 (环境/无主弹射物) 或非冠军怪返 null。
     */
    private static LivingEntity championAttacker(DamageSource source) {
        Entity attacker = source.getEntity();
        if (attacker instanceof LivingEntity living && MiningChampions.isChampion(living)) {
            return living;
        }
        return null;
    }

    /**
     * 是否缓冲豁免伤害类型 (缓冲中仍照常结算, 逐项裁定):
     *  - {@code minecraft:magic}: 冠军 DoT 致死份经 {@code ChampionDotTickHandler} 走 {@code damageSources().magic()}
     *    结算 ("DoT 按时间走"是红线 3 原文 —— 持续 DoT 不受缓冲拦截, 按其自身时间线扣血);
     *  - {@code champion_execution}: 命定之死处决 (spec 7.4) 是红线内既裁定的必死结算, 缓冲不得挡 (达标未果必死);
     *  - {@code champion_thorns}: 反震反伤自带 30%/s + 40%/窗 多源封顶, 缓冲挡了会给打反伤冠军的玩家开免费输出窗口。
     * 其余冠军直接伤害 (近战/大额 AOE 等) 一律受缓冲拦截。
     */
    private static boolean isExemptDamageType(DamageSource source) {
        return source.is(DamageTypes.MAGIC)
                || source.is(ChampionDamageTypes.CHAMPION_EXECUTION)
                || source.is(ChampionDamageTypes.CHAMPION_THORNS);
    }
}
