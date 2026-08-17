package com.miningdim.ore;

import com.miningdim.core.Difficulty;
import com.miningdim.power.mineral.PowerMineral;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;

/**
 * 矿种枚举。方块 ID 而非方块实例存入枚举，避免 Forge DeferredRegister 尚未完成时的静态取值。
 * 能源矿的旧离线配额全部为零，真实生成完全由 datapack worldgen 接管。
 */
public enum OreType {

    COAL(minecraft("coal_ore"), minecraft("deepslate_coal_ore"),
            100, new float[]{1.30f, 1.00f, 0.70f},
            new float[]{28f, 22f, 14f}, new int[]{900, 700, 480}, 4, 12),
    COPPER(minecraft("copper_ore"), minecraft("deepslate_copper_ore"),
            60, new float[]{1.10f, 1.00f, 0.80f},
            new float[]{16f, 16f, 13f}, new int[]{520, 520, 440}, 3, 8),
    IRON(minecraft("iron_ore"), minecraft("deepslate_iron_ore"),
            70, new float[]{1.20f, 1.10f, 0.90f},
            new float[]{20f, 20f, 18f}, new int[]{640, 640, 600}, 3, 8),
    GOLD(minecraft("gold_ore"), minecraft("deepslate_gold_ore"),
            25, new float[]{0.40f, 1.00f, 1.60f},
            new float[]{3f, 7f, 13f}, new int[]{110, 230, 420}, 2, 5),
    REDSTONE(minecraft("redstone_ore"), minecraft("deepslate_redstone_ore"),
            40, new float[]{0.60f, 1.00f, 1.20f},
            new float[]{8f, 12f, 16f}, new int[]{260, 380, 520}, 4, 9),
    LAPIS(minecraft("lapis_ore"), minecraft("deepslate_lapis_ore"),
            18, new float[]{0.80f, 1.00f, 1.10f},
            new float[]{4f, 5f, 6f}, new int[]{140, 170, 210}, 3, 6),
    EMERALD(minecraft("emerald_ore"), minecraft("deepslate_emerald_ore"),
            6, new float[]{0.20f, 0.60f, 1.40f},
            new float[]{0.3f, 1.0f, 3.0f}, new int[]{12, 36, 96}, 1, 2),
    DIAMOND(minecraft("diamond_ore"), minecraft("deepslate_diamond_ore"),
            8, new float[]{0.15f, 0.70f, 2.20f},
            new float[]{0.5f, 1.6f, 4.5f}, new int[]{18, 56, 150}, 1, 4),
    ANCIENT_DEBRIS(minecraft("ancient_debris"), minecraft("ancient_debris"),
            2, new float[]{0.00f, 0.10f, 1.00f},
            new float[]{0f, 0.15f, 0.6f}, new int[]{0, 6, 20}, 1, 2),

    BAUXITE(PowerMineral.BAUXITE.oreKey(), PowerMineral.BAUXITE.deepslateOreKey(), 0, zeroMultipliers(), zeroDensities(), zeroCounts(), 9, 9),
    BORAX(PowerMineral.BORAX.oreKey(), PowerMineral.BORAX.deepslateOreKey(), 0, zeroMultipliers(), zeroDensities(), zeroCounts(), 5, 5),
    SILVER(PowerMineral.SILVER.oreKey(), PowerMineral.SILVER.deepslateOreKey(), 0, zeroMultipliers(), zeroDensities(), zeroCounts(), 5, 5),
    TIN(PowerMineral.TIN.oreKey(), PowerMineral.TIN.deepslateOreKey(), 0, zeroMultipliers(), zeroDensities(), zeroCounts(), 8, 8),
    NICKEL(PowerMineral.NICKEL.oreKey(), PowerMineral.NICKEL.deepslateOreKey(), 0, zeroMultipliers(), zeroDensities(), zeroCounts(), 6, 6),
    CHROMIUM(PowerMineral.CHROMIUM.oreKey(), PowerMineral.CHROMIUM.deepslateOreKey(), 0, zeroMultipliers(), zeroDensities(), zeroCounts(), 4, 4),
    TUNGSTEN(PowerMineral.TUNGSTEN.oreKey(), PowerMineral.TUNGSTEN.deepslateOreKey(), 0, zeroMultipliers(), zeroDensities(), zeroCounts(), 3, 3);

