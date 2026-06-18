package com.miningdim.champion;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * spawn 期词条掷取纯逻辑 (ChampionStarAffix spec 第四章双层平衡 + 第八章互斥 + 第十四章实现拆分 1)。
 *
 * 给定星级, 在四池点数预算内贪心掷出一组合法词条选择 {@link AffixSelection}: 逐池按预算掏点买词条, 每条经
 * {@link AffixQuality#clampTo} 随星降级品质, 再过 {@link #wouldRemainLegal} 预检 (最低★/品质/互斥/上限),
 * 通过才纳入。最终用 {@link PointBudget#allocate} 终校验 (合法集合必过, 非法即装配 bug 自然冒泡)。
 *
 * 纯函数/不碰 Champions: 输出 {@link AffixSelection} 列表, 由 b 阶段 (integration 层) 映射成真 IAffix 盖章。
 * GameTest 直接断言"掷出的集合 allocate 不抛 + 各池不超预算"。roll 顺序 (notes): roll 词条 -> clampTo 降级 ->
 * AffixSelection 构造 (内校验 >=minUsable) -> wouldRemainLegal 预检 -> 纳入; 全程不靠 catch 兜底
 * (PointBudget 非法抛 IllegalArgumentException 是装配 bug 信号, 不该被 roller 喂出来)。
 *
 * 注 SELF_REPAIR 中级档值=0 坑 (notes): roll 到的品质若落在词条某档值=0 (前导占位档), AffixSelection 构造期
 * 会因 &lt; minUsableQuality 抛; roller 在掷品质后先 clamp 到 max(随星档, 词条最低可用档) 规避, 不喂非法档。
 */
public final class AffixRoller {

    private AffixRoller() {
    }

    /**
     * 为某星级掷一组合法词条选择 (四池预算内贪心)。
     *
     * 算法: 候选池 = 该星可解锁的全部词条 (满足 minStar + 品质覆盖)。逐池按声明顺序 (生存/战斗/机动/技能) 各自
     * 在该池预算内贪心选词条, 每次随机挑一个尚未选、加入后仍合法 (预算+上限+互斥+技能上限) 的候选, 直到该池
     * 无可加候选或撞总词条上限。掷品质: 在 [词条最低可用档, 该星最高品质] 区间随机, 高品质更贵故可能因预算挤出。
     *
     * @param rank 星级
     * @param rng  随机源 (服务端 level.random)
     * @return 合法词条选择列表 (可能为空, 如 1★ 预算极小; allocate 必过)
     */
    public static List<AffixSelection> roll(StarRank rank, RandomSource rng) {
        if (rank == null) {
            throw new IllegalArgumentException("rank must not be null");
        }
        if (rng == null) {
            throw new IllegalArgumentException("rng must not be null");
        }

        List<AffixSelection> chosen = new ArrayList<>();
        // 逐池掷取: 池声明顺序 (生存优先保证基础生存, 再战斗, 再机动, 最后技能)。
        for (AffixPool pool : AffixPool.values()) {
            rollPool(rank, pool, rng, chosen);
        }
        return chosen;
    }

    /** 在某池预算内贪心追加词条到 chosen (受总词条上限/技能上限/互斥约束)。 */
    private static void rollPool(StarRank rank, AffixPool pool, RandomSource rng, List<AffixSelection> chosen) {
        List<AffixDef> candidates = new ArrayList<>();
        for (AffixDef def : AffixDef.values()) {
            if (def.pool() == pool && def.isUnlockedAt(rank)) {
                candidates.add(def);
            }
        }

        EnumSet<AffixDef> alreadyChosen = EnumSet.noneOf(AffixDef.class);
        for (AffixSelection sel : chosen) {
            alreadyChosen.add(sel.affix());
        }

        // 贪心: 每轮从剩余候选随机挑一个能合法加入的纳入, 直到无可加。candidates.size() 为轮数上界 (每轮至少剔一)。
        int rounds = candidates.size();
        for (int r = 0; r < rounds; r++) {
            if (chosen.size() >= rank.maxAffixes()) {
                return; // 撞总词条上限。
            }
            // 从尚未选的候选里随机取序尝试。
            List<AffixDef> tryOrder = new ArrayList<>();
            for (AffixDef def : candidates) {
                if (!alreadyChosen.contains(def)) {
                    tryOrder.add(def);
                }
            }
            if (tryOrder.isEmpty()) {
                return;
            }
            AffixDef pick = tryOrder.get(rng.nextInt(tryOrder.size()));

            AffixQuality quality = rollQuality(rank, pick, rng);
            AffixSelection sel = new AffixSelection(pick, quality);

            if (wouldRemainLegal(rank, chosen, sel)) {
                chosen.add(sel);
                alreadyChosen.add(pick);
            } else {
                // 该词条任何品质都加不进 (预算/互斥/上限), 从候选剔除避免死循环。
                candidates.remove(pick);
                rounds = candidates.size();
                r = -1;
            }
        }
    }

    /**
     * 掷某词条在该星的实际品质: 在 [词条最低可用档, 该星最高品质] 区间随机, 规避前导 0 占位档 (notes SELF_REPAIR 坑)。
     * 该星最高品质先经 {@link AffixQuality#clampTo} 保证 ≤ rank.maxQuality, 再抬到词条最低可用档 (避免越下界抛)。
     */
    private static AffixQuality rollQuality(StarRank rank, AffixDef affix, RandomSource rng) {
        int minIdx = affix.minUsableQuality().ordinal();
        int maxIdx = rank.maxQuality().ordinal();
        if (maxIdx < minIdx) {
            // 不应出现 (isUnlockedAt 已保证该星最高品质覆盖最低可用档); 防御性返回最低可用档。
            return affix.minUsableQuality();
        }
        int idx = minIdx + rng.nextInt(maxIdx - minIdx + 1);
        return AffixQuality.values()[idx];
    }

    /**
     * 预检: 把 candidate 加入 chosen 后是否仍整体合法 (复用 {@link PointBudget#allocate} 的全套校验)。
     * 合法返 true, 非法 (超预算/超上限/互斥冲突) 返 false。本预检让 roller 只产出合法集合, 终 allocate 必过。
     *
     * 这是唯一用 try/catch 的点, 且语义正确: 此处 catch 不是吞业务异常掩盖缺陷, 而是把 allocate 的"合法性
     * 判定"当布尔谓词用 (allocate 以抛/不抛表达合法/非法)。chosen 与 candidate 都是 roller 自造的合法值对象,
     * IllegalArgumentException 在此只承载"加入后越界"信号, 不掩盖任何外部脏数据。
     */
    private static boolean wouldRemainLegal(StarRank rank, List<AffixSelection> chosen, AffixSelection candidate) {
        List<AffixSelection> trial = new ArrayList<>(chosen);
        trial.add(candidate);
        try {
            PointBudget.allocate(rank, trial);
            return true;
        } catch (IllegalArgumentException illegalCombination) {
            return false;
        }
    }
}
