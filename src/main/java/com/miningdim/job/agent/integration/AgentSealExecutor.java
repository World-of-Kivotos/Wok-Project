package com.miningdim.job.agent.integration;

import com.miningdim.job.agent.SealRegistry;
import top.theillusivec4.champions.api.IAffix;
import top.theillusivec4.champions.api.IChampion;
import top.theillusivec4.champions.common.util.ChampionBuilder;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 真封印执行 (SpecialAgent_Job_DesignSpec 六章封印: IChampion.getServer().setAffixes 临时移除少量词条 + 到期恢复
 * 原词条集)。本类是触 Champions 真 API 的封印执行侧, 与纯逻辑账本 {@link SealRegistry} (槽位/窗口/不叠加裁决)
 * 配合: SealRegistry 决定"哪条词条占哪个槽、到期 tick、不叠加"; 本类据其裁决真改实体词条列表并持有恢复所需的
 * 原词条快照。
 *
 * 原词条快照权威 (调研铁律: 防第二次并发封印重读已削弱列表丢原集): 某精英首次被封印时, 本类对
 * {@code IChampion.getServer().getAffixes()} 做一次性防御性拷贝存为该精英的"原词条集"(restore source);
 * 此后该精英窗口内的任何后续封印只增量从"当前实体词条列表"移除目标词条, 绝不重读 getAffixes 覆盖原集。
 * 全部封印到期、该精英无活跃封印时, 一次性 setAffixes(原集) 恢复并清快照。
 *
 * 真改链路 (六章 + 调研 ChampionBuilder.resetAndUpdate):
 *  - 移除: getAffixes 防御拷贝 -> 按 affixId 剔除目标 IAffix -> setAffixes(削弱列表) -> ChampionBuilder.resetAndUpdate
 *    (丢弃被移除词条的 AttributeModifier 并重同步)。
 *  - 恢复: setAffixes(原集快照) -> ChampionBuilder.resetAndUpdate (重挂原词条的 AttributeModifier)。
 *
 * 线程纪律: 受击/扫描面板/服务端 tick 均服务端主线程串行; ConcurrentHashMap 仅防跨线程读可见性 (与
 * SealRegistry/ContributionTracker 同范式)。
 *
 * compileOnly 隔离: 本类 import top.theillusivec4.champions.* —— 仅 ModList 守卫下经 {@link AgentIntegrationBootstrap}
 * 触达。dev 不加载, 故封印真改的正确性 (移除/恢复/AttributeModifier 重挂) 须正式服验 (见交付 deferred)。
 */
final class AgentSealExecutor {

    private AgentSealExecutor() {
    }

    /** championUUID -> 首次封印时捕获的原词条集快照 (恢复源; 不可变, 一次性捕获后窗口内不更新)。 */
    private static final ConcurrentHashMap<UUID, List<IAffix>> ORIGINAL_AFFIXES = new ConcurrentHashMap<>();

    /**
     * 对某精英真移除一条目标词条 (在 {@link SealRegistry#applySeal} 占槽成功后调用)。首次封印捕获原词条集快照,
     * 据 affixId 从当前实体词条列表剔除目标后写回 + resetAndUpdate。
     *
     * @param champion   非 null 的 IChampion (本工程盖章精英)
     * @param championId 精英 UUID (快照键)
     * @param affixId    被封词条全限定注册名 (与 SealRegistry 账本同口径; {@link AgentAffixClassifier#affixId})
     * @return 是否真移除了该词条 (true = 列表中找到并剔除; false = 当前列表无此词条, 不改实体)
     */
    static boolean sealAffix(IChampion champion, UUID championId, String affixId) {
        // 首次封印: 捕获原词条集快照 (防御拷贝, 恢复源)。computeIfAbsent 保证窗口内只捕获一次。
        ORIGINAL_AFFIXES.computeIfAbsent(championId,
                id -> new ArrayList<>(champion.getServer().getAffixes()));

        List<IAffix> current = new ArrayList<>(champion.getServer().getAffixes());
        boolean removed = current.removeIf(a -> affixId.equals(AgentAffixClassifier.affixId(a)));
        if (!removed) {
            return false; // 当前列表已无此词条 (被别处改/已剔): 不改实体。
        }
        champion.getServer().setAffixes(current);
        ChampionBuilder.resetAndUpdate(champion);
        return true;
    }

    /**
     * 全部封印到期后恢复某精英原词条集 (在 {@link SealRegistry#activeSeals}/{@code drainExpired} 判该精英已无活跃
     * 封印时调用)。一次性 setAffixes(原集快照) + resetAndUpdate 重挂被移除词条的 AttributeModifier, 并清快照。
     * 无快照 (从未封印过该精英) 时空操作。
     *
     * @param champion   非 null 的 IChampion
     * @param championId 精英 UUID
     * @return 是否执行了恢复 (true = 有快照并已恢复; false = 无快照, 空操作)
     */
    static boolean restoreAffixes(IChampion champion, UUID championId) {
        List<IAffix> original = ORIGINAL_AFFIXES.remove(championId);
        if (original == null) {
            return false; // 从未封印过: 无可恢复。
        }
        champion.getServer().setAffixes(new ArrayList<>(original));
        ChampionBuilder.resetAndUpdate(champion);
        return true;
    }

    /** 持快照的精英数 (= 当前处于封印态的精英数; tick 据此判是否需检查到期恢复, 与 SealRegistry.trackedChampionCount 应同步)。 */
    static int snapshotCount() {
        return ORIGINAL_AFFIXES.size();
    }

    /** 精英死亡 / 实例重置时定向清快照 (不触恢复; 实体已亡)。 */
    static void discard(UUID championId) {
        if (championId != null) {
            ORIGINAL_AFFIXES.remove(championId);
        }
    }

    /** 服务端停止清空, 防跨存档脏引用 (范式对齐 SealRegistry.reset)。 */
    static void reset() {
        ORIGINAL_AFFIXES.clear();
    }

    /** 全部持快照精英的 UUID 视图 (tick 恢复遍历用; 防外改返不可变副本键集合)。 */
    static List<UUID> snapshotChampions() {
        return new ArrayList<>(ORIGINAL_AFFIXES.keySet());
    }
}
