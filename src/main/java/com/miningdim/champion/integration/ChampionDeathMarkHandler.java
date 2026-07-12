package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionDamageTypes;
import com.miningdim.champion.ChampionDeathMarkMath;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionTargetLocks;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冠军压轴技能【命定之死 DEATH_MARK】(Champions 集成层; ChampionStarAffix spec 7.4, ★8 超凡+)。标记一名近期对本怪
 * 有输出的存活在线玩家, 限时 8s 内该玩家须对本怪打出【采样阈值】伤害, 否则处决 (maxHealth 真伤必死)。核心反制机制
 * 是"标记期对本怪伤害衰减 30% + 阈值按标记前实测 DPS ×1.6" —— 逼玩家在被减伤情况下补更多刀, 补不够则死。
 *
 * 三个事件入口:
 *  - {@link #onServerTick} (END, 每 {@value #SCAN_INTERVAL_TICKS}tick=1s): 按玩家 AABB 扫近处冠军 (与
 *    {@code ChampionSelfEffectHandler} 同范式), 对持本词条的冠军推进标记生命周期 (就绪即标记 / 达标解除 / 窗耗尽
 *    处决 / 目标丢失解除) + 每秒刷 actionbar。就绪判定 = CD 过 + 有合法候选 (无独立周期表, 1s 扫描粒度)。
 *  - {@link #onMarkedPlayerHurtChampion} (NORMAL): 被标记玩家对本怪的入伤先按【衰减前名义值】累计进度, 再
 *    ×0.7 衰减 (早于血池 LOWEST 净减伤)。进度用衰减前口径 = 自证门槛恒 1.6× 采样可达 (对抗审查修正, 否则
 *    叠乘成 2.29× 必死); ×0.7 只压低 BOSS 实际掉血 (迫使补刀语义)。
 *  - {@link #onPlayerDamageChampion} (LOWEST + receiveCanceled): 喂滚动采样账 (DPS 阈值基数)。
 *    Forge 无 Bukkit 式 MONITOR, LOWEST 即事件链末端相位; 血池怪 (本词条恒 8★ 有血池) 会 cancel 事件, 故
 *    receiveCanceled; 血池只 cancel 不 setAmount, LOWEST 读到的 {@code event.getAmount()} 即名义入伤 (与贡献账
 *    同口径)。
 *
 * 数值/阈值/采样/口径数学全下沉纯逻辑 {@link ChampionDeathMarkMath} (GameTest 真测); 跨冠军互斥 (命定/反击 一玩家
 * 一锁) 经 {@link ChampionTargetLocks}。冠军检出/词条品质经 {@link MiningChampions} 读自研 capability, 不触
 * 任何 top.theillusivec4.champions.*。注册由主线在 {@code ChampionSystem} 挂 forgeBus。
 *
 * 状态防泄漏 (与 {@code ChampionSelfEffectHandler} 同双保险): 冠军死亡摘状态 + 释放锁 ({@link #onChampionDeath}),
 * 加 TTL 清扫 ({@link #sweepStaleStates}) 兜 despawn/区块卸载不发死亡事件的泄漏。
 */
public final class ChampionDeathMarkHandler {

    /** 诊断日志: 命定之死真服对账用 (标记/每秒进度/结局)。低频事件 (标记/结局) 不门控; 每秒进度经诊断门控 (10 格内)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/deathmark");

    /** 扫描/就绪判定周期 (tick): 1s 扫一次近玩家冠军推进标记生命周期 (与 actionbar 每秒刷对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = ChampionDeathMarkMath.TICKS_PER_SECOND;

    /** 标记驱动断档放弃阈值 (tick): 两个扫描周期无驱动 = 目标已脱离扫描范围, 放弃标记防冻结延迟处决。 */
    private static final long DRIVE_GAP_ABORT_TICKS = 2L * SCAN_INTERVAL_TICKS;

    /** 生命周期推进的玩家可见距离 (格; 与自身被动/血条同量级)。远离该范围的冠军不结算。 */
    private static final double VIEW_RANGE = 48.0D;

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未受击/未被扫描) 即回收。丢态语义安全: 标记窗仅 8s、锁 duration 亦 8s
     * 兜底过期、GLOWING 8s 自灭, 5min 未触达的冠军必然无玩家在场 (无采样/无标记推进), 回收不改任何可观测行为, 只兜内存。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /** per-冠军命定之死状态 (采样账 + 活动标记 + CD)。冠军死亡摘除 + TTL 清扫双保险防泄漏。 */
    private final Map<UUID, DeathMarkState> stateByChampion = new HashMap<>();

    /**
     * 每秒扫近玩家冠军推进标记生命周期。按玩家 AABB 扫 + capability 检出冠军 (命令召唤 + 自然刷一视同仁),
     * 多玩家同看同一冠军本轮只结算一次。
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
        Set<UUID> processed = new HashSet<>();
        for (ServerLevel level : server.getAllLevels()) {
            List<ServerPlayer> players = level.players();
            if (players.isEmpty()) {
                continue;
            }
            for (ServerPlayer player : players) {
                AABB box = player.getBoundingBox().inflate(VIEW_RANGE);
                for (LivingEntity entity : level.getEntitiesOfClass(LivingEntity.class, box, LivingEntity::isAlive)) {
                    if (!processed.add(entity.getUUID())) {
                        continue; // 多玩家同看一冠军: 本轮只结算一次。
                    }
                    applyDeathMarkTick(entity, nowTick);
                }
            }
        }

        if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
            sweepStaleStates(nowTick);
        }
    }

    /** 回收 TTL 内未被触达 (未受击/未被扫描) 的状态条目 (语义安全性见 {@link #STATE_TTL_TICKS})。 */
    private void sweepStaleStates(long nowTick) {
        // MIN_VALUE (理论不出现: 建条目点即刻刷触达) 显式视为过期, 防减法溢出漏回收。
        stateByChampion.values().removeIf(state -> state.lastTouchedTick == Long.MIN_VALUE
                || nowTick - state.lastTouchedTick > STATE_TTL_TICKS);
    }

    /** 对一只实体 (若是持本词条冠军且已有采样账) 推进标记生命周期。 */
    private void applyDeathMarkTick(LivingEntity champion, long nowTick) {
        MiningChampionData champ = MiningChampions.get(champion).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality quality = champ.quality(AffixDef.DEATH_MARK);
        if (quality == null) {
            return; // 无命定之死词条。
        }
        DeathMarkState state = stateByChampion.get(champion.getUUID());
        if (state == null) {
            return; // 尚无人对本怪造成过伤害 (无采样账): 无候选, 无事可做。
        }
        state.lastTouchedTick = nowTick;
        state.pruneEmptySamplers(nowTick);

        if (state.hasActiveMark()) {
            driveActiveMark(champion, state, nowTick);
        } else {
            maybeStartMark(champion, quality, state, nowTick);
        }
    }

    /** 推进活动标记: 驱动断档放弃 / 目标丢失解除 / 达标解除 / 窗耗尽处决 / 进行中刷 actionbar。 */
    private void driveActiveMark(LivingEntity champion, DeathMarkState state, long nowTick) {
        // 驱动断档守卫 (对抗审查 major): 标记只在"玩家 48 格内"的扫描 tick 被驱动, 目标跑出范围/区块卸载会把
        // 生命周期冻结在原地 —— 若不设防, 数分钟后重新入场会撞上 windowExpired 直接满血处决且冻结期零预警。
        // 断档 >2s (两个扫描周期) 即静默放弃本轮标记 (不处决不结算): 甩开 BOSS 是合法的风筝逃脱反制。
        if (state.lastDriveTick != Long.MIN_VALUE
                && nowTick - state.lastDriveTick > DRIVE_GAP_ABORT_TICKS) {
            LOGGER.info("skill-deathmark champion={} outcome=abandoned_frozen player={} gap={}t",
                    championName(champion), state.markedPlayer, nowTick - state.lastDriveTick);
            endMark(champion, state, nowTick);
            return;
        }
        state.lastDriveTick = nowTick;

        ServerPlayer marked = resolveServerPlayer(champion, state.markedPlayer);
        if (marked == null || !marked.isAlive()) {
            // 目标离线/死亡: 提前解除 (无处决, 无音效; 玩家已死无需再判)。
            LOGGER.info("skill-deathmark champion={} outcome=target_lost player={} (offline/dead)",
                    championName(champion), state.markedPlayer);
            endMark(champion, state, nowTick);
            return;
        }
        if (ChampionDeathMarkMath.thresholdReached(state.progress, state.threshold)) {
            onMarkCleared(champion, marked, state);
            endMark(champion, state, nowTick);
            return;
        }
        if (ChampionDeathMarkMath.windowExpired(nowTick, state.markTick)) {
            executePlayer(champion, marked, state);
            endMark(champion, state, nowTick);
            return;
        }
        // 进行中: 每秒刷 actionbar (剩余秒 + 进度%) + 门控进度诊断。
        int remainSec = ChampionDeathMarkMath.remainingSeconds(nowTick, state.markTick);
        int pct = ChampionDeathMarkMath.progressPercent(state.progress, state.threshold);
        marked.displayClientMessage(Component.literal(
                "命定之死 剩余 " + remainSec + "s  进度 " + pct + "%"), true);
        if (ChampionDiagnostics.shouldTrace(champion)) {
            LOGGER.info("skill-deathmark champion={} player={} remaining={}t progress={}/{} pct={}",
                    championName(champion), marked.getGameProfile().getName(),
                    ChampionDeathMarkMath.remainingTicks(nowTick, state.markTick),
                    String.format("%.1f", state.progress), String.format("%.1f", state.threshold), pct);
        }
    }

    /** CD 过 + 有合法候选 + 抢锁成功 -> 开新标记。任一不满足本周期跳过。 */
    private void maybeStartMark(LivingEntity champion, AffixQuality quality, DeathMarkState state, long nowTick) {
        if (!ChampionDeathMarkMath.cooldownReady(nowTick, state.lastMarkEndTick)) {
            return; // CD 未过。
        }
        Candidate candidate = selectCandidate(champion, state, nowTick);
        if (candidate == null) {
            return; // 无合法候选 (近 10s 对本怪有输出的存活在线玩家)。
        }
        // 跨冠军互斥 (命定/反击 一玩家一锁): 该玩家已被别的锁定类技能占用则本周期跳过 (锁 duration = 标记窗)。
        if (!ChampionTargetLocks.tryAcquire(candidate.playerId(), ChampionTargetLocks.LockKind.DEATH_MARK,
                champion.getUUID(), nowTick, ChampionDeathMarkMath.WINDOW_TICKS)) {
            return;
        }
        double threshold = ChampionDeathMarkMath.markThreshold(
                quality, candidate.sampledDamage(), candidate.activeSpanTicks());
        state.markedPlayer = candidate.playerId();
        state.markTick = nowTick;
        state.threshold = threshold;
        state.progress = 0.0D;
        state.lastDriveTick = nowTick; // 断档守卫基线: 标记起点即首次驱动 (防标记后立即脱离时 MIN_VALUE 绕过守卫)。
        onMarkStart(champion, candidate.player(), candidate, threshold);
    }

    /**
     * 候选取采样伤害最高者 (spec 防藏 DPS 白嫖: 近 10s 对本怪有名义输出的存活在线玩家)。离线/死亡/零输出者排除;
     * 同分取先遍历到者 (稳定即可, spec 未要求 tie-break)。
     */
    private Candidate selectCandidate(LivingEntity champion, DeathMarkState state, long nowTick) {
        Candidate best = null;
        for (Map.Entry<UUID, ChampionDeathMarkMath.RollingDamageSampler> entry : state.samplers.entrySet()) {
            ChampionDeathMarkMath.RollingDamageSampler sampler = entry.getValue();
            double sampled = sampler.sampledDamage(nowTick);
            if (!ChampionDeathMarkMath.isEligibleCandidate(sampled)) {
                continue; // 近 10s 对本怪零输出: 不可标记。
            }
            ServerPlayer player = resolveServerPlayer(champion, entry.getKey());
            if (player == null || !player.isAlive()) {
                continue; // 离线/死亡: 非合法候选。
            }
            if (best == null || sampled > best.sampledDamage()) {
                best = new Candidate(entry.getKey(), player, sampled, sampler.activeSpanTicks(nowTick));
            }
        }
        return best;
    }

    /**
     * 被标记玩家对本怪入伤 ×0.7 衰减 (spec 7.4 迫使补刀)。NORMAL 优先级: 早于血池 LOWEST 净减伤。
     *
     * 进度口径修正 (对抗审查 major): 进度按【衰减前名义值】在此累计 —— 若按衰减后累计, 过关需持续打出
     * 采样 DPS 的 1.6/0.7 ≈ 2.29 倍, 对采样期已近满输出的玩家不可达 = 必死无反制, 违背红线 3 例外前提。
     * 衰减前口径下自证门槛回到 1.6 倍 (采样含换弹空窗, 爆发可超), ×0.7 仍作用于 BOSS 实际掉血 =
     * "迫使队友补刀"语义成立 (被标记者对 BOSS 的净贡献被压低, 团队 DPS 缺口须他人补)。
     */
    @SubscribeEvent(priority = EventPriority.NORMAL)
    public void onMarkedPlayerHurtChampion(LivingHurtEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return; // 非玩家来源。
        }
        DeathMarkState state = stateByChampion.get(event.getEntity().getUUID());
        if (state == null || !state.hasActiveMark()) {
            return; // 受击者非活动标记冠军。
        }
        if (!attacker.getUUID().equals(state.markedPlayer)) {
            return; // 仅被标记玩家对本怪的入伤衰减 (其它玩家不衰减)。
        }
        float amount = event.getAmount();
        if (amount <= 0.0F) {
            return;
        }
        state.progress += amount; // 衰减前名义值入进度 (与阈值的采样口径一致, 门槛 = 1.6x 可达)。
        event.setAmount((float) ChampionDeathMarkMath.decayedDamage(amount));
    }

    /**
     * 喂滚动采样账 (DPS 阈值基数) + 累计标记期进度。LOWEST + receiveCanceled: Forge EventPriority 无 Bukkit 式 MONITOR,
     * LOWEST 即事件链末端相位; 血池怪 cancel 事件后仍须落账 (受击是否发生与 cancel 无关) 故 receiveCanceled。
     * 读 {@code event.getAmount()} = 名义入伤: 命定之死怪恒 8★ 有血池, 血池 handler (亦 LOWEST) 只 cancel 不 setAmount,
     * 故 getAmount() 与本 handler 在 LOWEST 内的先后无关 (同为 NORMAL 衰减后名义), 与贡献账口径一致。标记期被标记
     * 玩家的入伤已被 NORMAL 衰减 ×0.7, 该量计入进度 (与阈值同口径), 但【暂停采样】(suppressed) 防衰减量污染下次阈值。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onPlayerDamageChampion(LivingHurtEvent event) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return; // 非玩家来源: 不采样 (采样账口径 = 玩家对本怪的输出)。
        }
        LivingEntity victim = event.getEntity();
        if (!victim.isAlive()) {
            // 血池 handler (亦 LOWEST) 可能已在本相位内先判致死 kill() 本怪 (嵌套 LivingDeathEvent -> onChampionDeath
            // 摘状态)。若本 feed 在其后跑, computeIfAbsent 会为已死冠军重建状态 (泄漏至 TTL)。死怪无需采样, 早退。
            return;
        }
        MiningChampionData champ = MiningChampions.get(victim).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 受击者非本工程冠军。
        }
        if (champ.quality(AffixDef.DEATH_MARK) == null) {
            return; // 无命定之死词条: 不喂账 (防为其它冠军泄漏采样表)。
        }
        double amount = event.getAmount();
        if (amount <= 0.0D) {
            return;
        }
        long nowTick = victim.level().getGameTime();
        DeathMarkState state = stateByChampion.computeIfAbsent(victim.getUUID(), k -> new DeathMarkState());
        state.lastTouchedTick = nowTick;

        boolean markedNow = state.hasActiveMark() && attacker.getUUID().equals(state.markedPlayer);
        // 进度不在此累计 (已移至 NORMAL 衰减点按【衰减前名义值】累计, 口径修正见 onMarkedPlayerHurtChampion)。
        // 采样: 标记期该玩家的衰减入伤暂停采样 (防压低下次阈值); 其它玩家/标记前正常采样。
        state.sampler(attacker.getUUID()).record(nowTick, amount, markedNow);
    }

    /** 冠军死亡: 摘状态 + 释放其对被标记玩家的锁 (标记随怪死消亡)。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        UUID championId = event.getEntity().getUUID();
        DeathMarkState state = stateByChampion.remove(championId);
        if (state != null && state.markedPlayer != null) {
            ChampionTargetLocks.release(state.markedPlayer, championId);
            LOGGER.info("skill-deathmark champion={} outcome=champion_death player={} (mark released)",
                    event.getEntity().getType().getDescriptionId(), state.markedPlayer);
        }
    }

    /** 标记瞬间表现: 高亮目标 8s + actionbar 预警 (窗口秒/阈值) + 全场施放音 (WITHER_SPAWN 0.5)。 */
    private void onMarkStart(LivingEntity champion, ServerPlayer target, Candidate candidate, double threshold) {
        target.addEffect(new MobEffectInstance(MobEffects.GLOWING, (int) ChampionDeathMarkMath.WINDOW_TICKS));
        target.displayClientMessage(Component.literal(
                "命定之死: " + ChampionDeathMarkMath.WINDOW_SECONDS + "s 内输出 "
                        + (long) Math.ceil(threshold) + " 伤害!"), true);
        if (champion.level() instanceof ServerLevel level) {
            level.playSound(null, champion.getX(), champion.getY(), champion.getZ(),
                    SoundEvents.WITHER_SPAWN, SoundSource.HOSTILE, 0.5F, 1.0F);
        }
        LOGGER.info("skill-deathmark champion={} MARK player={} sampledDps={} spanTicks={} threshold={}",
                championName(champion), target.getGameProfile().getName(),
                String.format("%.1f", ChampionDeathMarkMath.sampledDps(
                        candidate.sampledDamage(), candidate.activeSpanTicks())),
                candidate.activeSpanTicks(), String.format("%.1f", threshold));
    }

    /** 达标解除表现: 撤高亮 (防残留误导"仍被标记") + actionbar 解除提示 + 成功音 (PLAYER_LEVELUP)。 */
    private void onMarkCleared(LivingEntity champion, ServerPlayer target, DeathMarkState state) {
        target.removeEffect(MobEffects.GLOWING);
        target.displayClientMessage(Component.literal("命定之死 已解除"), true);
        if (champion.level() instanceof ServerLevel level) {
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.PLAYER_LEVELUP, SoundSource.PLAYERS, 1.0F, 1.0F);
        }
        LOGGER.info("skill-deathmark champion={} outcome=cleared player={} progress={} threshold={}",
                championName(champion), target.getGameProfile().getName(),
                String.format("%.1f", state.progress), String.format("%.1f", state.threshold));
    }

    /**
     * 窗耗尽未达标处决: 灵魂火粒子 + 雷鸣音 + champion_execution 真伤 (maxHealth × 1.0)。真伤类型无视护甲/保护附魔
     * 但不入 bypasses_invulnerability (保无敌帧/不死图腾可救)。DamageSource 构造照
     * {@code ChampionSelfEffectHandler.applyThorns} 的 registry Holder 写法 (1.20.1 DamageSources.source 全 private,
     * 公开路径 = registry 取 Holder 后 new DamageSource(holder, entity))。
     */
    private void executePlayer(LivingEntity champion, ServerPlayer target, DeathMarkState state) {
        if (champion.level() instanceof ServerLevel level) {
            level.sendParticles(ParticleTypes.SOUL_FIRE_FLAME,
                    target.getX(), target.getY() + 1.0D, target.getZ(), 40, 0.4D, 0.8D, 0.4D, 0.02D);
            level.playSound(null, target.getX(), target.getY(), target.getZ(),
                    SoundEvents.LIGHTNING_BOLT_THUNDER, SoundSource.HOSTILE, 1.0F, 1.0F);
        }
        Holder<DamageType> executionType = champion.level().registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ChampionDamageTypes.CHAMPION_EXECUTION);
        float execDamage = (float) ChampionDeathMarkMath.executionDamage(target.getMaxHealth());
        target.hurt(new DamageSource(executionType, champion), execDamage);
        LOGGER.info("skill-deathmark champion={} outcome=executed player={} damage={} progress={} threshold={}",
                championName(champion), target.getGameProfile().getName(),
                String.format("%.1f", execDamage), String.format("%.1f", state.progress),
                String.format("%.1f", state.threshold));
    }

    /** 结束标记 (达标/处决/目标丢失/怪死): 释放锁 + 清活动标记 + CD 从此刻起算。 */
    private void endMark(LivingEntity champion, DeathMarkState state, long nowTick) {
        if (state.markedPlayer != null) {
            ChampionTargetLocks.release(state.markedPlayer, champion.getUUID());
        }
        state.clearMark();
        state.lastMarkEndTick = nowTick;
    }

    /** 解析玩家实体 (离线/不在本层返 null; 服务端恒 ServerPlayer)。 */
    private static ServerPlayer resolveServerPlayer(LivingEntity champion, UUID playerId) {
        if (champion.level().getPlayerByUUID(playerId) instanceof ServerPlayer player) {
            return player;
        }
        return null;
    }

    /** 冠军类型描述 id (诊断日志; 无 gameprofile 的怪用类型名)。 */
    private static String championName(LivingEntity champion) {
        return champion.getType().getDescriptionId();
    }

    /** 一名合法候选: 玩家 UUID + 实体 + 采样窗内名义输出 (选最高者时的比较键) + 开火跨度 (DPS 稀释修复分母)。 */
    private record Candidate(UUID playerId, ServerPlayer player, double sampledDamage, long activeSpanTicks) {
    }

    /**
     * per-冠军命定之死状态: per-玩家滚动采样账 (DPS 阈值基数) + 活动标记 (被标记玩家/标记起 tick/阈值/进度) +
     * 上次标记结束 tick (CD 起算) + 最后触达 tick (TTL 清扫依据)。markedPlayer=null 表示无活动标记。
     */
    private static final class DeathMarkState {
        private final Map<UUID, ChampionDeathMarkMath.RollingDamageSampler> samplers = new HashMap<>();
        private UUID markedPlayer = null;
        private long markTick = Long.MIN_VALUE;
        private double threshold = 0.0D;
        private double progress = 0.0D;
        private long lastMarkEndTick = Long.MIN_VALUE;
        private long lastTouchedTick = Long.MIN_VALUE;
        private long lastDriveTick = Long.MIN_VALUE;

        private boolean hasActiveMark() {
            return markedPlayer != null;
        }

        private ChampionDeathMarkMath.RollingDamageSampler sampler(UUID playerId) {
            return samplers.computeIfAbsent(playerId, k -> new ChampionDeathMarkMath.RollingDamageSampler());
        }

        /** 过期剔除后回收空采样账 (标记期被标记玩家暂停采样, 其账过期后被回收, 无碍活动标记 —— 进度在 state 上不在账上)。 */
        private void pruneEmptySamplers(long nowTick) {
            samplers.values().removeIf(sampler -> {
                sampler.expire(nowTick);
                return sampler.isEmpty();
            });
        }

        private void clearMark() {
            markedPlayer = null;
            markTick = Long.MIN_VALUE;
            threshold = 0.0D;
            progress = 0.0D;
            lastDriveTick = Long.MIN_VALUE;
        }
    }
}
