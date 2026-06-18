package com.miningdim.job.engineer;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.Ingredient;

/**
 * 纳米护甲板档位 (MillenniumEngineer_Mod_DesignSpec 3.1)。五档 (低/中/高/极品/超凡) + 闪耀特例, 供生产/修复/
 * 特效逻辑共用, 避免散落比较。每档绑定: 矿石配方 (识别 + 单板消耗经 config) / 解锁等级 (7.2) / 修复模型
 * (固定值或耐久百分比) / 单档原始经验 (7.4) / 是否可掷特效 (6.1 高档起)。
 *
 * 全部数值经 {@link EngineerConfig} 实时 get (10.3 C6: 业务类内硬编码字面量即缺陷); 本枚举只承载档位结构 +
 * 矿石身份 (哪种原版物品 + 解锁等级 + 是否百分比修复 + 是否掷特效) 这些与档位绑定不会调的结构性事实。
 */
public enum NanoTier {

    /** 低级板: 4 铁锭 -> 1 板; L1; 固定值修复; 无特效。 */
    LOW(0, 1, false, false),

    /** 中级板: 5 金锭 -> 1 板; L3; 固定值修复; 无特效。 */
    MEDIUM(1, 3, false, false),

    /** 高级板: 3 钻石 -> 1 板; L5; 固定值修复; 特效从此开始。 */
    HIGH(2, 5, false, true),

    /** 极品板: 1 下界合金锭 -> 2 板; L7; 按最大耐久百分比修复; 可掷特效。 */
    SUPERIOR(3, 7, true, true),

    /** 超凡板: 1 下界合金锭 -> 1 板; L9; 更高百分比修复; 可掷特效。 */
    TRANSCENDENT(4, 9, true, true),

    /** 闪耀板: 2 下界合金锭 -> 概率 1 板; L10; 100% + 清旧特效 + 必出新特效。 */
    RADIANT(5, 10, true, true);

    private final int index;
    private final int unlockLevel;
    private final boolean percentRepair;
    private final boolean canRollEffect;

    NanoTier(int index, int unlockLevel, boolean percentRepair, boolean canRollEffect) {
        this.index = index;
        this.unlockLevel = unlockLevel;
        this.percentRepair = percentRepair;
        this.canRollEffect = canRollEffect;
    }

    /** 稳定序号 (clickMenuButton tierIndex / NBT 持久化用); 与 {@link #ordinal()} 一致但语义显式。 */
    public int index() {
        return index;
    }

    /** 该档解锁所需工程师等级 (7.2 等级解锁表)。 */
    public int unlockLevel() {
        return unlockLevel;
    }

    /** 修复模型是否按最大耐久百分比 (true=百分比, false=固定值); RADIANT 走 100% 特例不读此位。 */
    public boolean isPercentRepair() {
        return percentRepair;
    }

    /** 修复时是否可能掷出特效 (6.1: 高级板起; RADIANT 必出由修复逻辑特判)。 */
    public boolean canRollEffect() {
        return canRollEffect;
    }

    /** 闪耀特例 (100% 修满 + 清旧 + 必出特效)。 */
    public boolean isRadiant() {
        return this == RADIANT;
    }

    /**
     * 该档的输入矿石 Ingredient (3.2 矿石绑档生产配方)。用于生产台输入槽识别 "投的是哪种矿"。
     * 闪耀/超凡/极品共用下界合金锭, 由 {@link #maxTierForOre(ItemStack)} 据矿种返回所允许最高档。
     */
    public Ingredient oreIngredient() {
        return switch (this) {
            case LOW -> Ingredient.of(Items.IRON_INGOT);
            case MEDIUM -> Ingredient.of(Items.GOLD_INGOT);
            case HIGH -> Ingredient.of(Items.DIAMOND);
            case SUPERIOR, TRANSCENDENT, RADIANT -> Ingredient.of(Items.NETHERITE_INGOT);
        };
    }

