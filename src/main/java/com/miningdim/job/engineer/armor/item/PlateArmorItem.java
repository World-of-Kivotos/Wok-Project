package com.miningdim.job.engineer.armor.item;

import com.miningdim.job.engineer.EngineerConfig;
import com.miningdim.job.engineer.armor.PlateArmorEquipmentMaterial;
import com.miningdim.job.engineer.armor.PlateArmorStats;
import com.miningdim.job.engineer.armor.PlateArmorVariant;
import com.miningdim.job.engineer.armor.client.ThorIntegratedArmorClient;
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

/** 54 个外观共用的可穿戴插板胸甲；等级、类型与材料由注册时绑定的 variant 决定，玩家 NBT 不可伪造。 */
public final class PlateArmorItem extends ArmorItem {

    private static final String THOR_INTEGRATED_TEXTURE =
            "miningdim:textures/models/armor/plate_armor_thor_integrated_layer_1.png";

    private final PlateArmorVariant variant;

    public PlateArmorItem(PlateArmorVariant variant) {
        super(PlateArmorEquipmentMaterial.forWeight(variant.weight()), Type.CHESTPLATE,
                new Item.Properties().stacksTo(1).durability(1));
        this.variant = variant;
    }

    public PlateArmorVariant variant() {
        return variant;
    }

    @Override
    public void initializeClient(Consumer<IClientItemExtensions> consumer) {
        consumer.accept(ThorIntegratedArmorClient.forItem(this));
    }

    @Override
    @Nullable
    public String getArmorTexture(ItemStack stack, Entity entity, EquipmentSlot slot, String type) {
        if (variant == PlateArmorVariant.THOR_INTEGRATED && slot == EquipmentSlot.CHEST) {
            return THOR_INTEGRATED_TEXTURE;
        }
        return null;
    }

    @Override
    public int getMaxDamage(ItemStack stack) {
        return EngineerConfig.PLATE_ARMOR.maxDurability(variant.material());
    }

    /** 禁止原版按两个 TaCZ 伤害段分别磨损；统一磨损由插板处理器和 TaCZ Post/Kill 集成执行。 */
    @Override
    public <T extends LivingEntity> int damageItem(ItemStack stack, int amount, T entity,
                                                    Consumer<T> onBroken) {
        return 0;
    }

    /** 先完成本击防护，再按进入插板公式前的来伤统一磨损一次。 */
    public void applyCombatWear(ItemStack stack, double incomingDamage, Player wearer) {
        if (stack.isEmpty() || incomingDamage <= 0.0D || !Double.isFinite(incomingDamage)) {
            return;
        }
        if (!isFunctional(stack)) {
            breakExhausted(stack, wearer);
            return;
        }
        int wear = Math.max(1, (int) Math.floor(incomingDamage / 4.0D));
        int next = stack.getDamageValue() + wear;
        if (next >= stack.getMaxDamage()) {
            breakExhausted(stack, wearer);
        } else {
            stack.setDamageValue(next);
        }
    }

    public static PlateArmorItem equippedBy(Player player) {
        ItemStack stack = player.getItemBySlot(EquipmentSlot.CHEST);
        Item item = stack.getItem();
        return item instanceof PlateArmorItem plate && plate.isFunctional(stack) ? plate : null;
    }

    /** 配置热重载降低最大耐久后，旧物品可能立即越过新上限；耗尽态不得继续提供防护。 */
    public boolean isFunctional(ItemStack stack) {
        return !stack.isEmpty() && stack.getDamageValue() < stack.getMaxDamage();
    }

    public void breakExhausted(ItemStack stack, Player wearer) {
        if (!stack.isEmpty()) {
            stack.shrink(1);
            wearer.broadcastBreakEvent(EquipmentSlot.CHEST);
        }
    }

    @Override
    public void appendHoverText(ItemStack stack, Level level, List<Component> tooltip, TooltipFlag flag) {
        super.appendHoverText(stack, level, tooltip, flag);
        PlateArmorStats stats = PlateArmorStats.resolve(variant);

        tooltip.add(Component.translatable("tooltip.miningdim.plate_armor.level", variant.tier().name())
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plate_armor.category",
                        Component.translatable("category.miningdim.plate_armor"))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plate_armor.type",
                        Component.translatable(variant.weight().translationKey()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plate_armor.material",
                        Component.translatable(variant.material().translationKey()))
                .withStyle(ChatFormatting.GRAY));

        tooltip.add(Component.empty());
        tooltip.add(Component.translatable("tooltip.miningdim.plate_armor.durability",
                        Math.max(0, stack.getMaxDamage() - stack.getDamageValue()), stack.getMaxDamage())
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plate_armor.ballistic_r",
                        percent(stats.ballisticProtection()))
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plate_armor.ballistic_q",
                        percent(stats.armorPiercingBuffer()))
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plate_armor.general_g",
                        percent(stats.generalProtection()))
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plate_armor.capacity_t",
                        decimal(stats.pressureCapacity()))
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plate_armor.movement",
                        signedPercent(stats.movementModifier()))
                .withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.plate_armor.replaces_vanilla")
                .withStyle(ChatFormatting.DARK_GRAY));
    }

    private static String percent(double value) {
        return String.format(Locale.ROOT, "%.0f%%", value * 100.0D);
    }

    private static String signedPercent(double value) {
        return String.format(Locale.ROOT, "%+.0f%%", value * 100.0D);
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.0f", value);
    }
}
