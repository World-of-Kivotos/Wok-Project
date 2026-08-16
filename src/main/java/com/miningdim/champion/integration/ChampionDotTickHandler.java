package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionAffixParticles;
import com.miningdim.champion.ChampionAttackValues;
import com.miningdim.champion.ChampionEffectRegistries;
import com.miningdim.champion.aggregate.PlayerControlAggregator;
import com.miningdim.champion.aggregate.PlayerDotAccumulator;
import com.miningdim.champion.aggregate.PlayerDotSources;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.UUID;

/**
 * 冠军 DoT (燃烧/寒霜冻伤) 每秒持续结算 + 寒霜持续减速的【单一权威 tick handler】(Champions 集成层;
 * ChampionStarAffix spec 7.2 持续 DoT + 红线 4 DoT ≤15% maxHP/s + 9.5 聚合器 + 9A.3 #8 per-player 跨多怪聚合)。
 *
 * 持续 DoT (spec 7.2: 命中后在 3s 刷新窗内每秒持续扣血, 不要求每秒都有新命中): 受击点 ({@link ChampionAttackHandler})
 * 命中只【刷层 + 续 3s 窗】进 per-player {@link PlayerDotSources}; 本 handler 每秒对该玩家【仍在窗内的每个在册源】
 * 按当前层数调 {@link ChampionAttackValues#burningTickHp}/{@link ChampionAttackValues#frostFreezeTickHp} 补记本秒名义
 * 伤害进 {@link PlayerDotAccumulator}, 再经 {@code DotAggregator} 把多源合计夹 ≤15% maxHP/s 一次性施加。窗口过期
 * ({@link PlayerDotSources#pruneExpired}) 自然停伤。如此玩家风筝/冠军够不到的那秒也照常持续扣血 (问题: 旧实现仅
 * 命中秒 record, 非命中秒 pending 空 -> 退化成"仅命中秒扣血")。寒霜减速同样按窗内活跃源每秒持续施加 (走控制聚合
 * ≤50%), 非仅命中秒。
 *
 * DoT 致死 (问题: 纯 DoT 扣到 0 血却不死): 原版 {@code LivingEntity.setHealth(0)} 只夹血量不触发 {@code die()}
 * (死亡由 {@code hurt()->actuallyHurt->health<=0->die()} 驱动)。故本秒合计 DoT 致死时 (health - total ≤ 0), 致死的
 * 最后一份走 {@code player.hurt(DoT 源, lethalAmount)} 触发原版死亡流程; 致死前先清 i-frame
 * ({@code invulnerableTime}=0) 保 hurt 不被受击无敌帧吞掉致死伤害 (vanilla 1.20.1 bypasses_cooldown 标签为空, 无内置
 * 源跳无敌帧, 故显式清帧)。非致死份继续走 setHealth 直接扣血 —— 规避 i-frame 吞每秒 DoT 与易伤 (撕裂/塔罗) 对已
 * 红线 4 钳后权威量的二次放大 (15% 封顶是权威, 不可再经入伤链放大)。
 *
 * compileOnly 隔离: 本类不 import 任何 top.theillusivec4.champions.* (累加器/源模型/聚合器是纯逻辑), 但归 integration
 * 包 (仅 Champions 加载时由 bootstrap 挂 forgeBus, 与攻击 handler 同生命周期; dev 下 DOT 表恒空, 本 tick no-op)。
 */
public final class ChampionDotTickHandler {

    /** 诊断日志: DoT 链真服首验用 (每秒对每个身上有 DoT 的玩家打一行 合计伤/活跃源层数; 打在测试者自己身上天然低频)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/dot");

    /** DoT 粒子节流: 每多少 tick 在中招玩家身上播一轮签名粒子 (按活跃源类型: 燃烧火/寒霜雪; cosmetic, 与扣血分离)。 */
    private static final int DOT_PARTICLE_INTERVAL_TICKS = 3;

    /**
     * 寒霜减速每秒重施时长 (tick): tick handler 每秒按活跃寒霜源层数施加一段 1s 的原版减速, 下一秒在窗内再续施,
     * 故只需覆盖到下次 tick 重施前 (= 1s = 20tick)。窗口过期源不再续施, 减速自然衰退。
     */
    private static final int SLOW_REAPPLY_TICKS = 20;

