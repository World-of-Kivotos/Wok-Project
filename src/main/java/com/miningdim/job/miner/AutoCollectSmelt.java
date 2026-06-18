package com.miningdim.job.miner;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;

import java.util.List;
import java.util.Optional;

/**
 * 便利偏好 (Miner_Job_DesignSpec 第五章): 自动入包 (满则落地, L2) + 自动熔炼 (1:1 不增量, 铁/铜 L6, 金 L8, 可关)。
 *
 * 自动熔炼用原版 {@link RecipeType#SMELTING} 查表: 输入物对应的熔炼产物 1:1 替换 (输入 count 个 -> 产物 count 个)。
 * 严防增量: 只把可熔炼输入换成等量产物, 不复制、不乘倍。可熔炼范围按等级 (铁/铜 L6, 金 L8) 限定, 其它矿石不熔炼。
 *
 * 自动入包: 把一组掉落物直接塞玩家库存, 满则落地 (不丢失)。供 BreakEvent 拦掉落后调用。
 *
 * 纯副作用执行 (库存写), 不持状态; 开关位在 {@link MinerChargeState}。
 */
public final class AutoCollectSmelt {

    private AutoCollectSmelt() {
    }

    /**
     * 对一组掉落物按 (是否自动熔炼/熔炼范围) 折算后入包 (满则落地)。
     *
     * @param player       挖矿者
     * @param level        矿工等级 (决定熔炼范围)
     * @param drops        原始掉落物 (连锁/普通挖掘产出)
     * @param autoCollect  自动入包开关
     * @param autoSmelt    自动熔炼开关
     */
    public static void collect(ServerPlayer player, int level, List<ItemStack> drops,
                               boolean autoCollect, boolean autoSmelt) {
        if (!autoCollect) {
            return; // 不入包: 由原版掉落处理 (掉地上)。
        }
        ServerLevel serverLevel = player.serverLevel();
        for (ItemStack original : drops) {
            if (original.isEmpty()) {
                continue;
            }
            ItemStack result = original;
            if (autoSmelt) {
                result = smeltResult(serverLevel, original, level);
            }
            giveOrDrop(player, result);
        }
    }

    /**
     * 自动熔炼折算 (1:1 不增量): 若 input 可熔炼且其产物在本等级允许范围内, 返回等量产物; 否则原样返回 input。
     *
     * @param level 矿工等级 (L6 铁/铜, L8 加金; 其它矿石不在范围, 原样返回)
     */
    public static ItemStack smeltResult(ServerLevel level, ItemStack input, int minerLevel) {
        if (!MinerSkills.autoSmeltBaseUnlocked(minerLevel)) {
            return input; // 未解锁基础熔炼。
        }
        SimpleContainer probe = new SimpleContainer(input);
        Optional<SmeltingRecipe> recipe =
                level.getRecipeManager().getRecipeFor(RecipeType.SMELTING, probe, level);
        if (recipe.isEmpty()) {
            return input; // 无熔炼配方: 不熔炼 (如石头/煤本身)。
        }
        ItemStack product = recipe.get().getResultItem(level.registryAccess());
        if (product.isEmpty()) {
            return input;
        }
        // 限定范围: 只熔炼锭类产物 (铁/铜 L6 起, 金 L8 起); 其它熔炼产物 (玻璃/木炭/食物) 不在自动熔炼范围, 防误熔。
        if (isGoldProduct(product) && !MinerSkills.autoSmeltGoldUnlocked(minerLevel)) {
            return input; // 金未解锁 (L6-7): 不熔炼金矿, 原样返回。
        }
        if (!isAllowedSmeltProduct(product)) {
            return input;
        }
        // 1:1 不增量: 产物个数 = 输入个数 (单份产物 stack 复制并设 count)。
        ItemStack out = product.copy();
        out.setCount(input.getCount());
        return out;
    }

    /** 入包, 满则落地 (不丢失)。 */
    private static void giveOrDrop(ServerPlayer player, ItemStack stack) {
        if (stack.isEmpty()) {
            return;
        }
        ItemStack toAdd = stack.copy();
        boolean added = player.getInventory().add(toAdd);
        if (!added || !toAdd.isEmpty()) {
            player.drop(toAdd, false);
        }
    }

    /** 产物是否为金锭 (金熔炼范围判据)。 */
    private static boolean isGoldProduct(ItemStack product) {
        return product.is(net.minecraft.world.item.Items.GOLD_INGOT);
    }

    /**
     * 允许自动熔炼的产物白名单 (1:1 锭): 铁锭/铜锭 (L6 起), 金锭 (L8 起, 由 isGoldProduct 单独门控)。
     * 其它熔炼产物 (玻璃/木炭/食物等) 不在自动熔炼范围, 原样返回防误熔。
     */
    private static boolean isAllowedSmeltProduct(ItemStack product) {
        return product.is(net.minecraft.world.item.Items.IRON_INGOT)
                || product.is(net.minecraft.world.item.Items.COPPER_INGOT)
                || product.is(net.minecraft.world.item.Items.GOLD_INGOT);
    }
}
