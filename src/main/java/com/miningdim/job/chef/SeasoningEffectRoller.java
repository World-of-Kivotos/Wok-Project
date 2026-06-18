package com.miningdim.job.chef;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;

/**
 * 效果掷出器 (Chef_Job_DesignSpec 第四/六章): 按厨师等级解锁的效果池 + 调料偏置, 在小游戏命中时掷效果。
 *
 * 强红线 (第六章), 全部在本类强制, 删任一约束对应 GameTest 必挂:
 *  R1 个数: 一道菜带的效果数 <= {@link ChefQuality#maxEffects()} (低 1 -> 闪耀 3);
 *  R2 战斗向门控: 战斗向 (isCombat) 只在 {@link ChefQuality#combatUnlocked()} 档 (高/超凡/闪耀) 可掷;
 *  R3 一菜一战斗: 一道菜最多 1 个战斗向效果;
 *  R4 零翻车: {@link ChefQuality#noFailure()} 档 (超凡/闪耀) 永不掷负面 (isNegative);
 *  R5 保证正向: 超凡/闪耀至少不空手 (零负面 + 个数足额, 给身份感)。
 *
 * 等级解锁 (第七章节奏 "先苦后甜"): 低级厨师池小 (只基础续航 + 翻车), 高级才解锁战斗向 (膳香/披甲/...)。
 * 解锁与品质档双重门控: 既要厨师等级够 (池里有), 又要品质档够 (combatUnlocked)。
 *
 * 服务端权威 (第四章): 仅服务端 BlockEntity 在做菜完成时调用, 客户端不掷 (防作弊)。
 */
public final class SeasoningEffectRoller {

    private SeasoningEffectRoller() {
    }

    /** 厨师等级解锁门槛 (第七章 "高级才解锁强效果"): 战斗向效果需厨师等级 >= 此值才进池。 */
    private static final int COMBAT_UNLOCK_LEVEL = 5;
    /** 稳膛/披甲等高阶战斗向需更高等级 (避免低级就拿全套战斗向)。 */
    private static final int ADVANCED_COMBAT_UNLOCK_LEVEL = 7;

    /**
     * 掷出一道菜的全部效果实例 (个数受 maxEffects 约束, 战斗向 <=1, 负面仅低/中/高)。
     *
     * @param random      服务端 RandomSource (BlockEntity.level.random)
     * @param chefLevel   操作厨师等级 (1-10)
     * @param quality     达成品质档
     * @param bias        调料偏置方向
     * @param hitCount    小游戏调味命中数 (决定实际掷几个, 但不超过 maxEffects)
     * @return 效果实例列表 (size <= quality.maxEffects())
     */
    public static List<ChefEffectInstance> rollAll(RandomSource random, int chefLevel, ChefQuality quality,
                                                   SeasoningBias bias, int hitCount) {
        int target = Math.min(quality.maxEffects(), Math.max(1, hitCount));
        List<ChefEffectType> pool = unlockedPool(chefLevel, quality);

        List<ChefEffectInstance> result = new ArrayList<>();
        EnumSet<ChefEffectType> chosen = EnumSet.noneOf(ChefEffectType.class);
        boolean combatUsed = false;

        // 偏置: 把 bias 指向的效果加权 (在加权池里多次出现), 增大被掷中概率, 但不排他 (其它仍可掷)。
        for (int i = 0; i < target; i++) {
            List<ChefEffectType> candidates = filterCandidates(pool, chosen, combatUsed, quality);
            if (candidates.isEmpty()) {
                break; // 池耗尽 (个数受限于可掷种类): 不强凑, 自然少于 target。
            }
            ChefEffectType picked = weightedPick(random, candidates, bias);
            chosen.add(picked);
            if (picked.isCombat()) {
                combatUsed = true;
            }
            int magnitude = ChefEffectMagnitude.snapshot(picked, quality);
            result.add(new ChefEffectInstance(picked, magnitude));
        }

        // R5 保证正向: 零翻车档若意外空手 (池极小), 兜底塞一个基础正向 (增量), 不让超凡/闪耀出空菜。
        if (result.isEmpty() && quality.noFailure()) {
            result.add(new ChefEffectInstance(ChefEffectType.NOURISH_FOOD,
                    ChefEffectMagnitude.snapshot(ChefEffectType.NOURISH_FOOD, quality)));
        }
        return result;
    }