    /** 该档单板矿石消耗 (实时 config; 3.2 表)。 */
    public int oreCost() {
        return switch (this) {
            case LOW -> EngineerConfig.LOW_IRON_COST.get();
            case MEDIUM -> EngineerConfig.MEDIUM_GOLD_COST.get();
            case HIGH -> EngineerConfig.HIGH_DIAMOND_COST.get();
            case SUPERIOR -> EngineerConfig.SUPERIOR_NETHERITE_COST.get();
            case TRANSCENDENT -> EngineerConfig.TRANSCENDENT_NETHERITE_COST.get();
            case RADIANT -> EngineerConfig.RADIANT_NETHERITE_COST.get();
        };
    }

    /** 该档一次成功生产的护甲板产出数 (极品 2 板, 其余 1 板; 闪耀概率由生产逻辑处理, 成功时此值)。 */
    public int outputCount() {
        return this == SUPERIOR ? EngineerConfig.SUPERIOR_OUTPUT_COUNT.get() : 1;
    }

    /** 该档单档原始经验 (实时 config; 7.4 表)。 */
    public int rawXp() {
        return switch (this) {
            case LOW -> EngineerConfig.RAW_XP_LOW.get();
            case MEDIUM -> EngineerConfig.RAW_XP_MEDIUM.get();
            case HIGH -> EngineerConfig.RAW_XP_HIGH.get();
            case SUPERIOR -> EngineerConfig.RAW_XP_SUPERIOR.get();
            case TRANSCENDENT -> EngineerConfig.RAW_XP_TRANSCENDENT.get();
            case RADIANT -> EngineerConfig.RAW_XP_RADIANT.get();
        };
    }

    /** 该档生成耗时 (tick; 实时 config; 4.1 机器档决定速度)。 */
    public int produceTicks() {
        return switch (this) {
            case LOW -> EngineerConfig.PRODUCE_TICKS_LOW.get();
            case MEDIUM -> EngineerConfig.PRODUCE_TICKS_MEDIUM.get();
            case HIGH -> EngineerConfig.PRODUCE_TICKS_HIGH.get();
            case SUPERIOR -> EngineerConfig.PRODUCE_TICKS_SUPERIOR.get();
            case TRANSCENDENT -> EngineerConfig.PRODUCE_TICKS_TRANSCENDENT.get();
            case RADIANT -> EngineerConfig.PRODUCE_TICKS_RADIANT.get();
        };
    }

    /** 稳定序号还原档位 (NBT/网络越界回 LOW, 不掩盖业务但防数组越界崩溃)。 */
    public static NanoTier byIndex(int idx) {
        NanoTier[] all = values();
        if (idx < 0 || idx >= all.length) {
            return LOW;
        }
        return all[idx];
    }

    /**
     * 给定输入矿石栈, 返回它所允许生产的最高档位 (3.2: 低矿造不了高板, 高矿可降级造低板)。
     * 不是该矿种则返回 null (调用方据此拒绝, 不静默掩盖)。
     *
     * 矿种 -> 最高档映射: 铁=LOW; 金=MEDIUM; 钻=HIGH; 下界合金锭=RADIANT (含极品/超凡/闪耀全部高档)。
     */
    public static NanoTier maxTierForOre(ItemStack ore) {
        if (ore.isEmpty()) {
            return null;
        }
        if (ore.is(Items.IRON_INGOT)) {
            return LOW;
        }
        if (ore.is(Items.GOLD_INGOT)) {
            return MEDIUM;
        }
        if (ore.is(Items.DIAMOND)) {
            return HIGH;
        }
        if (ore.is(Items.NETHERITE_INGOT)) {
            return RADIANT;
        }
        return null;
    }

    /** 目标档 target 是否 <= 矿种允许的最高档 maxAllowed (3.2 矿石绑档门)。 */
    public boolean allowedByOre(NanoTier maxAllowed) {
        return maxAllowed != null && this.index <= maxAllowed.index;
    }
}