    /** 原版 MOVEMENT_SLOWDOWN 每级减速量 (-15%/级): 把夹后减速量 floor 折成最近不超档的 amplifier。 */
    private static final double SLOWDOWN_PER_AMPLIFIER = 0.15D;

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (!ChampionEffectRegistries.hasAnyDot()) {
            return; // 无在册 DoT (累加器 + 源模型均空): 跳过 (dev / 无 DoT 战斗时 no-op)。
        }
        MinecraftServer server = event.getServer();
        long nowTick = server.overworld().getGameTime();
        boolean emitParticles = server.getTickCount() % DOT_PARTICLE_INTERVAL_TICKS == 0;

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            UUID playerId = player.getUUID();
            if (!ChampionEffectRegistries.hasDot(playerId)) {
                continue;
            }
            // 每 tick 先清过期源 (窗口过期精确到 tick): 令火粒子/减速与"当前真活跃源"一致。修视觉残留 bug ——
            // 旧实现火粒子只 gate 在 hasDot(注册表存在)上, 源 prune 成空后 Map 键残留致 hasDot 恒真, DoT 伤害已停
            // 火粒子仍每 3tick 永久播; 现按活跃源快照决定播不播 + 播哪种。
            PlayerDotSources sources = ChampionEffectRegistries.dotSourcesFor(playerId);
            sources.pruneExpired(nowTick);

            // 持续 DoT 粒子 (cosmetic): 仅当前仍有活跃源才播, 且按源类型播 (燃烧火/寒霜雪); 无活跃源不播。
            if (emitParticles && player.level() instanceof ServerLevel serverLevel) {
                emitActiveDotParticles(serverLevel, player, sources.activeSources());
            }

            PlayerDotAccumulator acc = ChampionEffectRegistries.dotFor(playerId);
            double maxHp = player.getMaxHealth();
            // shouldFlush 有开窗副作用, 须每 tick 调 (短路左项恒求值); maxHp>0 才真结算本秒 DoT。
            if (acc.shouldFlush(nowTick) && maxHp > 0.0D) {
                // 1s 边界: 先按当前在册源补记本秒名义伤害 (持续 DoT 核心) + 持续施加寒霜减速, 再 flush 统一扣血。
                recordActiveSources(player, maxHp, nowTick);
                PlayerDotAccumulator.FlushResult result = acc.flush(maxHp, nowTick);
                if (result.total() > 0.0D) {
                    // 诊断 (F071 降级): 原为无门控 INFO, 一场 20 人被 DoT 的团战就是 20 行/秒持续输出。三次
                    // String.format + summarizeSources 都是 SLF4J 参数化日志的【实参】, 会先求值再传入本调用,
                    // 加不加 isDebugEnabled 门控直接决定这几次求值是否发生 —— 不加守卫等于没降级。
                    // applyDotDamage 是业务动作, 必须留在守卫外面, 不随日志级别变化。
                    if (LOGGER.isDebugEnabled()) {
                        LOGGER.debug("dot-tick {} total={} hp={}/{} sources={}",
                                player.getName().getString(), String.format("%.2f", result.total()),
                                String.format("%.1f", player.getHealth()), String.format("%.1f", maxHp),
                                summarizeSources(sources.activeSources()));
                    }
                    applyDotDamage(player, result.total());
                }
            }

