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
        serverPlayer.containerMenu.broadcastChanges();
        serverPlayer.displayClientMessage(Component.translatable(
                "message.miningdim.engineer.repair.done", result.durabilityRestored()), true);
        return InteractionResultHolder.success(plate);
    }

    /**
     * 选修复目标: 优先穿戴中最破损 (damageValue 最大) 的护甲; 其次副手若为 Damageable 且可修 (避免与持板手冲突)。
     * 全无可修则返回 null。选目标的档位口径委派给纯函数 {@link #selectWornTarget} / {@link #eligibleAsTarget}。
     */
    private static ItemStack pickRepairTarget(ServerPlayer player, InteractionHand usedHand, NanoTier plateTier) {
        ItemStack best = selectWornTarget(player.getInventory().armor, plateTier);
        if (best != null) {
            return best;
        }
        // 无 (可修) 护甲: 试副手 (持板手是主手时) / 主手 (持板手是副手时) 的 Damageable 物品。
        InteractionHand other = usedHand == InteractionHand.MAIN_HAND
                ? InteractionHand.OFF_HAND : InteractionHand.MAIN_HAND;
        ItemStack otherStack = player.getItemInHand(other);
        return eligibleAsTarget(otherStack, plateTier) ? otherStack : null;
    }

    /**
     * 从一组穿戴甲中选修复目标 (纯函数, 供 GameTest 直接断言, 无 ServerPlayer 依赖)。普通档选最破损一件; 闪耀板
     * 即便满耐久 (damage=0) 也可选作目标 —— spec 6.1/5.1 line 117: 闪耀 = 100% + 清旧特效 + 必出新特效, 作用于
     * "任意" 护甲, 与 {@link NanoRepair} line 57 对闪耀放行满甲 ({@code before<=0 && !isRadiant()} 才拒) 的口径对齐。
     *
     * 闪耀的 bestDamage 起点取 -1 (使 damage=0 也能命中 {@code > bestDamage}), 普通档保持 0 (满甲无修复意义不选)。
     * 全无合格目标返回 null (调用方据此报 no_target, 不空耗板)。
     */
    public static ItemStack selectWornTarget(Iterable<ItemStack> wornArmor, NanoTier plateTier) {
        ItemStack best = null;
        int bestDamage = plateTier.isRadiant() ? -1 : 0;
        for (ItemStack armor : wornArmor) {
            if (!armor.isEmpty() && armor.isDamageableItem() && armor.getDamageValue() > bestDamage) {
                best = armor;
                bestDamage = armor.getDamageValue();
            }
        }
        return best;
    }

    /** 单件 Damageable 物品是否可作修复目标 (闪耀允许满耐久换特效; 普通档要求实际破损)。 */
    public static boolean eligibleAsTarget(ItemStack stack, NanoTier plateTier) {
        if (stack.isEmpty() || !stack.isDamageableItem()) {
            return false;
        }
        int floor = plateTier.isRadiant() ? -1 : 0;
        return stack.getDamageValue() > floor;
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
