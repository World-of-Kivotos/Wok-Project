package com.miningdim.job.engineer;

import com.miningdim.job.engineer.item.NanoArmorPlateItem;
import com.miningdim.job.engineer.armor.item.PlateArmorItem;
import com.miningdim.job.engineer.shield.item.PlasmaShieldItem;
import com.miningdim.job.engineer.effect.NanoAnvilGuard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.ItemStack;

import java.util.EnumSet;
import java.util.Optional;
import java.util.UUID;

/**
 * 纳米修复执行 (MillenniumEngineer_Mod_DesignSpec 五 / 六)。对任意 {@link ItemStack#isDamageableItem() Damageable}
 * 物品改 {@code setDamageValue} —— 这是 "修一切" 的技术基础 (5.3: 含无原版修复配方的模组护甲)。
 *
 * 修复曲线 (5.1, 全 config): 低/中/高 固定值; 极品/超凡 按最大耐久百分比; 闪耀 100% + 清旧特效 + 必出新特效。
 * 特效掷出 (6.1): 高级板起按 (base + coef*品质) 概率掷一个随机特效 (% 最大血量/耐久建模); 再次纳米修复先清旧。
 * 修复经验 (7.4): 板 producerUUID == 修复者才结算修复经验 (用自己板 +50%); 不匹配照修但无经验。
 *
 * Unbreakable NBT 物品 (PENDING 12.6): 无可修耐久, 直接拒绝修复 (返回 fail), 不消耗板 (异常不掩盖, 见 notes)。
 */
public final class NanoRepair {

    private NanoRepair() {
    }

    /** 修复结果 (调用方据此决定是否消耗板、刷新耐久条; 不可变)。 */
    public record Result(boolean success, int durabilityRestored, long repairXpGranted, String failKey) {
        static Result fail(String key) {
            return new Result(false, 0, 0L, key);
        }
    }

    /**
     * 用一块纳米护甲板修复一件护甲。
     *
     * @param armor  待修护甲 (任意 Damageable; 服务端权威, 调用方保证非空)
     * @param plate  纳米护甲板栈 (顶部为 NanoArmorPlateItem; 档位由物品携带)
     * @param player 修复者 (经验归属 + 升级)
     * @param random RNG (特效掷出)
     * @return 修复结果
     */
    public static Result repair(ItemStack armor, ItemStack plate, ServerPlayer player, RandomSource random) {
        if (!(plate.getItem() instanceof NanoArmorPlateItem plateItem)) {
            return Result.fail("message.miningdim.engineer.repair.not_plate");
        }
        if (isShieldType(armor)) {
            return Result.fail("message.miningdim.engineer.repair.shield_incompatible");
        }
        if (armor.isEmpty() || !armor.isDamageableItem()) {
            return Result.fail("message.miningdim.engineer.repair.not_damageable");
        }
        if (armor.getTag() != null && armor.getTag().getBoolean("Unbreakable")) {
            // PENDING 12.6: 不可破坏物品无耐久可修, 拒绝 (不掩盖, 不空耗板)。
            return Result.fail("message.miningdim.engineer.repair.unbreakable");
        }

        NanoTier tier = plateItem.tier();
        int before = armor.getDamageValue();
        if (before <= 0 && !supportsRadiantReroll(armor, tier)) {
            // 满耐久时仅允许对能承载旧纳米特效的普通护甲重掷。插板会清除旧特效且不会获得新特效，
            // 工具上的护甲特效也永远不会生效；两者都必须拒绝，避免空耗闪耀维修套件。
            return Result.fail("message.miningdim.engineer.repair.already_full");
        }

        int maxDamage = armor.getMaxDamage();
        int restore = repairAmount(tier, maxDamage);
        int after = Math.max(0, before - restore);
        if (tier.isRadiant()) {
            after = 0; // 闪耀 100% 修满。
        }
        armor.setDamageValue(after);
        int restored = before - after;

        // 特效结算 (6.1): 闪耀必清旧 + 必出; 高级板起按概率掷 (掷前清旧, 一次性副产品语义)。
        // 品质杠杆 (4.2/6.1): 从板 NBT 读回生产时记录的品质命中数, 品质越高掷出特效概率越高 (chance = base +
        // coef*qualityHits)。闪耀必出不读品质。
        if (armor.getItem() instanceof PlateArmorItem) {
            // 插板允许沿用纳米板维修经济，但不得继承旧纳米护盾/图腾等全免特效，避免两套护甲原理叠加。
            NanoNbt.clearEffects(armor);
        } else if (armor.getItem() instanceof ArmorItem) {
            applyEffectsOnRepair(armor, tier, NanoNbt.qualityHits(plate), random);
        } else {
            // 套件可以维修任意 Damageable，但护甲特效只对穿戴栏生效；工具和武器不得携带无效特效 NBT。
            NanoNbt.clearEffects(armor);
        }

        // 修复经验 (7.4): producerUUID == 修复者才给 (用自己板 +50%); 不匹配无经验。
        long xpGranted = settleRepairXp(plate, tier, player);

        return new Result(true, restored, xpGranted, "");
    }