            // DoT 自然耗尽 (源全空 + 累加器无 pending): 即时回收注册表条目, 令 hasDot 归假, 下 tick 不再空转/空放粒子。
            ChampionEffectRegistries.releaseDotIfIdle(playerId);
        }
    }

    /**
     * 按当前活跃 DoT 源【类型】在中招玩家身上播签名粒子 (燃烧 -> 火 / 寒霜 -> 雪; 复用 {@link ChampionAffixParticles}
     * 单一映射)。同类型只播一轮; 无活跃源不播 —— DoT 窗口过期 (prune) 后 activeSources 空即停粒子 (修 DoT 已停粒子残留)。
     */
    private void emitActiveDotParticles(ServerLevel level, ServerPlayer player,
                                        List<PlayerDotSources.ActiveSource> active) {
        boolean burning = false;
        boolean frost = false;
        for (PlayerDotSources.ActiveSource src : active) {
            if (src.def() == AffixDef.BURNING) {
                burning = true;
            } else if (src.def() == AffixDef.FROST) {
                frost = true;
            }
        }
        if (burning) {
            spawnDotParticle(level, player, ChampionAffixParticles.ambientParticle(AffixDef.BURNING));
        }
        if (frost) {
            spawnDotParticle(level, player, ChampionAffixParticles.ambientParticle(AffixDef.FROST));
        }
    }

    /** 在玩家包围盒中心附近随机偏移播几颗指定粒子 (纯服务端, vanilla 自动同步附近客户端)。 */
    private void spawnDotParticle(ServerLevel level, ServerPlayer player, ParticleOptions particle) {
        level.sendParticles(particle,
                player.getX(), player.getY() + player.getBbHeight() * 0.5D, player.getZ(),
                3, player.getBbWidth() * 0.35D, player.getBbHeight() * 0.4D, player.getBbWidth() * 0.35D, 0.01D);
    }

    /**
     * 把该玩家仍在 3s 刷新窗内的每个 DoT 源按当前层数补记本秒名义伤害进累加器 (持续 DoT, {@link #recordNominalForSecond}),
     * 并对仍活跃的寒霜源按层持续施减速 (走控制聚合 ≤50%)。补记与减速分离: 名义伤害补记是 player-free 纯逻辑 (按
     * UUID 走注册表, 供 GameTest 直接驱动), 减速须真玩家故留本 handler。
     */
    private void recordActiveSources(ServerPlayer player, double maxHp, long nowTick) {
        recordNominalForSecond(player.getUUID(), maxHp, nowTick);
        // 减速: 重新取活跃寒霜源 (上一步已 prune), 按当前层数每秒续施。
        PlayerDotSources sources = ChampionEffectRegistries.dotSourcesFor(player.getUUID());
        for (PlayerDotSources.ActiveSource src : sources.activeSources()) {
            if (src.def() == AffixDef.FROST) {
                applyFrostSlow(player, src.quality(), src.stacks(), nowTick);
            }
        }
    }

    /**
     * 把某玩家仍在 3s 刷新窗内的每个 DoT 源按当前层数补记【本秒名义伤害】进其累加器 (持续 DoT 核心: 命中后即便
     * 无新命中, 窗内每秒都按在册层数补记本秒伤害)。先 {@link PlayerDotSources#pruneExpired} 清过期源 (窗口过期自然
     * 停伤), 再遍历活跃源把燃烧/寒霜冻伤名义 HP 记入 (≤15% 合计封顶由 flush 时 DotAggregator 夹)。
     *
     * player-free (按 UUID 走注册表): 供本子系统 GameTest 推进多秒直接驱动 + 断言累计掉血 (删本逐秒补记 -> 非命中
     * 秒 pending 恒空 -> DoT 退化成仅命中秒扣血, 持续 DoT 累计断言必挂)。
     *
     * @param playerId 受 DoT 玩家 UUID
     * @param maxHp    玩家有效最大血量 (&gt;0; %maxHP 折 HP 基数)
     * @param nowTick  当前 gameTime tick (prune 过期源 + 推进窗判定)
     */
    public static void recordNominalForSecond(UUID playerId, double maxHp, long nowTick) {
        PlayerDotSources sources = ChampionEffectRegistries.dotSourcesFor(playerId);
        sources.pruneExpired(nowTick);
        List<PlayerDotSources.ActiveSource> active = sources.activeSources();
        if (active.isEmpty()) {
            return;
        }
        PlayerDotAccumulator acc = ChampionEffectRegistries.dotFor(playerId);
        for (PlayerDotSources.ActiveSource src : active) {
            double hp;
            if (src.def() == AffixDef.BURNING) {
                hp = ChampionAttackValues.burningTickHp(src.quality(), src.stacks(), maxHp);
            } else if (src.def() == AffixDef.FROST) {
                hp = ChampionAttackValues.frostFreezeTickHp(src.quality(), src.stacks(), maxHp);
            } else {
                continue; // 仅燃烧/寒霜入 DoT 补记。
            }
            if (hp > 0.0D) {
                // 源标识 = (冠军 UUID, 词条): 区分多冠军同时上同类 DoT, 跨秒同源累加由 flush 衰减。
                acc.record(dotSourceKey(src), hp);
            }
        }
    }

    /**
     * 寒霜减速持续施加: 按当前层数折减速量 (自夹 ≤50%), 申请进 per-player 控制聚合 (7s 窗 ≤50% 受控 + ≥2s 自由窗),
     * 施加一段 1s 的原版 MOVEMENT_SLOWDOWN。控制聚合 admit 返回被夹后可施加 tick (超额作废返 0 = 本秒不施, 保自由窗)。
     */
    private void applyFrostSlow(ServerPlayer player, AffixQuality frostQuality, int stacks, long nowTick) {
        double slowPct = ChampionAttackValues.frostSlowPct(frostQuality, stacks);
        if (slowPct <= 0.0D) {
            return;
        }
        PlayerControlAggregator control = ChampionEffectRegistries.controlFor(player.getUUID());
        long granted = control.admit(nowTick, SLOW_REAPPLY_TICKS);
        if (granted <= 0L) {
            return; // 控制额度耗尽 (7s 窗 ≤50%): 本秒减速作废, 保留自由窗。
        }
        int amp = (int) Math.floor(slowPct / SLOWDOWN_PER_AMPLIFIER) - 1;
        if (amp < 0) {
            amp = 0;
        }
        player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, (int) granted, amp, false, true));
    }

    /**
     * 施加本秒合计 DoT (已红线 4 钳后权威量 ≤15% maxHP): 非致死走 setHealth 直接扣血 (规避 i-frame 吞 DoT + 易伤
     * 二次放大); 致死 (total ≥ health) 走 {@code hurt} 触发原版死亡。致死前清 i-frame 保致死伤害不被无敌帧吞, 用
     * DoT 专属源 (magic, 无视护甲、与冠军近战源区分) 走入伤链触发 {@code actuallyHurt -> health<=0 -> die()}。
     *
     * public static: 供本子系统 GameTest (champion 包) 直接对低血 mock 玩家断言"持续 DoT 致死真触发死亡"。判别信号取
     * {@code Pose.DYING} (仅 die() 置位; setHealth(0) 不改 pose, 也不触发 die()) —— 删本致死分支退回纯 setHealth 则
     * 玩家停在 0 血 STANDING 不进 DYING, 测试的 Pose.DYING 断言必挂 (isDeadOrDying/isAlive 仅看 health 不可判别)。
     *
     * @param player 受 DoT 玩家
     * @param total  本秒合计 DoT 实际伤害 (HP; 已红线 4 钳后, 须 &gt;0 由调用方保证)
     */
    public static void applyDotDamage(ServerPlayer player, double total) {
        double health = player.getHealth();
        if (total < health) {
            // 非致死: 直接扣血 (权威量不再经入伤链二次放大/被无敌帧吞)。
            player.setHealth((float) (health - total));
            return;
        }
        // 致死: 走原版死亡流程。setHealth(0) 不触发 die(), 故对致死份走 hurt; 清 i-frame 保不被受击无敌帧吞致死伤。
        player.invulnerableTime = 0;
        DamageSource dotSource = player.level().damageSources().magic();
        // lethalAmount 取当前血量 + 护盾 (吸收) + 1, 保 actuallyHurt 后 health<=0 必触发 die() (含吸收盾耗尽)。
        float lethalAmount = (float) (health + player.getAbsorptionAmount() + 1.0D);
        player.hurt(dotSource, lethalAmount);
        // 兜底: DoT 是红线4钳后【权威】量, 必须能致死。magic 不在 bypasses_invulnerability 标签内, 会被 spawn 保护/
        // 抗性 V/创造无敌 等吞掉致死伤 (player.hurt 返 false, 玩家卡在正数血量不死)。若 magic 未致死, 用 vanilla /kill
        // 同款 genericKill (在 bypasses_invulnerability 标签内, 穿透一切保护) 强制触发 die(), 保证持续 DoT 真能致死。
        if (player.isAlive() && player.getHealth() > 0.0F) {
            player.hurt(player.level().damageSources().genericKill(), Float.MAX_VALUE);
        }
    }

    /** DoT 累加器源键 (冠军 UUID + 词条复合串): 同源同秒累加, 区分多冠军同时上同类 DoT。 */
    private static String dotSourceKey(PlayerDotSources.ActiveSource src) {
        return src.championId() + "/" + src.def().name();
    }

    /** 活跃 DoT 源摘要串 (诊断日志用): "BURNING x3,FROST x2" 形态 (词条 x 层数, 逗号分隔; 空源返 "-")。 */
    private static String summarizeSources(List<PlayerDotSources.ActiveSource> active) {
        if (active.isEmpty()) {
            return "-";
        }
        StringBuilder sb = new StringBuilder();
        for (PlayerDotSources.ActiveSource src : active) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(src.def().name()).append(" x").append(src.stacks());
        }
        return sb.toString();
    }
}
