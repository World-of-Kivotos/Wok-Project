package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionDamageReduction;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionRedlines;
import com.miningdim.champion.CompositeArmorRampTracker;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.StarRank;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
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
 * ≤1024 的血, 避免双重扣血/原版提前判死。拦死 (6.2 #3): 影子血池 wouldDieFrom 致死则扣池到 0 + setHealth(0) +
 * 摘池放行, 真死流程由外层 vanilla hurt() 尾部的 die() 驱动 (setHealth(0) 已使 isDeadOrDying 为真, 不需要也不
 * 应再主动调 kill() —— F101: 详见 onLivingHurt 内联注释); 否则取消 vanilla 伤害, 影子血扣完由本 handler 权威
 * 推进。渲染镜像 (6.2 #2): 受击分支即时刷 + ServerTickEvent 每 tick 对在册血池实体统一按
 * {@link BloodPool#displayHealth()} 刷 (含回血同步与绕过本 handler 的伤害路径兜底), 保血条不滞后/不诈活。
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

        // 管理员击杀/虚空 (bypasses_invulnerability 标签: /kill 的 generic_kill 与 out_of_world) 不进减伤/血池
        // 管线: 刚毅单次封顶会把 /kill 的 Float.MAX_VALUE 削成 <=120/次 (9527 血带刚毅的 8★ 要 /kill 上百次,
        // 真服验收反馈), 而该类伤害语义上无视一切 -> 直接放行 vanilla 全额扣血致死, 血池由 onLivingDeath 随死亡回收。
        if (event.getSource().is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
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
            // 影子血池致死 (6.2 #3 拦死单一判据): 扣到 0, 取消本次 vanilla 伤害, 摘池放行。
            pool.applyDamage(netDamage);
            flushCurrentHp(victim, pool);
            event.setCanceled(true);

            // 不预记贡献 (F101 修复): LivingEntity.hurt() 的 setHealth(0) 之后紧跟
            // `else if (this.isDeadOrDying()) return false;` (LivingEntity.java:1064) —— 本次 setHealth(0) 已使
            // isDeadOrDying 为真, 若本 handler 再调 kill() (即 hurt(genericKill, MAX_VALUE)) 必在这条分支恒 no-op,
            // 不会重入 LivingHurtEvent。真正致死是外层 vanilla hurt 尾部的
            // `if (this.isDeadOrDying()) ... this.die(source);` (LivingEntity.java:1175-1182), 它在 actuallyHurt
            // 返回、即本次 LivingHurtEvent 全部监听器 (含 LOWEST 的本 handler) 跑完【之后】才执行。故
            // ChampionRewardHandler.onChampionHurt (同为 LOWEST, receiveCanceled=true, 注册序在本 handler 之后
            // 故 FIFO 后跑) 必然先按常规入伤口径记完这笔致命击, 才轮到 die() 触发 onChampionDeath 去 drain ——
            // 常规记账已独占这笔账, 这里再记一次就是把致命击算两遍。

            // 摘池 (放行 vanilla 真死路径): 摘除后本次 event 循环内不会再有本 handler 的重入 (受击已在处理中),
            // 摘池是为了让任何后续/嵌套受击 (若外部 mod 在更高优先级另触发一次) 找不到池而放行走 vanilla。
            BloodPoolRegistry.remove(victim.getUUID());
            victim.setHealth(0.0F);
            return;
        }

        // 未致死: 影子血池扣净伤, 取消 vanilla 本次伤害 (影子血权威, vanilla 不重复扣其 ≤1024 血)。
        pool.applyDamage(netDamage);
        flushCurrentHp(victim, pool);
        event.setCanceled(true);
        // 渲染镜像即时同步 (6.2 #2): vanilla 血条按影子血占比映射到 [0,1024], 保血条不因取消伤害而卡满。
        mirrorToVanilla(victim, pool);
    }

    /**
     * 把影子血池当前血落账进冠军 capability (F040: 供服务端重启/区块重载后 {@link #onEntityJoinLevel} 按此重建血池)。
     * 受击是唯一与维度无关且逐次精确的落账点 —— 每 tick 镜像 ({@link #onServerTick}) 只覆盖矿洞维度在册实体,
     * 命令传送到其它维度的冠军受击后仍能经此落账, 不依赖 tick 镜像覆盖范围。取不到 capability (非 Mob/未挂载)
     * 静默跳过, 不影响血池本身权威。
     */
    private static void flushCurrentHp(LivingEntity victim, BloodPool pool) {
        MiningChampions.get(victim).ifPresent(data -> data.setCurrentHp(pool.currentHp()));
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
     * 把影子血池映射回 vanilla getHealth 供原版血条渲染 (缺陷2 修复 a): 单一来源 = {@link BloodPool#displayHealth(double)}
     * 按实体【实际属性上限】等比例映射 —— promoter 已把血池怪 vanilla 血量属性设到有效血真值, 测试服 AttributeFix
     * (max_health 上限 1e6) 环境下 getMaxHealth() = 池 maxHp, Jade 悬浮血条直显真血; 无 AttributeFix 属性自钳 1024,
     * 镜像自动退回保守比例 (与旧行为一致, 不诈活)。displayHealth>0 (池未死) 才写 vanilla 血, 池已死 (==0) 由致死
     * 分支 (onLivingHurt) 的 setHealth(0) 处理, 此处不强写 0 避免与致死分支打架。
     */
    private static void mirrorToVanilla(LivingEntity victim, BloodPool pool) {
        float mirrored = pool.displayHealth(victim.getMaxHealth());
        if (mirrored > 0.0F) {
            victim.setHealth(mirrored);
        }
    }

    /**
     * 每 tick 对在册血池实体统一刷 vanilla 血条 (缺陷2 修复 b): 受击瞬刷只覆盖"被本 handler 处理的受击", 而回血
     * tick ({@link BloodPool#heal}) 与"绕过本 handler 的伤害路径"(直接 setHealth/秒杀效果/更高优先级 mod 先
     * setCanceled 截走) 都不会触发受击镜像, 导致血条滞后或顶高 (诈活)。故移到 ServerTickEvent END 每 tick 对
     * {@link BloodPoolRegistry} 在册实体 (通常极少) 统一按 displayHealth 刷一遍, 含回血同步。受击分支保留即时刷
     * (低血阈值等当 tick 即时反馈); 本 tick 循环末尾同样 flushCurrentHp 一次, 兜住只走回血路径 (
     * {@code ChampionSelfEffectHandler}/{@code ChampionSelfRepairHandler} 调 {@link BloodPool#heal}) 而不经受击
     * 落账口径的漂移 (F040)。
     *
     * 遍历只在 {@link MiningConstants#MINING_LEVEL} 维度查实体是既有行为, 命令召唤到其它维度的冠军拿不到 tick
     * 镜像是另一个已知问题, 本次不动。
     *
     * F039 修复: 原实现每 tick 无条件 {@code snapshot()} (LinkedHashMap 全表复制) 再判空, 空表也照样分配一份
     * 拷贝纯属浪费; 服务端主线程串行, 本循环遍历期间不会有 install/remove 插队, 故改走 {@link BloodPoolRegistry#live()}
     * 零拷贝视图, 先 {@link BloodPoolRegistry#isEmpty()} 早退。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (BloodPoolRegistry.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        ServerLevel mining = server.getLevel(MiningConstants.MINING_LEVEL);
        if (mining == null) {
            return; // 维度未加载 (启动早期/配置异常): 本 tick 跳过镜像, 不刷屏。
        }
        for (Map.Entry<UUID, BloodPool> entry : BloodPoolRegistry.live().entrySet()) {
            Entity entity = mining.getEntity(entry.getKey());
            if (entity instanceof LivingEntity living && living.isAlive()) {
                mirrorToVanilla(living, entry.getValue());
                flushCurrentHp(living, entry.getValue());
            }
        }
    }

    /**
     * 冠军实体离开世界 (F039): 本仓已有 11 个同类 per-冠军状态表 (惩罚/技能冷却等) 各自都在此类事件里清扫, 唯独
     * 血池此前缺这条通道。自然 despawn 不发 {@link LivingDeathEvent} (MobPressureSystem 以 MobSpawnType.SPAWNER
     * 落地且从不 setPersistenceRequired, 走原版 Mob.checkDespawn 的 discard 路径), 只发本事件, 故 {@link #onLivingDeath}
     * 单独回收覆盖不到 despawn 的 6★+ 冠军, 条目永久驻留、每 tick 镜像还要对着已消失的 UUID 空扫。
     *
     * 分流按 {@link Entity.RemovalReason}: KILLED/DISCARDED ({@code shouldDestroy()}=true, 涵盖自然 despawn 与
     * 主动 discard) 与 UNLOADED_TO_CHUNK (区块卸载但玩家未随行) 视为真正离场 -> 摘池; UNLOADED_WITH_PLAYER (随
     * 玩家客户端视距卸载, 服务端侧仍在) 与 CHANGED_DIMENSION (换维度, 同 UUID 马上以新实例回来) 保留在册 —— 摘了
     * 反而丢当前血, 回来时还要靠 {@link #onEntityJoinLevel} 重建。reason 为 null (理论不该出现) 保守保留不回收。
     */
    @SubscribeEvent
    public void onEntityLeaveLevel(EntityLeaveLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        Entity entity = event.getEntity();
        UUID id = entity.getUUID();
        BloodPool pool = BloodPoolRegistry.get(id);
        if (pool == null) {
            return;
        }
        if (entity instanceof LivingEntity living) {
            flushCurrentHp(living, pool);
        }
        Entity.RemovalReason reason = entity.getRemovalReason();
        if (reason == null) {
            return; // 保守保留: 理论上离场事件必带 reason, 防御性不回收。
        }
        if (reason.shouldDestroy() || reason == Entity.RemovalReason.UNLOADED_TO_CHUNK) {
            BloodPoolRegistry.remove(id);
            compositeRamps.remove(id);
        }
    }

    /**
     * 冠军实体进入世界 (F040): 服务端重启/区块重载后, 已盖章冠军 (star≥1) 若按 spec 6.2 判据需要血池
     * ({@link ChampionPromoter#requiresBloodPool}) 但表中无池, 按持久化的 {@link MiningChampionData#currentHp()}
     * 重建, 而不是让第一次受击时 {@link BloodPoolRegistry#get} 返 null 静默切回 vanilla 血权威 (F040 核心缺陷)。
     *
     * 时序安全: {@code MobPressureSystem} 的 addFreshEntityWithPassengers (触发本事件) 在
     * {@code ChampionSpawnSeam.promote} (盖章) 之前, 新刷冠军进本事件时 star 仍为 0 (isChampion=false) 自然
     * no-op, 随后由 promoter 建池; 两条路径 (新刷 vs 载入重建) 不打架。不用 {@code loadedFromDisk()} 做门 ——
     * 跨维度传送 (CHANGED_DIMENSION) 时它为 false, 用它做门会让换维度的冠军永远重建不出池。
     *
     * 脏值容忍: currentHp 不在 (0, effectiveHp] 内 (版本漂移/理论不该发生) 时不让单个脏值毁掉整只冠军 —— 按满血
     * 重建并打一行 warn, 与 {@link MiningChampionData#deserializeNBT} 对脏词条"跳过该条不摧毁整体"的容忍策略一致。
     */
    @SubscribeEvent
    public void onEntityJoinLevel(EntityJoinLevelEvent event) {
        if (event.getLevel().isClientSide()) {
            return;
        }
        if (!(event.getEntity() instanceof LivingEntity living)) {
            return;
        }
        UUID id = living.getUUID();
        if (BloodPoolRegistry.has(id)) {
            return; // 幂等: 已在册, 避开与 promoter 抢建。
        }
        MiningChampionData data = MiningChampions.get(living).orElse(null);
        if (data == null || !data.isChampion()) {
            return;
        }
        double effHp = data.effectiveHp();
        StarRank rank = StarRank.ofStar(data.star());
        if (!ChampionPromoter.requiresBloodPool(rank, effHp)) {
            return; // 1-5★ 且未破 1024: vanilla 血权威, 不建池。
        }
        double cur = data.currentHp();
        if (!(cur > 0.0D && cur <= effHp)) {
            LOGGER.warn("champion {} currentHp={} out of (0,{}] on rejoin, rebuilding blood pool at full health",
                    living.getType().getDescriptionId(), cur, effHp);
            BloodPoolRegistry.install(id, effHp);
            return;
        }
        BloodPoolRegistry.install(id, effHp, cur);
    }
}
