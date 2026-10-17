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

    /** 煎锅的方块实体 (#12)。 */
    public static final String SKILLET = "vectorwing.farmersdelight.common.block.entity.SkilletBlockEntity";

    /** 煎锅出菜: {@code void cookAndOutputItems(ItemStack cookingStack, Level level)}。 */
    public static final String SKILLET_COOK = "cookAndOutputItems";

    /** 炉灶的方块实体 (#14)。 */
    public static final String STOVE = "vectorwing.farmersdelight.common.block.entity.StoveBlockEntity";

    /** 炉灶出菜: {@code void cookAndOutputItems()} (逐格, 无参)。 */
    public static final String STOVE_COOK = "cookAndOutputItems";

    /** 厨锅的方块实体 (#15)。 */
    public static final String POT = "vectorwing.farmersdelight.common.block.entity.CookingPotBlockEntity";

    /** 厨锅把做好的菜挪进输出格: {@code void moveMealToOutput()} (无参, 菜此刻在成品展示格里)。 */
    public static final String POT_MOVE_MEAL = "moveMealToOutput";

    /** 厨锅开始烹饪: {@code boolean processCooking(CookingPotRecipe, CookingPotBlockEntity)}。 */
    public static final String POT_PROCESS = "processCooking";

    /** 厨锅的成品展示格 (1.2.9: 6; 前 6 格是原料)。 */
    public static final int POT_MEAL_SLOT = 6;

    /** 厨锅的输出格 (1.2.9: 8)。 */
    public static final int POT_OUTPUT_SLOT = 8;

    /** 厨锅的原料格数 (0..5)。 */
    public static final int POT_INPUT_SLOTS = 6;

    private FreshnessFdTargets() {
    }
}
