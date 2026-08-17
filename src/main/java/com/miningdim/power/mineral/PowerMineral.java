package com.miningdim.power.mineral;

import com.miningdim.core.MiningConstants;
import net.minecraft.resources.ResourceLocation;

/**
 * 能源导体加工链的七种基础矿物。矿石 ID、物品 ID、颜色与默认矿脉参数在此处统一定义，
 * 数据包可覆盖矿脉的最终生成规则。
 */
public enum PowerMineral {

    BAUXITE("bauxite", "raw_aluminum", "aluminum_ingot", 0xC47A3F, 10, 6, 3, 9),
    BORAX("borax", "borax", null, 0x8AA8A0, 0, 4, 3, 5),
    SILVER("silver", "raw_silver", "silver_ingot", 0xC7D1D8, 0, 3, 5, 5),
    TIN("tin", "raw_tin", "tin_ingot", 0x91A7B4, 0, 5, 3, 8),
    NICKEL("nickel", "raw_nickel", "nickel_ingot", 0xA7A65A, 0, 0, 4, 6),
    CHROMIUM("chromium", "raw_chromium", "chromium_ingot", 0xB76B91, 0, 0, 3, 4),
    TUNGSTEN("tungsten", "raw_tungsten", "tungsten_ingot", 0x566A73, 0, 0, 2, 3);

    private final String path;
    private final String rawMaterialId;
    private final String ingotId;
    private final int tintColor;
    private final int easyAttemptsPerChunk;
    private final int mediumAttemptsPerChunk;
    private final int hardAttemptsPerChunk;
    private final int veinSize;

    PowerMineral(String path, String rawMaterialId, String ingotId, int tintColor,
                 int easyAttemptsPerChunk, int mediumAttemptsPerChunk, int hardAttemptsPerChunk, int veinSize) {
        this.path = path;
        this.rawMaterialId = rawMaterialId;
        this.ingotId = ingotId;
        this.tintColor = tintColor;
        this.easyAttemptsPerChunk = easyAttemptsPerChunk;
        this.mediumAttemptsPerChunk = mediumAttemptsPerChunk;
        this.hardAttemptsPerChunk = hardAttemptsPerChunk;
        this.veinSize = veinSize;
    }

    public String oreId() {
        return path + "_ore";
    }

    public String deepslateOreId() {
        return "deepslate_" + path + "_ore";
    }

    public String rawMaterialId() {
        return rawMaterialId;
    }

    public boolean hasIngot() {
        return ingotId != null;
    }

    public String ingotId() {
        if (ingotId == null) {
            throw new IllegalStateException(name() + " has no ingot item");
        }
        return ingotId;
    }

    public int tintColor() {
        return tintColor;
    }

    public int easyAttemptsPerChunk() {
        return easyAttemptsPerChunk;
    }

    public int mediumAttemptsPerChunk() {
        return mediumAttemptsPerChunk;
    }

    public int hardAttemptsPerChunk() {
        return hardAttemptsPerChunk;
    }

    public int veinSize() {
        return veinSize;
    }

    public ResourceLocation oreKey() {
        return new ResourceLocation(MiningConstants.MODID, oreId());
    }

    public ResourceLocation deepslateOreKey() {
        return new ResourceLocation(MiningConstants.MODID, deepslateOreId());
    }
}
