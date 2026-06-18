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
     * 单次随机刻按概率推进一个成长阶段, 期望成熟时间命中下方耕地档位的目标间隔 (表B)。
     *
     * 推导: 原版随机刻在 randomTickSpeed=3 时, 每个作物方块平均约每 (4096/3) 游戏刻被随机刻一次。但
     * randomTick 的调用本身已是 "被随机选中" 的事件, 故本法只需定义 "每次被随机刻时推进一阶的概率 p",
     * 使 (期望需要的随机刻次数 / 阶段) × (平均随机刻间隔) × 阶段数 = 目标成熟 tick。
     *
     * 令 R = 平均随机刻间隔 (tick) = 4096 / randomTickSpeed; 每阶段期望 tick = growthIntervalTicks / maxAge;
     * 每阶段期望随机刻次数 = 每阶段期望 tick / R; p = 1 / 该次数 (钳制到 (0,1])。
     * 这样 "高级地肉眼更快" 直接由更小的 growthIntervalTicks 体现 (表B 设计意图)。
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
        double avgTicksBetweenRandomTicks = 4096.0D / randomTickSpeed;
        double ticksPerStage = (double) tier.growthIntervalTicks() / getMaxAge();
        double expectedRandomTicksPerStage = ticksPerStage / avgTicksBetweenRandomTicks;
        double advanceChance = expectedRandomTicksPerStage <= 1.0D
                ? 1.0D
                : 1.0D / expectedRandomTicksPerStage;
        if (random.nextDouble() < advanceChance) {
            int next = getAge(state) + 1;
            level.setBlock(pos, getStateForAge(Math.min(next, getMaxAge())), 2);
        }
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
