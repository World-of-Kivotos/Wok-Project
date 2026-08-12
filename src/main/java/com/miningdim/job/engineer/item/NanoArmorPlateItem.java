package com.miningdim.job.engineer.item;

import com.miningdim.job.engineer.NanoNbt;
import com.miningdim.job.engineer.NanoRepair;
import com.miningdim.job.engineer.NanoTier;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.Stats;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * 纳米维修套件物品 (MillenniumEngineer_Mod_DesignSpec 3.1)。保留 nano_plate 注册 ID 兼容旧存档；六档各一个实例,
 * 档位构造时绑定 (与 EntranceBlock
 * 按难度绑定同范式)。承载 NBT (经 {@link NanoNbt}): producerUUID (经验归属) + productionXpPending (取走即清)。
 *
 * 修复入口 (5.3): 一手持套件、一手持待修物品可精确指定目标；未指定时修复“损耗比例最高的穿戴中护甲”。
 * 成功消耗一个套件。经 {@link NanoRepair} 改 setDamageValue (修一切), 结算修复经验 (producerUUID 匹配 +50%),
 * 掷/清特效。集中式选目标避免从零搭一套修复 GUI, 同时满足 "修任意 Damageable" (含模组护甲)。
 */
public final class NanoArmorPlateItem extends Item {

    private final NanoTier tier;

    public NanoArmorPlateItem(Properties properties, NanoTier tier) {
        super(properties);
        this.tier = tier;
    }

    public NanoTier tier() {
        return tier;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack plate = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return InteractionResultHolder.success(plate);
        }

        ItemStack target = pickRepairTarget(serverPlayer, hand, tier);
        if (target == null) {
            serverPlayer.displayClientMessage(
                    Component.translatable("message.miningdim.engineer.repair.no_target"), true);
            return InteractionResultHolder.fail(plate);
        }

        NanoRepair.Result result = NanoRepair.repair(target, plate, serverPlayer,
                serverPlayer.serverLevel().getRandom());
        if (!result.success()) {
            serverPlayer.displayClientMessage(Component.translatable(result.failKey()), true);
            return InteractionResultHolder.fail(plate);
        }

