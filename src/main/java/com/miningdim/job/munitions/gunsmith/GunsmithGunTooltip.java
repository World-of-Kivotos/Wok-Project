package com.miningdim.job.munitions.gunsmith;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.Locale;

public final class GunsmithGunTooltip {

    private GunsmithGunTooltip() {
    }

    public static void append(List<Component> tooltip, GunsmithGunStats stats, GunsmithBaseStats baseStats) {
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.header")
                .withStyle(ChatFormatting.GOLD));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.damage",
                        literal(formatOne(baseStats.damage())),
                        literal(formatOne(stats.effectiveDamage(baseStats))))
                .withStyle(valueStyle(stats.damage())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.headshot",
                        literal(formatTwo(baseStats.headshot())),
                        literal(formatTwo(stats.effectiveHeadshot(baseStats))))
                .withStyle(valueStyle(stats.headshot())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.range",
                        literal(formatRange(baseStats.effectiveRange())),
                        literal(formatRange(stats.effectiveRange(baseStats))))
                .withStyle(valueStyle(stats.range())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.recoil",
                        literal(formatPercent(stats.recoilChange())))
                .withStyle(changeStyle(stats.recoilChange())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.spread",
                        literal(formatPercent(stats.spreadChange())))
                .withStyle(changeStyle(stats.spreadChange())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.handling",
                        literal(formatSeconds(baseStats.adsTime())),
                        literal(formatSeconds(stats.effectiveAdsTime(baseStats))))
                .withStyle(valueStyle(stats.handling())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.average",
                        literal(GunsmithPartItem.formatCoefficient(stats.average())))
                .withStyle(valueStyle(stats.average())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.components")
                .withStyle(ChatFormatting.GOLD));
        for (GunsmithGunStats.PartSummary part : stats.parts()) {
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_gun.component",
                            Component.translatable(part.part().labelKey()),
                            Component.translatable(part.quality().labelKey()),
                            literal(GunsmithPartItem.formatCoefficient(part.coefficient())))
                    .withStyle(qualityStyle(part.quality())));
        }
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

    private static ChatFormatting qualityStyle(GunsmithPartQuality quality) {
        return switch (quality) {
            case COMMON -> ChatFormatting.WHITE;
            case IMPROVED -> ChatFormatting.GREEN;
            case MILSPEC -> ChatFormatting.BLUE;
            case PRECISION -> ChatFormatting.LIGHT_PURPLE;
            case LEGENDARY -> ChatFormatting.RED;
        };
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
