package com.miningdim.freshness;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

/**
 * FD 煎锅的**手持烹饪** (Spoilage_Granary_DesignSpec「可选配置」#13): 成品不走 {@code ItemUtils},
 * 所以继承与"烂原料不出品"都只能自己接。
 *
 * <p>煎锅物品把菜存在自己的 NBT 子 compound {@code Cooking} 里; 做好之后直接
 * {@code player.getInventory().add(...)} (满了才掉在地上)。判定逻辑收在这里, 两个 mixin 只做转发:
 * 一个按开发环境的官方方法名织入, 一个按正式服的 SRG 名织入 —— 环境里只会有其中一个对得上,
 * 另一个按可选配置的 `defaultRequire = 0` 静默跳过。</p>
 */
public final class FreshnessFdHandCooking {

    /** 这一趟的原料 (方法返回时用来算继承)。 */
    private static final ThreadLocal<ItemStack> SOURCE = new ThreadLocal<>();
    /** 这一趟的预期产物 (只在背包/掉落物里认这份还没盖章的同类物品)。 */
    private static final ThreadLocal<ItemStack> EXPECTED = new ThreadLocal<>();

    private FreshnessFdHandCooking() {
    }

    /**
     * 开始手持烹饪。
     *
     * @return 这一趟要不要**取消** (锅里的东西已经烂了: 不出品, 煎锅原样还回去)
     */
    public static boolean begin(ItemStack skillet, Level level) {
        SOURCE.remove();
        EXPECTED.remove();
        if (!(level instanceof ServerLevel serverLevel)) {
            return false;
        }
        ItemStack food = storedFood(skillet);
        if (food.isEmpty()) {
            return false;
        }
        long nowMinute = FreshnessClockData.nowMinute(serverLevel);
        if (FreshnessProcessing.anyRotten(List.of(food), nowMinute)) {
            return true;
        }
        ItemStack expected = expectedProduct(food, level);
        if (expected.isEmpty() || !FreshnessCategories.spoils(expected)) {
            return false;
        }
        SOURCE.set(food.copy());
        EXPECTED.set(expected);
        return false;
    }

    /** 方法返回: 成品已经进背包或掉了, 找到那份还没盖章的同类物品按原料继承 (加热 +25%)。 */
    public static void stampAtReturn(Level level, LivingEntity user) {
        try {
            ItemStack source = SOURCE.get();
            ItemStack expected = EXPECTED.get();
            if (source == null || expected == null || !(level instanceof ServerLevel serverLevel)) {
                return;
            }
            ItemStack product = findUnstampedProduct(level, user, expected);
            if (product != null) {
                FreshnessProcessing.inherit(product, List.of(source),
                        FreshnessProcessing.HEAT_BONUS, FreshnessClockData.nowMinute(serverLevel));
            }
        } finally {
            SOURCE.remove();
            EXPECTED.remove();
        }
    }

    /** 煎锅物品里存着的那件原料 (FD 1.2.9: 物品 NBT 的 {@code Cooking} 子 compound)。 */
    public static ItemStack storedFood(ItemStack skillet) {
        CompoundTag tag = skillet.getTag();
        if (tag == null || !tag.contains(FreshnessFdTargets.SKILLET_ITEM_COOKING, 10)) {
            return ItemStack.EMPTY;
        }
        return ItemStack.of(tag.getCompound(FreshnessFdTargets.SKILLET_ITEM_COOKING));
    }

    /**
     * 这一锅的预期产物: 用煎锅物品自己的 {@code getCookingRecipe} 查 (与机器那条路同一个配方来源)。
     *
     * <p>反射调用而不是直接引用 FD 的类型: 这个方法本身就是 FD 的静态方法, 但签名里的
     * {@code CampfireCookingRecipe} 是 MC 类型, 反射一次比为了它再暴露一个签名便宜。</p>
     */
    public static ItemStack expectedProduct(ItemStack food, Level level) {
        try {
            Method method = Class.forName(FreshnessFdTargets.SKILLET_ITEM)
                    .getMethod("getCookingRecipe", ItemStack.class, Level.class);
            Object result = method.invoke(null, food, level);
            if (result instanceof Optional<?> optional && optional.isPresent()) {
                Object recipe = optional.get();
                Method resultItem = recipe.getClass()
                        .getMethod("getResultItem", net.minecraft.core.RegistryAccess.class);
                Object stack = resultItem.invoke(recipe, level.registryAccess());
                if (stack instanceof ItemStack itemStack) {
                    return itemStack.copy();
                }
            }
        } catch (ReflectiveOperationException ignored) {
            // FD 换版本就退化成"只拦烂原料": 宁可少盖一章, 也不能崩。
        }
        return ItemStack.EMPTY;
    }

    /** 在玩家背包里 (不行再在附近掉落物里) 找那份还没盖章的产物。 */
    private static ItemStack findUnstampedProduct(Level level, LivingEntity user, ItemStack expected) {
        if (user instanceof ServerPlayer player) {
            for (int slot = 0; slot < player.getInventory().getContainerSize(); slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!stack.isEmpty() && stack.is(expected.getItem()) && !FreshnessData.isStamped(stack)) {
                    return stack;
                }
            }
        }
        AABB box = user.getBoundingBox().inflate(2.0D);
        for (ItemEntity entity : level.getEntitiesOfClass(ItemEntity.class, box)) {
            ItemStack stack = entity.getItem();
            if (!stack.isEmpty() && stack.is(expected.getItem()) && !FreshnessData.isStamped(stack)) {
                return stack;
            }
        }
        return null;
    }
}
