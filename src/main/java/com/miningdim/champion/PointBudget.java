package com.miningdim.champion;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 四池点数预算分配器纯逻辑 (ChampionStarAffix spec 第四章双层平衡模型 + 第八章互斥矩阵 + 第十四章实现拆分 1)。
 *
 * 给定星级 {@link StarRank} 与一组词条选择 {@link AffixSelection}, 本类做两件事:
 *  (1) 校验装配合法性 (四池点数不超预算 + 总词条上限 + 技能数上限 + 品质随星解锁 + 最低星 + 互斥矩阵);
 *  (2) 计算各池买完词条后的剩余点数 → 基础属性膨胀 (生存剩→血/减伤, 战斗剩→伤害, 机动剩→移速,
 *      技能池剩余作废不转膨胀, spec 第四章)。
 *
 * 纯函数/不可变结果, 无世界引用、不碰 Champions —— spawn 期分配器据此盖章 (b 阶段接线), GameTest 直接断言。
 * 校验失败一律抛 {@link IllegalArgumentException} 自然冒泡 (异常必须痛, 不静默丢词条): 调用方 (spawn roller)
 * 在 roll 阶段就该保证候选集合法, 把非法组合喂进来是装配 bug 而非业务空值。
 *
 * 互斥语义 (spec 第八章):
 *  - 同族至多一: MOVE_SPEED (高速/超速)、SIZE (巨大化/缩小化)、MULTI_STRIKE (双倍/四倍)、DEATH_MARK (命定/反击)。
 *  - 传送家族 TELEPORT_FAMILY 跨池全局同时 ≤2 (凯撒/利刃虽归技能也计入)。
 *  - 重型护甲 ⨉ 全部机动 + 偏斜 + 刚毅 (单向跨族禁配)。
 *  - 巨大化 ⨉ 全部机动。
 *  - 缩小化 强制 +1 机动 (仅最低档); 本校验只断言"若有缩小化则必有一条机动词条", 不替调用方补词条。
 */
public final class PointBudget {

    private PointBudget() {
    }

    /** 传送家族跨池全局同时上限 (spec 第八章: ≤2)。 */
    public static final int TELEPORT_FAMILY_GLOBAL_CAP = 2;

    /**
     * 校验并结算一组词条选择对某星级的预算分配。合法则返回 {@link Allocation} (各池花费/剩余 + 膨胀); 非法抛
     * IllegalArgumentException。
     *
     * @param rank       星级
     * @param selections 词条选择列表 (顺序无关; 同一词条不应重复, 重复按多条计入成本与上限)
     * @return 分配结果 (各池剩余点数 + 基础属性膨胀)
     */
    public static Allocation allocate(StarRank rank, List<AffixSelection> selections) {
        if (rank == null) {
            throw new IllegalArgumentException("rank must not be null");
        }
        if (selections == null) {
            throw new IllegalArgumentException("selections must not be null");
        }

        validateCounts(rank, selections);
        validateUnlock(rank, selections);
        validateMutex(selections);

        Map<AffixPool, Integer> spent = new EnumMap<>(AffixPool.class);
        for (AffixPool pool : AffixPool.values()) {
            spent.put(pool, 0);
        }
        for (AffixSelection sel : selections) {
            AffixPool pool = sel.affix().pool();
            spent.merge(pool, sel.cost(), Integer::sum);
        }

        Map<AffixPool, Integer> remaining = new EnumMap<>(AffixPool.class);
        for (AffixPool pool : AffixPool.values()) {
            int budget = rank.budgetFor(pool);
            int used = spent.get(pool);
            if (used > budget) {
                throw new IllegalArgumentException(
                        "pool " + pool + " over budget: spent " + used + " > budget " + budget
                                + " (star " + rank.star() + ")");
            }
            remaining.put(pool, budget - used);
        }

        return new Allocation(rank, spent, remaining);
    }

    /** 总词条上限 + 技能数上限校验 (spec 第五章)。 */
    private static void validateCounts(StarRank rank, List<AffixSelection> selections) {
        if (selections.size() > rank.maxAffixes()) {
            throw new IllegalArgumentException(
                    "affix count " + selections.size() + " > cap " + rank.maxAffixes()
                            + " (star " + rank.star() + ")");
        }
        int skills = 0;
        for (AffixSelection sel : selections) {
            if (sel.affix().isSkill()) {
                skills++;
            }
        }
        if (skills > rank.maxSkills()) {
            throw new IllegalArgumentException(
                    "skill count " + skills + " > skill cap " + rank.maxSkills()
                            + " (star " + rank.star() + ")");
        }
    }

    /** 最低星 + 品质随星解锁校验 (spec 第四/七章): 每条词条须满足最低★ 且品质 ≤该星最高品质且 ≥词条最低可用档。 */
    private static void validateUnlock(StarRank rank, List<AffixSelection> selections) {
        for (AffixSelection sel : selections) {
            AffixDef affix = sel.affix();
            if (rank.star() < affix.minStar()) {
                throw new IllegalArgumentException(
                        affix + " min star " + affix.minStar() + " > rank star " + rank.star());
            }
            if (sel.quality().ordinal() > rank.maxQuality().ordinal()) {
                throw new IllegalArgumentException(
                        affix + " quality " + sel.quality() + " > star max quality " + rank.maxQuality()
                                + " (star " + rank.star() + ")");
            }
        }
    }

