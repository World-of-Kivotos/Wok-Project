package com.miningdim.freshness;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

/**
 * 六类保质期 (Spoilage_Granary_DesignSpec「保质期与豁免」)。
 *
 * 类别只由物品 tag 决定 ({@code data/miningdim/tags/items/freshness/*.json}), 保质期数值写在配置里:
 * 改配置全服存量立刻按新值算, 因为保质期**不写进物品**。判定顺序见 {@link FreshnessCategories}。
 */
public enum FreshnessCategory {

    /** 生鲜 12 小时: 生肉、生鱼、肉馅、海鲜。只有冷库起收。 */
    FRESH("fresh", 12),

    /** 果蔬/蛋/面团 36 小时。 */
    PRODUCE("produce", 36),

    /** 熟食与菜品 48 小时, 也是「能吃但没标类别」的兜底类。 */
    COOKED("cooked", 48),

    /** 根茎/瓜 48 小时。 */
    ROOT("root", 48),

    /** 谷物/干货 48 小时。 */
    GRAIN("grain", 48),

    /** 腌制/风干 7 天: 唯一能在外面放一周的类别。 */
    PRESERVED("preserved", 168);

    private final String tagPath;
    private final int defaultShelfLifeHours;

    FreshnessCategory(String tagPath, int defaultShelfLifeHours) {
        this.tagPath = tagPath;
        this.defaultShelfLifeHours = defaultShelfLifeHours;
    }

    /** tag 名前缀 (相对 {@code freshness/})。 */
    public String tagPath() {
        return tagPath;
    }

    /** 配置缺省时的保质期; 实际取值一律经 {@link FreshnessConfig#shelfLifeHours}。 */
    public int defaultShelfLifeHours() {
        return defaultShelfLifeHours;
    }

    /** 物品 tag: {@code miningdim:freshness/<tagPath>}。 */
    public TagKey<Item> tag() {
        return TagKey.create(Registries.ITEM,
                new ResourceLocation(MiningConstants.MODID, "freshness/" + tagPath));
    }

    /** 语言键 (tooltip 第二行「类别」)。 */
    public String translationKey() {
        return "tooltip.miningdim.freshness.category." + tagPath;
    }
}