    /** 5.1 修复量: 低/中/高 固定值; 极品/超凡 按最大耐久百分比; 闪耀由调用方特判 100% (此处返回满量)。 */
    public static int repairAmount(NanoTier tier, int maxDamage) {
        if (tier.isRadiant()) {
            return maxDamage;
        }
        if (tier.isPercentRepair()) {
            double pct = tier == NanoTier.SUPERIOR
                    ? EngineerConfig.REPAIR_PERCENT_SUPERIOR.get()
                    : EngineerConfig.REPAIR_PERCENT_TRANSCENDENT.get();
            return (int) Math.floor(maxDamage * pct);
        }
        return switch (tier) {
            case LOW -> EngineerConfig.REPAIR_FIXED_LOW.get();
            case MEDIUM -> EngineerConfig.REPAIR_FIXED_MEDIUM.get();
            case HIGH -> EngineerConfig.REPAIR_FIXED_HIGH.get();
            default -> 0; // SUPERIOR/TRANSCENDENT/RADIANT 已在上面分支处理。
        };
    }

    /** 闪耀套件在满耐久时仅有“重掷旧纳米特效”这一种有效用途。 */
    public static boolean supportsRadiantReroll(ItemStack target, NanoTier tier) {
        return tier.isRadiant()
                && target.getItem() instanceof ArmorItem
                && !(target.getItem() instanceof PlateArmorItem);
    }

    /**
     * 特效掷出/清空 (6.1)。闪耀: 清旧 + 必出一个新特效。高级板起: 按 (base + coef*品质) 概率掷, 掷前清旧
     * (一次性副产品语义)。低/中级板: 无特效, 但仍清旧 (它们不掷, 不清会留下上一次的特效, 与 "再次纳米修复
     * 丢弃旧特效" 矛盾)。
     */
    static void applyEffectsOnRepair(ItemStack armor, NanoTier tier, int qualityHits, RandomSource random) {
        NanoNbt.clearEffects(armor);
        if (!tier.canRollEffect()) {
            return; // 低/中级板无特效 (已清旧)。
        }
        if (tier.isRadiant()) {
            NanoNbt.writeEffects(armor, EnumSet.of(rollEffect(random)));
            NanoAnvilGuard.stripMendingFromNanoEffectArmor(armor);
            return;
        }
        double chance = EngineerConfig.EFFECT_ROLL_BASE_CHANCE.get()
                + EngineerConfig.EFFECT_ROLL_QUALITY_COEF.get() * qualityHits;
        chance = Math.max(0.0, Math.min(1.0, chance));
        if (random.nextDouble() < chance) {
            NanoNbt.writeEffects(armor, EnumSet.of(rollEffect(random)));
            NanoAnvilGuard.stripMendingFromNanoEffectArmor(armor);
        }
    }

    /** 等离子盾以及带纳米护盾特效的护甲都属于充能护盾，不能使用维修套件。 */
    public static boolean isShieldType(ItemStack stack) {
        return stack.getItem() instanceof PlasmaShieldItem
                || NanoNbt.hasEffect(stack, NanoEffect.SHIELD);
    }

    /** 等概率随机选一个特效 (四选一; 6.2)。 */
    private static NanoEffect rollEffect(RandomSource random) {
        NanoEffect[] all = NanoEffect.values();
        return all[random.nextInt(all.length)];
    }

    /** 7.4 修复经验: 板 producerUUID == 修复者 -> rawXp*(1+ownBonus) 入账; 不匹配 -> 0。 */
    static long settleRepairXp(ItemStack plate, NanoTier tier, ServerPlayer player) {
        Optional<UUID> producer = NanoNbt.producer(plate);
        UUID repairer = player.getUUID();
        if (producer.isEmpty() || !producer.get().equals(repairer)) {
            return 0L; // 小号产板喂大号 / 买来的板: 照修但无修复经验 (9.3 反代练)。
        }
        double bonus = 1.0 + EngineerConfig.OWN_PLATE_REPAIR_XP_BONUS.get();
        long raw = (long) Math.floor(tier.rawXp() * bonus);
        return EngineerLevels.grantRawXp(player, raw);
    }
}
