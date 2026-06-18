package com.miningdim.job.miner;

import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 矿脉时运 (方案 B) 额外掉落施加 (Miner_Job_DesignSpec 第六/十章)。把矿工等级时运
 * ({@link MinerSkills#fortuneExtraExpectancy}) 转成对一组掉落物的额外产出, 追加进掉落列表; 追加的产出物个数
 * 随掉落一起经唯一物化点入包/落地, 并经 {@link MinerSystem#replayEconomyOreCount} 同步进方案 B 当日矿物计数
 * (含时运额外掉落), 故时运 "用更少块达到隐藏软上限" 而非抬上限 (反通胀第三道约束)。
 *
 * 施加范围 (本类作用域): 连锁 ({@link MinerSystem#onChainProduce}) / 隧道 ({@link MinerActions} tunnelProduce)
 * 的连带破坏 —— 这两条路径的掉落由 {@link ChainMiningEngine} 经 Block.getDrops 快照后交本类增补, 再交 sink 物化。
 * 单块原版挖矿的掉落由 vanilla 直接物化, 不经本类 (其时运改造须 loot modifier / BlockEvent, 属 spec 第十三章
 * PENDING 5 的待定项, 见 notes; 本类不覆盖单块路径以免对所有方块 (含非矿) 误加掉落)。
 *
 * 纯计算 + RandomSource 驱动, 不持状态; 期望取自 {@link MinerConstants} (经 MinerSkills)。
 */
public final class MinerFortune {

    private MinerFortune() {
    }

    /**
     * 时运额外掉落个数 (确定性纯函数, 供 GameTest 断言期望与边界): 期望 expectancy 拆为整数部分 (每个基础产出物
     * 必得这么多额外) + 小数部分 (每个基础产出物按该概率再得 1 个)。小数部分的概率比较用调用方提供的 roll [0,1)
     * 做, 使本函数可在测试中以确定 roll 断言具体额外个数 (而非随机)。
     *
     * 当前曲线 expectancy ∈ [0.08, 0.50] (L4-L10), 整数部分恒 0, 故等价于 baseCount 次概率 expectancy 的伯努利;
     * 但仍按整数+小数拆分实现, 以容忍未来配置漂移到 >=1.0 的期望而口径不变。
     *
     * @param baseCount   基础产出物个数 (>=0)
     * @param expectancy  额外掉落期望 (>=0; 通常 [0, 0.5])
     * @param roll        小数部分判定用的随机数 [0,1) (测试传定值, 生产传 rng.nextDouble())
     * @return 额外产出物个数 (>=0)
     */
    public static int extraDropCount(int baseCount, double expectancy, double roll) {
        if (baseCount <= 0 || expectancy <= 0.0D) {
            return 0;
        }
        int whole = (int) Math.floor(expectancy);
        double frac = expectancy - whole;
        int extra = whole * baseCount;
        // 小数部分: 每个基础产出物按 frac 概率再得 1 个; 用同一 roll 比较 (确定性可测)。
        if (frac > 0.0D && roll < frac) {
            extra += baseCount;
        }
        return extra;
    }

    /**
     * 对一组掉落物按矿工等级时运追加额外掉落, 返回追加后的新列表 (原列表不改)。每个非空掉落 stack 按
     * {@link #extraDropCount} 决定额外个数, 以同物品追加一个额外 stack (count = 额外个数)。
     * 未解锁时运 (期望 0) 或空列表时原样返回 (无追加)。
     *
     * @param drops      原始掉落 (连锁/隧道经 Block.getDrops 的快照)
     * @param minerLevel 矿工等级 (决定时运期望)
     * @param rng        服务端随机源 (权威; 每个 stack 各掷一次)
     * @return 含额外掉落的新列表 (至少含原始掉落的拷贝)
     */
    public static List<ItemStack> withFortuneExtras(List<ItemStack> drops, int minerLevel, RandomSource rng) {
        double expectancy = MinerSkills.fortuneExtraExpectancy(minerLevel);
        List<ItemStack> out = new ArrayList<>(drops.size());
        for (ItemStack stack : drops) {
            if (stack.isEmpty()) {
                continue;
            }
            out.add(stack.copy());
            if (expectancy <= 0.0D) {
                continue;
            }
            int extra = extraDropCount(stack.getCount(), expectancy, rng.nextDouble());
            if (extra > 0) {
                ItemStack bonus = stack.copy();
                bonus.setCount(extra);
                out.add(bonus);
            }
        }
        return out;
    }
}
