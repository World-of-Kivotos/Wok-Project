package com.miningdim.job.agent.integration;

import com.miningdim.champion.AffixDef;
import com.miningdim.champion.AffixQuality;
import com.miningdim.champion.MiningChampionData;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeModifier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.level.Level;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真封印执行 (SpecialAgent_Job_DesignSpec 六章封印: {@link MiningChampionData#removeAffix} 临时移除少量词条 +
 * 到期增量恢复), 已自研脱离 Champions —— 直接操作自研 {@link MiningChampionData} capability, 不 import 任何
 * top.theillusivec4.champions.*。
 *
 * 增量快照语义 (与旧版"整份原词条集快照 + setAffixes 整份覆盖"的关键区别): 本类只记【被封的那几条】+ 增量还原,
 * 不做整份词条集快照/覆盖。理由: 窗口内 LITTLE_BOY 会自摘一次性词条 ({@link MiningChampionData#removeAffix},
 * {@code ChampionLittleBoyHandler} 在用) —— 若整份还原, 会把该窗口内已消耗的核弹词条重新装回去, 造成可重复
 * 触发的漏洞。增量还原 (只把"本类封印时移除的那几条"合并回当前词条表) 不会动窗口内被其它系统摘除的词条。
 *
 * 线程纪律: 受击/扫描面板/服务端 tick 均服务端主线程串行; ConcurrentHashMap 仅防跨线程读可见性 (与
 * SealRegistry/ContributionTracker 同范式)。
 */
final class AgentSealExecutor {

    private AgentSealExecutor() {
    }

    /** 某精英被封印期间的执行侧快照: 封印时所在维度 + 被移除的词条->品质 + 最晚恢复宽限期 tick。 */
    private record SealedRecord(ResourceKey<Level> dimension, EnumMap<AffixDef, AffixQuality> removed,
                                 long restoreDeadlineTick) {
    }

    /** championUUID -> 当前被封印中的执行侧快照 (恢复源; 增量记录, 非整份词条集拷贝)。 */
    private static final ConcurrentHashMap<UUID, SealedRecord> SEALED = new ConcurrentHashMap<>();

    /**
     * SPRINT/OVERDRIVE/SELF_REPAIR 三条词条在 {@code champion.integration} 侧不是每 tick 从 capability 重算的
     * 即时效果, 而是挂在实体上的常驻 MOVEMENT_SPEED {@link AttributeModifier} ({@code ChampionSelfEffectHandler}
     * 的 ensureSprintModifier/ensureOverdriveModifier, {@code ChampionSelfRepairHandler} 的 rootAndDisarm)。
     * champ.removeAffix 只清 capability, 不摘这条常驻修饰 —— 若不额外处理, 封印会变成纯观感 (面板回 OK、词条真
     * 被摘, 但精英移速一格未变; F024 复核发现, 三个独立复核者均确认)。词条恢复无需对称补挂: 一旦
     * {@link #restoreAffixes} 把词条放回 capability, 前述两个 handler 各自的每 tick 扫描 (1s 周期) 会据
     * capability 现状自动重新挂上对应 modifier。
     *
     * 已知边界 (B09, 与本类另一处已登记限制 —— {@link #sealAffix} 类注释里 "不触 mob.refreshDimensions()" ——
     * 同范式): 这三个 modifier 的固定 UUID 是 champion.integration 包私有常量, 本分支边界不含该包, 无法直接
     * 引用。改用 modifier 的公开 name 字符串 (addTransientModifier 时写入, {@link AttributeModifier#getName}
     * 公开可读) 做匹配摘除 —— 这是名字符串耦合而非类型安全引用, champion.integration 侧若改这三个字面量,
     * 本表会静默失效且无编译期告警。彻底修法是把这三个 UUID 提到双方都能 import 的公共位置 (如 AffixDef 自身
     * 或新增桥接类), 需跨分支协调, 本轮受分支边界约束不做。
     */
    private static final Map<AffixDef, String> STEADY_STATE_MOVEMENT_MODIFIER_NAMES = Map.of(
            AffixDef.SPRINT, "champion_sprint",
            AffixDef.OVERDRIVE, "champion_overdrive",
            AffixDef.SELF_REPAIR, "champion_self_repair_root");

    /**
     * 摘除 def 对应的 champion.integration 常驻 MOVEMENT_SPEED modifier (若当前有挂)。非
     * {@link #STEADY_STATE_MOVEMENT_MODIFIER_NAMES} 收录的词条空操作 (那些词条的效果本就每 tick 从 capability
     * 重算, 摘 capability 词条即时生效, 无需桥接)。见 {@link #STEADY_STATE_MOVEMENT_MODIFIER_NAMES} 类注释。
     */
    private static void stripSteadyStateModifier(LivingEntity target, AffixDef def) {
        String modifierName = STEADY_STATE_MOVEMENT_MODIFIER_NAMES.get(def);
        if (modifierName == null) {
            return;
        }
        AttributeInstance attr = target.getAttribute(Attributes.MOVEMENT_SPEED);
        if (attr == null) {
            return;
        }
        for (AttributeModifier modifier : attr.getModifiers()) {
            if (modifierName.equals(modifier.getName())) {
                attr.removeModifier(modifier);
                break; // 按名字唯一挂载 (两个 handler 均幂等挂一条), 找到即可停。
            }
        }
    }

    /**
     * 对某精英真移除一条目标词条 (在 {@link com.miningdim.job.agent.SealRegistry#applySeal} 占槽成功后调用)。
     * 当前未装配该词条 (被别处改 / 已摘除) 不改实体。
     *
     * 不触 {@code mob.refreshDimensions()}: 体型词条 (GIGANTISM/MINIATURIZATION) 属 {@code AffixPool.SURVIVAL}
     * 池, 按 {@link AgentAffixClassifier#classify} 恒不可封, 封印链路不会出现体型变化, 无需刷新碰撞箱。
     *
     * @param target              目标精英实体
     * @param champ               非 null 的 {@link MiningChampionData} (本工程盖章精英)
     * @param def                 被封词条
     * @param restoreDeadlineTick 恢复宽限期截止 tick (实体暂时找不到时仍保留快照重试的上限)
     * @return 是否真移除了该词条 (true = 当前列表中找到并剔除)
     */
    static boolean sealAffix(LivingEntity target, MiningChampionData champ, AffixDef def, long restoreDeadlineTick) {
        AffixQuality q = champ.quality(def);
        if (q == null) {
            return false; // 当前未装配该词条: 不改实体。
        }
        if (!champ.removeAffix(def)) {
            return false;
        }
        stripSteadyStateModifier(target, def); // F024 复核: SPRINT/OVERDRIVE/SELF_REPAIR 摘 capability 词条不够, 见类注释。
        SEALED.compute(target.getUUID(), (id, existing) -> {
            EnumMap<AffixDef, AffixQuality> removed = existing != null ? existing.removed() : new EnumMap<>(AffixDef.class);
            removed.put(def, q);
            long deadline = existing != null ? Math.max(existing.restoreDeadlineTick(), restoreDeadlineTick) : restoreDeadlineTick;
            return new SealedRecord(target.level().dimension(), removed, deadline);
        });
        return true;
    }

    /**
     * 全部封印到期后增量恢复某精英被封词条 (在 {@link com.miningdim.job.agent.SealRegistry#activeSeals} 判该精英
     * 已无活跃封印时调用)。把快照记录的 (词条->品质) 合并回当前词条表, 重新 {@code promote}。
     *
     * 【必须照做, 严禁漏掉】{@link MiningChampionData#promote} 的最后一行会把 summonedByAffix 复位成 false ——
     * 漏掉"先读 isSummonedByAffix() 后在 promote 后补 markSummonedByAffix()"这两步, 就会把一只被封印过的支援
     * 召唤物变成正常冠军, 从而变成可反复召唤的战斗印钞口 (spec 红线 8-a)。
     *
     * @param target     目标精英实体
     * @param champ      非 null 的 {@link MiningChampionData}
     * @param championId 精英 UUID
     * @return 是否执行了恢复 (true = 有快照并已恢复; false = 无快照, 空操作)
     */
    static boolean restoreAffixes(LivingEntity target, MiningChampionData champ, UUID championId) {
        SealedRecord rec = SEALED.remove(championId);
        if (rec == null) {
            return false; // 无快照 (未被封印过 / 已恢复过): 无可恢复。
        }
        // 不用 EnumMap(Map) 拷贝构造: champ.affixes() 是 Collections.unmodifiableMap 包装 (非 EnumMap 实例),
        // 该构造器对非 EnumMap 来源要求"至少一条映射才能推断键类型", 全部词条已被封印剥空时 (仅剩这一份待
        // 恢复的快照) 会抛 IllegalArgumentException("Specified map is empty")。改用 class 构造 + putAll 规避。
        EnumMap<AffixDef, AffixQuality> merged = new EnumMap<>(AffixDef.class);
        merged.putAll(champ.affixes());
        merged.putAll(rec.removed());
        boolean summoned = champ.isSummonedByAffix();
        champ.promote(champ.star(), merged, champ.effectiveHp());
        if (summoned) {
            champ.markSummonedByAffix(); // 见上方警告: promote 恒复位 summonedByAffix, 此处补盖防召唤物身份丢失。
        }
        return true;
    }

    /**
     * 某精英当前被封印中因而已从词条表移除的词条 (供扫描探针把这些条目重新并进候选表, 否则面板永远看不到
     * "封印中"那一行)。无记录返空 Map。
     *
     * @param championId 精英 UUID
     * @return 不可变视图 (无记录返空 Map)
     */
    static Map<AffixDef, AffixQuality> sealedAffixesOf(UUID championId) {
        SealedRecord rec = SEALED.get(championId);
        if (rec == null) {
            return Collections.emptyMap();
        }
        return Collections.unmodifiableMap(rec.removed());
    }

    /** 某精英封印时所在维度 (到期恢复 tick 据此定位所在 ServerLevel, 而非写死矿洞维度); 无记录返 null。 */
    static ResourceKey<Level> dimensionOf(UUID championId) {
        SealedRecord rec = SEALED.get(championId);
        return rec == null ? null : rec.dimension();
    }

    /** 某精英快照的恢复宽限期截止 tick (无记录返 0, 调用方应先判 snapshotChampions 是否含该 UUID)。 */
    static long restoreDeadlineTick(UUID championId) {
        SealedRecord rec = SEALED.get(championId);
        return rec == null ? 0L : rec.restoreDeadlineTick();
    }

    /** 持快照的精英数 (= 当前处于封印态的精英数; tick 据此判是否需检查到期恢复)。 */
    static int snapshotCount() {
        return SEALED.size();
    }

    /** 精英死亡 / 实例重置时定向清快照 (不触恢复; 实体已亡)。 */
    static void discard(UUID championId) {
        if (championId != null) {
            SEALED.remove(championId);
        }
    }

    /** 服务端停止清空, 防跨存档脏引用 (范式对齐 SealRegistry.reset)。 */
    static void reset() {
        SEALED.clear();
    }

    /** 全部持快照精英的 UUID 视图 (tick 恢复遍历用; 防外改返不可变副本键集合)。 */
    static List<UUID> snapshotChampions() {
        return List.copyOf(SEALED.keySet());
    }
}
