package com.miningdim.job.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 每精英活跃封印状态机纯逻辑 (SpecialAgent_Job_DesignSpec 六章 + 九章防叠叠乐): 维护 championUUID ->
 * 已封词条 + 到期 tick + 槽占用, 强制 (a) 窗口到期恢复 (b) 多干员不叠加 (每精英 1 槽, 8★+ 2 槽, 先到先得,
 * 取最强单份/互斥)。
 *
 * 防叠叠乐铁律 (九章 + 用户硬约束): 同一精英即使多干员在场, 封印不因人数叠加 —— 槽位是精英自身的固定容量
 * (1 槽 / 8★+ 2 槽), 槽满后第二人封印被拒 (槽已占), 绝不因人数增多延长窗口或多封词条。已占槽到期恢复后释放,
 * 可再被任意干员争抢 (先到先得)。
 *
 * 纯逻辑账本 (范式对齐 {@link com.miningdim.champion.reward.ContributionTracker}): 只持 UUID + tick + 词条
 * 标识 (String affixId, 与集成层 IAffix 注册名同口径), 不碰 ServerPlayer/IChampion/世界, dev GameTest 触达安全。
 * 真封印执行 (setAffixes 移除/恢复) 由集成层据本账本的 active/expired 裁决调用; 本层只做槽位 + 窗口 + 不叠加
 * 的纯账目裁决。
 *
 * 线程纪律: 受击/扫描/死亡均服务端主线程串行; ConcurrentHashMap 仅防跨线程读可见性。
 */
public final class SealRegistry {

    private SealRegistry() {
    }

    /** championUUID -> 该精英当前活跃封印列表 (槽位)。 */
    private static final ConcurrentHashMap<UUID, List<ActiveSeal>> LEDGER = new ConcurrentHashMap<>();

    /**
     * 单个活跃封印 (一个槽位的占用): 谁封的 (ownerUUID) + 封了哪条词条 (affixId) + 类别 + 到期 tick。
     * affixId 与集成层 IAffix 注册名同口径 (集成层据此从 getAffixes 列表移除/恢复对应词条)。
     */
    public static final class ActiveSeal {
        private final UUID ownerUuid;
        private final String affixId;
        private final SealCategory category;
        private final long expiryTick;

        ActiveSeal(UUID ownerUuid, String affixId, SealCategory category, long expiryTick) {
            this.ownerUuid = ownerUuid;
            this.affixId = affixId;
            this.category = category;
            this.expiryTick = expiryTick;
        }

        public UUID ownerUuid() {
            return ownerUuid;
        }

        public String affixId() {
            return affixId;
        }

        public SealCategory category() {
            return category;
        }

        public long expiryTick() {
            return expiryTick;
        }

        boolean isActive(long nowTick) {
            return nowTick < expiryTick;
        }
    }

    /** 封印申请结果 (不可变值对象): 成功带到期 tick, 失败带原因。 */
    public record ApplyResult(boolean ok, long expiryTick, FailReason reason) {

        static ApplyResult success(long expiryTick) {
            return new ApplyResult(true, expiryTick, null);
        }

        static ApplyResult fail(FailReason reason) {
            return new ApplyResult(false, 0L, reason);
        }
    }

    /** 封印申请失败原因 (六章面板失败提示)。 */
    public enum FailReason {
        /** 全部槽位已被占 (槽已占; 防叠叠乐核心拒绝点)。 */
        ALL_SLOTS_OCCUPIED,
        /** 该词条已被封印中 (互斥: 同一词条不重复封, 不因第二人再封而延长)。 */
        AFFIX_ALREADY_SEALED
    }

