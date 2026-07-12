package com.miningdim.command;

import com.miningdim.trap.StaticTrapKind;
import com.miningdim.trap.TrapRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;

import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * /mining oresurvey 的纯统计核心 (worldgen 翻修 1.0.2 调参命令)。以玩家为心的立方体内扫描"已加载" section,
 * 统计矿方块数 (石头 + 深板岩变体合并计)、按 y 每 {@link #LAYER_SIZE} 格的分层小计, 以及 {@link TrapRegistry}
 * 内伪装陷阱数 (分 kind)。矿计数与陷阱计数是两条独立口径: 世界里只存真原版矿石 (陷阱伪装已在区块加载时就地
 * 换成真矿石, 见 {@link com.miningdim.trap.TrapDisguiseConverter}), 故世界扫描把伪装陷阱一并计入其伪装矿种,
 * 陷阱身份另由 TrapRegistry 单独报出 —— 二者不互相扣减。
 *
 * 性能: 逐 section 用 palette 预检 ({@link LevelChunkSection#maybeHas}) 跳过不含任何目标矿的 section,
 * 空 section ({@link LevelChunkSection#hasOnlyAir}) 直接跳过, 只对 palette 命中的 section 做逐方块遍历。
 * 未加载区块 (getChunkNow 返回 null) 跳过 —— 命令只勘测玩家周边已加载区域, 不触发区块生成/加载。
 *
 * 纯逻辑抽为 static, 便于 GameTest 摆已知方块断言精确计数与分层归属 (删掉计数循环/分层公式测试必挂)。
 */
public final class OreSurvey {

    private OreSurvey() {
    }

    /** 分层小计的层高 (方块)。y 每 32 格归一层, 层底 = floorDiv(y,32)*32。 */
    public static final int LAYER_SIZE = 32;

    /** 半径上限 (命令层同样钳制)。立方体边长 = 2*radius+1, 半径 64 时约 129^3, section 预检足以扛住。 */
    public static final int MAX_RADIUS = 64;

    /**
     * 归并后的矿类 (石头变体与深板岩变体合并计)。声明顺序即报表顺序。label 用于聊天报表对齐 (纯文本, 无 Emoji)。
     */
    public enum OreCategory {
        COAL("coal"),
        COPPER("copper"),
        IRON("iron"),
        GOLD("gold"),
        REDSTONE("redstone"),
        LAPIS("lapis"),
        DIAMOND("diamond"),
        EMERALD("emerald"),
        ANCIENT_DEBRIS("ancient_debris");

        private final String label;

        OreCategory(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** Block -> 归并矿类 的静态映射 (含石头 + 深板岩双变体)。IdentityHashMap: Block 单例, 引用相等即可。 */
    private static final Map<Block, OreCategory> ORE_BLOCKS = buildOreBlockMap();

    private static Map<Block, OreCategory> buildOreBlockMap() {
        Map<Block, OreCategory> m = new IdentityHashMap<>();
        m.put(Blocks.COAL_ORE, OreCategory.COAL);
        m.put(Blocks.DEEPSLATE_COAL_ORE, OreCategory.COAL);
        m.put(Blocks.COPPER_ORE, OreCategory.COPPER);
        m.put(Blocks.DEEPSLATE_COPPER_ORE, OreCategory.COPPER);
        m.put(Blocks.IRON_ORE, OreCategory.IRON);
        m.put(Blocks.DEEPSLATE_IRON_ORE, OreCategory.IRON);
        m.put(Blocks.GOLD_ORE, OreCategory.GOLD);
        m.put(Blocks.DEEPSLATE_GOLD_ORE, OreCategory.GOLD);
        m.put(Blocks.REDSTONE_ORE, OreCategory.REDSTONE);
        m.put(Blocks.DEEPSLATE_REDSTONE_ORE, OreCategory.REDSTONE);
        m.put(Blocks.LAPIS_ORE, OreCategory.LAPIS);
        m.put(Blocks.DEEPSLATE_LAPIS_ORE, OreCategory.LAPIS);
        m.put(Blocks.DIAMOND_ORE, OreCategory.DIAMOND);
        m.put(Blocks.DEEPSLATE_DIAMOND_ORE, OreCategory.DIAMOND);
        m.put(Blocks.EMERALD_ORE, OreCategory.EMERALD);
        m.put(Blocks.DEEPSLATE_EMERALD_ORE, OreCategory.EMERALD);
        m.put(Blocks.ANCIENT_DEBRIS, OreCategory.ANCIENT_DEBRIS);
        return m;
    }

    /** 某方块状态归属的矿类 (非矿返回 null)。redstone_ore 的点亮/未点亮同为一个 Block 单例, 天然合并。 */
    public static OreCategory classify(BlockState state) {
        return ORE_BLOCKS.get(state.getBlock());
    }

    /** 分层底 y (每 LAYER_SIZE 归一层)。floorDiv 保证负 y 正确归层 (如 -40 -> -64, -32 -> -32)。 */
    public static int layerBucket(int y) {
        return Math.floorDiv(y, LAYER_SIZE) * LAYER_SIZE;
    }

    /**
     * 立方体扫描结果 (矿计数与陷阱计数分列): ores 为归并矿类计数, layers 为分层底 y -> 该层矿总数,
     * total 为矿总数, loadedChunks/scannedSections/skippedSections 为扫描规模 (供报表尾行审计)。
     */
    public record Result(
            Map<OreCategory, Integer> ores,
            Map<Integer, Integer> layers,
            int total,
            int loadedChunks,
            int scannedSections,
            int skippedSections) {
    }

    /**
     * 以 center 为心、半径 radius 的立方体内扫描已加载 section 统计矿方块。radius 由调用方钳制到
     * [1, {@link #MAX_RADIUS}]; 此处按传入值执行 (不再二次钳制, 避免掩盖越界传参)。
     */
    public static Result survey(ServerLevel level, BlockPos center, int radius) {
        int minX = center.getX() - radius;
        int maxX = center.getX() + radius;
        int minY = Math.max(center.getY() - radius, level.getMinBuildHeight());
        int maxY = Math.min(center.getY() + radius, level.getMaxBuildHeight() - 1);
        int minZ = center.getZ() - radius;
        int maxZ = center.getZ() + radius;

        Map<OreCategory, Integer> ores = new EnumMap<>(OreCategory.class);
        Map<Integer, Integer> layers = new HashMap<>();
        int total = 0;
        int loadedChunks = 0;
        int scannedSections = 0;
        int skippedSections = 0;

        for (int cx = minX >> 4; cx <= (maxX >> 4); cx++) {
            for (int cz = minZ >> 4; cz <= (maxZ >> 4); cz++) {
                LevelChunk chunk = level.getChunkSource().getChunkNow(cx, cz);
                if (chunk == null) {
                    continue; // 未加载: 不生成/不加载, 直接跳过 (命令只勘测已加载区域)。
                }
                loadedChunks++;
                int x0 = Math.max(minX, cx << 4);
                int x1 = Math.min(maxX, (cx << 4) + 15);
                int z0 = Math.max(minZ, cz << 4);
                int z1 = Math.min(maxZ, (cz << 4) + 15);

                for (int sectionBottomY = (minY >> 4) << 4; sectionBottomY <= maxY; sectionBottomY += 16) {
                    int idx = chunk.getSectionIndex(sectionBottomY);
                    if (idx < 0 || idx >= chunk.getSections().length) {
                        continue; // section 超出该维度构建高度, 跳过。
                    }
                    LevelChunkSection section = chunk.getSection(idx);
                    // palette 预检: 空 section 或 palette 内不含任何目标矿 -> 整段跳过, 不逐方块遍历。
                    if (section.hasOnlyAir() || !section.maybeHas(s -> classify(s) != null)) {
                        skippedSections++;
                        continue;
                    }
                    scannedSections++;

                    int y0 = Math.max(minY, sectionBottomY);
                    int y1 = Math.min(maxY, sectionBottomY + 15);
                    for (int y = y0; y <= y1; y++) {
                        for (int x = x0; x <= x1; x++) {
                            for (int z = z0; z <= z1; z++) {
                                OreCategory cat = classify(section.getBlockState(x & 15, y & 15, z & 15));
                                if (cat == null) {
                                    continue;
                                }
                                ores.merge(cat, 1, Integer::sum);
                                layers.merge(layerBucket(y), 1, Integer::sum);
                                total++;
                            }
                        }
                    }
                }
            }
        }
        return new Result(ores, layers, total, loadedChunks, scannedSections, skippedSections);
    }

    /**
     * 立方体内 TrapRegistry 伪装陷阱按 kind 计数。entries 由 {@link TrapRegistry#nearby} 按 chunk 粒度粗收,
     * 此处按精确立方体 (|dx|,|dy|,|dz| <= radius) 过滤后分 kind 归并。返回含全部 kind 的表 (未命中 kind 计 0)。
     */
    public static Map<StaticTrapKind, Integer> countTraps(List<TrapRegistry.Entry> entries, BlockPos center, int radius) {
        Map<StaticTrapKind, Integer> counts = new EnumMap<>(StaticTrapKind.class);
        for (StaticTrapKind kind : StaticTrapKind.values()) {
            counts.put(kind, 0);
        }
        for (TrapRegistry.Entry e : entries) {
            BlockPos p = e.pos();
            if (Math.abs(p.getX() - center.getX()) <= radius
                    && Math.abs(p.getY() - center.getY()) <= radius
                    && Math.abs(p.getZ() - center.getZ()) <= radius) {
                counts.merge(e.kind(), 1, Integer::sum);
            }
        }
        return counts;
    }
}