    /**
     * 按厨师等级 + 品质档解锁的效果池 (第四章: 池随等级扩大)。
     * 始终含基础续航/探索向; 战斗向需等级 + 品质双门控; 负面仅在非零翻车档进池。
     */
    public static List<ChefEffectType> unlockedPool(int chefLevel, ChefQuality quality) {
        List<ChefEffectType> pool = new ArrayList<>();
        // 基础正向 (全等级、全品质可掷)。
        pool.add(ChefEffectType.AMPLIFY);
        pool.add(ChefEffectType.NOURISH_FOOD);
        pool.add(ChefEffectType.AFTERTASTE_SAT);
        pool.add(ChefEffectType.SATED_JUMP);
        pool.add(ChefEffectType.REFRESH);
        pool.add(ChefEffectType.NIGHT_SIGHT);
        // 续航窗口型 (等级 >=3 解锁耐饥, 给中期续航)。
        if (chefLevel >= 3) {
            pool.add(ChefEffectType.ENDURANCE);
        }
        // 战斗向 (品质 combatUnlocked + 等级双门控)。
        if (quality.combatUnlocked() && chefLevel >= COMBAT_UNLOCK_LEVEL) {
            pool.add(ChefEffectType.NOURISH_HEAL);
            pool.add(ChefEffectType.PURIFY);
            pool.add(ChefEffectType.GREASE);
            pool.add(ChefEffectType.AFTERTASTE_REGEN);
        }
        if (quality.combatUnlocked() && chefLevel >= ADVANCED_COMBAT_UNLOCK_LEVEL) {
            pool.add(ChefEffectType.SHIELD);
            pool.add(ChefEffectType.STABLE_AIM);
        }
        // 稳膛中级即可解锁一档 (第十一章稳膛 中=50%), 但仍是战斗向受 combatUnlocked 门控;
        // MEDIUM 不 combatUnlocked, 故稳膛实际最低从 HIGH 起 (与膳香/披甲一致), MEDIUM 列数值留作上限提示。
        // 负面 (仅非零翻车档)。
        if (!quality.noFailure()) {
            pool.add(ChefEffectType.UNDERDONE);
            pool.add(ChefEffectType.SCORCHED);
            pool.add(ChefEffectType.NAUSEA);
            if (quality == ChefQuality.LOW) {
                // 多盐/失败品: 仅低/中翻车; 失败品仅低级 (第六章表)。
                pool.add(ChefEffectType.OVERSALT);
                pool.add(ChefEffectType.SPOILED);
            } else if (quality == ChefQuality.MEDIUM) {
                pool.add(ChefEffectType.OVERSALT);
            }
        }
        return pool;
    }

    /**
     * 过滤本轮可掷的候选: 去掉已选; 若已用过战斗向则去掉所有战斗向 (R3); 零翻车档去掉负面 (R4, 兜底,
     * unlockedPool 已不放, 此处双保险); 战斗向需 combatUnlocked (R2, 同样双保险)。
     */
    private static List<ChefEffectType> filterCandidates(List<ChefEffectType> pool,
                                                         EnumSet<ChefEffectType> chosen,
                                                         boolean combatUsed, ChefQuality quality) {
        List<ChefEffectType> out = new ArrayList<>();
        for (ChefEffectType t : pool) {
            if (chosen.contains(t)) {
                continue;
            }
            if (t.isCombat() && (combatUsed || !quality.combatUnlocked())) {
                continue; // R3 一菜一战斗 + R2 品质门控。
            }
            if (t.isNegative() && quality.noFailure()) {
                continue; // R4 零翻车。
            }
            out.add(t);
        }
        return out;
    }

    /** 加权抽取: bias 指向的效果在抽样里多占权重 (出现次数 x额外权重), 偏向但不排他。 */
    private static ChefEffectType weightedPick(RandomSource random, List<ChefEffectType> candidates,
                                               SeasoningBias bias) {
        // 构造加权列表: 命中 bias 池方向的效果各 +2 权重 (共 3 份), 其余 1 份。
        List<ChefEffectType> weighted = new ArrayList<>();
        for (ChefEffectType t : candidates) {
            int weight = inBiasPool(t, bias) ? 3 : 1;
            for (int i = 0; i < weight; i++) {
                weighted.add(t);
            }
        }
        return weighted.get(random.nextInt(weighted.size()));
    }

    /** 某效果是否在 bias 指向的偏置池内 (第五章 调料->效果池映射方向)。 */
    private static boolean inBiasPool(ChefEffectType t, SeasoningBias bias) {
        return switch (bias) {
            case SAVORY -> t == ChefEffectType.ENDURANCE || t == ChefEffectType.NOURISH_FOOD
                    || t == ChefEffectType.AMPLIFY;
            case SWEET -> t == ChefEffectType.AMPLIFY || t == ChefEffectType.AFTERTASTE_SAT;
            case OILY -> t == ChefEffectType.SHIELD || t == ChefEffectType.NOURISH_HEAL
                    || t == ChefEffectType.GREASE;
            case SOUR -> t == ChefEffectType.PURIFY || t == ChefEffectType.STABLE_AIM;
            case SPICY -> t == ChefEffectType.NOURISH_HEAL || t == ChefEffectType.REFRESH;
            case AROMATIC -> t == ChefEffectType.NIGHT_SIGHT || t == ChefEffectType.ENDURANCE;
            case COMPLEX, NONE -> false; // COMPLEX 不收窄 (偏多效果由 maxEffects 体现), 无单点加权。
        };
    }
}
