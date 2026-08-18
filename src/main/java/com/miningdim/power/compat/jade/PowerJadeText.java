package com.miningdim.power.compat.jade;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import snownee.jade.api.ITooltip;
import snownee.jade.api.ui.BoxStyle;
import snownee.jade.api.ui.IElement;
import snownee.jade.api.ui.IElementHelper;
import snownee.jade.api.ui.IProgressStyle;

import java.util.Locale;

/** 统一 Jade 的翻译、语义色和紧凑信息排版。 */
final class PowerJadeText {

    static final int ENERGY_BRIGHT = 0xFF31D2B4;
    static final int ENERGY_DARK = 0xFF17675A;
    static final int PROCESS_BRIGHT = 0xFFE0A449;
    static final int PROCESS_DARK = 0xFF78501D;
    static final int INFUSION_BRIGHT = 0xFFB58AE6;
    static final int INFUSION_DARK = 0xFF594276;
    static final int FUEL_BRIGHT = 0xFFF0C45A;
    static final int FUEL_DARK = 0xFF806526;

    private static final int THERMAL_SAFE_BRIGHT = 0xFF55C878;
    private static final int THERMAL_SAFE_DARK = 0xFF255E36;
    private static final int THERMAL_WARM_BRIGHT = 0xFFE0A449;
    private static final int THERMAL_WARM_DARK = 0xFF78501D;
    private static final int THERMAL_DANGER_BRIGHT = 0xFFE0525C;
    private static final int THERMAL_DANGER_DARK = 0xFF782B31;
    private static final int PROGRESS_TEXT = 0xFFF1F4F7;

    private PowerJadeText() {
    }

    static Component enumValue(String keyPrefix, String enumName) {
        return Component.translatable(keyPrefix + "." + enumName.toLowerCase(Locale.ROOT));
    }

    static Component statusValue(String keyPrefix, String enumName) {
        return colored(enumValue(keyPrefix, enumName), statusColor(enumName));
    }

    static Component enumList(String keyPrefix, String serializedNames) {
        String[] names = serializedNames.isBlank() ? new String[] {"NONE"} : serializedNames.split(",");
        MutableComponent result = Component.empty();
        for (int index = 0; index < names.length; index++) {
            if (index > 0) {
                result.append(", ");
            }
            result.append(statusValue(keyPrefix, names[index]));
        }
        return result;
    }

    static Component metric(String key, Object... values) {
        Object[] styledValues = new Object[values.length];
        for (int index = 0; index < values.length; index++) {
            Object value = values[index];
            styledValues[index] = value instanceof Component
                    ? value
                    : Component.literal(String.valueOf(value)).withStyle(ChatFormatting.WHITE);
        }
        return Component.translatable(key, styledValues).withStyle(ChatFormatting.GRAY);
    }

    static Component colored(Component component, ChatFormatting color) {
        return component.copy().withStyle(color);
    }

    static void addPair(ITooltip tooltip, Component left, Component right) {
        IElementHelper elements = tooltip.getElementHelper();
        tooltip.add(elements.text(left));
        tooltip.append(elements.text(right).align(IElement.Align.RIGHT));
    }

    static void addProgress(ITooltip tooltip, Component text, double current, double maximum,
                            int brightColor, int darkColor, ResourceLocation progressTag) {
        IElementHelper elements = tooltip.getElementHelper();
        IProgressStyle style = elements.progressStyle()
                .color(brightColor, darkColor)
                .textColor(PROGRESS_TEXT);
        tooltip.add(elements.progress(progress(current, maximum), text, style, BoxStyle.DEFAULT, true)
                .tag(progressTag));
    }

    static void addTemperatureProgress(ITooltip tooltip, Component text, double temperature,
                                       double dangerTemperature, ResourceLocation progressTag) {
        double ratio = progress(temperature, dangerTemperature);
        if (ratio >= 0.9D) {
            addProgress(tooltip, text, temperature, dangerTemperature,
                    THERMAL_DANGER_BRIGHT, THERMAL_DANGER_DARK, progressTag);
        } else if (ratio >= 0.7D) {
            addProgress(tooltip, text, temperature, dangerTemperature,
                    THERMAL_WARM_BRIGHT, THERMAL_WARM_DARK, progressTag);
        } else {
            addProgress(tooltip, text, temperature, dangerTemperature,
                    THERMAL_SAFE_BRIGHT, THERMAL_SAFE_DARK, progressTag);
        }
    }

    static String oneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static float progress(double current, double maximum) {
        if (maximum <= 0.0D) {
            return 0.0F;
        }
        return (float) Math.max(0.0D, Math.min(1.0D, current / maximum));
    }

    private static ChatFormatting statusColor(String name) {
        return switch (name) {
            case "RUNNING", "ACTIVE", "INSTALLED", "NONE" -> ChatFormatting.GREEN;
            case "SCRAM", "MELTDOWN", "TRIPPED", "INSUFFICIENT", "OVER_VOLTAGE", "BUFFER_OVERFLOW",
                    "SUPERCONDUCTOR_QUENCH" -> ChatFormatting.RED;
            case "IDLE", "ABSENT", "NOT_REQUIRED" -> ChatFormatting.GRAY;
            default -> ChatFormatting.AQUA;
        };
    }
}
