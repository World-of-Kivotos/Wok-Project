package com.miningdim.job.farmer.granary;

import net.minecraftforge.common.ForgeConfigSpec;

import java.util.List;

/**
 * 粮仓的服务端配置 ({@code miningdim-farmer.toml} 的 {@code [granary]} 段, 由 {@code FarmerModule} 注册)。
 *
 * 设计文档点名要求「管理员改一行配置就能整体调整每级囤数和每囤格数」, 所以额度表放在配置里而不是常量里:
 * 默认 L1 3 个、每升一级 +1、L10 毕业额外 +1, 共 13 个; 每个储粮囤 27 格。
 * 越界等级一律钳到 [1,10] 再查表, 配置表短了也按最后一档兜底 (防御性, 不让改错配置把服搞崩)。
 */
public final class GranaryConfig {

    /** 默认额度表: 下标 = 等级 − 1。 */
    public static final List<Integer> DEFAULT_BIN_CAP_PER_LEVEL =
            List.of(3, 4, 5, 6, 7, 8, 9, 10, 11, 13);

    public static final ForgeConfigSpec SPEC;
    public static final ForgeConfigSpec.ConfigValue<List<? extends Integer>> BIN_CAP_PER_LEVEL;
    public static final ForgeConfigSpec.IntValue BIN_SLOTS;
    public static final ForgeConfigSpec.IntValue MAX_CENTERS_PER_PLAYER;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.push("granary");
        BIN_CAP_PER_LEVEL = builder
                .comment("Storage bin cap per farmer level (index 0 = L1). Default: 3,4,5,6,7,8,9,10,11,13")
                .defineList("binCapPerLevel", DEFAULT_BIN_CAP_PER_LEVEL,
                        entry -> entry instanceof Integer value && value > 0);
        BIN_SLOTS = builder.comment("Slots per storage bin (same as a barrel)")
                .defineInRange("binSlots", GranaryConstants.BIN_SLOTS, 1, 64);
        MAX_CENTERS_PER_PLAYER = builder.comment("Granary center blocks one player may place")
                .defineInRange("maxCentersPerPlayer", GranaryConstants.MAX_CENTERS_PER_PLAYER, 1, 16);
        builder.pop();
        SPEC = builder.build();
    }

    private GranaryConfig() {
    }

    /** 某农夫等级能放几个储粮囤 (等级钳到 [1,10], 表短了取最后一档)。 */
    public static int binCapForLevel(int farmerLevel) {
        int clamped = Math.max(1, Math.min(10, farmerLevel));
        List<? extends Integer> table = BIN_CAP_PER_LEVEL.get();
        if (table.isEmpty()) {
            return DEFAULT_BIN_CAP_PER_LEVEL.get(0);
        }
        int index = Math.min(clamped, table.size()) - 1;
        Integer value = table.get(index);
        return value == null || value <= 0 ? DEFAULT_BIN_CAP_PER_LEVEL.get(0) : value;
    }

    /** 每个储粮囤几格。 */
    public static int binSlots() {
        return BIN_SLOTS.get();
    }

    /** 每人最多几座账台。 */
    public static int maxCentersPerPlayer() {
        return MAX_CENTERS_PER_PLAYER.get();
    }
}
