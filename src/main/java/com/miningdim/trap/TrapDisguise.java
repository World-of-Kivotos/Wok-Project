package com.miningdim.trap;

import com.miningdim.core.Difficulty;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashSet;
import java.util.Set;

/**
 * 静态陷阱伪装皮肤表 (协议级伪装的"外观"侧, 与 {@link TrapRegistry} 的"身份"侧解耦)。区块加载时
 * {@link TrapDisguiseConverter} 据此把 trap_ore 换成一块真原版矿石: 矿种按所在难度群系的真矿表均匀随机,
 * 石头/深板岩变体按局部上下文选 —— 使伪装块与自然矿石逐位无法区分 (F3/Jade/矿透一律只见普通矿石)。
 *
 * 难度矿池 (用户裁决):
 *  - easy:   煤/铁/铜
 *  - medium: 煤/铁/铜/金/红石/青金石
 *  - hard:   铁/金/红石/青金石/钻石/绿宝石 (不含远古残骸)
 *
 * 深板岩变体判定口径 (对齐 {@code data/miningdim/worldgen/noise_settings/mining.json} 的 medium surface_rule):
 * medium 的 deepslate 按 y 分层 (absolute 24 及以下纯深板岩、40 及以上纯石头、24~40 梯度混合); easy 全石头、
 * hard 全深板岩。梯度带无法凭 y 精确复现原版逐位随机, 故转换器优先采样相邻真实方块 ({@link #isDeepslateFamily}/
 * {@link #isStoneFamily}) 取地面真值, 采样不定 (如空气暴露) 才回退 {@link #deepslateByModel} 的 y 分层模型。
 */
public final class TrapDisguise {

    private TrapDisguise() {
    }

    /** 一个矿种的石头变体 / 深板岩变体方块对。 */
    public enum OreSkin {
        COAL(Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
        IRON(Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
        COPPER(Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
        GOLD(Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE),
        REDSTONE(Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
        LAPIS(Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
        DIAMOND(Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
        EMERALD(Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE);

        private final Block stone;
        private final Block deepslate;

        OreSkin(Block stone, Block deepslate) {
            this.stone = stone;
            this.deepslate = deepslate;
        }

        Block variant(boolean deepslateContext) {
            return deepslateContext ? deepslate : stone;
        }
    }

    private static final OreSkin[] EASY_POOL = {OreSkin.COAL, OreSkin.IRON, OreSkin.COPPER};
    private static final OreSkin[] MEDIUM_POOL = {
            OreSkin.COAL, OreSkin.IRON, OreSkin.COPPER, OreSkin.GOLD, OreSkin.REDSTONE, OreSkin.LAPIS};
    private static final OreSkin[] HARD_POOL = {
            OreSkin.IRON, OreSkin.GOLD, OreSkin.REDSTONE, OreSkin.LAPIS, OreSkin.DIAMOND, OreSkin.EMERALD};

    /**
     * medium 深板岩梯度带的回退分界 y (noise_settings medium surface_rule 的 absolute 24~40 梯度中点)。
     * 仅当相邻方块采样不定时用作 y 分层回退, 使 y<=24 判深板岩、y>=40 判石头恒成立 (中点 32 二分梯度带)。
     */
    private static final int MEDIUM_GRADIENT_MID_Y = 32;

    /** 全部伪装矿石方块 (石头 + 深板岩变体)。幽灵守卫据此判"注册表命中但已不是矿石族"。 */
    private static final Set<Block> DISGUISE_BLOCKS = new HashSet<>();
    /** 深板岩变体伪装矿石 (相邻采样判深板岩上下文用)。 */
    private static final Set<Block> DEEPSLATE_DISGUISE_BLOCKS = new HashSet<>();

    static {
        for (OreSkin skin : OreSkin.values()) {
            DISGUISE_BLOCKS.add(skin.stone);
            DISGUISE_BLOCKS.add(skin.deepslate);
            DEEPSLATE_DISGUISE_BLOCKS.add(skin.deepslate);
        }
    }

    private static OreSkin[] poolFor(Difficulty difficulty) {
        return switch (difficulty) {
            case EASY -> EASY_POOL;
            case MEDIUM -> MEDIUM_POOL;
            case HARD -> HARD_POOL;
        };
    }

    /**
     * 为一个陷阱位选一块伪装矿石: 矿种在该难度矿池内均匀随机, 石头/深板岩变体由 deepslateContext 决定。
     *
     * @param difficulty       陷阱所在难度区域
     * @param deepslateContext 该位是否处深板岩上下文 (由转换器采样/y 分层得出)
     * @param random           服务端权威 RandomSource (level.getRandom(); 不引入自建种子)
     */
    public static BlockState pickSkin(Difficulty difficulty, boolean deepslateContext, RandomSource random) {
        OreSkin[] pool = poolFor(difficulty);
        OreSkin skin = pool[random.nextInt(pool.length)];
        return skin.variant(deepslateContext).defaultBlockState();
    }

    /** 是否为本系统会布下的伪装矿石之一 (幽灵守卫: 注册表命中但方块不属此集合即幽灵条目)。 */
    public static boolean isDisguiseOre(BlockState state) {
        return DISGUISE_BLOCKS.contains(state.getBlock());
    }

    /** 是否深板岩族 (自然深板岩 / 已转换的深板岩变体伪装矿石); 相邻采样判上下文用。 */
    public static boolean isDeepslateFamily(BlockState state) {
        return state.is(Blocks.DEEPSLATE) || DEEPSLATE_DISGUISE_BLOCKS.contains(state.getBlock());
    }

    /** 是否石头族 (自然石头 / 已转换的石头变体伪装矿石); 相邻采样判上下文用。 */
    public static boolean isStoneFamily(BlockState state) {
        Block block = state.getBlock();
        return state.is(Blocks.STONE)
                || (DISGUISE_BLOCKS.contains(block) && !DEEPSLATE_DISGUISE_BLOCKS.contains(block));
    }

    /**
     * 相邻采样不定时的 y 分层回退模型 (对齐 medium surface_rule): easy 恒石头、hard 恒深板岩、
     * medium 以梯度中点 {@value #MEDIUM_GRADIENT_MID_Y} 二分 (y 小于中点判深板岩)。
     */
    public static boolean deepslateByModel(Difficulty difficulty, int y) {
        return switch (difficulty) {
            case EASY -> false;
            case HARD -> true;
            case MEDIUM -> y < MEDIUM_GRADIENT_MID_Y;
        };
    }
}