    /** 互斥矩阵校验 (spec 第八章)。 */
    private static void validateMutex(List<AffixSelection> selections) {
        Map<AffixDef.MutexFlag, Integer> flagCount = new EnumMap<>(AffixDef.MutexFlag.class);
        int teleportFamily = 0;
        boolean hasHeavyArmor = false;
        boolean hasFortitude = false;
        boolean hasDeflector = false;
        boolean hasGigantism = false;
        boolean hasMiniaturization = false;
        boolean hasAnyMobility = false;

        for (AffixSelection sel : selections) {
            AffixDef affix = sel.affix();
            AffixDef.MutexFlag flag = affix.mutexFlag();
            flagCount.merge(flag, 1, Integer::sum);

            if (flag == AffixDef.MutexFlag.TELEPORT_FAMILY) {
                teleportFamily++;
            }
            if (affix == AffixDef.HEAVY_ARMOR) {
                hasHeavyArmor = true;
            }
            if (affix == AffixDef.FORTITUDE_SHIELD) {
                hasFortitude = true;
            }
            if (affix == AffixDef.DEFLECTOR_SHIELD) {
                hasDeflector = true;
            }
            if (affix == AffixDef.GIGANTISM) {
                hasGigantism = true;
            }
            if (affix == AffixDef.MINIATURIZATION) {
                hasMiniaturization = true;
            }
            if (affix.pool() == AffixPool.MOBILITY) {
                hasAnyMobility = true;
            }
        }

        // 同族至多一: MOVE_SPEED / SIZE / MULTI_STRIKE / DEATH_MARK。
        requireAtMostOne(flagCount, AffixDef.MutexFlag.MOVE_SPEED, "高速移动/超速移动");
        requireAtMostOne(flagCount, AffixDef.MutexFlag.SIZE, "巨大化/缩小化");
        requireAtMostOne(flagCount, AffixDef.MutexFlag.MULTI_STRIKE, "双倍打击/四倍痛处");
        requireAtMostOne(flagCount, AffixDef.MutexFlag.DEATH_MARK, "命定之死/反击单元");

        // 传送家族跨池全局同时 ≤2。
        if (teleportFamily > TELEPORT_FAMILY_GLOBAL_CAP) {
            throw new IllegalArgumentException(
                    "teleport family count " + teleportFamily + " > cap " + TELEPORT_FAMILY_GLOBAL_CAP);
        }

        // 重型护甲 ⨉ 全部机动 + 偏斜 + 刚毅。
        if (hasHeavyArmor && hasAnyMobility) {
            throw new IllegalArgumentException("重型护甲 互斥全部机动词条");
        }
        if (hasHeavyArmor && hasDeflector) {
            throw new IllegalArgumentException("重型护甲 互斥偏斜护盾");
        }
        if (hasHeavyArmor && hasFortitude) {
            throw new IllegalArgumentException("重型护甲 互斥刚毅护盾");
        }

        // 巨大化 ⨉ 全部机动。
        if (hasGigantism && hasAnyMobility) {
            throw new IllegalArgumentException("巨大化 互斥全部机动词条");
        }

        // 缩小化 强制 +1 机动 (须至少一条机动词条占池, spec 第八章)。
        if (hasMiniaturization && !hasAnyMobility) {
            throw new IllegalArgumentException("缩小化 强制 +1 机动: 须至少装配一条机动词条");
        }
    }

    private static void requireAtMostOne(Map<AffixDef.MutexFlag, Integer> counts,
                                         AffixDef.MutexFlag flag, String label) {
        Integer n = counts.get(flag);
        if (n != null && n > 1) {
            throw new IllegalArgumentException("互斥族 [" + label + "] 至多取一, 实得 " + n);
        }
    }

    /**
     * 分配结果 (不可变): 各池花费/剩余点数 + 剩余点换算的基础属性膨胀。膨胀换算系数 (剩余点 → 血/伤/移速)
     * spec 第六章自述"随星级非线性 (config 暴露)", 当前未给定具体曲线属 PENDING (spec 第十三章), 故本结果
     * 只暴露"各池剩余点数"原始值, 不写死换算系数 —— b 阶段接 config 曲线再折算成具体膨胀, 避免编造数值。
     * 技能池剩余点数恒按 0 暴露 (spec 第四章: 技能池不转膨胀)。
     */
    public static final class Allocation {

        private final StarRank rank;
        private final Map<AffixPool, Integer> spent;
        private final Map<AffixPool, Integer> remaining;

        Allocation(StarRank rank, Map<AffixPool, Integer> spent, Map<AffixPool, Integer> remaining) {
            this.rank = rank;
            this.spent = new EnumMap<>(spent);
            this.remaining = new EnumMap<>(remaining);
        }

        public StarRank rank() {
            return rank;
        }

        /** 某池已花点数。 */
        public int spent(AffixPool pool) {
            return spent.get(pool);
        }

        /** 某池剩余点数 (买完词条后)。 */
        public int remaining(AffixPool pool) {
            return remaining.get(pool);
        }

        /**
         * 某池可转换成基础膨胀的剩余点数: 生存/战斗/机动 = 剩余点数原值; 技能池恒 0 (spec 第四章: 技能池剩余
         * 作废不转膨胀)。具体膨胀曲线 (点 → 血/伤/移速) 由 b 阶段 config 折算, 本法只给"可转换的点数"。
         */
        public int convertibleRemainder(AffixPool pool) {
            if (!pool.convertsRemainderToBaseStats()) {
                return 0;
            }
            return remaining.get(pool);
        }

        @Override
        public String toString() {
            List<String> parts = new ArrayList<>();
            for (AffixPool pool : AffixPool.values()) {
                parts.add(pool + "(spent=" + spent.get(pool) + ",rem=" + remaining.get(pool) + ")");
            }
            return "Allocation[star=" + rank.star() + "," + String.join(",", parts) + "]";
        }
    }
}
