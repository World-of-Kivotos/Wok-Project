package com.miningdim.champion.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.ChampionAffixState;
import com.miningdim.champion.ChampionEffectRegistries;
import com.miningdim.champion.ChampionSelfBuffValues;
import com.miningdim.champion.StarRank;
import com.miningdim.champion.aggregate.RetaliationAggregator;
import com.miningdim.champion.bloodpool.BloodPool;
import com.miningdim.champion.bloodpool.BloodPoolRegistry;
import com.miningdim.champion.integration.affix.MiningAffix;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import top.theillusivec4.champions.api.IAffix;
import top.theillusivec4.champions.api.IChampion;
import top.theillusivec4.champions.common.capability.ChampionCapability;
import top.theillusivec4.champions.common.rank.Rank;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 冠军【自身被动词条】(Stage2 批1) 效果施加 (Champions 集成层; ChampionStarAffix spec 7.1 再生组织/易燃再生/反震 +
 * 7.3 高速移动)。四类自效果:
 *  - 再生组织 REGEN_TISSUE: 脱战 (5s 无伤) 每秒回 %maxHP (惩罚脱离/翻盘)。
 *  - 易燃再生 FLAMMABLE_REGEN: 距上次受伤 ≥1.5s 每秒回 FLAT HP (受伤停回 off-switch)。
 *  - 高速移动 SPRINT: 挂 MOVEMENT_SPEED 瞬态 modifier (+移速%; 幂等按 UUID 只挂一次)。
 *  - 反震 THORNS: 被玩家击中时按攻击者 maxHP% 反伤打回攻击者 (内 CD ≥3s + 经 {@link RetaliationAggregator} 30%/s
 *    多源封顶); 击退/AOE-对周围/高亮前摇属后续批 (需 KnockbackSafetyGuard, 批1只做对攻击者反伤)。
 *
 * 两个入口:
 *  - {@link #onServerTick} (END, 每 {@value #SCAN_INTERVAL_TICKS}tick=1s): 按玩家 AABB 扫近处冠军 (与
 *    {@code ChampionParticleHandler}/{@code ChampionBossBarHandler} 同范式, 覆盖命令召唤 + 自然刷两种来源),
 *    对每只施回血 (血池/vanilla) + 维护移速 modifier。1s 扫一次即施一整秒名义回血。
 *  - {@link #onChampionHurt} (HIGH, 早于血池 LOWEST 取消): 记录 lastHurtTick (回血门槛) + 施反震反伤。
 *
 * 血池权威 (spec 6.2): 6★+ 冠军回血走影子血池 {@link BloodPool#heal} (vanilla 血条由 {@code ChampionBloodPoolHandler}
 * 每 tick 镜像同步); 1-5★ 无池冠军回 vanilla getHealth。再生组织 %maxHP 的基数亦按此分流 (血池 maxHp / vanilla maxHealth)。
 *
 * compileOnly 隔离: 本类 import top.theillusivec4.champions.* (受击/tick 读 IChampion 词条池), 属 integration 隔离包,
 * 仅 Champions 加载时由 {@link ChampionIntegrationBootstrap} 挂 forgeBus (dev GameTest 不加载; 数值/门槛纯逻辑下沉
 * {@link ChampionSelfBuffValues} 真测)。
 */
public final class ChampionSelfEffectHandler {

    /** 自效果扫描/回血结算周期 (tick): 1s 扫一次近玩家冠军, 施一整秒回血 + 维护移速 (与纯逻辑 HEAL_TICK_INTERVAL 对齐)。 */
    private static final int SCAN_INTERVAL_TICKS = (int) ChampionSelfBuffValues.HEAL_TICK_INTERVAL;

    /** 自效果作用的玩家可见距离 (格; 与 BOSS 血条/粒子同量级)。远离该范围的冠军不结算 (无玩家在场无需回血/加速)。 */
    private static final double VIEW_RANGE = 48.0D;

    /** 高速移动 MOVEMENT_SPEED modifier 固定 UUID (幂等挂载; 瞬态不入 NBT)。 */
    private static final UUID SPRINT_MODIFIER_UUID = UUID.fromString("d8b6a3f1-2c47-4e9a-b1d3-5a7c9e0f2b48");

    /** 本工程批1自效果词条集 (仅这些被本 handler 施加; 其余词条走各自 handler / 未实现)。 */
    private static final Set<AffixDef> SELF_AFFIXES = Set.of(
            AffixDef.REGEN_TISSUE, AffixDef.FLAMMABLE_REGEN, AffixDef.SPRINT, AffixDef.THORNS);

    /** per-冠军自效果状态 (受击 tick / 反震 tick); 冠军死亡摘除防泄漏。 */
    private final Map<UUID, SelfState> stateByChampion = new HashMap<>();

    /**
     * 每秒扫近玩家冠军施回血 + 维护移速。按玩家 AABB 扫 + Champions capability 检出冠军 (命令召唤 + 自然刷一视同仁),
     * 多玩家同时看同一冠军本轮只结算一次。
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
                    applySelfTick(entity, nowTick);
                }
            }
        }
    }

    /** 对一只实体 (若是本工程冠军) 施 tick 自效果: 移速维护 + 脱战/战斗回血。 */
    private void applySelfTick(LivingEntity entity, long nowTick) {
        IChampion champion = ChampionCapability.getCapability(entity).resolve().orElse(null);
        if (champion == null || champion.getServer() == null) {
            return; // 非冠军。
        }
        IChampion.Server server = champion.getServer();
        Rank rank = server.getRank().orElse(null);
        if (rank == null || rank.getTier() <= 0) {
            return;
        }
        Map<AffixDef, AffixQuality> equipped = collectSelfAffixes(server, rank.getTier());
        if (equipped.isEmpty()) {
            return; // 无本工程自效果词条。
        }

        // 移速 (幂等): 有高速移动则确保 MOVEMENT_SPEED modifier 挂上。
        AffixQuality sprint = equipped.get(AffixDef.SPRINT);
        if (sprint != null) {
            ensureSprintModifier(entity, ChampionSelfBuffValues.sprintSpeedBonus(sprint));
        }

        // 回血: 需 lastHurtTick 判脱战/停回窗 (无 state = 从未受伤 = Long.MIN_VALUE 视为脱战/可回)。
        long lastHurt = lastHurtTickOf(entity.getUUID());
        double healPerSecond = 0.0D;
        AffixQuality regen = equipped.get(AffixDef.REGEN_TISSUE);
        if (regen != null && ChampionSelfBuffValues.isOutOfCombat(nowTick, lastHurt)) {
            healPerSecond += ChampionSelfBuffValues.regenTissueHealPerSecond(regen, effectiveMaxHp(entity));
        }
        AffixQuality flammable = equipped.get(AffixDef.FLAMMABLE_REGEN);
        if (flammable != null && ChampionSelfBuffValues.flammableRegenReady(nowTick, lastHurt)) {
            healPerSecond += ChampionSelfBuffValues.flammableRegenHealPerSecond(flammable);
        }
        if (healPerSecond > 0.0D) {
            applyHeal(entity, healPerSecond);
        }
    }

    /**
     * 冠军被击: 记录 lastHurtTick (回血门槛) + 施反震反伤。HIGH 优先级: 早于血池 handler (LOWEST) 取消, 保被
     * 血池吞掉 vanilla 扣血的 6★+ 冠军也照记受击 + 反伤 (受击是否发生与净减伤/取消无关)。
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onChampionHurt(LivingHurtEvent event) {
        LivingEntity victim = event.getEntity();
        IChampion champion = ChampionCapability.getCapability(victim).resolve().orElse(null);
        if (champion == null || champion.getServer() == null) {
            return; // 受击者非冠军。
        }
        IChampion.Server server = champion.getServer();
        Rank rank = server.getRank().orElse(null);
        if (rank == null || rank.getTier() <= 0) {
            return;
        }
        Map<AffixDef, AffixQuality> equipped = collectSelfAffixes(server, rank.getTier());
        if (equipped.isEmpty()) {
            return; // 无本工程自效果词条: 不维护状态 (防为第三方冠军泄漏 state)。
        }

        long nowTick = victim.level().getGameTime();
        SelfState state = stateByChampion.computeIfAbsent(victim.getUUID(), k -> new SelfState());
        state.lastHurtTick = nowTick;

        AffixQuality thorns = equipped.get(AffixDef.THORNS);
        if (thorns != null) {
            applyThorns(event, victim, thorns, nowTick, state);
        }
    }

    /**
     * 反震反伤: 攻击者是玩家 + 过内 CD 时, 按攻击者 maxHP% 折名义反伤, 经该攻击者 {@link RetaliationAggregator}
     * 30%/s + 40%/窗 多源封顶夹断后打回攻击者 (THORNS 伤害类型; {@code ChampionAttackHandler} 已守卫不对其再触 on-hit 词条)。
     */
    private void applyThorns(LivingHurtEvent event, LivingEntity victim, AffixQuality thornsQuality,
                             long nowTick, SelfState state) {
        if (!(event.getSource().getEntity() instanceof ServerPlayer attacker)) {
            return; // 非玩家来源 (环境/召唤物): 不反伤。
        }
        if (!ChampionSelfBuffValues.thornsReady(nowTick, state.lastThornsTick)) {
            return; // 内 CD 内: 本次不反伤 (限频)。
        }
        double attackerMaxHp = attacker.getMaxHealth();
        if (!(attackerMaxHp > 0.0D)) {
            return;
        }
        double raw = ChampionSelfBuffValues.thornsReflectRaw(thornsQuality, attackerMaxHp);
        RetaliationAggregator agg = ChampionEffectRegistries.retaliationFor(attacker.getUUID(), attackerMaxHp);
        double reflected = agg.admit(raw, nowTick);
        if (reflected <= 0.0D) {
            return; // 反伤秒窗/窗口额度耗尽: 本次不反弹 (红线 2)。
        }
        attacker.hurt(victim.level().damageSources().thorns(victim), (float) reflected);
        state.lastThornsTick = nowTick;
    }

    /** 冠军死亡: 摘 per-冠军自效果状态防泄漏 (移速 modifier 随实体销毁自然消失, 无需手摘)。 */
    @SubscribeEvent
    public void onChampionDeath(LivingDeathEvent event) {
        stateByChampion.remove(event.getEntity().getUUID());
    }

    /**
     * 收集该冠军装配的本工程批1自效果词条 (def -> 品质; 无我方 NBT 的命令召冠军走 tier 兜底品质)。星级越界 (脏 NBT
     * 或非我方 1-10★ rank tier 的第三方冠军) 返回空 map 优雅降级 —— 本 handler 每 tick 扫近玩家全部冠军, 不能因单只
     * 越界冠军 StarRank.ofStar 抛断整轮 (与 ChampionAttackHandler 星级范围守卫同口径)。
     */
    private static Map<AffixDef, AffixQuality> collectSelfAffixes(IChampion.Server server, int tier) {
        int star = resolveStar(server, tier);
        if (star < StarRank.MIN_STAR || star > StarRank.MAX_STAR) {
            return Map.of(); // 星级越界: 不施自效果, 不抛。
        }
        StarRank rank = StarRank.ofStar(star);
        CompoundTag affixQualityTag = affixQualityTagOf(server);
        Map<AffixDef, AffixQuality> equipped = new EnumMap<>(AffixDef.class);
        for (IAffix affix : server.getAffixes()) {
            if (!(affix instanceof MiningAffix mining)) {
                continue; // 非本工程词条。
            }
            AffixDef def = mining.def();
            if (!SELF_AFFIXES.contains(def)) {
                continue; // 非批1自效果词条 (减伤/攻击等走各自 handler)。
            }
            equipped.put(def, ChampionAffixState.qualityOf(affixQualityTag, def, rank));
        }
        return equipped;
    }

    /** 幂等挂 MOVEMENT_SPEED MULTIPLY_TOTAL modifier (已挂则跳过)。 */
    private static void ensureSprintModifier(LivingEntity entity, double bonus) {
        AttributeInstance attr = entity.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) {
            return;
        }
        if (attr.getModifier(SPRINT_MODIFIER_UUID) != null) {
            return; // 已挂, 幂等。
        }
        attr.addTransientModifier(new AttributeModifier(
                SPRINT_MODIFIER_UUID, "champion_sprint", bonus, AttributeModifier.Operation.MULTIPLY_TOTAL));
    }

    /** 施一整秒回血: 6★+ 走影子血池 (vanilla 血条由血池 handler 每 tick 镜像); 1-5★ 无池走 vanilla heal。 */
    private static void applyHeal(LivingEntity entity, double healPerSecond) {
        BloodPool pool = BloodPoolRegistry.get(entity.getUUID());
        if (pool != null) {
            pool.heal(healPerSecond);
            return;
        }
        entity.heal((float) healPerSecond);
    }

    /** 再生组织 %maxHP 的有效最大血量基数: 6★+ 血池 maxHp / 1-5★ vanilla maxHealth。 */
    private static double effectiveMaxHp(LivingEntity entity) {
        BloodPool pool = BloodPoolRegistry.get(entity.getUUID());
        if (pool != null) {
            return pool.maxHp();
        }
        return entity.getMaxHealth();
    }

    /** 取冠军星级整数: 优先本工程盖章 star NBT, 缺失 (命令召) 用 rank tier 兜底 (范围校验在 collectSelfAffixes)。 */
    private static int resolveStar(IChampion.Server server, int tier) {
        CompoundTag data = server.getData(ChampionPromoter.DATA_KEY);
        return (data != null && data.contains(ChampionPromoter.NBT_STAR))
                ? data.getInt(ChampionPromoter.NBT_STAR) : tier;
    }

    /** 取 DATA_KEY 主表下 affix_quality 子表 (无 -> null, qualityOf 走 tier 兜底)。 */
    private static CompoundTag affixQualityTagOf(IChampion.Server server) {
        CompoundTag data = server.getData(ChampionPromoter.DATA_KEY);
        if (data == null || !data.contains(ChampionAffixState.NBT_AFFIX_QUALITY)) {
            return null;
        }
        return data.getCompound(ChampionAffixState.NBT_AFFIX_QUALITY);
    }

    /** 某冠军上次受伤 tick (无 state = 从未受伤 = Long.MIN_VALUE)。 */
    private long lastHurtTickOf(UUID championId) {
        SelfState state = stateByChampion.get(championId);
        return state == null ? Long.MIN_VALUE : state.lastHurtTick;
    }

    /** per-冠军自效果状态: 上次受伤 tick (回血门槛) + 上次反震反伤 tick (内 CD)。 */
    private static final class SelfState {
        private long lastHurtTick = Long.MIN_VALUE;
        private long lastThornsTick = Long.MIN_VALUE;
    }
}
