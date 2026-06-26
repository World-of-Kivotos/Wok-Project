package com.miningdim.job.farmer.block;

import com.miningdim.job.farmer.FarmerTier;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.state.BlockState;

import java.util.function.Supplier;

/**
 * mod 小麦作物方块 (FarmingXP_Mod_DesignSpec 表B / 第二章机制)。单一作物方块, 成长速率与产量由其下方
 * {@link FarmerFarmlandBlock} 的档位 ({@link FarmerTier}) 动态决定 (不是每档一个作物方块)。
 *
 * 三条强制约束:
 *  1. 只能长在 mod 耕地上 (设计目标 2): {@link #mayPlaceOn}/canSurvive 仅当下方方块在
 *     {@link com.miningdim.job.farmer.FarmerTags#FARMER_FARMLAND} tag 内才允许 (五档 mod 耕地全在该 tag,
 *     原版耕地/泥土不在); 故原版耕地上的 mod 作物无法存活 (放下即破坏) -> 不产经验。用 tag 而非 instanceof
 *     判定: 与 tag 类注释一致, 且可扩展 (第三方耕地进 tag 即复用存活基底)。
 *  2. 档位化成长 (表B): {@link #randomTick} 覆写原版光照成长公式, 改为按下方耕地档位的目标成熟时长
 *     折算的每随机刻概率推进一阶, 使期望成熟时间命中该档间隔 (低 10min ... 超凡 4min)。
 *  3. 反作弊 (第十章): 禁用骨粉 ({@link #isValidBonemealTarget} 恒 false), 否则每日软上限形同虚设。
 *
 * 成熟态被破坏并掉落的经验结算不在本类: 由 {@link com.miningdim.job.farmer.FarmerSystem#onCropHarvested}
 * 在 BlockEvent.BreakEvent 统一裁决 (只认 "成熟态破坏" 这一事件, 第二章防重复刷取)。
 */
public final class FarmerCropBlock extends CropBlock {

    /** 种子物品供给 (注册顺序: 作物方块注册时种子物品可能尚未 get(), 故用 Supplier 延迟求值)。 */
    private final Supplier<? extends ItemLike> seedItem;

    public FarmerCropBlock(Properties properties, Supplier<? extends ItemLike> seedItem) {
        super(properties);
        if (seedItem == null) {
            throw new IllegalArgumentException("FarmerCropBlock seedItem supplier must not be null");
        }
        this.seedItem = seedItem;
    }

