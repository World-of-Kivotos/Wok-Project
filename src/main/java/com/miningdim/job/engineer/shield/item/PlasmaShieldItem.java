package com.miningdim.job.engineer.shield.item;

import com.miningdim.job.engineer.EngineerConfig;
import com.miningdim.job.engineer.shield.PlasmaShieldConfig;
import com.miningdim.job.engineer.shield.PlasmaShieldEquipmentMaterial;
import com.miningdim.job.engineer.shield.PlasmaShieldState;
import com.miningdim.job.engineer.shield.PlasmaShieldVariant;
import com.miningdim.job.engineer.shield.client.PlasmaShieldClientArmor;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.client.extensions.common.IClientItemExtensions;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/** Wearable chest-slot energy shield. Its family and grade are fixed by the registered item. */
public final class PlasmaShieldItem extends ArmorItem {

    private final PlasmaShieldVariant shieldVariant;

    public PlasmaShieldItem(PlasmaShieldVariant shieldVariant) {
        super(PlasmaShieldEquipmentMaterial.forSeries(shieldVariant.series()), Type.CHESTPLATE,
                new Item.Properties().stacksTo(1).durability(1));
        this.shieldVariant = shieldVariant;
    }

    public PlasmaShieldVariant shieldVariant() {
        return shieldVariant;
    }

    public static PlasmaShieldItem equippedBy(Player player) {
        ItemStack stack = player.getItemBySlot(EquipmentSlot.CHEST);
        return stack.getItem() instanceof PlasmaShieldItem shield ? shield : null;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(PlasmaShieldClientArmor.extension());
    }

    @Override
    @Nullable
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        return "miningdim:textures/item/" + shieldVariant.itemId() + ".png";
    }

    /** Energy shields do not consume vanilla armor durability. */
    @Override
    public <T extends LivingEntity> int damageItem(ItemStack stack, int amount, T entity,
                                                    Consumer<T> onBroken) {
        return 0;
    }

    /**
     * 禁止原版合成台的 RepairItemRecipe 参与本物品。
     *
     * 本物品带 durability(1) 因而满足原版"可修复"判定 (RepairItemRecipe.matches 经
     * ItemStack.isRepairable 委托到本方法); 而该配方产出的是不含 MiningDimPlasmaShield 标签的
     * 新物品, {@link PlasmaShieldState#read} 会把缺失标签视为满电。两者相加使玩家可以把两件
     * 耗尽的护盾合成为一件满电护盾, 凭空绕过有限电池设计。
     */
    @Override
    public boolean isRepairable(ItemStack stack) {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        PlasmaShieldConfig.Stats stats = EngineerConfig.PLASMA_SHIELD.stats(shieldVariant);
        PlasmaShieldState state = PlasmaShieldState.read(stack, stats);
        tooltip.add(Component.translatable("tooltip.miningdim.plasma_shield.type",
                        Component.translatable(shieldVariant.series().translationKey()))
                .withStyle(ChatFormatting.AQUA));
        tooltip.add(Component.translatable("tooltip.miningdim.plasma_shield.tier",
                        Component.translatable(shieldVariant.tier().translationKey()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plasma_shield.shield",
                        decimal(state.shield()), decimal(stats.capacity()))
                .withStyle(ChatFormatting.BLUE));
        tooltip.add(Component.translatable("tooltip.miningdim.plasma_shield.total_energy",
                        decimal(state.totalEnergy()), decimal(stats.maxTotalEnergy()))
                .withStyle(ChatFormatting.DARK_AQUA));
        tooltip.add(Component.translatable("tooltip.miningdim.plasma_shield.heat",
                        decimal(state.heat()), decimal(stats.maxHeat()))
                .withStyle(state.overheated() ? ChatFormatting.RED : ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.miningdim.plasma_shield.heat_per_damage",
                        decimal(stats.heatPerDamage()))
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plasma_shield.cooling",
                        decimal(stats.coolingPerSecond()))
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plasma_shield.recharge",
                        decimal(stats.rechargePerSecond()), decimal(stats.rechargeDelayTicks() / 20.0D))
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plasma_shield.recharge_source")
                .withStyle(ChatFormatting.DARK_GRAY));
        if (stats.movementModifier() != 0.0D) {
            tooltip.add(Component.translatable("tooltip.miningdim.plasma_shield.movement",
                            signedPercent(stats.movementModifier()))
                    .withStyle(ChatFormatting.RED));
        }
        if (state.overheated()) {
            tooltip.add(Component.translatable("tooltip.miningdim.plasma_shield.shutdown")
                    .withStyle(ChatFormatting.RED));
        }
    }

    private static String signedPercent(double value) {
        double percentage = value * 100.0D;
        return (percentage >= 0.0D ? "+" : "") + decimal(percentage) + "%";
    }

    private static String decimal(double value) {
        String formatted = String.format(Locale.ROOT, "%.2f", value);
        int end = formatted.length();
        while (end > 0 && formatted.charAt(end - 1) == '0') {
            end--;
        }
        if (end > 0 && formatted.charAt(end - 1) == '.') {
            end--;
        }
        return formatted.substring(0, end);
    }
}