        plate.shrink(1);
        serverPlayer.awardStat(Stats.ITEM_USED.get(this));
        serverPlayer.containerMenu.broadcastChanges();
        if (result.durabilityRestored() > 0) {
            serverPlayer.displayClientMessage(Component.translatable(
                    "message.miningdim.engineer.repair.done", target.getHoverName(), result.durabilityRestored()), true);
        } else {
            serverPlayer.displayClientMessage(Component.translatable(
                    "message.miningdim.engineer.repair.rerolled", target.getHoverName()), true);
        }
        return InteractionResultHolder.success(plate);
    }

    /**
     * 选修复目标: 优先另一只手明确指定的 Damageable 物品；没有合法指定目标时，再选穿戴中损耗比例最高的护甲。
     * 全无可修则返回 null。选目标口径委派给纯函数 {@link #chooseRepairTarget}。
     */
    private static ItemStack pickRepairTarget(ServerPlayer player, InteractionHand usedHand, NanoTier plateTier) {
        InteractionHand other = usedHand == InteractionHand.MAIN_HAND
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack otherStack = player.getItemInHand(other);
        return chooseRepairTarget(otherStack, player.getInventory().armor, plateTier);
    }

    /** 纯函数目标决策：另一只手用于精确指定，穿戴甲作为便捷兜底。 */
    public static ItemStack chooseRepairTarget(ItemStack explicitlyHeld, Iterable<ItemStack> wornArmor,
                                               NanoTier plateTier) {
        if (eligibleAsTarget(explicitlyHeld, plateTier)) {
            return explicitlyHeld;
        }
        return selectWornTarget(wornArmor, plateTier);
    }

    /**
     * 从一组穿戴甲中选修复目标 (纯函数, 供 GameTest 直接断言, 无 ServerPlayer 依赖)。按已损耐久比例选择，
     * 避免高耐久物品仅凭绝对损耗值抢走低耐久但濒临损坏物品的维修。闪耀板
     * 可对能承载旧纳米特效的满耐久普通护甲重掷特效；插板和工具满耐久时没有有效收益，不会被选中。
     * 全无合格目标返回 null (调用方据此报 no_target, 不空耗套件)。
     */
    public static ItemStack selectWornTarget(Iterable<ItemStack> wornArmor, NanoTier plateTier) {
        ItemStack best = null;
        for (ItemStack armor : wornArmor) {
            if (!eligibleAsTarget(armor, plateTier)) {
                continue;
            }
            if (best == null || isMoreDamaged(armor, best)) {
                best = armor;
            }
        }
        return best;
    }

    /** 单件 Damageable 物品是否可作修复目标；与 NanoRepair 的拒绝边界保持一致，避免非法物品阻塞兜底目标。 */
    public static boolean eligibleAsTarget(ItemStack stack, NanoTier plateTier) {
        if (stack.isEmpty() || !stack.isDamageableItem() || NanoRepair.isShieldType(stack)) {
            return false;
        }
        if (stack.getTag() != null && stack.getTag().getBoolean("Unbreakable")) {
            return false;
        }
        return stack.getDamageValue() > 0 || NanoRepair.supportsRadiantReroll(stack, plateTier);
    }

    private static boolean isMoreDamaged(ItemStack candidate, ItemStack current) {
        long candidateRatio = (long) candidate.getDamageValue() * current.getMaxDamage();
        long currentRatio = (long) current.getDamageValue() * candidate.getMaxDamage();
        if (candidateRatio != currentRatio) {
            return candidateRatio > currentRatio;
        }
        return candidate.getDamageValue() > current.getDamageValue();
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.miningdim.nano_plate.tier",
                        Component.translatable("tier.miningdim.nano." + tier.name().toLowerCase()))
                .withStyle(ChatFormatting.GRAY));
        if (tier.isRadiant()) {
            tooltip.add(Component.translatable("tooltip.miningdim.nano_plate.repair_full")
                    .withStyle(ChatFormatting.AQUA));
        } else if (tier.isPercentRepair()) {
            int percent = (int) Math.round((tier == NanoTier.SUPERIOR
                    ? com.miningdim.job.engineer.EngineerConfig.REPAIR_PERCENT_SUPERIOR.get()
                    : com.miningdim.job.engineer.EngineerConfig.REPAIR_PERCENT_TRANSCENDENT.get()) * 100.0D);
            tooltip.add(Component.translatable("tooltip.miningdim.nano_plate.repair_percent", percent)
                    .withStyle(ChatFormatting.AQUA));
        } else {
            tooltip.add(Component.translatable("tooltip.miningdim.nano_plate.repair_fixed",
                            NanoRepair.repairAmount(tier, 1))
                    .withStyle(ChatFormatting.AQUA));
        }
        tooltip.add(Component.translatable(tier.canRollEffect()
                        ? (tier.isRadiant()
                                ? "tooltip.miningdim.nano_plate.effect_guaranteed"
                                : "tooltip.miningdim.nano_plate.effect_possible")
                        : "tooltip.miningdim.nano_plate.effect_none")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("tooltip.miningdim.nano_plate.use_explicit")
                .withStyle(ChatFormatting.YELLOW));
        tooltip.add(Component.translatable("tooltip.miningdim.nano_plate.use_worn")
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.nano_plate.plasma_incompatible")
                .withStyle(ChatFormatting.DARK_RED));
        Optional<UUID> producer = NanoNbt.producer(stack);
        if (producer.isPresent()) {
            tooltip.add(Component.translatable("tooltip.miningdim.nano_plate.stamped")
                    .withStyle(ChatFormatting.GRAY));
        }
    }
}
