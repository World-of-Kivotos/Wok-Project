package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionSelfRepairCycle;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 冠军【自我修复单元】(SELF_REPAIR, spec 7.4 ★4 c14; v2 2026-07-07 真服验收用户二调) 读条自愈效果施加。
 * 有效血 ≤50% 且 CD 就绪时进读条修复: 读条 6s 真定身 (移速归零修饰 + 停导航 + 清目标) + 免伤 90%
 * ({@link ChampionSelfRepairCycle#CHANNEL_DAMAGE_KEEP}), 每秒无条件回一跳血, 读满进 CD; 近战命中打断进 CD =
 * 唯一反制 (v1 的"受伤停跳 1.5s"已删: 与免伤互斥矛盾, 持续火力下技能永远零回血)。计时/三态迁移全下沉纯逻辑
 * {@link ChampionSelfRepairCycle} (dev GameTest 逐 tick 精确验), 本 handler 只做真服侧: 实体检出/定身修饰/免伤改量/
 * 回血施加/发光粒子音效/近战判别。
 *
 * 两个入口:
 *  - {@link #onServerTick} (END, 每 tick): 对 {@link #stateByChampion} 在册冠军, 读条中的每 tick 定身 + 推进跳血 (
 *    per-tick 精度 —— 定身/打断窗要求非 1s 节流可容忍); 非读条中的按当前有效血占比试起读条 (读设定后血量, 触发精确)。
 *    在册集由 {@link #onChampionHurt} 战斗入册维护 (冠军血量只降于受伤 -> 达触发阈值者必已受伤入册), 免全世界扫描。
 *  - {@link #onChampionHurt} (HIGH, 早于血池 LOWEST 取消): 冠军受任意伤害即入册; 读条期入伤 ×0.1; 近战命中打断读条。
 *
 * 血池权威 (spec 6.2): 6★+ 冠军回血/血占比走影子血池 {@link BloodPool} (照 {@code ChampionSelfEffectHandler} 范式),
 * 1-5★ 无池走 vanilla getHealth/getMaxHealth。
 *
 * per-冠军状态 {@link RepairState} (纯逻辑 cycle + 所在维度 + TTL 触达 tick): 冠军死亡摘除 + TTL 清扫双保险 —— 冠军未设
 * persistenceRequired, despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏。注册由主线 {@code ChampionSystem#register}
 * 挂 forgeBus (脱离 Champions 依赖, dev GameTest 可加载)。
 */
public final class ChampionSelfRepairHandler {

    /** 诊断日志: 技能读条真服对账用 (状态迁移逐条; 逐 tick 定身不打)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/skill");

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未受伤/未在读条) 即回收。丢态语义安全: 回收后视为"从未受伤/未在循环",
     * 而触发只需当下血占比 + CD (读条结束才计 CD), 5min 未触达的冠军必远离战斗, 回收不改任何可观测行为, 只兜内存。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /**
     * per-冠军自我修复状态 (UUID -> 状态)。仅战斗受伤过的冠军入册 (血量只降于受伤 -> 达 ≤50% 触发阈值者必已入册),
     * 免每 tick 全世界扫描。冠军死亡 ({@link #onChampionDeath}) 摘除 + TTL 清扫防 despawn 泄漏。
     */
    private final Map<UUID, RepairState> stateByChampion = new HashMap<>();

    /**
     * 每 tick 推进在册冠军的读条 (定身 + 跳血) 与试起读条 (据设定后血占比)。per-tick 而非 1s 节流: 定身要每 tick 压住导航/
     * 位移, 打断窗与跳血对齐要 tick 级精度; 在册集极小 (仅受伤过的自我修复冠军), 逐个按维度 O(1) 检出实体, 开销可忽略。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (stateByChampion.isEmpty()) {
            return; // 最廉价早退: 无在册自我修复冠军。
        }
        MinecraftServer server = event.getServer();
        long nowTick = server.overworld().getGameTime();

        Iterator<Map.Entry<UUID, RepairState>> it = stateByChampion.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, RepairState> entry = it.next();
            RepairState st = entry.getValue();
            LivingEntity entity = resolve(server, st.dimension, entry.getKey());
            if (entity == null || !entity.isAlive()) {
                // 实体已 despawn/卸载/死亡 (读条中途丢失亦然): 无实体可回血/定身, 摘除 (读条自然作废)。
                it.remove();
                continue;
            }
            processChampionTick(entity, st, nowTick);
        }

        // TTL 清扫 (despawn/卸载不发死亡事件的泄漏兜底): 低频回收长期未触达的状态条目。
        if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
            sweepStaleStates(nowTick);
        }
    }

    /** 对一只在册自我修复冠军推进一 tick: 读条中 -> 定身 + 跳血 + 读满特效; 非读条 -> 按血占比试起读条。 */
    private void processChampionTick(LivingEntity entity, RepairState st, long nowTick) {
        AffixQuality quality = selfRepairQuality(entity);
        if (quality == null) {
            return; // 词条被摘/非冠军 (理论不该): 不推进, 交 TTL 回收。
        }
        double healPerSecond = AffixDef.SELF_REPAIR.valueFor(quality);
        ChampionSelfRepairCycle cycle = st.cycle;

        if (cycle.isChanneling()) {
            st.lastTouchedTick = nowTick;
            rootAndDisarm(entity); // 逐 tick 定身 + 抑制攻击 (输出窗口... v2 = 强拆窗口); 逐 tick 不打日志。
            ChampionSelfRepairCycle.ChannelTick tick = cycle.advance(nowTick, healPerSecond);
            if (tick.heal() > 0.0D) {
                applyHeal(entity, tick.heal());
                emitHealParticles(entity);
                if (ChampionDiagnostics.shouldTrace(entity)) {
                    LOGGER.info("skill-repair heal {} +{}HP",
                            entity.getType().getDescriptionId(), String.format("%.1f", tick.heal()));
                }
            }
            if (tick.completed()) {
                unroot(entity); // 读满即撤定身移速修饰 (打断路径在 onChampionHurt 撤)。
                playDoneEffects(entity);
                LOGGER.info("skill-repair done {} heal/s={}",
                        entity.getType().getDescriptionId(), String.format("%.0f", healPerSecond));
            }
            return;
        }

        // 非读条 (IDLE/COOLDOWN): 读设定后血占比试起读条 (中级档 0 由 cycle 内 healPerSecond<=0 挡)。
        double fraction = effectiveFraction(entity);
        if (cycle.tryStart(nowTick, fraction, healPerSecond)) {
            st.lastTouchedTick = nowTick;
            beginChannelPresentation(entity);
            rootAndDisarm(entity); // 起读条即刻定身 (免首 tick 空档)。
            LOGGER.info("skill-repair start {} frac={} heal/s={}",
                    entity.getType().getDescriptionId(), String.format("%.2f", fraction),
                    String.format("%.0f", healPerSecond));
        }
    }

    /**
     * 冠军受任意伤害: 入册 + 读条期免伤 90% (v2 用户拍板) + 近战命中打断读条。HIGH 优先级: 早于血池 handler (LOWEST)
     * 取消, 保被血池吞掉 vanilla 扣血的 6★+ 冠军也照判 (受伤是否发生与净减伤/取消无关); 免伤在此改量, 血池的
     * 被动词条减伤在其后按已折量继续结算 (读条免伤是时限状态独立乘算, 非红线 1 单点内的被动源, 用户裁定登记于
     * spec 7.4 批3 注记)。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onChampionHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        if (selfRepairQuality(victim) == null) {
            return; // 非本工程冠军 / 未装配自我修复。
        }
        long nowTick = victim.level().getGameTime();
        ResourceKey<Level> dim = victim.level().dimension();
        RepairState st = stateByChampion.computeIfAbsent(victim.getUUID(), k -> new RepairState(dim, nowTick));
        st.lastTouchedTick = nowTick;

        if (st.cycle.isChanneling()) {
            float amount = event.getAmount();
            if (amount > 0.0F) {
                // 读条期免伤 90% (远程磨不动, 逼近战强拆); 打断的那一击同样折量 (打断与否不看伤害量)。
                event.setAmount((float) ChampionSelfRepairCycle.channelIncomingDamage(amount));
            }
        }

        // 近战命中 (仅 MOB_ATTACK/MOB_ATTACK_NO_AGGRO/PLAYER_ATTACK, 不含爆炸/远程) 打断读条进 CD。
        if (isMeleeDamage(event.getSource()) && st.cycle.interruptByMelee(nowTick)) {
            unroot(victim); // 撤定身移速修饰 (恢复行动)。
            victim.removeEffect(MobEffects.GLOWING); // 打断即撤读条发光 (免残留误导"仍在修复")。
            playInterruptEffects(victim);
            LOGGER.info("skill-repair interrupt {} by melee", victim.getType().getDescriptionId());
        }
    }

    /** 冠军死亡: 摘 per-冠军状态防泄漏 (发光/定身随实体销毁自然消失, 无需手撤)。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        stateByChampion.remove(event.getEntity().getUUID());
    }

    /** 回收 TTL 内未被触达 (未受伤/未在读条) 的状态条目 (语义安全性见 {@link #STATE_TTL_TICKS})。 */
    private void sweepStaleStates(long nowTick) {
        // MIN_VALUE (理论不出现: 建条目即刷触达) 显式视为过期, 防减法溢出漏回收。
        stateByChampion.values().removeIf(st -> st.lastTouchedTick == Long.MIN_VALUE
                || nowTick - st.lastTouchedTick > STATE_TTL_TICKS);
    }

    /** 该实体装配的自我修复品质 (非本工程冠军 / 未装配返 null)。 */
    private static AffixQuality selfRepairQuality(LivingEntity entity) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return null;
        }
        return champ.quality(AffixDef.SELF_REPAIR);
    }

    /** 按状态记录的维度检出在册冠军实体 (维度未加载/实体不在返 null; UUID -> 实体为 O(1) 表查)。 */
    private static LivingEntity resolve(MinecraftServer server, ResourceKey<Level> dimension, UUID id) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) {
            return null;
        }
        Entity found = level.getEntity(id);
        return found instanceof LivingEntity living ? living : null;
    }

    /** 读条定身移速修饰固定 UUID (MULTIPLY_TOTAL -1.0 = 移速归零; 瞬态不入 NBT)。 */
    private static final UUID ROOT_MODIFIER_UUID = UUID.fromString("7c2e9a41-5b3d-4f86-a0c7-e91d24b6f358");

    /**
     * 读条定身 + 抑制攻击 (仅读条期每 tick)。v2 真服修正: v1 的"停导航 + 归零位移"每 tick 与 MoveControl 的前进
     * 速度互搏, 观感只是减速不是定身 (真服首验反馈) —— 改挂 MOVEMENT_SPEED MULTIPLY_TOTAL -1.0 瞬态修饰
     * (结果移速恒 0, AI 想走也走不动), 叠加停导航 + 清攻击目标。清目标即抑制攻击 —— 1.20.1 无公开"只禁攻击不丢
     * 目标"的 Mob API, 取最简可靠的清目标; 代价是丢仇恨, 读条结束由原生索敌 goal 重新锁玩家。
     */
    private static void rootAndDisarm(LivingEntity entity) {
        if (!(entity instanceof Mob mob)) {
            return; // 冠军 capability 只挂 Mob, 恒可转型; 防御性早退。
        }
        AttributeInstance attr = mob.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null && attr.getModifier(ROOT_MODIFIER_UUID) == null) {
            attr.addTransientModifier(new AttributeModifier(
                    ROOT_MODIFIER_UUID, "champion_self_repair_root", -1.0D,
                    AttributeModifier.Operation.MULTIPLY_TOTAL));
        }
        mob.getNavigation().stop();
        mob.setTarget(null);
    }

    /** 撤读条定身移速修饰 (读满/近战打断时恢复行动; 实体死亡随销毁自然消失)。 */
    private static void unroot(LivingEntity entity) {
        AttributeInstance attr = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr != null) {
            attr.removeModifier(ROOT_MODIFIER_UUID);
        }
    }

    /** 读条开始表现 (spec 9A.6): 自身发光, 时长 = 读条全长 (读满同 tick 自然到期; 打断在 onChampionHurt 撤除)。 */
    private static void beginChannelPresentation(LivingEntity entity) {
        entity.addEffect(new MobEffectInstance(
                MobEffects.GLOWING, (int) ChampionSelfRepairCycle.CHANNEL_TICKS, 0, false, false));
    }

    /** 每跳回血粒子 (spec 9A.6): 身位 6 粒 happy_villager。 */
    private static void emitHealParticles(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                entity.getX(), entity.getY() + entity.getBbHeight() * 0.5D, entity.getZ(),
                6, 0.3D, 0.4D, 0.3D, 0.0D);
    }

    /** 读满表现 (spec 9A.6): 升级音 (音量 0.6)。 */
    private static void playDoneEffects(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                SoundEvents.PLAYER_LEVELUP, SoundSource.HOSTILE, 0.6F, 1.0F);
    }

    /** 打断表现 (spec 9A.6): poof 粒子 + 铁砧落地音 (音量 0.5)。 */
    private static void playInterruptEffects(LivingEntity entity) {
        if (!(entity.level() instanceof ServerLevel level)) {
            return;
        }
        level.sendParticles(ParticleTypes.POOF,
                entity.getX(), entity.getY() + entity.getBbHeight() * 0.5D, entity.getZ(),
                12, 0.3D, 0.4D, 0.3D, 0.01D);
        level.playSound(null, entity.getX(), entity.getY(), entity.getZ(),
                SoundEvents.ANVIL_LAND, SoundSource.HOSTILE, 0.5F, 1.0F);
    }

    /** 施本跳回血: 6★+ 走影子血池 (vanilla 血条由血池 handler 每 tick 镜像); 1-5★ 无池走 vanilla heal (照自身被动范式)。 */
    private static void applyHeal(LivingEntity entity, double heal) {
        BloodPool pool = BloodPoolRegistry.get(entity.getUUID());
        if (pool != null) {
            pool.heal(heal);
            return;
        }
        entity.heal((float) heal);
    }

    /** 触发判定的有效血占比: 6★+ 血池 fraction / 1-5★ vanilla getHealth/getMaxHealth (maxHealth ≤0 退化视为满血不触发)。 */
    private static double effectiveFraction(LivingEntity entity) {
        BloodPool pool = BloodPoolRegistry.get(entity.getUUID());
        if (pool != null) {
            return pool.fraction();
        }
        double max = entity.getMaxHealth();
        if (!(max > 0.0D)) {
            return 1.0D;
        }
        return entity.getHealth() / max;
    }

    /** 近战伤害判别 (打断口径, 仅近战不含爆炸; 参照 {@code ChampionBloodPoolHandler} 近战判别去爆炸分支)。 */
    private static boolean isMeleeDamage(DamageSource source) {
        return source.is(DamageTypes.MOB_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.PLAYER_ATTACK);
    }

    /**
     * per-冠军自我修复状态: 纯逻辑读条状态机 (三态 + 计时权威) + 所在维度 (每 tick O(1) 检出实体) + 最后触达 tick
     * (TTL 清扫依据; 建条目/受伤/读条推进时刷新)。TTL/维度是 handler 内存/检出簿记, 与状态机纯逻辑解耦。
     */
    private static final class RepairState {
        private final ChampionSelfRepairCycle cycle = new ChampionSelfRepairCycle();
        private final ResourceKey<Level> dimension;
        private long lastTouchedTick;

        private RepairState(ResourceKey<Level> dimension, long nowTick) {
            this.dimension = dimension;
            this.lastTouchedTick = nowTick;
        }
    }
}
