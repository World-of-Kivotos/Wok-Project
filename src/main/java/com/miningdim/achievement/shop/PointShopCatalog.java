package com.miningdim.achievement.shop;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 成就点商店当前上架的商品 (不可变快照)。{@link PointShopLoader} 在每次数据包 (重新) 加载后整表替换, 装进
 * {@code AchievementServices}; 列表与兑换都只读这份快照。
 *
 * 展示顺序: sort 降序, 同 sort 按 id 升序 (与称号列表同一口径)。
 */
public final class PointShopCatalog {

    /** 开服前、停服后与没有任何商品时的空快照。 */
    public static final PointShopCatalog EMPTY = new PointShopCatalog(List.of());

    private static final Comparator<PointShopGoods> DISPLAY_ORDER = Comparator
            .comparingInt(PointShopGoods::sort).reversed()
            .thenComparing(PointShopGoods::id);

    private final List<PointShopGoods> ordered;
    private final Map<ResourceLocation, PointShopGoods> byId;

    private PointShopCatalog(List<PointShopGoods> goods) {
        List<PointShopGoods> sorted = new ArrayList<>(goods);
        sorted.sort(DISPLAY_ORDER);
        Map<ResourceLocation, PointShopGoods> index = new LinkedHashMap<>();
        for (PointShopGoods entry : sorted) {
            if (index.put(entry.id(), entry) != null) {
                throw new IllegalArgumentException("duplicate point shop goods id " + entry.id());
            }
        }
        this.ordered = Collections.unmodifiableList(sorted);
        this.byId = Collections.unmodifiableMap(index);
    }

    /** 用一组商品建快照。 */
    public static PointShopCatalog of(Collection<PointShopGoods> goods) {
        return goods.isEmpty() ? EMPTY : new PointShopCatalog(List.copyOf(goods));
    }

    /** 按展示顺序的全部商品。 */
    public List<PointShopGoods> all() {
        return ordered;
    }

    public Optional<PointShopGoods> goods(ResourceLocation id) {
        return Optional.ofNullable(byId.get(id));
    }

    public int size() {
        return ordered.size();
    }
}
