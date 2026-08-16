package com.miningdim.quest.objective;

import com.miningdim.quest.QuestFacts;
import com.miningdim.quest.QuestObjective;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

/**
 * 上交 N 个指定物品 (塔科夫"寻物上交")。
 *
 * <b>选品硬约束</b>: 只能选产量受真实生产约束的物品 —— 怪物掉落、加工品、稀有战利品。绝不能选圆石/泥土/木头
 * 这类可无限速刷的方块, 否则这条判据就退化成被排除掉的"村民交易"同类 (零成本无限完成 = 保底变印钞)。
 *
 * 玩家可以从市场买来上交, 这是<b>可接受</b>的: 物品被真销毁 (物品 sink), 全服可上交总量因此受真实产能约束,
 * 且给市场创造需求。注意它是物品 sink 而非信用点 sink —— 任务本身仍是信用点 faucet, 别与经济审计里"信用点
 * sink 全失效"那条混为一谈。
 *
 * 上交动作本身由 {@code /quest turnin} 执行: 先确认目标要这件物品并算出剩余需求, 再从背包扣, 扣掉之后才发出
 * {@link QuestFacts.ItemTurnIn}。顺序不可颠倒。
 *
 * @param item          需要上交的物品
 * @param requiredCount 需要上交的总个数
 */
public record TurnInItemObjective(Item item, int requiredCount) implements QuestObjective {

    public TurnInItemObjective {
        if (item == null) {
            throw new IllegalArgumentException("turn-in item must not be null");
        }
        if (requiredCount < 1) {
            throw new IllegalArgumentException("requiredCount must be >= 1, got " + requiredCount);
        }
    }

    /** 物品的本地化显示名 (走 ItemStack 的 hover name, 不自维护一份中文表)。 */
    public String itemDisplayName() {
        return new ItemStack(item).getHoverName().getString();
    }

    @Override
    public String describe() {
        return "上交 " + itemDisplayName() + " x" + requiredCount;
    }

    @Override
    public int match(QuestFacts facts) {
        if (!(facts instanceof QuestFacts.ItemTurnIn turnIn)) {
            return 0;
        }
        if (turnIn.item() != item) {
            return 0;
        }
        // 一次上交多个即一次记多个; QuestProgress 会把超出剩余需求的部分夹掉, 但命令层已先按剩余需求裁剪过,
        // 正常路径不会走到夹取 (夹取是防御, 不是主路径)。
        return Math.max(0, turnIn.count());
    }
}
