package com.miningdim.enchant;

import com.miningdim.economy.ShopPriceTable;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ArmorMaterials;
import net.minecraft.world.item.AxeItem;
import net.minecraft.world.item.HoeItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.PickaxeItem;
import net.minecraft.world.item.ShovelItem;
import net.minecraft.world.item.SwordItem;
import net.minecraft.world.item.TieredItem;
import net.minecraft.world.item.Tiers;

/**
 * 金钱修补的计价纯逻辑: 算某件装备"修 1 点耐久要多少信用点"。
 *
 * 公式与其理由见 {@link MoneyMendingConfig}。本类只做两件事 —— 认出物品的材质档与用料件数, 据此得出材料
 * 总价; 再除以最大耐久乘倍率。无世界引用, 可纯函数测试。
 *
 * <b>不认识的物品一律不支持</b> (木/石/皮革/锁链/海龟壳, 以及所有 mod 物品含 TaCZ 枪械与铸甲师护甲板)。
 * 宁可让附魔<b>附不上去</b>, 也不给一个含糊的兜底费率 —— 兜底费率正是套利藏身的地方: 只要某件高价装备落进
 * 一个偏低的默认档, "修比买便宜"就成立了。将来要支持它们, 应当为其显式定价再放开。
 */
public final class RepairPricing {

    /** 不支持该物品的哨兵 (与"费用为 0"区分开: 后者会变成免费修补)。 */
    public static final long UNSUPPORTED = -1L;

    private RepairPricing() {
    }

    /**
     * 各类装备的用料件数 (原版合成配方)。armor 的四件套件数差异很大 (5/8/7/4) 而耐久也不同, 故必须逐件算,
     * 不能按材质档一口价 —— 否则同档内部就会出现"某件被低估"的缝。
     */
    private static int unitCount(Item item) {
        if (item instanceof ArmorItem armor) {
            return switch (armor.getType()) {
                case HELMET -> 5;
                case CHESTPLATE -> 8;
                case LEGGINGS -> 7;
                case BOOTS -> 4;
            };
        }
        if (item instanceof PickaxeItem || item instanceof AxeItem) {
            return 3;
        }
        if (item instanceof SwordItem || item instanceof HoeItem) {
            return 2;
        }
        if (item instanceof ShovelItem) {
            return 1;
        }
        return 0; // 不认识的形态。
    }

    /**
     * 单位材料价 (信用点/个)。返回 &lt;= 0 表示该材质档不支持。
     *
     * 下界合金单独处理: 它的物品不是"用 N 个下界合金锭做的", 而是"钻石版 + 1 个下界合金锭升级", 故它的材料
     * 总价要按 {@link #materialValue} 里的升级式算, 本方法对它返回钻石单价 (升级件另加)。
     */
    private static double unitValue(Item item) {
        if (item instanceof TieredItem tiered) {
            var tier = tiered.getTier();
            if (tier == Tiers.IRON) {
                return MoneyMendingConfig.IRON_UNIT_VALUE.get();
            }
            if (tier == Tiers.GOLD) {
                return ShopPriceTable.ORE_BASE_GOLD;
            }
            if (tier == Tiers.DIAMOND || tier == Tiers.NETHERITE) {
                return ShopPriceTable.ORE_BASE_DIAMOND;
            }
            return 0.0D; // 木/石: 重做比修便宜, 不支持。
        }
        if (item instanceof ArmorItem armor) {
            var material = armor.getMaterial();
            if (material == ArmorMaterials.IRON) {
                return MoneyMendingConfig.IRON_UNIT_VALUE.get();
            }
            if (material == ArmorMaterials.GOLD) {
                return ShopPriceTable.ORE_BASE_GOLD;
            }
            if (material == ArmorMaterials.DIAMOND || material == ArmorMaterials.NETHERITE) {
                return ShopPriceTable.ORE_BASE_DIAMOND;
            }
            return 0.0D; // 皮革/锁链/海龟壳: 无锚价且价值低, 不支持。
        }
        return 0.0D;
    }

    /** 该物品是不是下界合金档 (材料总价要额外加一个下界合金锭)。 */
    private static boolean isNetherite(Item item) {
        if (item instanceof TieredItem tiered) {
            return tiered.getTier() == Tiers.NETHERITE;
        }
        return item instanceof ArmorItem armor && armor.getMaterial() == ArmorMaterials.NETHERITE;
    }

    /** 物品的材料总价 (信用点); 不支持返回 0。 */
    private static double materialValue(Item item) {
        int units = unitCount(item);
        double unit = unitValue(item);
        if (units <= 0 || unit <= 0.0D) {
            return 0.0D;
        }
        double base = units * unit;
        return isNetherite(item) ? base + MoneyMendingConfig.netheriteIngotValue() : base;
    }

    /** 该物品能否被金钱修补附上 (供 {@code canEnchant} 与面板判定)。 */
    public static boolean supports(Item item) {
        return item.canBeDepleted() && item.getMaxDamage() > 0 && materialValue(item) > 0.0D;
    }

    /**
     * 修 1 点耐久的费用 (信用点, 向上取整且至少 1)。不支持的物品返回 {@link #UNSUPPORTED}。
     *
     * 向上取整而非四舍五入: 便宜档 (钻石镐约 1.92) 若向下取整会掉到 1, 与倍率无关地打了折; 至少 1 则保证
     * 永远不存在"免费自动修补"这种状态 —— 那正是这个附魔最不该变成的东西。
     */
    public static long costPerDurability(ItemStack stack) {
        Item item = stack.getItem();
        if (!supports(item)) {
            return UNSUPPORTED;
        }
        double perPoint = materialValue(item) / item.getMaxDamage() * MoneyMendingConfig.PRICE_MULTIPLIER.get();
        return Math.max(1L, (long) Math.ceil(perPoint));
    }
}
