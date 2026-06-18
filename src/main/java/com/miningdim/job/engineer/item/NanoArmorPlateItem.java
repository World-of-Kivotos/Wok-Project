package com.miningdim.job.engineer.item;

import com.miningdim.job.engineer.NanoNbt;
import com.miningdim.job.engineer.NanoRepair;
import com.miningdim.job.engineer.NanoTier;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
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
 * 纳米护甲板物品 (MillenniumEngineer_Mod_DesignSpec 3.1)。六档各一个实例, 档位构造时绑定 (与 EntranceBlock
 * 按难度绑定同范式)。承载 NBT (经 {@link NanoNbt}): producerUUID (经验归属) + productionXpPending (取走即清)。
 *
 * 修复入口 (5.3): 手持护甲板右键 -> 修复 "最破损的穿戴中护甲" (无穿戴破损时改修副手 Damageable 物品),
 * 成功消耗一块板。经 {@link NanoRepair} 改 setDamageValue (修一切), 结算修复经验 (板 producerUUID 匹配 +50%),
 * 掷/清特效。集中式选目标 (最破损) 避免从零搭一套修复 GUI, 同时满足 "修任意 Damageable" (含模组护甲)。
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

        ItemStack target = pickRepairTarget(serverPlayer, hand);
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
        serverPlayer.containerMenu.broadcastChanges();
        serverPlayer.displayClientMessage(Component.translatable(
                "message.miningdim.engineer.repair.done", result.durabilityRestored()), true);
        return InteractionResultHolder.success(plate);
    }

    /**
     * 选修复目标: 优先穿戴中最破损 (damageValue 最大) 的护甲; 其次副手若为 Damageable 且破损 (避免与持板手冲突)。
     * 全无可修则返回 null。
     */
    private static ItemStack pickRepairTarget(ServerPlayer player, InteractionHand usedHand) {
        ItemStack best = null;
        int bestDamage = 0;
        for (ItemStack armor : player.getInventory().armor) {
            if (!armor.isEmpty() && armor.isDamageableItem() && armor.getDamageValue() > bestDamage) {
                best = armor;
                bestDamage = armor.getDamageValue();
            }
        }
        if (best != null) {
            return best;
        }
        // 无破损护甲: 试副手 (持板手是主手时) / 主手 (持板手是副手时) 的 Damageable 物品。
        InteractionHand other = usedHand == InteractionHand.MAIN_HAND
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack otherStack = player.getItemInHand(other);
        if (!otherStack.isEmpty() && otherStack.isDamageableItem() && otherStack.getDamageValue() > 0) {
            return otherStack;
        }
        return null;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        tooltip.add(Component.translatable("tooltip.miningdim.nano_plate.tier",
                Component.translatable("tier.miningdim.nano." + tier.name().toLowerCase())));
        Optional<UUID> producer = NanoNbt.producer(stack);
        if (producer.isPresent()) {
            tooltip.add(Component.translatable("tooltip.miningdim.nano_plate.stamped"));
        }
    }
}
