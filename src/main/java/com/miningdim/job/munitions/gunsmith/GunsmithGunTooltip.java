package com.miningdim.job.munitions.gunsmith;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

public final class GunsmithGunTooltip {

    private GunsmithGunTooltip() {
    }

    public static void append(List<Component> tooltip, GunsmithGunStats stats) {
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.header")
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.damage",
                        literal(formatOne(GunsmithGunStats.M4_BASE_DAMAGE)),
                        literal(formatOne(stats.effectiveDamage())))
                .withStyle(valueStyle(stats.damage())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.headshot",
                        literal(formatTwo(GunsmithGunStats.M4_BASE_HEADSHOT)),
                        literal(formatTwo(stats.effectiveHeadshot())))
                .withStyle(valueStyle(stats.headshot())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.rpm",
                        literal(Integer.toString(GunsmithGunStats.M4_BASE_RPM)),
                        literal(Integer.toString(stats.effectiveRpm())))
                .withStyle(valueStyle(stats.recoil())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.recoil",
                        literal(formatPercent(stats.recoilChange())))
                .withStyle(changeStyle(stats.recoilChange())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.spread",
                        literal(formatPercent(stats.spreadChange())))
                .withStyle(changeStyle(stats.spreadChange())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.handling",
                        literal(formatSeconds(GunsmithGunStats.M4_BASE_ADS_TIME)),
                        literal(formatSeconds(stats.effectiveAdsTime())))
                .withStyle(valueStyle(stats.handling())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.average",
                        literal(GunsmithPartItem.formatCoefficient(stats.average())))
                .withStyle(valueStyle(stats.average())));
    }

    private static Component literal(String value) {
        return Component.literal(value);
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

    private static String formatPercent(double value) {
        return String.format(Locale.ROOT, "%+.0f%%", value * 100.0D);
    }
}
