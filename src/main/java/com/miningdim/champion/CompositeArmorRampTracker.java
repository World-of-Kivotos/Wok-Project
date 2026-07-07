package com.miningdim.champion;

import com.miningdim.champion.ChampionDamageReduction.DamageCategory;

import java.util.EnumMap;
import java.util.Map;

/**
 * 复合装甲【同源适应】受击计数器纯逻辑 (ChampionStarAffix spec 7.1 复合装甲 v2, 2026-07-07 用户定向加强)。
 *
 * 语义 (取代旧"全伤害单桶 ramp"): 装甲按【伤害类别】({@link DamageCategory}: 子弹/近战/爆炸/其它) 分桶适应 ——
 *  - 受某类伤害: 该类桶 +1 (夹到 {@link ChampionDamageReduction#COMPOSITE_RAMP_STEPS} 满 5 层 = 该品质减伤上限),
 *    同时【清空全部其它类别桶】(装甲适应当前威胁、忘掉旧威胁)。
 *  - 玩家反制: 换伤害类别 (枪打久了换近战/丢雷) = 新类别从 0 层重爬 (吃满伤害数击), 且旧类别桶被清 —— 切回旧武器
 *    同样从 0 重爬。持续单一输出 (纯突突) 则很快满层吃 35~75% 减伤 (原版 adaptable 式博弈, 我方给了真重置手段)。
 *  - 3s 无伤: 全桶重置 (脱战归零)。
 *
 * 单冠军一只的有状态计数器 (服务端 tick 串行, 非线程安全), 不碰世界/实体, GameTest 直接断言。多只冠军各持一个
 * 实例 (受击 handler 按 UUID 持 Map), 实例间独立。返回的 hitCount 交
 * {@link ChampionDamageReduction#compositeRampRate} 折算成当前减伤率进净减伤连乘 (帽 75%, 红线 1)。
 */
public final class CompositeArmorRampTracker {

    /** per-类别当前层数 (缺省 = 0 层)。 */
    private final Map<DamageCategory, Integer> stacks = new EnumMap<>(DamageCategory.class);

    private long lastHitTick = Long.MIN_VALUE;

    /**
     * 记一次某类别受击, 返回该类别适应后的当前层数 (∈ [1, COMPOSITE_RAMP_STEPS])。
     * 距上次任意受击 ≥3s 先全桶清零 (无伤重置); 然后清空其它类别桶 (同源适应), 本类别 +1 夹到满层。
     *
     * @param category 本次伤害类别 (受击 handler 从 DamageSource 折算)
     * @param nowTick  当前 gameTime tick (无伤重置窗判定)
     * @return 本次受击后该类别的累计层数 (供 compositeRampRate 折算减伤率)
     */
    public int onHit(DamageCategory category, long nowTick) {
        if (category == null) {
            throw new IllegalArgumentException("damage category must not be null");
        }
        if (lastHitTick == Long.MIN_VALUE || nowTick - lastHitTick >= ChampionDamageReduction.COMPOSITE_RAMP_RESET_TICKS) {
            stacks.clear(); // 3s 无伤: 全桶重置。
        }
        lastHitTick = nowTick;

        // 同源适应: 装甲转向当前类别, 其它类别桶清空 (换类别 = 双向真重置)。
        int current = stacks.getOrDefault(category, 0);
        stacks.clear();
        if (current < ChampionDamageReduction.COMPOSITE_RAMP_STEPS) {
            current++;
        }
        stacks.put(category, current);
        return current;
    }

    /** 某类别当前层数 (诊断/测试用; 未在册返 0)。 */
    public int stacksOf(DamageCategory category) {
        return stacks.getOrDefault(category, 0);
    }

    /** 上次受击 tick (诊断/测试用; 未受击为 Long.MIN_VALUE)。 */
    public long lastHitTick() {
        return lastHitTick;
    }
}
