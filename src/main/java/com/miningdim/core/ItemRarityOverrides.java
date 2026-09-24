package com.miningdim.core;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

/**
 * {@code Item.getRarity(ItemStack)} 的覆盖接缝。core 自有的 {@code ItemRarityOverrideMixin} 只认这里,
 * 业务模块在 mod 构造期注册解析器, mixin 因此不直接引用任何业务包 (模块边界见 docs/modules/module-registry.json,
 * 这正是债务 D036 退出条件里要求的"core 自有接口"形态)。
 *
 * 解析器会在客户端渲染线程 (tooltip 首行、快捷栏物品名) 与服务端线程 (聊天栏 [物品] 名、死亡消息) 上被调用,
 * 必须只读、无副作用、足够便宜。返回 null 表示不覆盖, 交回原版逻辑 (包括附魔后的稀有度升档)。
 */
public final class ItemRarityOverrides {
    private static final List<Function<ItemStack, Rarity>> RESOLVERS = new CopyOnWriteArrayList<>();

    private ItemRarityOverrides() {
    }

    /** 只应在 mod 构造期调用; 先注册者优先。 */
    public static void register(Function<ItemStack, Rarity> resolver) {
        RESOLVERS.add(Objects.requireNonNull(resolver, "resolver"));
    }

    /** 第一个给出非 null 结果的解析器胜出; 都不认领时返回 null。 */
    public static Rarity resolve(ItemStack stack) {
        for (Function<ItemStack, Rarity> resolver : RESOLVERS) {
            Rarity rarity = resolver.apply(stack);
            if (rarity != null) {
                return rarity;
            }
        }
        return null;
    }
}
