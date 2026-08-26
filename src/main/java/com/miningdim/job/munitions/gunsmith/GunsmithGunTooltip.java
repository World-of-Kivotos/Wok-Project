package com.miningdim.job.munitions.gunsmith;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.world.item.ItemStack;

import java.util.List;
import java.util.Locale;

public final class GunsmithGunTooltip {

    private GunsmithGunTooltip() {
    }

    public static void appendDurability(List<Component> tooltip, ItemStack stack) {
        GunsmithGunDurability.State durability = GunsmithGunDurability.view(stack);
        ChatFormatting durabilityColor = durability.current() <= 0
                ? ChatFormatting.RED
                : durability.remainingRatio() <= 0.25D ? ChatFormatting.GOLD : ChatFormatting.GREEN;
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.durability",
                        Component.literal(Integer.toString(durability.current())).withStyle(durabilityColor),
                        Component.literal(Integer.toString(durability.maximum())).withStyle(ChatFormatting.GRAY))
                .withStyle(ChatFormatting.GRAY));
        if (durability.repairs() > 0) {
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.permanent_wear",
                            durability.maximum(), durability.originalMaximum(), durability.repairs())
                    .withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    public static void append(List<Component> tooltip, GunsmithGunStats stats, GunsmithBaseStats baseStats) {
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.summary",
                        modifiedValue(GunsmithPartItem.formatCoefficient(stats.average()), stats.average()))
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.damage_headshot",
                        baseValue(formatOne(baseStats.damage())),
                        modifiedValue(formatOne(stats.effectiveDamage(baseStats)), stats.damage()),
                        baseValue(formatTwo(baseStats.headshot())),
                        modifiedValue(formatTwo(stats.effectiveHeadshot(baseStats)), stats.headshot()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.range_recoil",
                        baseValue(formatRange(baseStats.effectiveRange())),
                        modifiedValue(formatRange(stats.effectiveRange(baseStats)), stats.range()),
                        changedValue(formatPercent(stats.recoilChange()), stats.recoilChange()))
                .withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.spread_handling",
                        changedValue(formatPercent(stats.spreadChange()), stats.spreadChange()),
                        baseValue(formatSeconds(baseStats.adsTime())),
                        modifiedValue(formatSeconds(stats.effectiveAdsTime(baseStats)), stats.adsSpeed()))
                .withStyle(ChatFormatting.GRAY));
        if (Math.abs(stats.fireRate() - 1.0D) > 0.0005D
                || Math.abs(stats.verticalRecoil() - 1.0D) > 0.0005D) {
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.special_gas",
                            Component.literal(formatPercent(stats.fireRate() - 1.0D))
                                    .withStyle(stats.fireRate() < 1.0D ? ChatFormatting.RED : ChatFormatting.GREEN),
                            Component.literal(formatPercent(stats.verticalRecoil() - 1.0D))
                                    .withStyle(stats.verticalRecoil() > 1.0D ? ChatFormatting.RED : ChatFormatting.GREEN))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (Math.abs(stats.ammoSpeed() - 1.0D) > 0.0005D) {
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.ammo_speed",
                            Component.literal(formatPercent(stats.ammoSpeed() - 1.0D))
                                    .withStyle(stats.ammoSpeed() < 1.0D
                                            ? ChatFormatting.RED : ChatFormatting.GREEN))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (Math.abs(stats.armorIgnore() - 1.0D) > 0.0005D) {
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.armor_ignore",
                            Component.literal(formatPercent(stats.armorIgnore() - 1.0D))
                                    .withStyle(stats.armorIgnore() < 1.0D
                                            ? ChatFormatting.RED : ChatFormatting.GREEN))
                    .withStyle(ChatFormatting.GRAY));
        }
        if (stats.forcesBurstFireMode()) {
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.fire_mode",
                            Component.translatable("gunsmith.fire_mode.three_round_burst")
                                    .withStyle(ChatFormatting.GREEN))
                    .withStyle(ChatFormatting.GRAY));
        }
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.components")
                .withStyle(ChatFormatting.GOLD));
        List<GunsmithGunStats.PartSummary> parts = stats.parts();
        for (int index = 0; index < parts.size(); index += 2) {
            MutableComponent row = Component.empty().append(partComponent(parts.get(index)));
            if (index + 1 < parts.size()) {
                row.append(Component.literal("  |  ").withStyle(ChatFormatting.DARK_GRAY));
                row.append(partComponent(parts.get(index + 1)));
            }
            tooltip.add(row);
        }
    }

    private static Component partComponent(GunsmithGunStats.PartSummary part) {
        return Component.translatable("tooltip.miningdim.gunsmith_gun.component",
                        Component.translatable(part.variant() == GunsmithPartVariant.BASE
                                ? part.part().labelKey() : part.variant().labelKey())
                                .withStyle(ChatFormatting.GRAY),
                        GunsmithPartItem.styledQualityName(part.quality()),
                        Component.literal(GunsmithPartItem.formatCoefficient(part.coefficient()))
                                .withStyle(valueStyle(part.coefficient())))
                .withStyle(ChatFormatting.GRAY);
    }

    private static Component baseValue(String value) {
        return Component.literal(value).withStyle(ChatFormatting.DARK_GRAY);
    }

    private static Component modifiedValue(String value, double coefficient) {
        return Component.literal(value).withStyle(valueStyle(coefficient));
    }

    private static Component changedValue(String value, double change) {
        return Component.literal(value).withStyle(changeStyle(change));
    }

    private static ChatFormatting valueStyle(double coefficient) {
        if (coefficient > 1.0005D) {
            return ChatFormatting.GREEN;
        }
        if (coefficient < 0.9995D) {
            return ChatFormatting.RED;
        }
        return ChatFormatting.GRAY;
    }

    private static ChatFormatting changeStyle(double change) {
        if (change < -0.0005D) {
            return ChatFormatting.GREEN;
        }
        if (change > 0.0005D) {
            return ChatFormatting.RED;
        }
        return ChatFormatting.GRAY;
    }

    private static String formatOne(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatTwo(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }

    private static String formatSeconds(double value) {
        return String.format(Locale.ROOT, "%.2fs", value);
    }

    private static String formatRange(double value) {
        return String.format(Locale.ROOT, "%.1fm", value);
    }

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%+.0f%%", value * 100.0D);
    }
}
