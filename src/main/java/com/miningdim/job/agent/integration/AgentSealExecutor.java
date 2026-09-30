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
 * 恢复源 = 精英自身 capability: 被封的那几条 (词条->原品质) 在摘除的同一刻经 {@link MiningChampionData#recordSealedAffix}
 * 写穿到冠军 capability, 随实体 NBT 存盘、跨维度、随区块卸载重载。旧版只把它记在本类的进程内存表里, 两条路径会把
 * 词条永久剥掉 (违反六/九章"绝不永久削废"): 封印窗口内服务端重启 -> 内存表清空, 词条蒸发; 封完离开让区块卸载超过
 * 恢复宽限期 -> 快照被丢弃, 词条蒸发。两种情况下精英都保留原星级与按初始星级计的奖励池, 等于永久削弱却照付全价。
 *
 * 本类仍保留一张进程内 {@link #TRACKED} 索引 (championUUID -> 封印时维度 + 索引保留截止 tick), 只为让
 * {@link AgentSealHandler#processExpiredSeals} 每 tick 知道去哪个维度找哪些精英、在封印到期的那一 tick 精确恢复。索引
 * 丢了 (服务端重启 / 过了宽限期) 不再等于词条丢了: 精英下次入世或 tick 时由 {@link AgentSealHandler#reconcilePersistedSeal}
 * 按 capability 对账恢复。
 *
 * 增量快照语义 (与旧版"整份原词条集快照 + setAffixes 整份覆盖"的关键区别): 只记【被封的那几条】+ 增量还原,
 * 不做整份词条集快照/覆盖。理由: 窗口内 LITTLE_BOY 会自摘一次性词条 ({@link MiningChampionData#removeAffix},
 * {@code ChampionLittleBoyHandler} 在用) —— 若整份还原, 会把该窗口内已消耗的核弹词条重新装回去, 造成可重复
 * 触发的漏洞。增量还原 (只把"封印时移除的那几条"合并回当前词条表) 不会动窗口内被其它系统摘除的词条。
 *
 * 线程纪律: 受击/扫描面板/服务端 tick/实体入世均服务端主线程串行; ConcurrentHashMap 仅防跨线程读可见性 (与
 * SealRegistry/ContributionTracker 同范式)。
 */
final class AgentSealExecutor {

    private AgentSealExecutor() {
    }

    /** 某精英的封印 tick 索引项: 所在维度 (到期 tick 据此定位实体) + 索引保留截止 tick。不含恢复源, 恢复源在 capability。 */
    private record TrackedSeal(ResourceKey<Level> dimension, long restoreDeadlineTick) {
    }

    /** championUUID -> 封印 tick 索引 (只是"该去哪找谁"的内存索引; 被封词条的真相在冠军 capability)。 */
    private static final ConcurrentHashMap<UUID, TrackedSeal> TRACKED = new ConcurrentHashMap<>();

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
     * 当前未装配该词条 (被别处改 / 已摘除) 不改实体。移除的同时把 (词条->原品质) 写穿到 capability 的被封词条登记
     * (恢复源), 再登记 tick 索引。
     *
     * 不触 {@code mob.refreshDimensions()}: 体型词条 (GIGANTISM/MINIATURIZATION) 属 {@code AffixPool.SURVIVAL}
     * 池, 按 {@link AgentAffixClassifier#classify} 恒不可封, 封印链路不会出现体型变化, 无需刷新碰撞箱。
     *
     * @param target              目标精英实体
     * @param champ               非 null 的 {@link MiningChampionData} (本工程盖章精英)
     * @param def                 被封词条
     * @param restoreDeadlineTick 索引保留截止 tick (实体暂时找不到时 tick 仍按索引重试的上限; 过了只删索引, 不丢词条)
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
        // 与 removeAffix 同一 tick 写穿恢复源: 此后任何存盘都同时带着"词条已摘"与"原品质登记", 不会只存下前者。
        champ.recordSealedAffix(def, q);
        stripSteadyStateModifier(target, def); // F024 复核: SPRINT/OVERDRIVE/SELF_REPAIR 摘 capability 词条不够, 见类注释。
        track(target.getUUID(), target.level().dimension(), restoreDeadlineTick);
        return true;
    }

    /**
     * 登记 (或刷新) 某精英的封印 tick 索引: 维度取最新值 (跨维度后以新维度为准), 截止 tick 取两者较晚者 (8★+ 两槽
     * 先后封印时不被后一次缩短)。封印时与实体带着被封词条重新入世且窗口未过时调用。
     */
    static void track(UUID championId, ResourceKey<Level> dimension, long restoreDeadlineTick) {
        TRACKED.merge(championId, new TrackedSeal(dimension, restoreDeadlineTick),
                (existing, fresh) -> new TrackedSeal(fresh.dimension(),
                        Math.max(existing.restoreDeadlineTick(), fresh.restoreDeadlineTick())));
    }

    /**
     * 全部封印到期后增量恢复某精英被封词条 (到期 tick 或入世/tick 对账判该精英已无活跃封印时调用)。从 capability
     * 取走被封词条登记 ({@link MiningChampionData#takeSealedAffixes}, 取走即清), 合并回当前词条表, 经
     * {@link MiningChampionData#replaceAffixes} 只换词条表; 同时删掉 tick 索引。取走即清保证多条恢复路径谁先到谁恢复,
     * 后到者空转, 不会重复合并。
     *
     * 【严禁改回 promote】{@link MiningChampionData#promote} 是重新盖章: 会把 summonedByAffix 与 worldBoss 两个身份
     * 标记复位成 false、把当前血量回满。拿它做恢复, 被封印过的支援召唤物会变成可反复召唤的发奖冠军 (spec 红线 8-a),
     * 被封印过的世界 BOSS 会丢掉世界 BOSS 身份 (击倒公告、成就击杀过滤与 NBT 标记从此失效; 封印窗口远短于一场世界
     * BOSS 战, 第一次封印到期即触发)。
     *
     * @param target 目标精英实体
     * @param champ  非 null 的 {@link MiningChampionData}
     * @return 是否执行了恢复 (true = capability 有被封词条并已放回; false = 无登记, 空操作)
     */
    static boolean restoreAffixes(LivingEntity target, MiningChampionData champ) {
        TRACKED.remove(target.getUUID());
        Map<AffixDef, AffixQuality> removed = champ.takeSealedAffixes();
        if (removed.isEmpty()) {
            return false; // 无登记 (未被封印过 / 已由另一条路径恢复过): 无可恢复。
        }
        // 不用 EnumMap(Map) 拷贝构造: champ.affixes() 是 Collections.unmodifiableMap 包装 (非 EnumMap 实例),
        // 该构造器对非 EnumMap 来源要求"至少一条映射才能推断键类型", 全部词条已被封印剥空时 (仅剩这一份待
        // 恢复的登记) 会抛 IllegalArgumentException("Specified map is empty")。改用 class 构造 + putAll 规避。
        EnumMap<AffixDef, AffixQuality> merged = new EnumMap<>(AffixDef.class);
        merged.putAll(champ.affixes());
        merged.putAll(removed);
        champ.replaceAffixes(merged); // 见上方警告: 只换词条表, 身份标记与当前血量原样保留。
        return true;
    }

    /** 某精英是否在 tick 索引中 (在册的由到期 tick 精确恢复, tick 兜底对账不插手)。 */
    static boolean isTracked(UUID championId) {
        return championId != null && TRACKED.containsKey(championId);
    }

    /** 某精英封印时 (或最近一次带封印入世时) 所在维度 (到期恢复 tick 据此定位 ServerLevel); 不在册返 null。 */
    static ResourceKey<Level> dimensionOf(UUID championId) {
        TrackedSeal rec = TRACKED.get(championId);
        return rec == null ? null : rec.dimension();
    }

    /** 某精英索引的保留截止 tick (不在册返 0, 调用方应先判 trackedChampions 是否含该 UUID)。 */
    static long restoreDeadlineTick(UUID championId) {
        TrackedSeal rec = TRACKED.get(championId);
        return rec == null ? 0L : rec.restoreDeadlineTick();
    }

    /** 在册精英数 (tick 据此判是否需检查到期恢复)。 */
    static int trackedCount() {
        return TRACKED.size();
    }

    /**
     * 只删某精英的 tick 索引, 不动 capability (精英死亡 / 过了宽限期仍找不到实体时调用)。死亡时 capability 随实体
     * 一起消亡; 找不到实体时被封词条仍留在 capability 里, 精英下次入世或 tick 时由对账恢复。
     */
    static void untrack(UUID championId) {
        if (championId != null) {
            TRACKED.remove(championId);
        }
    }

    /** 服务端停止清空 tick 索引, 防跨存档脏引用 (范式对齐 SealRegistry.reset)。被封词条在各精英 capability 里随存档走。 */
    static void reset() {
        TRACKED.clear();
    }

    /** 全部在册精英的 UUID 视图 (tick 恢复遍历用; 防外改返不可变副本键集合)。 */
    static List<UUID> trackedChampions() {
        return List.copyOf(TRACKED.keySet());
    }
}
