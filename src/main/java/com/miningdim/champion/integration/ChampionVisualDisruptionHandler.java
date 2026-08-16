package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionEffectRegistries;
import com.miningdim.champion.ChampionVisualDisruptionValues;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.aggregate.PlayerControlAggregator;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 冠军【技能词条·视觉干扰】(Stage2; ChampionStarAffix spec 7.4 ★4 c12) 效果施加 (Champions 集成层)。周期性对冠军
 * 【当前攻击目标玩家】施原版失明 (Blindness, amplifier 0):
 *  - 周期/时长按品质取 {@link ChampionVisualDisruptionValues} (周期 240/210/180/160/140 tick, 名义时长
 *    20/30/40/45/50 tick); 周期锚点是 per-冠军"已累加循环 tick", 仅在有存活目标的扫描推进 (无目标不耗周期)。
 *  - 红线 5 (控制聚合): 失明属控制, 名义时长须经该目标玩家 {@link PlayerControlAggregator#admit} 的 7s 窗 50% 上限
 *    + ≥2s 自由窗夹断, 施加【裁后】时长; 被裁到 0 则本周期作废不施 (周期照走不补偿, 单一权威在聚合器)。
 *  - 表现 (spec 9A.6 原版可见原语): 施放瞬间对目标眼位喷墨鱼墨粒子 + 播远古守卫诅咒音, 让被失明玩家肉眼/耳可辨来源。
 *
 * 入口 {@link #onServerTick} (END, 每 {@value #SCAN_INTERVAL_TICKS}tick=1s): 按玩家 AABB 扫近处冠军 (与
 * {@code ChampionSelfEffectHandler} 同范式, 覆盖命令召唤 + 自然刷两种来源), 对装配视觉干扰的冠军推进周期 + 到点施放。
 *
 * per-冠军循环状态 (已累加循环 tick + 最后触达 tick) 双清: 冠军死亡摘除 + TTL 清扫兜底 —— 冠军未设
 * persistenceRequired, 自然 despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏 (与 {@code ChampionSelfEffectHandler}
 * 同纪律)。
 *
 * 自研 capability 检出 (MiningChampions.get + quality(VISUAL_DISRUPTION)), 不触任何 top.theillusivec4.champions.*;
 * 注册由主线在 {@code ChampionSystem} 挂 forgeBus。数值/周期判定纯逻辑下沉 {@link ChampionVisualDisruptionValues} 真测。
 */
public final class ChampionVisualDisruptionHandler {

    /** 诊断日志: 施放时打一行 (低频, 不门控) —— champion/tier/target/名义时长/裁后时长, 真服对账控制裁剪用。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/skill");

    /** 扫描/周期推进周期 (tick): 1s 扫一次近玩家冠军 (与纯逻辑 SCAN_INTERVAL_TICKS 对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = (int) ChampionVisualDisruptionValues.SCAN_INTERVAL_TICKS;

    /** per-冠军循环状态; 冠军死亡摘除 + TTL 清扫双保险 (despawn/卸载不发死亡事件的泄漏兜底)。 */
    private final Map<UUID, DisruptionState> stateByChampion = new HashMap<>();

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未被有目标的扫描推进) 即回收。丢态语义安全: 回收后视为"循环从零起",
     * 而周期至多 12s、无目标本就冻结不推进 —— 5min 未触达的冠军必然远离玩家或长期脱战, 回收不改变任何可观测行为,
     * 只兜内存。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /**
     * 每秒扫近玩家冠军: 对装配视觉干扰的冠军推进周期 + 到点施放。按玩家 AABB 扫 + 自研 capability 检出冠军
     * (命令召唤 + 自然刷一视同仁), 多玩家同看一冠军本轮只结算一次。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        if (server.getTickCount() % SCAN_INTERVAL_TICKS != 0) {
            return;
        }
        long nowTick = server.overworld().getGameTime();
        for (ChampionProximityScanner.Sighting sighting : ChampionProximityScanner.sightings(server)) {
            if (!sighting.entity().isAlive()) {
                continue; // 快照按 tick 复用, 同 tick 更早的 handler 可能已致死: 存活性逐条重查。
            }
            applyDisruptionTick(sighting.entity(), nowTick);
        }

        // TTL 清扫 (despawn/卸载不发死亡事件的泄漏兜底): 低频回收长期未触达的状态条目。
        if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
            sweepStaleStates(nowTick);
        }
    }

    /** 回收 TTL 内未被触达的状态条目 (语义安全性见 {@link #STATE_TTL_TICKS})。 */
    private void sweepStaleStates(long nowTick) {
        // MIN_VALUE (理论不出现: 建条目点即刻刷触达) 显式视为过期, 防减法溢出漏回收。
        stateByChampion.values().removeIf(state -> state.lastTouchedTick == Long.MIN_VALUE
                || nowTick - state.lastTouchedTick > STATE_TTL_TICKS);
    }

    /** 对一只实体 (若是装配视觉干扰的本工程冠军) 推进周期; 到点且有存活目标则施失明。 */
    private void applyDisruptionTick(LivingEntity entity, long nowTick) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality quality = champ.quality(AffixDef.VISUAL_DISRUPTION);
        if (quality == null) {
            return; // 未装配视觉干扰: 不建状态 (防为无关冠军泄漏 state)。
        }
        ServerPlayer target = resolveTarget(entity);
        if (target == null) {
            return; // 无存活玩家攻击目标: 本 tick 不施且周期不推进 (无目标不耗周期; 不建/不触达状态)。
        }

        DisruptionState state = stateByChampion.computeIfAbsent(entity.getUUID(), k -> new DisruptionState());
        state.lastTouchedTick = nowTick;
        state.elapsedCycleTicks = ChampionVisualDisruptionValues.advanceCycle(state.elapsedCycleTicks);
        if (!ChampionVisualDisruptionValues.cycleReady(state.elapsedCycleTicks, quality)) {
            return; // 未到周期 (skill 冷却充能中; 首次施放在获得目标后一整个周期)。
        }
        // 到点: 清零重计 (无论控制是否裁到 0, 周期照走不补偿)。
        state.elapsedCycleTicks = 0L;
        castBlindness(entity, champ.star(), target, quality, nowTick);
    }

    /**
     * 施放一次视觉干扰: 名义失明时长经目标玩家控制聚合器夹断 (红线 5), 施加裁后时长的原版 Blindness (amplifier 0);
     * 裁到 0 则本周期作废不施 (周期已清零, 不补偿)。诊断日志无论是否裁 0 均打 (施放属低频事件, 真服对账用)。
     */
    private void castBlindness(LivingEntity champion, int star, ServerPlayer target, AffixQuality quality, long nowTick) {
        long nominalTicks = ChampionVisualDisruptionValues.blindnessDurationTicks(quality);
        PlayerControlAggregator control = ChampionEffectRegistries.controlFor(target.getUUID());
        long granted = control.admit(nowTick, nominalTicks);

        LOGGER.info("skill-blind champion={} tier{} target={} nominal={}t clamped={}t",
                champion.getType().getDescriptionId(), star, target.getGameProfile().getName(),
                nominalTicks, granted);

        if (granted <= 0L) {
            return; // 控制额度耗尽 (7s 窗 <=50% / 保 ≥2s 自由窗): 本周期作废不施 + 不放特效 (红线 5)。
        }
        target.addEffect(new MobEffectInstance(MobEffects.BLINDNESS, (int) granted, 0, false, true));
        emitCastEffects(target);
    }

    /**
     * 施放瞬间原版可见反馈 (spec 9A.6 原语): 目标眼位喷墨鱼墨 (~12 粒小散布) + 播远古守卫诅咒音 (音量 0.6),
     * 让被失明玩家肉眼/耳可辨来源 (纯原版客户端可见, 不赌玩家装我方 mod)。仅在失明真落地 (裁后 &gt;0) 时喷,
     * 裁到 0 不放 (否则误导玩家以为被失明)。
     */
    private static void emitCastEffects(ServerPlayer target) {
        if (!(target.level() instanceof ServerLevel level)) {
            return;
        }
        level.sendParticles(ParticleTypes.SQUID_INK,
                target.getX(), target.getEyeY(), target.getZ(), 12, 0.25D, 0.25D, 0.25D, 0.0D);
        level.playSound(null, target.getX(), target.getEyeY(), target.getZ(),
                SoundEvents.ELDER_GUARDIAN_CURSE, SoundSource.HOSTILE, 0.6F, 1.0F);
    }

    /** 冠军当前攻击目标 (须为存活 ServerPlayer, 否则 null): 非 Mob / 无目标 / 目标非玩家 / 目标死亡均返 null。 */
    private static ServerPlayer resolveTarget(LivingEntity entity) {
        if (!(entity instanceof Mob mob)) {
            return null;
        }
        if (!(mob.getTarget() instanceof ServerPlayer target)) {
            return null;
        }
        if (!target.isAlive()) {
            return null;
        }
        return target;
    }

    /** 冠军死亡: 摘 per-冠军循环状态防泄漏。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        stateByChampion.remove(event.getEntity().getUUID());
    }

    /**
     * per-冠军循环状态: 已累加循环 tick (初始 0, 仅有目标扫描推进 {@link ChampionVisualDisruptionValues#SCAN_INTERVAL_TICKS},
     * 到点由 handler 清零重计) + 最后触达 tick (TTL 清扫依据, 有目标扫描时刷新)。
     */
    private static final class DisruptionState {
        private long elapsedCycleTicks = 0L;
        private long lastTouchedTick = Long.MIN_VALUE;
    }
}
