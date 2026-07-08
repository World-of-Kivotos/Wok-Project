package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionBladeWaltzPlan;
import com.miningdim.champion.ChampionDamageTypes;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冠军【技能词条·利刃华尔兹 BLADE_WALTZ】(批4 波2; ChampionStarAffix spec 7.4 连段突袭) 效果施加 (集成层)。CD 就绪且
 * 锁定目标在缰绳内时起手 1.5s 预兆, 预兆毕对目标做 N 段瞬移突袭 (每 10t 一段): 每段瞬到目标旁一击, 整套总伤恰压在
 * 连段帽 60% maxHP (每击 = 帽/N 均分)。数值/时序/门控/中止/落点环几何纯逻辑下沉 {@link ChampionBladeWaltzPlan}
 * (dev GameTest 真验), 本 handler 只做真服侧: 实体检出/目标缰绳查询/预兆表现/逐候选过
 * {@link KnockbackSafetyGuard#evaluateLanding} 选点/mob.teleportTo/每击 SKILL_AOE 伤下发。
 *
 * <p>每击伤害走 {@link ChampionDamageTypes#CHAMPION_SKILL_AOE} 源 (主线拍板): 该类型被 {@code ChampionAttackHandler}
 * 豁免近战 on-hit rider —— 突袭击天然不附带混沌击飞/燃烧/寒霜/损甲等副作用 (连段是干净的判决伤, 不磨甲不挂 DoT)。
 * 每击【不】grant {@link AoeImmunityBuffer} 免疫缓冲 (缓冲是大额 AOE 专用, 利刃每击小额)。但每击本身走 SKILL_AOE 类型,
 * 若目标恰在别的冠军大额 AOE (电磁/天雷/小男孩) 授予的 2s 免疫缓冲窗内, 本击会被缓冲 HIGHEST 闸掐 0 —— 这是红线 3
 * 反叠杀语义 (大额 AOE + 利刃不得叠加致死), 非缺陷。利刃是【冠军自身位移】(把冠军挪到目标旁), 不对玩家施位移/控制,
 * 故不涉 {@link PlayerLandingProtection} / 控制聚合。
 *
 * <p>状态机 (per-冠军, 三相): CHARGING (CD 冷却; 1s 扫描门控推进) -&gt; 就绪且缰绳内则起 TELEGRAPH (1.5s 预兆, tick 级
 * 推进) -&gt; 预兆毕转 STRIKING (N 段, 每 10t 一段, tick 级推进) -&gt; 全段毕/中止回 CHARGING (CD 从此刻起算)。两个时间
 * 尺度:
 * <ul>
 *   <li>{@link #onServerTick} 每 tick 先推进在册【活动连段】(TELEGRAPH/STRIKING 需 tick 级精度: 预兆逐帧、突袭精确
 *       10t 一段; CHARGING 条目在此跳过);</li>
 *   <li>再每 {@value #SCAN_INTERVAL_TICKS}tick(1s) 按玩家 AABB 扫近处冠军 (与 {@code ChampionBlinkHandler} 同范式,
 *       覆盖命令召唤 + 自然刷), CD 就绪 + 有目标 + 缰绳内者起手预兆。</li>
 * </ul>
 * 中止 (主线拍板): 突袭进行中目标死亡/离线 或 冠军距目标 &gt;12 格, 中止剩余突袭 (回 CHARGING 走 CD); 单段落点全拒则
 * 该段跳过 (不结算, 序列继续)。跳过亦消耗一个段位 (整套至多 N 段), 故实付恒 &le; 60% maxHP。
 *
 * <p>per-冠军状态双清 (与 {@code ChampionBlinkHandler} 同纪律): 冠军死亡摘除 + TTL 清扫兜底 —— 冠军未设
 * persistenceRequired, despawn/区块卸载不发 LivingDeathEvent, 只靠死亡清理会泄漏。CHARGING 态须留存以记 CD 锚点
 * (lastComboEndTick), 由 TTL 兜长期脱战冠军的内存。全部实例态 (无静态账本), 故无需 onServerStopping 清理 (由
 * {@code ChampionSystem#register} 挂 forgeBus)。
 */
public final class ChampionBladeWaltzHandler {

    /** 诊断日志: 批4 波2 利刃华尔兹真服首验/对账用 (预兆起/连段结束低频不门控; 每击/跳过经 shouldTrace 门控只追近玩家的怪)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/skill-blade-waltz");

    /** 扫描/CD 就绪判定周期 (tick): 1s 扫一次近玩家冠军 (与纯逻辑 {@link ChampionBladeWaltzPlan#SCAN_INTERVAL_TICKS} 对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = (int) ChampionBladeWaltzPlan.SCAN_INTERVAL_TICKS;

    /** 作用的玩家可见距离 (格; 与自身被动/BOSS 血条同量级)。远离该范围的冠军不结算 (无玩家在场无需连段)。 */
    private static final double VIEW_RANGE = 48.0D;

    /** 预兆期目标脚下粒子的节流粒度 (tick): 每 5 tick 喷一次 (30t 预兆窗喷 6 次, 控 spec 9A.6 粒子预算)。 */
    private static final int TELEGRAPH_PARTICLE_INTERVAL = 5;

    /** 每段瞬移两端 POOF 颗数 (spec 9A.6 粒子预算: 连段最多 7 段 × 2 端, 单端取小)。 */
    private static final int STRIKE_POOF_COUNT = 8;

    /** 预兆期目标脚下 CRIT 颗数 (单次喷)。 */
    private static final int TELEGRAPH_PARTICLE_COUNT = 6;

    /** per-冠军连段状态; 冠军死亡摘除 + TTL 清扫双保险 (despawn/卸载不发死亡事件的泄漏兜底)。 */
    private final Map<UUID, BladeWaltzState> stateByChampion = new HashMap<>();

    /** 状态 TTL 清扫周期 (tick): 每 60s 扫一次 (低频, 表通常极小)。 */
    private static final int STATE_SWEEP_INTERVAL_TICKS = 1200;

    /**
     * 状态条目 TTL (tick): 5min 未被触达 (未被扫描门控推进/未在活动连段) 即回收。丢态语义安全: 回收 CHARGING 态仅
     * 丢失 CD 锚点 = 视为"CD 已就绪", 而连段至多 5s、脱战冠军本就不起手 —— 5min 未触达的冠军必远离玩家或长期脱战,
     * 回收不改任何可观测行为, 只兜内存 (活动连段每 tick 触达, 绝不会在此被回收)。
     */
    private static final long STATE_TTL_TICKS = 6000L;

    /**
     * 每 tick 先推进在册活动连段 (tick 级), 再每 1s 门控扫描起手。活动连段需 tick 精度: 预兆逐帧喷粒子、突袭精确按
     * 10t 一段推进; 扫描 (CD 就绪起手) 是 1s 节流 (CD 30s 无需亚秒精度)。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        MinecraftServer server = event.getServer();
        long nowTick = server.overworld().getGameTime();

        // 每 tick: 推进在册活动连段 (CHARGING 条目在内部跳过, 廉价; 无 state 直接早退)。
        if (!stateByChampion.isEmpty()) {
            advanceActiveCombos(server, nowTick);
        }

        // 1s 扫描: CD 就绪 + 缰绳内起手预兆。
        if (server.getTickCount() % SCAN_INTERVAL_TICKS == 0) {
            scanNearbyChampions(server, nowTick);
            if (server.getTickCount() % STATE_SWEEP_INTERVAL_TICKS == 0) {
                sweepStaleStates(nowTick);
            }
        }
    }

    /**
     * 每 tick 推进在册活动连段: 遍历状态表, 仅处理 TELEGRAPH/STRIKING 条目 (按维度 O(1) 检出实体)。实体消失/死亡/非
     * Mob -&gt; 摘除 (连段作废); 否则按相位推进 (预兆/突袭)。活动条目每 tick 刷触达, 绝不被 TTL 回收。
     */
    private void advanceActiveCombos(MinecraftServer server, long nowTick) {
        Iterator<Map.Entry<UUID, BladeWaltzState>> it = stateByChampion.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, BladeWaltzState> entry = it.next();
            BladeWaltzState state = entry.getValue();
            if (state.phase == Phase.CHARGING) {
                continue; // 冷却态: 由 1s 扫描推进, 此处不动。
            }
            ServerLevel level = server.getLevel(state.dimension);
            if (level == null) {
                it.remove(); // 维度卸载: 连段作废。
                continue;
            }
            Entity found = level.getEntity(entry.getKey());
            if (!(found instanceof Mob mob) || !mob.isAlive()) {
                it.remove(); // despawn/卸载/死亡/非 Mob: 连段作废, 摘除。
                continue;
            }
            state.lastTouchedTick = nowTick; // 活动连段刷触达 (防中途被 TTL 回收)。
            if (state.phase == Phase.TELEGRAPH) {
                advanceTelegraph(mob, level, state, nowTick);
            } else {
                advanceStriking(mob, level, state, nowTick);
            }
        }
    }

    /**
     * 预兆推进 (tick 级): 目标死亡/离线则中止 (回 CHARGING 走 CD); 到点转 STRIKING (首段由下 tick 的突袭分支执行);
     * 未到点则按节流喷目标脚下粒子。预兆期【不】追踪落点 (落点每段临执行时按当前目标位置重选, 见 {@link #performStrike})。
     */
    private void advanceTelegraph(Mob mob, ServerLevel level, BladeWaltzState state, long nowTick) {
        ServerPlayer target = resolveServerPlayer(level, state.targetId);
        if (target == null || !target.isAlive()) {
            endCombo(mob, state, nowTick, "telegraph-target-lost"); // 预兆期目标丢失: 中止, 不突袭。
            return;
        }
        if (nowTick >= state.telegraphEndTick) {
            // 预兆毕: 转突袭相位; 首段 nextStrikeTick = 此刻, 下 tick 突袭分支即执行第 1 段。
            state.phase = Phase.STRIKING;
            state.strikeIndex = 0;
            state.nextStrikeTick = nowTick;
            return;
        }
        if (nowTick % TELEGRAPH_PARTICLE_INTERVAL == 0) {
            emitTelegraphParticles(level, target);
        }
    }

    /**
     * 突袭推进 (tick 级): 未到段时刻则等待; 到段则先判中止 (目标死亡/离线/距 &gt;12 格) 再执行本段瞬移突袭。执行毕
     * 递增段位, 满 N 段即结束连段 (回 CHARGING 走 CD), 否则排下段 (+10t)。
     */
    private void advanceStriking(Mob mob, ServerLevel level, BladeWaltzState state, long nowTick) {
        if (nowTick < state.nextStrikeTick) {
            return; // 段间等待。
        }
        ServerPlayer target = resolveServerPlayer(level, state.targetId);
        if (target == null || !target.isAlive()) {
            endCombo(mob, state, nowTick, "target-lost"); // 目标死亡/离线: 中止剩余突袭。
            return;
        }
        if (ChampionBladeWaltzPlan.shouldAbort(mob.distanceToSqr(target))) {
            endCombo(mob, state, nowTick, "target-fled"); // 冠军距目标 >12 格: 目标甩脱, 中止剩余突袭。
            return;
        }
        performStrike(mob, level, target, state, nowTick);
        state.strikeIndex++;
        if (state.strikeIndex >= state.plannedStrikes) {
            endCombo(mob, state, nowTick, "complete"); // 全段执行毕 (含被跳过的段): 连段结束。
            return;
        }
        state.nextStrikeTick = nowTick + ChampionBladeWaltzPlan.STRIKE_INTERVAL_TICKS;
    }

    /**
     * 执行一段突袭: 以当前目标位置为环心逐候选过守卫求首个安全落点 -&gt; 瞬移 (两端 poof) -&gt; 面向目标 + 挥砍摆臂 +
     * 挥砍音 -&gt; 目标吃一击 SKILL_AOE 伤。全候选被拒则本段跳过 (不瞬移不结算, 序列继续)。
     */
    private void performStrike(Mob mob, ServerLevel level, ServerPlayer target, BladeWaltzState state, long nowTick) {
        boolean trace = ChampionDiagnostics.shouldTrace(mob);
        List<ChampionBladeWaltzPlan.Landing> candidates =
                ChampionBladeWaltzPlan.strikeCandidates(target.getX(), target.getY(), target.getZ());
        for (ChampionBladeWaltzPlan.Landing cand : candidates) {
            BlockPos targetPos = BlockPos.containing(cand.x(), cand.y(), cand.z());
            KnockbackSafetyGuard.Decision decision = KnockbackSafetyGuard.evaluateLanding(level, targetPos);
            if (decision.outcome() != KnockbackSafetyGuard.Outcome.SAFE) {
                continue; // 该候选落点不安全 (岩浆/火/虚空边缘/被塞墙): 试下一候选。
            }
            BlockPos landing = decision.landing();
            double lx = landing.getX() + 0.5D;
            double ly = landing.getY();
            double lz = landing.getZ() + 0.5D;

            emitPoof(level, mob.getX(), mob.getY() + mob.getBbHeight() * 0.5D, mob.getZ()); // 起点消散。
            mob.teleportTo(lx, ly, lz);
            mob.getNavigation().stop(); // 瞬移后停旧路径, 由原生索敌重新贴脸。
            emitPoof(level, lx, ly + mob.getBbHeight() * 0.5D, lz); // 落点浮现。
            mob.lookAt(target, 180.0F, 180.0F);
            mob.swing(InteractionHand.MAIN_HAND); // 挥砍摆臂 (纯客户端可见动作)。
            level.playSound(null, lx, ly, lz, SoundEvents.PLAYER_ATTACK_SWEEP, SoundSource.HOSTILE, 0.9F, 1.0F);

            applyStrikeDamage(mob, level, target, state.quality);
            if (trace) {
                LOGGER.info("skill-blade-waltz strike champion={} target={} hit#{}/{} to=({},{},{})",
                        mob.getType().getDescriptionId(), target.getGameProfile().getName(),
                        state.strikeIndex + 1, state.plannedStrikes, fmt(lx), fmt(ly), fmt(lz));
            }
            return;
        }
        // 全拒: 本段无安全落点, 跳过 (不瞬移不结算, 序列继续; 本段仍消耗一个段位, 整套至多 N 段)。
        if (trace) {
            LOGGER.info("skill-blade-waltz strike champion={} target={} hit#{}/{} no-safe-landing skip",
                    mob.getType().getDescriptionId(), target.getGameProfile().getName(),
                    state.strikeIndex + 1, state.plannedStrikes);
        }
    }

    /**
     * 下发一击 SKILL_AOE 伤 (perStrikePct × 目标 maxHP)。DamageSource 构造照 {@code ChampionDeathMarkHandler#executePlayer}
     * 的 registry Holder 写法 (1.20.1 DamageSources.source 全 private, 公开路径 = registry 取 Holder 后 new
     * DamageSource(holder, entity))。CHAMPION_SKILL_AOE 吃玩家护甲、豁免近战 on-hit rider、可被免疫缓冲拦 (见类注)。
     */
    private void applyStrikeDamage(Mob mob, ServerLevel level, ServerPlayer target, AffixQuality quality) {
        Holder<DamageType> skillType = level.registryAccess()
                .registryOrThrow(Registries.DAMAGE_TYPE)
                .getHolderOrThrow(ChampionDamageTypes.CHAMPION_SKILL_AOE);
        float damage = (float) ChampionBladeWaltzPlan.perStrikeDamage(quality, target.getMaxHealth());
        target.hurt(new DamageSource(skillType, mob), damage);
    }

    /**
     * 每秒扫近玩家冠军 (与 {@code ChampionBlinkHandler} 同范式): 按玩家 AABB 扫 + 自研 capability 检出装配利刃华尔兹的
     * 冠军 (命令召唤 + 自然刷一视同仁), CD 就绪 + 有目标 + 缰绳内者起手预兆; 多玩家同看一冠军本轮只结算一次。
     */
    private void scanNearbyChampions(MinecraftServer server, long nowTick) {
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
                    applyBladeWaltzScan(level, entity, nowTick);
                }
            }
        }
    }

    /** 对一只实体 (若是装配利刃华尔兹的本工程冠军且处 CHARGING) CD 就绪 + 缰绳内则起手预兆。 */
    private void applyBladeWaltzScan(ServerLevel level, LivingEntity entity, long nowTick) {
        MiningChampionData champ = MiningChampions.get(entity).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 非本工程冠军。
        }
        AffixQuality quality = champ.quality(AffixDef.BLADE_WALTZ);
        if (quality == null) {
            return; // 未装配利刃华尔兹: 不建状态 (防为无关冠军泄漏 state)。
        }
        BladeWaltzState existing = stateByChampion.get(entity.getUUID());
        if (existing != null && existing.phase != Phase.CHARGING) {
            return; // 活动连段 (预兆/突袭): 由每 tick 推进独占, 扫描不并发起手。
        }
        ServerPlayer target = resolveTarget(entity);
        if (target == null) {
            return; // 无存活玩家攻击目标: 不起手 (不建/不触达 state)。
        }
        if (!(entity instanceof Mob mob)) {
            return; // 冠军 capability 只挂 Mob; 防御性早退。
        }
        if (!ChampionBladeWaltzPlan.withinTether(entity.distanceToSqr(target))) {
            return; // 超缰绳: 不起手 (CD 保持就绪, 待目标进缰绳)。
        }
        BladeWaltzState state = stateByChampion.computeIfAbsent(entity.getUUID(),
                k -> new BladeWaltzState(level.dimension()));
        state.lastTouchedTick = nowTick;
        if (!ChampionBladeWaltzPlan.cooldownReady(nowTick, state.lastComboEndTick)) {
            return; // CD 冷却中。
        }
        beginTelegraph(mob, level, target, quality, state, nowTick);
    }

    /**
     * 起手预兆: 记锚点 + 快照突袭次数/品质 + 表现层 (自身 Glowing 全程 + 目标 actionbar 警告 + 磨刀音 + 首帧目标脚下
     * 粒子)。Glowing 覆盖预兆 + 突袭全程 (让玩家全程能肉眼锁定这只危险冠军)。
     */
    private void beginTelegraph(Mob mob, ServerLevel level, ServerPlayer target, AffixQuality quality,
                                BladeWaltzState state, long nowTick) {
        int plannedStrikes = ChampionBladeWaltzPlan.strikeCount(quality);
        state.phase = Phase.TELEGRAPH;
        state.targetId = target.getUUID();
        state.quality = quality;
        state.plannedStrikes = plannedStrikes;
        state.strikeIndex = 0;
        state.telegraphEndTick = nowTick + ChampionBladeWaltzPlan.TELEGRAPH_TICKS;
        state.nextStrikeTick = Long.MIN_VALUE;

        // Glowing 时长 = 预兆 + 全部段位间隔 (预兆毕到末段约 plannedStrikes × 10t), 让高亮贯穿危险窗。
        int glowTicks = (int) (ChampionBladeWaltzPlan.TELEGRAPH_TICKS
                + (long) plannedStrikes * ChampionBladeWaltzPlan.STRIKE_INTERVAL_TICKS);
        mob.addEffect(new MobEffectInstance(MobEffects.GLOWING, glowTicks));
        target.displayClientMessage(Component.literal("利刃华尔兹锁定!"), true);
        level.playSound(null, mob.getX(), mob.getY(), mob.getZ(),
                SoundEvents.GRINDSTONE_USE, SoundSource.HOSTILE, 0.9F, 0.8F);
        emitTelegraphParticles(level, target); // 预兆首帧 (后续每 5t 由 advanceTelegraph 续喷)。

        // 起手 = 技能施放 (低频, 30s CD): 不经诊断门控, 真服对账必打。
        LOGGER.info("skill-blade-waltz telegraph champion={} target={} strikes={} perStrikePct={} in={}t",
                mob.getType().getDescriptionId(), target.getGameProfile().getName(), plannedStrikes,
                String.format("%.4f", ChampionBladeWaltzPlan.perStrikePct(quality)),
                ChampionBladeWaltzPlan.TELEGRAPH_TICKS);
    }

    /**
     * 结束连段 (全段毕/中止): 回 CHARGING 相位 + CD 从此刻起算 (lastComboEndTick=now) + 清连段字段。状态条目留存
     * (CHARGING 记 CD 锚点, 由 TTL 兜回收)。
     */
    private void endCombo(Mob mob, BladeWaltzState state, long nowTick, String reason) {
        state.phase = Phase.CHARGING;
        state.lastComboEndTick = nowTick;
        state.targetId = null;
        state.quality = null;
        state.plannedStrikes = 0;
        state.strikeIndex = 0;
        state.telegraphEndTick = Long.MIN_VALUE;
        state.nextStrikeTick = Long.MIN_VALUE;
        state.lastTouchedTick = nowTick;
        // 连段结束 (低频, 每套至多一次): 不经诊断门控, 真服对账必打。
        LOGGER.info("skill-blade-waltz combo-end champion={} reason={}",
                mob.getType().getDescriptionId(), reason);
    }

    /** 冠军死亡: 摘 per-冠军连段状态防泄漏。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        stateByChampion.remove(event.getEntity().getUUID());
    }

    /** 回收 TTL 内未被触达 (未被扫描门控推进/未在活动连段) 的状态条目 (语义安全性见 {@link #STATE_TTL_TICKS})。 */
    private void sweepStaleStates(long nowTick) {
        // MIN_VALUE (理论不出现: 建条目点即刻刷触达) 显式视为过期, 防减法溢出漏回收。
        stateByChampion.values().removeIf(state -> state.lastTouchedTick == Long.MIN_VALUE
                || nowTick - state.lastTouchedTick > STATE_TTL_TICKS);
    }

    /** 预兆期目标脚下 CRIT 粒子 (spec 9A.6 原版可见原语): 让玩家肉眼预判"这只锁定了我"。 */
    private static void emitTelegraphParticles(ServerLevel level, ServerPlayer target) {
        level.sendParticles(ParticleTypes.CRIT,
                target.getX(), target.getY() + 0.1D, target.getZ(),
                TELEGRAPH_PARTICLE_COUNT, 0.3D, 0.1D, 0.3D, 0.05D);
    }

    /** 单端瞬移 poof (spec 9A.6): 起点消散 / 落点浮现, 纯原版客户端可见。 */
    private static void emitPoof(ServerLevel level, double x, double y, double z) {
        level.sendParticles(ParticleTypes.POOF, x, y, z, STRIKE_POOF_COUNT, 0.3D, 0.4D, 0.3D, 0.02D);
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

    /** 按 UUID 在给定层解析玩家 (跨维度/离线返 null; 存活性由调用方判)。 */
    private static ServerPlayer resolveServerPlayer(ServerLevel level, UUID playerId) {
        if (playerId != null && level.getPlayerByUUID(playerId) instanceof ServerPlayer player) {
            return player;
        }
        return null;
    }

    private static String fmt(double v) {
        return String.format("%.1f", v);
    }

    /** 利刃华尔兹连段相位: 充能 (CD 冷却, 1s 扫描门控推进) / 预兆 (1.5s 锁定) / 突袭 (N 段瞬移打击)。 */
    private enum Phase {
        CHARGING,
        TELEGRAPH,
        STRIKING
    }

    /**
     * per-冠军连段状态: 相位 + 所在维度 (活动连段每 tick O(1) 检出实体) + 锁定目标 UUID + 品质快照 (段伤源) + 计划段数
     * 快照 + 已执行段位 + 预兆到点 tick + 下段执行 tick + 上次连段结束 tick (CD 锚点, MIN_VALUE=从未施放) + 最后触达
     * tick (TTL 清扫依据)。CHARGING 态仅 dimension/lastComboEndTick/lastTouchedTick 有意义, 余字段复位。
     */
    private static final class BladeWaltzState {
        private final ResourceKey<Level> dimension;
        private Phase phase = Phase.CHARGING;
        private UUID targetId = null;
        private AffixQuality quality = null;
        private int plannedStrikes = 0;
        private int strikeIndex = 0;
        private long telegraphEndTick = Long.MIN_VALUE;
        private long nextStrikeTick = Long.MIN_VALUE;
        private long lastComboEndTick = Long.MIN_VALUE;
        private long lastTouchedTick = Long.MIN_VALUE;

        private BladeWaltzState(ResourceKey<Level> dimension) {
            this.dimension = dimension;
        }
    }
}
