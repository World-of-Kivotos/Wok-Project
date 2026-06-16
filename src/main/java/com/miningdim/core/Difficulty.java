package com.miningdim.core;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.biome.Biome;

/**
 * 矿山三难度档 (R1/R2)。新模型: 难度 = 一整块固定区域。每档绑定一个固定网格单元 X 列 (并排展开,
 * 见 MiningConstants.*_CELL_X)、一个稳定序号 (持久化/网络用 byte)、一份基材调色板 (R3, worldgen 据此
 * 按难度+局部坐标确定性选 BlockState)、以及对应自定义 biome 的 ResourceKey (6.4)。
 *
 * 旧模型 (按 worldY 子盒分带) 已废弃: 难度不再由 worldY 决定, 整块 384 高都属同一难度;
 * 难度现由所在 region (XZ) 决定 (MiningServices.instanceManager().regionAt(x,z).difficulty())。
 * 几何边界一律用 RegionBox / MiningConstants.REGION_FULL_*, 不再经 Difficulty 取 Y 范围。
 */
public enum Difficulty {

    // 调色板 (R3 用户拍板):
    //  Easy:   STONE 主, 偶尔 ANDESITE/DIORITE 点缀 (无深板岩混合)。
    //  Medium: STONE+DEEPSLATE 噪声混合, 越深深板岩概率越高 (顶 8% -> 底 70%), 无点缀。
    //  Hard:   DEEPSLATE 主, 偶尔 TUFF/BLACKSTONE 点缀 (无深板岩浅深之分, 整块以深板岩为主)。
    EASY(0, "easy", MiningConstants.EASY_CELL_X, "mining_easy",
            new MaterialPalette(BaseMaterial.STONE, 0.0, 0.0, 0.10,
                    BaseMaterial.ANDESITE, BaseMaterial.DIORITE)),
    MEDIUM(1, "medium", MiningConstants.MEDIUM_CELL_X, "mining_medium",
            new MaterialPalette(BaseMaterial.STONE, 0.08, 0.70, 0.0,
                    BaseMaterial.STONE, BaseMaterial.STONE)),
    HARD(2, "hard", MiningConstants.HARD_CELL_X, "mining_hard",
            new MaterialPalette(BaseMaterial.DEEPSLATE, 0.0, 0.0, 0.12,
                    BaseMaterial.TUFF, BaseMaterial.BLACKSTONE));

    private final int id;
    private final String configName;
    private final int regionCellX;
    private final ResourceKey<Biome> biomeKey;
    private final MaterialPalette palette;

    Difficulty(int id, String configName, int regionCellX, String biomePath, MaterialPalette palette) {
        this.id = id;
        this.configName = configName;
        this.regionCellX = regionCellX;
        this.biomeKey = ResourceKey.create(Registries.BIOME,
                new ResourceLocation(MiningConstants.MODID, biomePath));
        this.palette = palette;
    }

    /** 稳定序号, 用于持久化与网络包的 byte 编码 (网络协议 difficulty:byte)。 */
    public int id() {
        return id;
    }

    /** 配置/命令/JSON 用的小写名 (easy/medium/hard)。 */
    public String configName() {
        return configName;
    }

    /** 该难度固定区域所在网格单元 X 列 (R1; RegionGrid.fixedRegionFor 据此 + stride 派生 RegionBox)。 */
    public int regionCellX() {
        return regionCellX;
    }

    /** 该难度的基材调色板 (R3; worldgen 据此按局部坐标确定性选基材令牌)。 */
    public MaterialPalette palette() {
        return palette;
    }

    /** 该难度对应自定义 biome 的 ResourceKey (供 MiningBiomeSource 返回 holder)。 */
    public ResourceKey<Biome> biomeKey() {
        return biomeKey;
    }

    /** byte 序号反查难度档; 非法序号自然抛 IllegalArgumentException (C9, 不掩盖)。 */
    public static Difficulty byId(int id) {
        for (Difficulty d : values()) {
            if (d.id == id) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown difficulty id: " + id);
    }

    /** 配置名反查 (大小写不敏感); 非法名自然抛 IllegalArgumentException。 */
    public static Difficulty byConfigName(String name) {
        for (Difficulty d : values()) {
            if (d.configName.equalsIgnoreCase(name)) {
                return d;
            }
        }
        throw new IllegalArgumentException("Unknown difficulty name: " + name);
    }
}
