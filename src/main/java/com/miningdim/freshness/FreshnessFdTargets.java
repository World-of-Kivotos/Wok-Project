package com.miningdim.freshness;

/**
 * FD (Farmer's Delight) 兼容 mixin 的目标与成员名 (Spoilage_Granary_DesignSpec「可选配置」#10、#11)。
 *
 * <p>写成字符串而不是直接引用 FD 的类型: FD 是**可选**依赖, 正式服没装它时我们的 mixin 类也不该去解析
 * 它。配合 {@code @Pseudo} 与 {@link FreshnessCompatMixinPlugin} 的"没装就整体跳过", 这些类在任何环境下
 * 都加载得起来。</p>
 *
 * <p>签名对准整合包里的 <b>1.2.9</b> (docs/Spoilage_Granary_DesignSpec.md 附录)。换 FD 版本必须先在
 * 测试服过一遍: 目标方法找不到时注入点不会织入, 由 {@code FreshnessCuttingBoardGameTests} 当场发现。</p>
 */
public final class FreshnessFdTargets {

    /** 砧板的方块实体。 */
    public static final String CUTTING_BOARD =
            "vectorwing.farmersdelight.common.block.entity.CuttingBoardBlockEntity";

    /** 砧板处理: {@code boolean processStoredItemUsingTool(ItemStack tool, Player player)}。 */
    public static final String CUTTING_BOARD_PROCESS = "processStoredItemUsingTool";

    /** 砧板板上那件东西: {@code ItemStack getStoredItem()}。 */
    public static final String CUTTING_BOARD_STORED = "getStoredItem";

    /** FD 统一喷物品的工具类 (砧板、锅、炉灶的产物都从这里出去)。 */
    public static final String ITEM_UTILS = "vectorwing.farmersdelight.common.utility.ItemUtils";

    /** 喷一件物品: {@code void spawnItemEntity(Level, ItemStack, double×6)}。 */
    public static final String SPAWN_ITEM_ENTITY = "spawnItemEntity";

    private FreshnessFdTargets() {
    }
}
