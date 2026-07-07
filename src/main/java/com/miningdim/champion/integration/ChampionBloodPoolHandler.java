package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionDamageReduction;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionRedlines;
import com.miningdim.champion.CompositeArmorRampTracker;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.reward.ContributionTracker;
import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 6★+ 冠军自定义血池受击接线 (Champions 集成层; ChampionStarAffix spec 6.2 血池 + 红线 1 净减伤单点 + 9A.2 铁律)。
 *
 * 单点净减伤铁律 (9A.2): 严禁逐词条串行减伤 (多源相乘穿透 75% 帽)。所有比例类减伤源
 * (超高分子/重型子弹抗 + 复合同源适应 ramp + 偏斜 EV + 缩小化体型折算) 在本 {@link LivingHurtEvent} 单点收集 rates 后
 * 经 {@link ChampionRedlines#clampNetKeepFactor} 一次性连乘钳制 (keep = max(∏(1-rᵢ), 0.25)); FLAT 类减伤
 * (刚毅单次封顶 + 重型护甲近战/爆炸 <T 整次免疫) 是与入伤量耦合的非比例硬上限, 在连乘净伤后经
 * {@link ChampionDamageReduction#applyFlatCaps} 再削顶 (单向变硬, 不与 49% 净减伤底冲突)。各源数值/分类折算
 * 全转交纯逻辑 {@link ChampionDamageReduction} (子弹/近战分类 + 5 档数值表), 本 handler 只读自研冠军 capability
 * ({@link MiningChampionData}) 词条池装配计划 + 拦死 + 渲染镜像。复合装甲 ramp 跨受击状态由 {@link #compositeRamps} per-冠军维护。
 *
 * 事件优先级 (EventPriority.LOWEST): 易伤放大由全局 {@code VulnerabilityHurtHandler} 在默认优先级先乘
 * (撕裂词条经易伤系统放大对冠军的伤害), 本血池减伤聚合须在其后读已放大的 {@code event.getAmount()} 再做净减伤,
 * 故挂 LOWEST 保证语义次序 (放大 -> 净减伤 -> 扣影子血)。
 *
 * 血池权威 (6.2 #2): 6★+ 冠军 vanilla getHealth/max_health 仅渲染镜像, 一切判定读影子血池。本 handler 把
 * event 的伤害从影子血池扣 (经净减伤), 并把 vanilla 这次伤害取消 (setCanceled) —— vanilla 不再扣自己那点
 * ≤1024 的血, 避免双重扣血/原版提前判死。拦死 (6.2 #3): 影子血池 wouldDieFrom 致死则主动 entity.kill();
 * 否则取消 vanilla 伤害, 影子血扣完由本 handler 权威推进。渲染镜像 (6.2 #2): 受击分支即时刷 + ServerTickEvent
 * 每 tick 对在册血池实体统一按 {@link BloodPool#displayHealth()} 刷 (含回血同步与绕过本 handler 的伤害路径
 * 兜底), 保血条不滞后/不诈活。
 *
 * 冠军数据源: 受击时经 {@link MiningChampions#get} 读自研 {@link MiningChampionData} 词条池 (星级 + 词条→品质
 * 直存), 不再触任何 top.theillusivec4.champions.* (数值/分类折算的纯逻辑下沉到 {@link ChampionDamageReduction} /
 * {@link CompositeArmorRampTracker} 真测)。血池在册与否仍经 {@link BloodPoolRegistry} (其成员由 ChampionPromoter
 * 在 spawn 期建池写入)。
 */
public final class ChampionBloodPoolHandler {

    /** 诊断日志: 减伤链真服首验用 (每次冠军受击打一行 入伤/各源减伤率/keep/净伤, 定位"复合装甲不生效"类反馈)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/reduction");

    /**
     * 复合装甲 per-冠军 ramp 受击计数器 (UUID -> tracker)。复合装甲 ramp 须跨多次受击维护 (每受击 +上限/5,
     * 3s 无伤重置), 故每只持复合装甲的冠军一个 {@link CompositeArmorRampTracker}; 冠军死亡 ({@link #onLivingDeath})
     * 摘除防泄漏。仅本血池 handler (6★+ 冠军) 维护, 与 ServerTickEvent 镜像同生命周期 (服务端单线程, 无需并发表)。
     */
    private final Map<UUID, CompositeArmorRampTracker> compositeRamps = new HashMap<>();

    /**
     * 6★+ 冠军受击: 经净减伤后从影子血池扣血, 拦死, 取消 vanilla 本次伤害 (影子血池为权威)。
     * 非血池冠军 (1-5★ 或普通怪) 不在册, 直接放行走 vanilla。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onLivingHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        float incoming = event.getAmount();
        if (incoming <= 0.0F) {
            return;
        }

        // 净减伤单点连乘钳制 (红线 1 / 9.2): 读冠军词条池, 比例类减伤源 (超高分子/重型子弹抗 + 复合同源适应 ramp +
        // 偏斜 EV + 缩小化体型折算) 收进 rates 一次性连乘 keep = max(∏(1-rᵢ),0.25) 钳死 75%; FLAT 类 (刚毅封顶 +
        // 重型 <T 免疫) 在连乘净伤后再削顶 (单向变硬, 不与 75% 底冲突)。各源数值/分类折算见 ChampionDamageReduction。
        // 减伤词条对【所有星级】冠军生效 (非 6★+ 血池专属): buildReductionPlan 对非本工程冠军返 isChampion=false 早退。
        ReductionPlan plan = buildReductionPlan(victim, event.getSource());
        if (!plan.isChampion) {
            return; // 非本工程冠军 (绝大多数受击): vanilla 自理, 不碰。
        }
        double[] rates = plan.rates.stream().mapToDouble(Double::doubleValue).toArray();
        double keep = ChampionRedlines.clampNetKeepFactor(rates);
        double netDamage = incoming * keep;
        netDamage = ChampionDamageReduction.applyFlatCaps(
                netDamage, plan.fortitudeCap, plan.heavyThreshold, plan.meleeOrExplosion);

        // 诊断 (真服首验, 仅 10 格内有玩家的怪): 每次冠军受击打一行, 直观看伤害类别/各减伤源折算率(含复合装甲
        // 当前同源适应层折率)/连乘keep/净伤 (定位"减伤不生效"+验换类别重置)。
        if (ChampionDiagnostics.shouldTrace(victim)) {
            LOGGER.info("champion-hit {} cat={} incoming={} rates={} keep={} net={} flat[fort={},heavyT={}]",
                    victim.getType().getDescriptionId(), plan.category, String.format("%.2f", incoming), plan.rates,
                    String.format("%.3f", keep), String.format("%.2f", netDamage),
                    plan.fortitudeCap, plan.heavyThreshold);
        }

        BloodPool pool = BloodPoolRegistry.get(victim.getUUID());
        if (pool == null) {
            // 1-5★ 冠军无影子血池: vanilla getHealth 为权威, 净减伤写回 event (不取消, vanilla 按钳后伤害扣血)。
            // 减伤词条对低星冠军同样生效 —— 红线 1 单点连乘已钳 ≤49%, FLAT 削顶亦已应用。
            event.setAmount((float) netDamage);
            return;
        }

        if (pool.wouldDieFrom(netDamage)) {
            // 影子血池致死 (6.2 #3 拦死单一判据): 扣到 0, 取消本次 vanilla 伤害, 先摘池再 kill。
            pool.applyDamage(netDamage);
            event.setCanceled(true);

            // 先落账再致死 (缺陷1 修复): kill() 内部走 hurt(OUT_OF_WORLD, MAX_VALUE) 会同步重入 LivingHurtEvent
            // (归因 OUT_OF_WORLD 不记) 紧接 LivingDeathEvent -> RewardHandler.onChampionDeath 在嵌套里 drain 清账。
            // 若不在 kill 前补记, 这笔致命单击 (常是最高伤) 要等嵌套返回后才被 RewardHandler.onChampionHurt 记入,
            // 此时账本已 drain -> 既漏算瓜分权重又泄漏到 ServerStopping。故在 kill 前用与 RewardHandler 同一归因
            // (resolvePlayerAttacker 复用, 不另造) 把这笔致命单击按"有效伤害"口径 (event.getAmount(), 与受击累计
            // 一致用名义入伤而非净伤) 落账, 使嵌套 drain 时最后一击已在账内。归因为 null (环境/召唤物致死) 时不记。
            ServerPlayer killer = ChampionRewardHandler.resolvePlayerAttacker(event);
            if (killer != null) {
                long nowTick = victim.level().getGameTime();
                ContributionTracker.record(victim.getUUID(), killer.getUUID(), incoming, nowTick);
            }

            // 摘池关键 (防 kill 重入): 若池仍在册则重入受击 wouldDieFrom 再判致死 -> 再取消 -> 再 kill 形成递归。
            // 先 remove 让重入的受击找不到池而放行到 vanilla, 由 vanilla 的 MAX_VALUE 伤害走真死路径。
            BloodPoolRegistry.remove(victim.getUUID());
            victim.setHealth(0.0F);
            victim.kill();
            return;
        }

        // 未致死: 影子血池扣净伤, 取消 vanilla 本次伤害 (影子血权威, vanilla 不重复扣其 ≤1024 血)。
        pool.applyDamage(netDamage);
        event.setCanceled(true);
        // 渲染镜像即时同步 (6.2 #2): vanilla 血条按影子血占比映射到 [0,1024], 保血条不因取消伤害而卡满。
        mirrorToVanilla(victim, pool);
    }

    /**
     * 读冠军词条池, 把 6 个减伤词条按品质折算成净减伤计划 (9.2 单点收集): 比例源进 rates (连乘), FLAT 源 (刚毅封顶/
     * 重型 <T 免疫) 出参另带。本方法是薄壳 —— 取 {@link MiningChampions#get} 的 {@link MiningChampionData}
     * (星级 + 词条→品质直存), 数值/分类折算全转交纯逻辑 {@link ChampionDamageReduction} (GameTest 测纯函数)。
     *
     * 非本工程冠军 (含普通怪) 返回 isChampion=false 的空计划, onLivingHurt 据此早退不碰 event。词条品质直接取自
     * {@link MiningChampionData#affixes} 的 value (不再经 rank/NBT 兜底折算)。
     */
    private ReductionPlan buildReductionPlan(LivingEntity victim, DamageSource source) {
        ReductionPlan plan = new ReductionPlan();

        MiningChampionData champ = MiningChampions.get(victim).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return plan; // 受击者非本工程冠军: 空计划 (onLivingHurt 据 isChampion=false 早退不碰 event)。
        }
        plan.isChampion = true; // 本工程冠军 (onLivingHurt 对其施减伤; 即便无减伤词条也 keep=1.0 无副作用)。

        boolean bullet = isBulletDamage(source);
        boolean meleeOrExplosion = isMeleeOrExplosionDamage(source);
        ChampionDamageReduction.DamageCategory category = categorize(source, bullet);

        for (Map.Entry<AffixDef, AffixQuality> entry : champ.affixes().entrySet()) {
            collectAffixReduction(entry.getKey(), entry.getValue(), bullet, meleeOrExplosion, category, victim, plan);
        }
        plan.meleeOrExplosion = meleeOrExplosion;
        plan.category = category;
        return plan;
    }

    /**
     * 单条减伤词条折算 (按词条分派): 比例源 add 进 rates; FLAT 源写 plan.fortitudeCap/heavyThreshold。子弹专属源
     * (超高分子/重型子弹抗/偏斜 EV) 仅子弹伤害纳入; 复合 ramp/缩小化对全伤害类型生效。
     */
    private void collectAffixReduction(AffixDef def, AffixQuality quality,
                                       boolean bullet, boolean meleeOrExplosion,
                                       ChampionDamageReduction.DamageCategory category, LivingEntity victim,
                                       ReductionPlan plan) {
        switch (def) {
            case COMPOSITE_ARMOR: {
                // 同源适应: per-冠军按伤害类别分桶爬升 (换类别双向清零 + 3s 无伤全重置), 当前类别层数折率进 rates。
                CompositeArmorRampTracker tracker =
                        compositeRamps.computeIfAbsent(victim.getUUID(), id -> new CompositeArmorRampTracker());
                int hits = tracker.onHit(category, victim.level().getGameTime());
                addRate(plan, ChampionDamageReduction.compositeRampRate(quality, hits));
                break;
            }
            case UHMWPE_ARMOR:
                if (bullet) {
                    addRate(plan, ChampionDamageReduction.uhmwpeBulletRate(quality));
                }
                break;
            case HEAVY_ARMOR:
                if (bullet) {
                    addRate(plan, ChampionDamageReduction.heavyArmorBulletRate(quality));
                }
                // 近战/爆炸 <T 免疫阈值 (FLAT, 后置削顶); 取 max 防多重型词条 (实际不会, 防御性)。
                if (meleeOrExplosion) {
                    plan.heavyThreshold = Math.max(plan.heavyThreshold,
                            ChampionDamageReduction.heavyArmorImmunityThreshold(quality));
                }
                break;
            case DEFLECTOR_SHIELD:
                if (bullet) {
                    addRate(plan, ChampionDamageReduction.deflectorBulletEvRate(quality));
                }
                break;
            case FORTITUDE_SHIELD:
                // 单次封顶取最硬 (越低越硬); 多刚毅词条不会出现, min 取最严防御性。
                plan.fortitudeCap = (plan.fortitudeCap <= 0.0D)
                        ? ChampionDamageReduction.fortitudeSingleHitCap(quality)
                        : Math.min(plan.fortitudeCap, ChampionDamageReduction.fortitudeSingleHitCap(quality));
                break;
            case MINIATURIZATION:
                addRate(plan, ChampionDamageReduction.miniaturizationReductionRate(quality));
                break;
            default:
                break; // 非减伤词条 (攻击/机动/技能): 不在本 handler 职责内。
        }
    }

    /** 把非 0 减伤率收进 rates (0 率不入连乘, 省 clampNetKeepFactor 无谓乘 1)。 */
    private static void addRate(ReductionPlan plan, double rate) {
        if (rate > 0.0D) {
            plan.rates.add(rate);
        }
    }

    /**
     * 是否 TACZ 子弹伤害: 取 DamageSource 伤害类型的 ResourceKey location (namespace/path), 交纯逻辑
     * {@link ChampionDamageReduction#isBulletDamage} 判 (tacz:bullet*)。typeHolder 未绑 ResourceKey (理论不该)
     * 时归非子弹 (保守: 不误享子弹抗减伤)。
     */
    private static boolean isBulletDamage(DamageSource source) {
        Optional<ResourceKey<DamageType>> key = source.typeHolder().unwrapKey();
        if (key.isEmpty()) {
            return false;
        }
        ResourceLocation id = key.get().location();
        return ChampionDamageReduction.isBulletDamage(id.getNamespace(), id.getPath());
    }

    /**
     * 是否近战或爆炸伤害 (重型 <T 免疫仅对此生效, spec 7.1 B 版): 爆炸读 vanilla IS_EXPLOSION 标签 (含 TACZ 爆炸
     * 弹归类); 近战 = 怪/玩家近战攻击 (MOB_ATTACK/PLAYER_ATTACK), 不含弹射物/子弹 (避免远程误享整次免疫)。
     */
    private static boolean isMeleeOrExplosionDamage(DamageSource source) {
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return true;
        }
        return source.is(DamageTypes.MOB_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.PLAYER_ATTACK);
    }

    /**
     * 伤害类别折算 (复合装甲同源适应分桶维度): 子弹 (tacz:bullet*) / 爆炸 (IS_EXPLOSION, 先于近战判防爆炸型近战误归) /
     * 近战 (MOB/PLAYER_ATTACK) / 其余 OTHER。bullet 已由调用方算好传入 (免二次解析 type id)。
     */
    private static ChampionDamageReduction.DamageCategory categorize(DamageSource source, boolean bullet) {
        if (bullet) {
            return ChampionDamageReduction.DamageCategory.BULLET;
        }
        if (source.is(DamageTypeTags.IS_EXPLOSION)) {
            return ChampionDamageReduction.DamageCategory.EXPLOSION;
        }
        if (source.is(DamageTypes.MOB_ATTACK)
                || source.is(DamageTypes.MOB_ATTACK_NO_AGGRO)
                || source.is(DamageTypes.PLAYER_ATTACK)) {
            return ChampionDamageReduction.DamageCategory.MELEE;
        }
        return ChampionDamageReduction.DamageCategory.OTHER;
    }

    /**
     * 一次受击的净减伤计划: 比例源 rates (连乘进 clampNetKeepFactor) + FLAT 削顶量 (刚毅单次封顶 / 重型 <T 免疫
     * 阈值) + 本次是否近战/爆炸 (重型免疫仅对此生效)。fortitudeCap/heavyThreshold &lt;=0 表示无该词条 (不削)。
     */
    private static final class ReductionPlan {
        /** 受击者是否本工程冠军 (有有效 rank): false 表示非冠军, onLivingHurt 据此早退不碰 event。 */
        boolean isChampion = false;
        final List<Double> rates = new ArrayList<>();
        double fortitudeCap = 0.0D;
        double heavyThreshold = 0.0D;
        boolean meleeOrExplosion = false;
        /** 本次伤害类别 (复合装甲同源适应分桶 + 诊断日志)。 */
        ChampionDamageReduction.DamageCategory category = ChampionDamageReduction.DamageCategory.OTHER;
    }

    /**
     * 冠军死亡: 回收影子血池 (无论真死路径如何, 死亡即清池防泄漏)。奖励结算由
     * {@link ChampionRewardHandler#onChampionDeath} 在另一 handler 处理 (职责分离: 本 handler 只管血池生命周期)。
     */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        UUID id = event.getEntity().getUUID();
        BloodPoolRegistry.remove(id);
        compositeRamps.remove(id); // 复合装甲 ramp 状态随死亡摘除防泄漏 (与血池同生命周期)。
    }

    /**
     * 把影子血池映射回 vanilla getHealth 供原版血条渲染 (缺陷2 修复 a): 单一来源 = {@link BloodPool#displayHealth()}
     * (已 clamp 到 [0,1024], 与影子血池占比一致), 不再用 fraction()×vanillaMax —— 后者在某伤害路径绕过本 handler
     * 时会把破 1024 的有效血压回 vanilla 顶高血条致"诈活/低血诈活"。displayHealth>0 (池未死) 才写 vanilla 血,
     * 池已死 (displayHealth==0) 由致死分支 kill() 路径处理, 此处不强写 0 避免与 kill 打架。
     */
    private static void mirrorToVanilla(LivingEntity victim, BloodPool pool) {
        float mirrored = pool.displayHealth();
        if (mirrored > 0.0F) {
            victim.setHealth(mirrored);
        }
    }

    /**
     * 每 tick 对在册血池实体统一刷 vanilla 血条 (缺陷2 修复 b): 受击瞬刷只覆盖"被本 handler 处理的受击", 而回血
     * tick ({@link BloodPool#heal}) 与"绕过本 handler 的伤害路径"(直接 setHealth/秒杀效果/更高优先级 mod 先
     * setCanceled 截走) 都不会触发受击镜像, 导致血条滞后或顶高 (诈活)。故移到 ServerTickEvent END 每 tick 对
     * {@link BloodPoolRegistry} 在册实体 (通常极少) 统一按 displayHealth 刷一遍, 含回血同步。受击分支保留即时刷
     * (低血阈值等当 tick 即时反馈)。
     *
     * 性能: 只遍在册血池快照 (6★+ 冠军, 数量极少), 非全世界实体扫描。自研后本 handler 由 {@code ChampionSystem#register}
     * 无条件挂 forgeBus (不再依赖 Champions), 血池在册与否经我方 {@link BloodPoolRegistry} (promoter 建池写入)。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        Map<UUID, BloodPool> pools = BloodPoolRegistry.snapshot();
        if (pools.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel mining = server.getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            return; // 维度未加载 (启动早期/配置异常): 本 tick 跳过镜像, 不刷屏。
        }
        for (Map.Entry<UUID, BloodPool> entry : pools.entrySet()) {
            Entity entity = mining.getEntity(entry.getKey());
            if (entity instanceof LivingEntity living && living.isAlive()) {
                mirrorToVanilla(living, entry.getValue());
            }
        }
    }
}