    /**
     * 单次随机刻按档位目标间隔推进成长, 期望成熟时间命中下方耕地档位的目标间隔 (表B)。
     *
     * 推导: 原版随机刻在 randomTickSpeed=N 时, 每个作物方块平均每 R = 4096/N 游戏刻被随机刻一次。本法把
     * "命中目标成熟 tick" 化为一个守恒量: 每次被随机刻时推进的期望阶数 E = 1 / expectedRandomTicksPerStage,
     * 其中 expectedRandomTicksPerStage = (每阶段期望 tick) / R, 每阶段期望 tick = growthIntervalTicks / maxAge。
     * 代入可得 期望成熟 tick = maxAge × R × expectedRandomTicksPerStage = growthIntervalTicks (与档位线性, 表B 意图)。
     *
     * 高档地 (6/5/4min) 的每阶段期望 tick 小于 R, 即 expectedRandomTicksPerStage < 1, 此时单次随机刻应推进
     * 多于一阶 (E > 1)。旧实现把推进概率钳到 1.0 且每刻最多推进一阶, 令这三档全退化为 "每随机刻推进恰一阶" =
     * maxAge × R ≈ 7.96min, 三档塌缩同值, 破坏表B 吞吐差异。修复: E >= 1 时取整数部分 + 小数部分作概率多推一阶,
     * 使每刻期望推进阶数严格等于 E ({@link #expectedStagesPerRandomTick}); E < 1 时退化为单阶概率推进 (与旧 >=1 分支同义)。
     */
    @Override
    public void randomTick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        if (isMaxAge(state)) {
            return; // 已成熟: 不再推进 (等待破坏结算)。
        }
        FarmerTier tier = tierBelow(level, pos);
        if (tier == null) {
            return; // 下方不是 mod 耕地 (防御; 正常放置已被 mayPlaceOn 拦住)。原版耕地上不成长 -> 不产经验。
        }
        int randomTickSpeed = level.getGameRules().getInt(net.minecraft.world.level.GameRules.RULE_RANDOMTICKING);
        if (randomTickSpeed <= 0) {
            return; // 服务器关随机刻: 作物冻结 (与原版同语义)。
        }
        double stagesPerTick = expectedStagesPerRandomTick(tier, randomTickSpeed, getMaxAge());
        int age = getAge(state);
        int adv = sampleStageAdvance(stagesPerTick, random);
        if (adv <= 0) {
            return; // 本刻未推进 (E < 1 时按概率落空)。
        }
        int next = Math.min(age + adv, getMaxAge());
        // 单次 setBlock 推进到新阶 (与原版 randomTick 的成长通知同语义: flag 2 触发观察者/比较器更新)。
        // 真正跨到成熟那阶时, 新状态即 maxAge, 后续破坏由 FarmerSystem.onCropHarvested 统一结算 (本类不发成熟事件)。
        level.setBlock(pos, getStateForAge(next), 2);
    }

    /**
     * 单次随机刻推进的期望成长阶数 E (表B 守恒量, 纯函数供 randomTick 与 GameTest 共用)。
     *
     * E = 1 / expectedRandomTicksPerStage, expectedRandomTicksPerStage = (growthIntervalTicks / maxAge) / (4096 / randomTickSpeed)。
     * 期望成熟 tick = maxAge / E × (4096 / randomTickSpeed) = growthIntervalTicks, 故 E 越大成熟越快 (高档地 E > 1, 单刻推进多阶)。
     *
     * @param tier           下方耕地档位 (提供 growthIntervalTicks)
     * @param randomTickSpeed 当前 randomTickSpeed gamerule (> 0)
     * @param maxAge         作物最大成长阶 (= getMaxAge())
     */
    public static double expectedStagesPerRandomTick(FarmerTier tier, int randomTickSpeed, int maxAge) {
        if (randomTickSpeed <= 0) {
            throw new IllegalArgumentException("randomTickSpeed must be > 0, got " + randomTickSpeed);
        }
        if (maxAge <= 0) {
            throw new IllegalArgumentException("maxAge must be > 0, got " + maxAge);
        }
        double avgTicksBetweenRandomTicks = 4096.0D / randomTickSpeed;
        double ticksPerStage = (double) tier.growthIntervalTicks() / maxAge;
        double expectedRandomTicksPerStage = ticksPerStage / avgTicksBetweenRandomTicks;
        return 1.0D / expectedRandomTicksPerStage;
    }

    /**
     * 把期望推进阶数 E (可为小数, 可 < 1 或 > 1) 采样成本刻实际推进的整数阶数, 使采样均值严格等于 E:
     * 整数部分必推, 小数部分作概率多推一阶。E < 1 时整数部分为 0, 退化为按小数概率推进零或一阶。
     */
    public static int sampleStageAdvance(double expectedStagesPerRandomTick, RandomSource random) {
        int whole = (int) expectedStagesPerRandomTick;
        double frac = expectedStagesPerRandomTick - whole;
        return random.nextDouble() < frac ? whole + 1 : whole;
    }

    /**
     * 只能种在 mod 耕地上 (设计目标 2)。下方方块在 {@link com.miningdim.job.farmer.FarmerTags#FARMER_FARMLAND}
     * tag 内才允许存活/放置 (五档 mod 耕地全在该 tag; 用 tag 判定可扩展, 与 tag 类注释一致)。
     */
    @Override
    protected boolean mayPlaceOn(BlockState belowState, BlockGetter level, BlockPos pos) {
        return belowState.is(com.miningdim.job.farmer.FarmerTags.FARMER_FARMLAND);
    }

    /**
     * 存活判定: 仅基底是 mod 耕地即可 (不要求原版的光照 >= 8)。mod 耕地是职业基建, 通常在地表/可控环境,
     * 不沿用原版光照存活约束 (避免地下耕地无意义枯死)。基底判定经 {@link #mayPlaceOn} 复用。
     */
    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return mayPlaceOn(level.getBlockState(below), level, below);
    }

    /** 收获/掉落与轮廓 (空手破坏掉种子) 用的种子物品 (BushBlock 自补种逻辑与原版一致)。 */
    @Override
    protected ItemLike getBaseSeedId() {
        return seedItem.get();
    }

    /** 中键复制 / 未成熟轮廓掉落物 = 种子 (与原版小麦同行为)。 */
    @Override
    public ItemStack getCloneItemStack(BlockGetter level, BlockPos pos, BlockState state) {
        return new ItemStack(seedItem.get());
    }

    // ---- 反作弊: 禁用骨粉 (第十章) ----

    /** 骨粉禁用: mod 作物不是有效骨粉目标 (否则每日经验软上限形同虚设, 第十章)。 */
    @Override
    public boolean isValidBonemealTarget(LevelReader level, BlockPos pos, BlockState state, boolean isClient) {
        return false;
    }

    @Override
    public boolean isBonemealSuccess(Level level, RandomSource random, BlockPos pos, BlockState state) {
        return false;
    }

    @Override
    public void performBonemeal(ServerLevel level, RandomSource random, BlockPos pos, BlockState state) {
        // 不执行任何成长 (骨粉对 mod 作物无效, 反作弊第十章)。留空非逃课: 骨粉本就被 isValidBonemealTarget 拦下,
        // 此处即便被外部强行调用也不推进成长 (防御性二次封堵)。
    }

    /** 读取下方耕地档位; 下方不是 mod 耕地返回 null (调用方据此短路)。 */
    public static FarmerTier tierBelow(BlockGetter level, BlockPos cropPos) {
        BlockState below = level.getBlockState(cropPos.below());
        Block block = below.getBlock();
        if (block instanceof FarmerFarmlandBlock farmland) {
            return farmland.tier();
        }
        return null;
    }
}
