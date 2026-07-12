package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionAttackValues;
import com.miningdim.champion.ChampionDamageTypes;
import com.miningdim.champion.ChampionDiagnostics;
import com.miningdim.champion.ChampionEffectRegistries;
import com.miningdim.champion.ChampionStrikeGate;
import com.miningdim.champion.MiningChampionData;
import com.miningdim.champion.MiningChampions;
import com.miningdim.champion.StarRank;
import com.miningdim.champion.aggregate.PlayerControlAggregator;
import com.miningdim.champion.aggregate.PlayerDotSources;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.effect.ModJobEffects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 冠军攻击玩家时攻击类词条效果的【集中单点施加】(Champions 集成层; ChampionStarAffix spec 7.2 战斗 + 9A.2 单点
 * 铁律 + 9A.3 #5/#6 on-hit)。冠军近战命中玩家时, 在【一个】 {@link LivingHurtEvent} 受击点统一施加全部攻击类
 * 词条 rider, 严禁逐词条 onAttack/onHurt 串行 (那会绕过红线 3 单击 ≤40% / 红线 4 DoT ≤15% / 红线 5 控制聚合)。
 *
 * 施加哪些 (spec 7.2):
 *  - 即时伤害 (重炮 +伤害放大 / 嗜血低血 +伤害放大 / 穿甲无视护甲真伤): 经 {@link ChampionAttackValues#singleHitTotalPct}
 *    合并 + 红线 3 单击/穿甲合计钳制, 折成额外 HP 叠到 event 伤害 (单点, 不逐词条 setAmount)。
 *  - DoT (燃烧 / 寒霜冻伤): on-hit 只【刷层 + 续 3s 刷新窗】进 per-player {@link PlayerDotSources} (受
 *    {@link ChampionStrikeGate} DoT 刷新内 CD ≥1s)。本秒及窗内每秒名义伤害补记 + ≤15% maxHP 聚合 + 寒霜减速持续
 *    施加全部由 {@link ChampionDotTickHandler} 每秒读在册层数完成 (spec 7.2 持续 DoT: 命中后 3s 窗内每秒持续扣血,
 *    不要求每秒新命中, 窗口过期自然停伤)。本 handler 不在受击点 record 名义伤害/扣 DoT 伤 (DoT 单一权威在 tick handler)。
 *  - 减速 (寒霜): 不在受击点施加, 由 tick handler 按窗内活跃寒霜源每秒持续进 per-player 控制聚合 (≤50% 总减速)。
 *  - 易伤 (撕裂): 复用全局 {@link com.miningdim.effect.VulnerabilityEffect}, 按撕裂层折 amplifier 续期, 乘伤由
 *    {@code VulnerabilityHurtHandler} 单点结算 (严禁另挂第二个易伤)。
 *  - 护甲磨损 (强酸): 损玩家护甲耐久 (纯经济磨损, 不入伤害口径)。
 *  - 分跳 (双倍/四倍打击): 把本击的完整名义单击 F 拆 N 跳同额小击 (每跳 F×系数; 双倍 0.6/四倍 0.35)。首跳即缩本
 *    {@link LivingHurtEvent}, 余 N-1 跳按 3tick 间隔调度 (静态 {@link #PENDING_ECHOES} 队列 + {@link #onServerTick}
 *    驱动), 每跳补一次 mob_attack 再进本管线; 回声跳经 {@link #ECHO_IN_PROGRESS} 标记跳过【再分跳/混沌/即时伤再
 *    放大/撕裂涨层】(见 {@link #applyStrikeSplit} 口径), 但 DoT/损甲 rider 照常。N 跳合计 = F × 净倍率 (双倍 1.2/四倍 1.4)。
 *  - 击飞 (混沌重击): 受 {@link ChampionStrikeGate} 击飞内 CD + 落地恢复窗限频后, 经 {@link KnockbackSafetyGuard}
 *    预测末端安全 (SAFE 全量/CLAMPED 缩水平/DENIED 不 push) + {@link PlayerControlAggregator} 控制聚合 (红线 5) +
 *    {@link PlayerLandingProtection} 受保护自查, 服务端权威 setDeltaMovement + hurtMarked 同步真击飞 (见 {@link #applyChaosKnockback})。
 *
 * 事件优先级 (EventPriority.HIGH): 须在玩家侧减伤单点 {@code PlayerDamageReduction} (LOWEST) 与易伤放大
 * {@code VulnerabilityHurtHandler} (默认) 之前把即时额外伤害叠入 event.getAmount(), 使后续减伤/易伤对完整冠军近战
 * 伤害结算 (先叠攻击词条额外伤 → 再易伤放大 → 再玩家减伤)。注意撕裂在 HIGH 涨层后, 同一事件的 NORMAL 易伤放大
 * 会用【含新层】的易伤放大本次 (批1 起既有口径); 回声跳不涨层, 防四跳同事件滚乘击穿净倍率。
 *
 * 单点铁律守卫: 本 handler 是冠军→玩家攻击的唯一施加点。冠军星级 + 词条→品质经自研
 * {@link com.miningdim.champion.MiningChampions} capability 读, 不触任何 top.theillusivec4.champions.*。由
 * {@code ChampionSystem#register} 无条件挂 forgeBus (自研后脱离 Champions 依赖, 不再 ModList 守卫, dev GameTest
 * 可加载可验; 纯数值/红线仍在 champion 包纯逻辑类 GameTest 验)。
 */
public final class ChampionAttackHandler {

    /** 诊断日志: 攻击链真服首验用 (每次冠军命中玩家打一行 入伤/出伤/DoT层数, 仅 10 格内有玩家的怪)。 */
    private static final Logger LOGGER = LoggerFactory.getLogger("miningdim/champion/attack");

    /** 撕裂/DoT 效果挂载时长 (tick): DoT 3s 刷新窗 + 易伤续期窗均取 3s = 60tick (spec 7.2: 3s 刷新)。 */
    private static final int RIDER_DURATION_TICKS = 60;

    /**
     * per-(玩家→冠军) 攻击交互状态: 外层键 = 受击玩家 UUID, 内层键 = 冠军 UUID。如此结构使玩家死亡/登出/换维度时
     * O(1) 清掉该玩家全部冠军交互状态 (反泄漏), 不必反解 XOR 配对键。服务端 tick 串行写, ConcurrentHashMap 仅防
     * 跨线程读可见性。
     */
    private final Map<UUID, Map<UUID, PairState>> stateByVictim = new ConcurrentHashMap<>();

    /**
     * 回声跳(分跳余跳)再进本 handler 的可重入标记: 余跳经 {@link #executeEchoJump} 调 victim.hurt 自然再触发
     * {@link #onLivingHurt}, 此标置真使该次跳过【再分跳 + 混沌触发 + 即时伤再放大】(spec: 混沌不可由多跳重复触发,
     * 净倍率不可被再拆/再灌满), 只保留 DoT/易伤/损甲 rider。ThreadLocal 而非普通 static 布尔: 服务端受击虽单线程,
     * 但 ThreadLocal 语义显式隔离 + 免与任何并行读者串味 (dev GameTest 亦以本类静态态跑, 隔离更稳)。
     */
    private static final ThreadLocal<Boolean> ECHO_IN_PROGRESS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /**
     * 待执行回声跳队列 (静态: 与 {@link #onServerTick} 消费方同域, 且供 {@link #reset} 在服务端停止时清 —— 队列存
     * 强实体引用, 不清则跨存档/跨重启泄漏旧服务端尸体引用)。服务端主线程串行写 (受击点入队 + END 相位出队), 非并发。
     * 非全局排序 (多冠军/多受害者交错入队): {@link #onServerTick} 每 tick 全扫取到期跳, 量极小 (仅近战多击冠军)。
     */
    private static final Deque<EchoJump> PENDING_ECHOES = new ArrayDeque<>();

    /**
     * 冠军近战命中玩家: 集中施加全部攻击类词条 rider (单点)。非冠军攻击者 / 非玩家受击者 / 本工程未盖章冠军
     * 直接放行 (vanilla 自理)。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return; // 受击者非玩家: 攻击类词条只施于玩家。
        }
        // 反震反弹伤 (vanilla thorns / 我方 champion_thorns 真伤)、命定处决 (champion_execution) 与技能 AOE
        // (champion_skill_aoe, 批4 波2 电磁/天雷/小男孩) 的 source.getEntity() 恰是冠军, 会误判为"冠军攻击玩家"
        // 从而额外触发一套 on-hit 攻击词条 (燃烧/寒霜/穿甲/强酸损甲…)。反伤/处决/AOE 是判决非近战攻击, 直接放行
        // (判决须是干净伤害: 不磨甲不挂 DoT, AOE 更不得白送一轮近战 rider)。
        if (event.getSource().is(DamageTypes.THORNS)
                || event.getSource().is(ChampionDamageTypes.CHAMPION_THORNS)
                || event.getSource().is(ChampionDamageTypes.CHAMPION_EXECUTION)
                || event.getSource().is(ChampionDamageTypes.CHAMPION_SKILL_AOE)) {
            return;
        }
        if (!(event.getSource().getEntity() instanceof LivingEntity attacker)) {
            return; // 无直接生物攻击者 (环境/无主弹射物): 不施攻击词条。
        }
        MiningChampionData champ = MiningChampions.get(attacker).orElse(null);
        if (champ == null || !champ.isChampion()) {
            return; // 攻击者非本工程盖章冠军。
        }
        // 自研 cap 恒有 star ∈ [1,10] + 词条→品质 (promote 期一次写全), 无需旧命令召唤"无 NBT 走 tier 兜底"分支。
        int star = champ.star();
        StarRank starRank = StarRank.ofStar(star);
        Map<AffixDef, AffixQuality> equipped = champ.affixes();
        if (equipped.isEmpty()) {
            return; // 无本工程攻击词条。
        }

        long nowTick = victim.level().getGameTime();
        double playerMaxHp = victim.getMaxHealth();
        PairState pair = stateByVictim
                .computeIfAbsent(victim.getUUID(), k -> new ConcurrentHashMap<>())
                .computeIfAbsent(attacker.getUUID(), k -> new PairState());

        // 回声跳(分跳余跳)经 victim.hurt 自然再进本 handler: 只保留 DoT/损甲 rider 照常, 须跳过【再分跳 + 混沌触发 +
        // 即时伤再放大 + 撕裂涨层】—— 余跳额度已是 F×系数 的最终值, 再拆/再放大会破净倍率 (混沌亦不可由多跳重复触发);
        // 撕裂若逐跳涨层, 本事件后续 VulnerabilityHurtHandler (NORMAL) 会用刚涨的层放大本跳, 四跳滚乘可把净 1.4x
        // 膨胀到 ~2.8x 击穿红线 3 (审查修复: 撕裂每次真实挥击至多涨一层, 与批1 节奏一致)。
        boolean echoStrike = ECHO_IN_PROGRESS.get();

        float amountIn = event.getAmount();
        if (!echoStrike) {
            applyInstantDamage(event, equipped, starRank, attacker, victim, playerMaxHp);
            applyStrikeSplit(event, equipped, attacker, victim);
        }
        applyDotsAndSlow(equipped, pair, attacker.getUUID(), nowTick, victim);
        if (!echoStrike) {
            applyRend(equipped, pair, nowTick, victim);
        }
        applyCorrosive(equipped, victim);
        // 混沌击飞: 0 伤命中 (被盾完全格挡/前序减伤清零) 不触发 —— 格挡这一核心防御动作必须能挡住击飞,
        // 也不为白嫖击飞空烧限频 CD (审查修复, 与 applyStrikeSplit 的正伤守卫同口径)。
        if (!echoStrike && event.getAmount() > 0.0F) {
            applyChaosKnockback(equipped, pair, nowTick, attacker, victim);
        }

        // 诊断 (真服首验, 仅 10 格内有玩家的怪): 每次冠军命中玩家打一行, 看词条/即时伤合并前后/DoT 当前层数。
        if (ChampionDiagnostics.shouldTrace(attacker)) {
            PlayerDotSources dots = ChampionEffectRegistries.dotSourcesFor(victim.getUUID());
            LOGGER.info("champion-attack {} star{} affixes={} victim={} amountIn={} amountOut={} burnStacks={} frostStacks={}",
                    attacker.getType().getDescriptionId(), star, equipped.keySet(), victim.getName().getString(),
                    String.format("%.2f", amountIn), String.format("%.2f", event.getAmount()),
                    dots.stacksOf(attacker.getUUID(), AffixDef.BURNING),
                    dots.stacksOf(attacker.getUUID(), AffixDef.FROST));
        }
    }

    /**
     * 即时伤害单点 (重炮 + 嗜血 + 穿甲): 合并放大 + 红线钳后折额外 HP 叠到 event。普通分量被重炮/嗜血放大夹到
     * 该星普通单击上限, 与穿甲真伤合计夹 ≤40% 单击。bonusOverVanilla 只补足到 %maxHP 名义值, 不削原版近战。
     */
    private void applyInstantDamage(LivingHurtEvent event, Map<AffixDef, AffixQuality> equipped,
                                    StarRank starRank, LivingEntity attacker, Player victim, double playerMaxHp) {
        double heavyCannonAmp = 0.0D;
        AffixQuality hc = equipped.get(AffixDef.HEAVY_CANNON);
        if (hc != null) {
            heavyCannonAmp = AffixDef.HEAVY_CANNON.valueFor(hc);
        }

        double bloodlustAmp = 0.0D;
        AffixQuality bl = equipped.get(AffixDef.BLOODLUST);
        if (bl != null) {
            bloodlustAmp = ChampionAttackValues.bloodlustDamageAmp(bl, championHpFraction(attacker));
        }

        double piercingPct = 0.0D;
        AffixQuality ap = equipped.get(AffixDef.ARMOR_PIERCING);
        if (ap != null) {
            piercingPct = AffixDef.ARMOR_PIERCING.valueFor(ap);
        }

        if (heavyCannonAmp == 0.0D && bloodlustAmp == 0.0D && piercingPct == 0.0D) {
            return; // 无即时伤害词条。
        }

        double totalPct = ChampionAttackValues.singleHitTotalPct(
                starRank, starRank.baseSingleHitPct(), heavyCannonAmp, bloodlustAmp, piercingPct);
        double bonusHp = ChampionAttackValues.bonusOverVanilla(totalPct, playerMaxHp, event.getAmount());
        if (bonusHp > 0.0D) {
            event.setAmount((float) (event.getAmount() + bonusHp));
        }
    }

    /**
     * DoT (燃烧/寒霜冻伤) on-hit 只【刷层 + 续 3s 刷新窗】进 per-player {@link PlayerDotSources} (受
     * {@link ChampionStrikeGate} 内 CD ≥1s 约束, 双倍/四倍分跳同帧不重复刷层)。本秒及窗内每秒名义伤害补记 +
     * 寒霜减速持续施加全部下放到 {@link ChampionDotTickHandler}: 命中后即便玩家风筝/冠军够不到, 只要仍在 3s 窗内
     * tick handler 都按当前在册层数每秒扣血 (spec 7.2: 持续 DoT, 不要求每秒新命中), 窗口过期自然停伤。受击点不再
     * 直接 record 名义伤害 (避免命中秒走受击点、非命中秒走 tick 的双路径; DoT 扣血单一权威收敛到 tick handler)。
     */
    private void applyDotsAndSlow(Map<AffixDef, AffixQuality> equipped, PairState pair, UUID attackerId, long nowTick,
                                  Player victim) {
        boolean hasBurning = equipped.containsKey(AffixDef.BURNING);
        boolean hasFrost = equipped.containsKey(AffixDef.FROST);
        if (!hasBurning && !hasFrost) {
            return;
        }

        // 命中即时火/雪粒子 (cosmetic 显示层, 与 DoT 扣血结算分离): 让被冠军点燃/冰冻在受击瞬间看得见。
        if (victim.level() instanceof ServerLevel serverLevel) {
            double cx = victim.getX();
            double cy = victim.getY() + victim.getBbHeight() * 0.5D;
            double cz = victim.getZ();
            double sx = victim.getBbWidth() * 0.4D;
            double sy = victim.getBbHeight() * 0.4D;
            if (hasBurning) {
                serverLevel.sendParticles(ParticleTypes.FLAME, cx, cy, cz, 8, sx, sy, sx, 0.02D);
            }
            if (hasFrost) {
                serverLevel.sendParticles(ParticleTypes.SNOWFLAKE, cx, cy, cz, 8, sx, sy, sx, 0.02D);
            }
        }

        if (!pair.gate.canRefreshDot(nowTick)) {
            return; // DoT 刷新内 CD 内 (双倍/四倍分跳同帧第二跳): 不刷层, 不续窗 (不破 ≤15% 聚合封顶)。
        }
        PlayerDotSources dotSources = ChampionEffectRegistries.dotSourcesFor(victim.getUUID());
        if (hasBurning) {
            dotSources.refresh(attackerId, AffixDef.BURNING, equipped.get(AffixDef.BURNING), nowTick);
        }
        if (hasFrost) {
            dotSources.refresh(attackerId, AffixDef.FROST, equipped.get(AffixDef.FROST), nowTick);
        }
        pair.gate.markDotRefreshed(nowTick);
    }

    /**
     * 撕裂: 续期撕裂层 (3s 窗内叠层, 窗外重置), 折易伤 amplifier 复用全局易伤效果。amplifier &lt;0 (撕裂 %不足
     * 易伤 I 档) 时不挂。乘伤由 VulnerabilityHurtHandler 单点结算; 多源 (撕裂+塔罗) 取最高 amplifier 由原版保证。
     */
    private void applyRend(Map<AffixDef, AffixQuality> equipped, PairState pair, long nowTick, Player victim) {
        AffixQuality q = equipped.get(AffixDef.REND);
        if (q == null) {
            return;
        }
        RendStacks rs = pair.rend;
        rs.bump(nowTick);

        int amp = ChampionAttackValues.rendAmplifier(q, rs.layers);
        if (amp < 0) {
            return; // 撕裂 %不足最低易伤档: 不挂效果。
        }
        // 复用全局易伤 (9A.2 铁律): 挂同一 VULNERABILITY 效果, 原版取最高 amplifier, 乘伤单点结算。
        victim.addEffect(new MobEffectInstance(
                ModJobEffects.VULNERABILITY.get(), RIDER_DURATION_TICKS, amp, false, true));
    }

    /** 强酸: 损玩家全护甲槽耐久 valueFor 点 (纯经济磨损, 不入伤害口径)。 */
    private void applyCorrosive(Map<AffixDef, AffixQuality> equipped, Player victim) {
        AffixQuality q = equipped.get(AffixDef.CORROSIVE);
        if (q == null) {
            return;
        }
        int durabilityLoss = ChampionAttackValues.corrosiveArmorDamage(q);
        if (durabilityLoss <= 0) {
            return;
        }
        for (ItemStack armor : victim.getArmorSlots()) {
            if (armor.isEmpty() || !armor.isDamageableItem()) {
                continue;
            }
            int newDamage = Math.min(armor.getDamageValue() + durabilityLoss, armor.getMaxDamage());
            armor.setDamageValue(newDamage);
        }
    }

    /**
     * 双倍/四倍分跳 (spec 7.2): 把冠军【这一击的完整名义单击 F】拆成 N 跳同额小击 —— 首跳即本 {@link LivingHurtEvent}
     * (缩到 F×每跳系数), 余 N-1 跳按 {@link ChampionStrikeGate#STRIKE_JUMP_INTERVAL_TICKS} tick 间隔入
     * {@link #PENDING_ECHOES} 队列, 由 {@link #onServerTick} 逐跳补 mob_attack。
     *
     * 口径 (系数乘的被乘数, 及排在即时伤【之后】的裁定 —— 用户授权"读现有 applyInstantDamage 管线后定"):
     *   本方法在 {@link #applyInstantDamage} 之后调, 故 event 的值 F 已是冠军完整放大单击 (重炮/嗜血/穿甲已并入)。
     *   每跳系数乘在 F 上, N 跳合计 = F × (系数 × N) = 双倍 F×1.2 / 四倍 F×1.4 (用户裁定净倍率), 是"完整单击的
     *   温和倍率再摊到 N 跳", 不与重炮/嗜血二次叠乘。
     *   反证弃"先分跳(乘 amountIn 原始名义)再即时伤"的排序: {@link ChampionAttackValues#bonusOverVanilla} 会把每个被
     *   缩小的跳"补足到 nominal %maxHP", 令每跳都灌满 = N×nominal 爆炸叠乘 (违净倍率 + 反叠叠乐), 故必排在即时伤之后。
     *   同理回声跳再进本 handler 时必须跳过即时伤 (见 onLivingHurt 的 echoStrike 门): 余跳额度已是 F×系数 最终值,
     *   再经 bonusOverVanilla 会被灌满, 故 echo 只走 DoT/易伤/损甲 rider 不再走即时伤/再分跳/混沌。
     *
     * 触发限定: 仅冠军对【服务端玩家】的近战直接伤害触发 (与其它 on-hit rider 同口径; 余跳调度需 ServerPlayer 在线
     * 核对, 非 ServerPlayer/客户端侧不改 event 避免削半净伤)。互斥词条 (MutexFlag.MULTI_STRIKE) 保至多一个多击 def。
     */
    private void applyStrikeSplit(LivingHurtEvent event, Map<AffixDef, AffixQuality> equipped,
                                  LivingEntity attacker, Player victim) {
        AffixDef multiDef;
        AffixQuality multiQuality;
        AffixQuality q4 = equipped.get(AffixDef.QUADRUPLE_STRIKE);
        AffixQuality q2 = equipped.get(AffixDef.DOUBLE_STRIKE);
        boolean trace = ChampionDiagnostics.shouldTrace(attacker); // 真服 2026-07-08 分跳零效果排障: 逐早退口取证。
        if (q4 != null) {
            multiDef = AffixDef.QUADRUPLE_STRIKE; // 双持(理论不出现)时四倍优先, 保确定性。
            multiQuality = q4;
        } else if (q2 != null) {
            multiDef = AffixDef.DOUBLE_STRIKE;
            multiQuality = q2;
        } else {
            if (trace) {
                LOGGER.info("skill-strike skip=no-multi-def keys={} q2={} q4={}", equipped.keySet(), q2, q4);
            }
            return; // 无多击词条。
        }
        if (!(victim instanceof ServerPlayer serverVictim)) {
            if (trace) {
                LOGGER.info("skill-strike skip=not-serverplayer victimClass={}", victim.getClass().getName());
            }
            return; // 分跳是服务端权威 (余跳需 ServerPlayer 调度): 客户端侧不改 event。
        }
        float f = event.getAmount();
        if (f <= 0.0F) {
            if (trace) {
                LOGGER.info("skill-strike skip=non-positive f={}", f);
            }
            return; // 本击无正伤 (被前序减伤清零/反射等): 不拆。
        }
        int jumps = ChampionStrikeGate.strikeJumps(multiDef, multiQuality);   // 2 / 4
        double factor = ChampionStrikeGate.strikeJumpFactor(multiDef);        // 0.6 / 0.35
        float perJump = (float) (f * factor);
        event.setAmount(perJump); // 首跳 = 完整单击 × 系数 (即时伤已并入 F)。
        MinecraftServer server = serverVictim.getServer();
        if (server == null) {
            return; // 无服务端上下文 (理论不出现于服务端受击点): 首跳已缩, 无从调度余跳。
        }
        // 余跳按 overworld 权威 gameTime 调度 (与 onServerTick 同一时钟, 跨维度一致; 不用 pair-gate 的 victim.level
        // gameTime —— 那是 gate 相对窗自用)。overworld 恒加载, 其 gameTime 每服务端 tick 推进。
        long schedulerNow = server.overworld().getGameTime();
        for (int i = 1; i < jumps; i++) {
            long dueTick = schedulerNow + i * ChampionStrikeGate.STRIKE_JUMP_INTERVAL_TICKS;
            PENDING_ECHOES.addLast(new EchoJump(attacker, serverVictim, perJump, dueTick));
        }
        if (trace) {
            LOGGER.info("skill-strike split def={} quality={} jumps={} f={} perJump={} queued={} due0={}",
                    multiDef, multiQuality, jumps, String.format("%.2f", f), String.format("%.2f", perJump),
                    jumps - 1, schedulerNow + ChampionStrikeGate.STRIKE_JUMP_INTERVAL_TICKS);
        }
    }

    /**
     * 混沌重击真击飞 (spec 7.2 + 9.3 红线 6): 通过 {@link ChampionStrikeGate} 击飞内 CD ≥2s + 落地恢复窗 ≥1s 限频后,
     * 经落点安全守卫 + 控制聚合 + 落地保护自查, 服务端权威把玩家沿冠军->玩家水平方向击飞。
     *
     * 闸序 (任一前置未过则伤害照常但不 push, 且限频账【不】记 —— 留下周期在别处再试, 不空耗混沌 CD):
     *  1. {@link PlayerLandingProtection#isProtected}: 受保护玩家不施二次位移 (本 push 走 setDeltaMovement 不经
     *     LivingKnockBackEvent, 落地保护的事件闸拦不住, 故此处显式自查 —— 波0 词条位移自查约定)。
     *  2. {@link KnockbackSafetyGuard#clampDisplacement} 预测末端 (玩家位置沿推方向外推 3 格): SAFE 全量 push /
     *     CLAMPED 水平初速按 (夹后落点距离/3) 等比缩 (竖直不缩) / DENIED 全程无安全落点则不 push。
     *  3. {@link PlayerControlAggregator#admit} 12tick: 击飞属控制类进 7s 窗 50% 上限 + ≥2s 自由窗 (红线 5); 拒则不 push。
     * push 落地: setDeltaMovement 叠加 + ServerPlayer.hurtMarked=true (vanilla ServerEntity.sendChanges 据此
     * broadcastAndSend 一个 ClientboundSetEntityMotionPacket 到玩家自身 connection 同步速度; 已核对 1.20.1 源码),
     * 并授 2s 抗位移落地保护 (红线 6: 防在飞玩家被另一击退推回危险区)。push 成功才 markKnockback 落限频账 + 打 skill 日志。
     */
    private void applyChaosKnockback(Map<AffixDef, AffixQuality> equipped, PairState pair, long nowTick,
                                     LivingEntity attacker, Player victim) {
        if (!equipped.containsKey(AffixDef.CHAOS_STRIKE)) {
            return;
        }
        if (!pair.gate.canKnockback(nowTick)) {
            return; // 内 CD / 落地恢复窗内: 本次不击飞。
        }
        if (!(victim instanceof ServerPlayer serverVictim) || !(victim.level() instanceof ServerLevel level)) {
            return; // 击飞是服务端权威位移: 需 ServerLevel 守卫 + ServerPlayer 同步速度。
        }
        if (PlayerLandingProtection.isProtected(serverVictim)) {
            return; // 落地保护窗内: 不施二次位移 (伤害照常, 限频账不记, 下周期再试)。
        }
        // 水平方向 = 冠军->玩家单位向量 (仅 XZ; 竖直由固定初速 0.5 提供)。
        double dx = serverVictim.getX() - attacker.getX();
        double dz = serverVictim.getZ() - attacker.getZ();
        double horiz = Math.sqrt(dx * dx + dz * dz);
        if (horiz < 1.0E-4D) {
            return; // 冠军与玩家水平重合 (无方向): 不击飞, 限频账不记。
        }
        double dirX = dx / horiz;
        double dirZ = dz / horiz;
        Vec3 from = serverVictim.position();
        Vec3 proposedEnd = new Vec3(
                from.x + dirX * ChampionStrikeGate.CHAOS_PUSH_DISTANCE,
                from.y,
                from.z + dirZ * ChampionStrikeGate.CHAOS_PUSH_DISTANCE);
        KnockbackSafetyGuard.Decision decision = KnockbackSafetyGuard.clampDisplacement(level, from, proposedEnd);
        if (decision.outcome() == KnockbackSafetyGuard.Outcome.DENIED) {
            return; // 全程无安全落点: 只结算伤害不 push, 限频账不记。
        }
        double horizontalScale = 1.0D;
        if (decision.outcome() == KnockbackSafetyGuard.Outcome.CLAMPED) {
            BlockPos landing = decision.landing();
            double clampedDist = Math.sqrt(
                    ((landing.getX() + 0.5D) - from.x) * ((landing.getX() + 0.5D) - from.x)
                    + ((landing.getZ() + 0.5D) - from.z) * ((landing.getZ() + 0.5D) - from.z));
            horizontalScale = ChampionStrikeGate.chaosClampedHorizontalScale(
                    clampedDist, ChampionStrikeGate.CHAOS_PUSH_DISTANCE);
        }
        // 击飞时长入控制聚合 (红线 5): 被拒 (额度耗尽/破自由窗) -> 不 push, 伤害照常, 限频账不记。
        PlayerControlAggregator control = ChampionEffectRegistries.controlFor(serverVictim.getUUID());
        long granted = control.admit(nowTick, ChampionStrikeGate.CHAOS_CONTROL_TICKS);
        if (granted <= 0L) {
            return;
        }
        // granted 折算推力 (审查修复, 红线 5 记账约定与 ChampionVisualDisruptionHandler 同口径): 聚合器只批出
        // 部分额度 (多控制源并发挤占) 时, 玩家实际承受的击飞强度必须随之衰减 —— 否则等于花小额账买满额位移,
        // 多源并发下红线 5 的强度约束形同虚设。竖直/水平同比缩 (滞空时长 ~ 初速, 与账面 tick 数对应)。
        double controlScale = (double) granted / ChampionStrikeGate.CHAOS_CONTROL_TICKS;
        // push: 叠加速度 (水平按守卫+额度双重缩放, 竖直按额度缩放) + ServerPlayer 置 hurtMarked 同步到自身客户端。
        double vx = dirX * ChampionStrikeGate.CHAOS_PUSH_HORIZONTAL * horizontalScale * controlScale;
        double vz = dirZ * ChampionStrikeGate.CHAOS_PUSH_HORIZONTAL * horizontalScale * controlScale;
        double vy = ChampionStrikeGate.CHAOS_PUSH_Y * controlScale;
        serverVictim.setDeltaMovement(serverVictim.getDeltaMovement().add(vx, vy, vz));
        serverVictim.hurtMarked = true;
        PlayerLandingProtection.grant(serverVictim); // 红线 6: 落点后 2s 抗位移, 防在飞被二次击退。
        // push 成功才落限频账 (落地预计 = now + 落地恢复窗粗估 1s)。
        long estimatedLanding = nowTick + ChampionStrikeGate.CHAOS_LANDING_RECOVERY_TICKS;
        pair.gate.markKnockback(nowTick, estimatedLanding);
        if (ChampionDiagnostics.shouldTrace(attacker)) {
            LOGGER.info("skill-chaos champion={} victim={} outcome={} hScale={} granted={}t ctlScale={}",
                    attacker.getType().getDescriptionId(), serverVictim.getName().getString(),
                    decision.outcome(), String.format("%.2f", horizontalScale), granted,
                    String.format("%.2f", controlScale));
        }
    }

    /**
     * 冠军当前血量占比 (嗜血低血判定; spec 6.2 #1 血池权威): 6★+ 读影子血池 fraction, 1-5★ 读 vanilla
     * getHealth/getMaxHealth 之比。血池缺失 (异常) 回退 vanilla 比, 不抛 (受击点不应因诊断数据缺失中断)。
     */
    private static double championHpFraction(LivingEntity champion) {
        BloodPool pool = BloodPoolRegistry.get(champion.getUUID());
        if (pool != null) {
            return pool.fraction();
        }
        float max = champion.getMaxHealth();
        if (max <= 0.0F) {
            return 1.0D;
        }
        return champion.getHealth() / max;
    }

    /** per-(玩家→冠军) 攻击交互状态聚合: on-hit 内 CD/击飞闸 + 撕裂层。燃烧/寒霜层数+刷新窗已上提到 per-player
     * {@link PlayerDotSources} (tick handler 每秒读), 不再藏于本 per-pair 状态。 */
    private static final class PairState {
        private final ChampionStrikeGate gate = new ChampionStrikeGate();
        private final RendStacks rend = new RendStacks();
    }

    /** 撕裂层数 + 刷新窗: 3s 窗内续期叠层 (上限 = 易伤封顶 +100%, 由 amplifier 映射自然钳), 窗外重置。 */
    private static final class RendStacks {
        private int layers = 0;
        private long lastTick = Long.MIN_VALUE;

        void bump(long nowTick) {
            if (lastTick != Long.MIN_VALUE && nowTick - lastTick > RIDER_DURATION_TICKS) {
                layers = 0; // 刷新窗外: 撕裂层衰减清零, 重新累叠。
            }
            layers++;
            lastTick = nowTick;
        }
    }

    /**
     * 每服务端 tick (END 相位) 出队到期回声跳: 全扫 {@link #PENDING_ECHOES} 取到期跳执行 (跨维度统一按 overworld
     * 权威 gameTime 比对, 与入队同时钟)。
     *
     * 先【抽干到期跳到本地列表再执行】而非边迭代边执行 —— 关键: 执行中 victim.hurt 可能致死玩家, 同步触发
     * {@link #onLivingDeath} 回调 {@link #pruneEchoesFor} 改本队列, 若正迭代活队列即 ConcurrentModificationException;
     * 抽干后队列不再被迭代, prune 只动剩余未到期跳 (安全), 已抽出的跳各自 {@link #executeEchoJump} 内再核对存活。
     */
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || PENDING_ECHOES.isEmpty()) {
            return;
        }
        MinecraftServer server = event.getServer();
        long nowTick = server.overworld().getGameTime();
        List<EchoJump> due = null;
        Iterator<EchoJump> it = PENDING_ECHOES.iterator();
        while (it.hasNext()) {
            EchoJump job = it.next();
            if (nowTick < job.dueTick()) {
                continue; // 未到点。
            }
            it.remove();
            if (due == null) {
                due = new ArrayList<>();
            }
            due.add(job);
        }
        if (due != null) {
            for (EchoJump job : due) {
                executeEchoJump(job);
            }
        }
    }

    /**
     * 执行一次回声跳: 逐跳核对【冠军活着 + 受害者活着在线 + 同维度 + 距离 ≤6 格】(脱战/跑远/换维/离场的余跳作废),
     * 通过则清受击无敌帧后补一次 mob_attack (attacker=冠军), 该跳经 {@link #ECHO_IN_PROGRESS} 标记再进 on-hit 管线
     * (跳过再分跳/混沌/即时伤, DoT/易伤/损甲 rider 照常)。
     *
     * 清无敌帧 (victim.invulnerableTime=0): 3tick 跳间隔 &lt; 上一击留的 20tick 受击无敌帧, 不清则 vanilla
     * LivingEntity.hurt 走"invulnerableTime&gt;10 且非 bypasses_cooldown"分支只补差值(本跳等额→amount≤lastHurt→
     * 直接吞掉返 false)。清后本跳走完整扣血分支并重置 20tick (已核对 1.20.1 源码; 与 {@link ChampionDotTickHandler}
     * 致死份同款清帧手法)。选清帧而非自建 bypasses_cooldown 伤害类型: 回声跳须保留"再进 on-hit 管线"性质走 mob_attack。
     */
    private static void executeEchoJump(EchoJump job) {
        LivingEntity champion = job.champion();
        ServerPlayer victim = job.victim();
        boolean trace = ChampionDiagnostics.shouldTrace(champion); // 真服 2026-07-08 分跳排障: 回声跳执行/作废取证。
        if (!champion.isAlive() || !victim.isAlive()) {
            if (trace) {
                LOGGER.info("skill-strike echo-drop reason=dead champAlive={} victimAlive={}",
                        champion.isAlive(), victim.isAlive());
            }
            return; // 冠军或受害者已死/离场。
        }
        if (champion.level() != victim.level()) {
            if (trace) {
                LOGGER.info("skill-strike echo-drop reason=dimension");
            }
            return; // 换维度/传走: 不同维度不补刀。
        }
        if (!ChampionStrikeGate.echoJumpInRange(champion.distanceToSqr(victim))) {
            if (trace) {
                LOGGER.info("skill-strike echo-drop reason=range distSq={}",
                        String.format("%.1f", champion.distanceToSqr(victim)));
            }
            return; // 脱战/跑出近战范围 (>6 格): 余跳作废。
        }
        victim.invulnerableTime = 0;
        ECHO_IN_PROGRESS.set(Boolean.TRUE);
        try {
            boolean landed = victim.hurt(victim.damageSources().mobAttack(champion), job.perJumpDamage());
            if (trace) {
                LOGGER.info("skill-strike echo landed={} perJump={}", landed, String.format("%.2f", job.perJumpDamage()));
            }
        } finally {
            ECHO_IN_PROGRESS.set(Boolean.FALSE);
        }
    }

    /** 摘除某实体 (冠军或受害者) 相关的全部在途回声跳 (死亡/登出/换维度反泄漏 + 防对尸体/离场者补刀)。 */
    private static void pruneEchoesFor(UUID entityId) {
        PENDING_ECHOES.removeIf(job -> job.champion().getUUID().equals(entityId)
                || job.victim().getUUID().equals(entityId));
    }

    /**
     * 服务端停止清空待执行回声跳队列 (队列存强实体引用, 不清则跨存档/跨重启泄漏旧服务端尸体引用)。须由
     * {@code ChampionSystem#onServerStopping} 调 (与波0 PlayerLandingProtection/AoeImmunityBuffer 静态账本同纪律;
     * 本 handler 队列是 static, 与那些账本一样无法经实例清)。
     */
    public static void reset() {
        PENDING_ECHOES.clear();
    }

    /**
     * 反泄漏: 死亡时清 per-pair 攻击交互状态 (撕裂层/DoT 层/on-hit 闸) + 摘该实体在途回声跳。状态外层键 = 受击玩家
     * UUID, 故 O(1) 整桶移除; 回声跳按冠军或受害者 UUID 摘 (故本 handler 亦收冠军死亡 —— 冠军非 Player, per-pair 桶
     * 不涉, 只摘其余跳)。与 {@link ChampionEffectRegistries.CleanupHandler} 清聚合器同生命周期, 但本 handler 的
     * per-pair 状态与回声跳队列独立持有, 须本 handler 自行清 (键域不同)。
     */
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        UUID deadId = event.getEntity().getUUID();
        pruneEchoesFor(deadId); // 冠军或受害者死亡: 摘其全部在途回声跳。
        if (event.getEntity() instanceof Player player) {
            stateByVictim.remove(player.getUUID());
        }
    }

    @SubscribeEvent
    public void onLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        UUID id = event.getEntity().getUUID();
        stateByVictim.remove(id);
        pruneEchoesFor(id); // 登出玩家的在途回声跳作废 (防对离场者补刀 + 反泄漏)。
    }

    @SubscribeEvent
    public void onChangedDimension(PlayerEvent.PlayerChangedDimensionEvent event) {
        UUID id = event.getEntity().getUUID();
        stateByVictim.remove(id);
        pruneEchoesFor(id); // 换维度玩家的在途回声跳作废 (跨维不补刀; executeEchoJump 亦有同维守卫二次兜底)。
    }

    /**
     * 一次待执行回声跳: 强引用冠军 + 受害者 (寿命至多 N×3tick 且死亡/登出/停服即摘, 泄漏窗极小)。执行前逐跳核对
     * 冠军/受害者存活 + 同维 + 距离 (见 {@link #executeEchoJump})。
     *
     * @param champion      施击冠军 (mob_attack 的 attacker)
     * @param victim        受击服务端玩家
     * @param perJumpDamage 每跳伤害 = 完整单击 F × 每跳系数 (首跳与余跳同额)
     * @param dueTick       到期执行 tick (overworld 权威 gameTime)
     */
    private record EchoJump(LivingEntity champion, ServerPlayer victim, float perJumpDamage, long dueTick) {
    }
}