    /**
     * 申请对某精英封印一条词条 (先经 {@link SealPlan} 校验等级/星级/类别门通过后调本层做槽位/不叠加裁决)。
     *
     * 不叠加裁决 (九章核心):
     *  1. 先清除该精英所有已到期封印 (释放槽位)。
     *  2. 若该 affixId 已在活跃封印中 (任意人封的) -> 拒 (AFFIX_ALREADY_SEALED; 互斥, 不因第二人再封延长)。
     *  3. 若活跃封印数 >= 槽容量 (sealSlots(level,star)) -> 拒 (ALL_SLOTS_OCCUPIED; 槽满)。
     *  4. 否则占一槽, 记 ActiveSeal (owner + affixId + category + 到期 tick = now + 窗口)。
     *
     * 槽容量取该精英的固定容量 (不因在场干员人数变化): sealSlots(agentLevel, star) (1 槽 / 8★+L9 = 2 槽)。
     *
     * @param championId 精英 UUID
     * @param ownerUuid  申请封印的干员 UUID
     * @param affixId    目标词条注册名 (与集成层 IAffix 同口径)
     * @param category   词条类别 (被动/机制; 决定窗口时长来源)
     * @param agentLevel 干员等级 (定槽容量 + 窗口时长)
     * @param star       精英初始星级 (定槽容量: 8★+ 2 槽)
     * @param nowTick    当前 gameTime
     * @return 成功带到期 tick; 失败带原因 (槽已占 / 词条已封)
     */
    public static ApplyResult applySeal(UUID championId, UUID ownerUuid, String affixId,
                                        SealCategory category, int agentLevel, int star, long nowTick) {
        if (championId == null || ownerUuid == null || affixId == null || category == null) {
            throw new IllegalArgumentException("championId/ownerUuid/affixId/category must not be null");
        }
        List<ActiveSeal> slots = LEDGER.computeIfAbsent(championId, id -> new ArrayList<>());

        // 1. 清到期封印 (释放槽)。
        slots.removeIf(s -> !s.isActive(nowTick));

        // 2. 互斥: 同一词条已封中则拒 (不因第二人再封延长/叠加)。
        for (ActiveSeal s : slots) {
            if (s.affixId().equals(affixId)) {
                return ApplyResult.fail(FailReason.AFFIX_ALREADY_SEALED);
            }
        }

        // 3. 槽容量门 (精英固定容量, 不随在场人数变化)。
        int capacity = AgentSkillTable.sealSlots(agentLevel, star);
        if (slots.size() >= capacity) {
            return ApplyResult.fail(FailReason.ALL_SLOTS_OCCUPIED);
        }

        // 4. 占槽。窗口时长按申请者等级 + 类别 (取最强单份: 由申请者自身能力决定, 不叠加多人)。
        int windowSeconds = AgentSkillTable.sealWindowSeconds(AgentSkillTable.clampLevel(agentLevel), category);
        long expiry = nowTick + (long) windowSeconds * 20L; // 秒 -> tick (原版 20 tick/s)。
        slots.add(new ActiveSeal(ownerUuid, affixId, category, expiry));
        return ApplyResult.success(expiry);
    }

    /**
     * 取出某精英当前 (nowTick 时刻) 仍活跃的封印列表 (先剔除已到期), 供集成层判定哪些词条钩子该短路。
     * 同时把到期封印从账本清除 (释放槽 + 触发集成层恢复路径)。
     *
     * @param championId 精英 UUID
     * @param nowTick    当前 gameTime
     * @return 活跃封印列表 (空 = 无封印或全到期; 不可变快照副本, 调用方不污染账本)
     */
    public static List<ActiveSeal> activeSeals(UUID championId, long nowTick) {
        if (championId == null) {
            return List.of();
        }
        List<ActiveSeal> slots = LEDGER.get(championId);
        if (slots == null) {
            return List.of();
        }
        slots.removeIf(s -> !s.isActive(nowTick));
        if (slots.isEmpty()) {
            LEDGER.remove(championId);
            return List.of();
        }
        return new ArrayList<>(slots); // 副本: 防外部改账本。
    }

    /**
     * 取出某精英在 nowTick 已到期、需集成层恢复词条的封印 (并从账本清除释放槽)。集成层据此调 setAffixes 恢复
     * 原词条 / 重加常驻 AttributeModifier (六章 teardown 自写)。
     *
     * @param championId 精英 UUID
     * @param nowTick    当前 gameTime
     * @return 本 tick 刚到期需恢复的封印列表 (按加入顺序)
     */
    public static List<ActiveSeal> drainExpired(UUID championId, long nowTick) {
        if (championId == null) {
            return List.of();
        }
        List<ActiveSeal> slots = LEDGER.get(championId);
        if (slots == null) {
            return List.of();
        }
        List<ActiveSeal> expired = new ArrayList<>();
        slots.removeIf(s -> {
            if (!s.isActive(nowTick)) {
                expired.add(s);
                return true;
            }
            return false;
        });
        if (slots.isEmpty()) {
            LEDGER.remove(championId);
        }
        return expired;
    }

    /** 某精英当前活跃封印槽占用数 (先剔除到期; 诊断/测试用)。 */
    public static int activeSealCount(UUID championId, long nowTick) {
        return activeSeals(championId, nowTick).size();
    }

    /** 某词条当前是否被封印中 (集成层词条钩子生效前判 sealed -> 短路, 10.2)。 */
    public static boolean isAffixSealed(UUID championId, String affixId, long nowTick) {
        if (championId == null || affixId == null) {
            return false;
        }
        for (ActiveSeal s : activeSeals(championId, nowTick)) {
            if (s.affixId().equals(affixId)) {
                return true;
            }
        }
        return false;
    }

    /** 精英死亡 / 实例重置时定向清除该精英全部封印态 (不触发恢复; 实体已亡)。 */
    public static void discard(UUID championId) {
        if (championId != null) {
            LEDGER.remove(championId);
        }
    }

    /** 服务端停止清空, 防跨存档脏引用 (范式对齐 ContributionTracker.reset)。 */
    public static void reset() {
        LEDGER.clear();
    }

    /** 当前账本中持封印态的精英数 (诊断/测试用)。 */
    public static int trackedChampionCount() {
        return LEDGER.size();
    }
}