    public static final int DEEPSLATE_Y_THRESHOLD = 0;

    private final ResourceLocation stoneVariantId;
    private final ResourceLocation deepslateVariantId;
    private final int baseWeight;
    private final float[] multipliers;
    private final float[] densityPerK;
    private final int[] maxCount;
    private final int veinSizeMin;
    private final int veinSizeMax;
    private static volatile Map<Block, OreType> blockIndex;

    OreType(ResourceLocation stoneVariantId, ResourceLocation deepslateVariantId, int baseWeight,
            float[] multipliers, float[] densityPerK, int[] maxCount, int veinSizeMin, int veinSizeMax) {
        this.stoneVariantId = stoneVariantId;
        this.deepslateVariantId = deepslateVariantId;
        this.baseWeight = baseWeight;
        this.multipliers = multipliers;
        this.densityPerK = densityPerK;
        this.maxCount = maxCount;
        this.veinSizeMin = veinSizeMin;
        this.veinSizeMax = veinSizeMax;
    }

    public static OreType fromBlock(Block block) {
        return blockIndex().get(block);
    }

    public Item representativeItem() {
        return resolveItem(stoneVariantId);
    }

    public BlockState blockStateAt(int worldY) {
        return (worldY < DEEPSLATE_Y_THRESHOLD ? deepslateVariant() : stoneVariant()).defaultBlockState();
    }

    public int baseWeight() {
        return baseWeight;
    }

    public float effectiveWeight(Difficulty difficulty) {
        return baseWeight * multipliers[difficulty.ordinal()];
    }

    public float densityPerK(Difficulty difficulty) {
        return densityPerK[difficulty.ordinal()];
    }

    public int maxCount(Difficulty difficulty) {
        return maxCount[difficulty.ordinal()];
    }

    public int veinSizeMin() {
        return veinSizeMin;
    }

    public int veinSizeMax() {
        return veinSizeMax;
    }

    private Block stoneVariant() {
        return resolveBlock(stoneVariantId);
    }

    private Block deepslateVariant() {
        return resolveBlock(deepslateVariantId);
    }

    private static Block resolveBlock(ResourceLocation id) {
        if (!BuiltInRegistries.BLOCK.containsKey(id)) {
            throw new IllegalStateException("Missing ore block registration: " + id);
        }
        return BuiltInRegistries.BLOCK.get(id);
    }

    private static Map<Block, OreType> blockIndex() {
        Map<Block, OreType> current = blockIndex;
        if (current != null) {
            return current;
        }
        Map<Block, OreType> resolved = new HashMap<>();
        for (OreType ore : values()) {
            resolved.put(ore.stoneVariant(), ore);
            resolved.put(ore.deepslateVariant(), ore);
        }
        current = Map.copyOf(resolved);
        blockIndex = current;
        return current;
    }

    private static Item resolveItem(ResourceLocation id) {
        if (!BuiltInRegistries.ITEM.containsKey(id)) {
            throw new IllegalStateException("Missing representative ore item registration: " + id);
        }
        return BuiltInRegistries.ITEM.get(id);
    }

    private static ResourceLocation minecraft(String path) {
        return new ResourceLocation("minecraft", path);
    }

    private static float[] zeroMultipliers() {
        return new float[]{0f, 0f, 0f};
    }

    private static float[] zeroDensities() {
        return new float[]{0f, 0f, 0f};
    }

    private static int[] zeroCounts() {
        return new int[]{0, 0, 0};
    }
}
