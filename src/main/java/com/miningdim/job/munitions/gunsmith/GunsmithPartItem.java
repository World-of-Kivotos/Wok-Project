package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.ModMunitionsItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;

public final class GunsmithPartItem extends Item {

    private static final String K_PLATFORM = "GunsmithPlatform";
    private static final String K_PART = "GunsmithPart";
    private static final String K_QUALITY = "GunsmithQuality";
    private static final String K_COEFFICIENT = "GunsmithCoefficient";

    public GunsmithPartItem(Properties properties) {
        super(properties);
    }

    public static ItemStack createStack(Item item, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartQuality quality) {
        return createStack(item, platform, part, quality, quality.midpointCoefficient());
    }

    public static ItemStack createRolledStack(Item item, GunsmithPlatform platform,
                                              GunsmithPressPart part, GunsmithPartQuality quality,
                                              net.minecraft.util.RandomSource random) {
        return createStack(item, platform, part, quality, quality.rollCoefficient(random));
    }

    public static ItemStack createStack(Item item, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartQuality quality,
                                        double coefficient) {
        ItemStack stack = new ItemStack(item);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putString(K_PLATFORM, platform.id());
        tag.putString(K_PART, part.id());
        tag.putString(K_QUALITY, quality.id());
        tag.putDouble(K_COEFFICIENT, roundCoefficient(coefficient));
        tag.putInt("CustomModelData", customModelData(platform, part, quality));
        return stack;
    }

    public static void addCreativeStacks(CreativeModeTab.Output output) {
        for (GunsmithPlatform platform : GunsmithPlatform.values()) {
            for (GunsmithPressPart part : GunsmithPressPart.values()) {
                for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                    output.accept(createStack(ModMunitionsItems.GUNSMITH_PART.get(), platform, part, quality));
                }
            }
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        if (tag == null || !tag.contains(K_PLATFORM) || !tag.contains(K_PART) || !tag.contains(K_QUALITY)) {
            return super.getName(stack);
        }
        GunsmithPlatform platform = GunsmithPlatform.byId(tag.getString(K_PLATFORM));
        GunsmithPressPart part = GunsmithPressPart.byId(tag.getString(K_PART));
        GunsmithPartQuality quality = GunsmithPartQuality.byId(tag.getString(K_QUALITY));
        MutableComponent name = Component.empty()
                .append(Component.translatable(platform.labelKey()))
                .append(Component.translatable(part.labelKey()))
                .append(Component.literal(" "))
                .append(Component.translatable(quality.labelKey()));
        return name.withStyle(qualityStyle(quality));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            return;
        }
        GunsmithPlatform platform = GunsmithPlatform.byId(tag.getString(K_PLATFORM));
        GunsmithPressPart part = GunsmithPressPart.byId(tag.getString(K_PART));
        GunsmithPartQuality quality = GunsmithPartQuality.byId(tag.getString(K_QUALITY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.platform",
                Component.translatable(platform.labelKey())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.part",
                Component.translatable(part.labelKey())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.quality",
                Component.translatable(quality.labelKey())).withStyle(qualityStyle(quality)));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.coefficient",
                Component.literal(formatCoefficient(coefficientOf(stack)))).withStyle(ChatFormatting.AQUA));
    }

    public static boolean isGunsmithPart(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return !stack.isEmpty()
                && stack.is(ModMunitionsItems.GUNSMITH_PART.get())
                && tag != null
                && tag.contains(K_PLATFORM)
                && tag.contains(K_PART)
                && tag.contains(K_QUALITY);
    }

    public static boolean matches(ItemStack stack, GunsmithPlatform platform, GunsmithPressPart part) {
        return isGunsmithPart(stack) && platformOf(stack) == platform && partOf(stack) == part;
    }

    public static GunsmithPlatform platformOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? GunsmithPlatform.AR : GunsmithPlatform.byId(tag.getString(K_PLATFORM));
    }

    public static GunsmithPressPart partOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? GunsmithPressPart.CORE : GunsmithPressPart.byId(tag.getString(K_PART));
    }

    public static GunsmithPartQuality qualityOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        return tag == null ? GunsmithPartQuality.COMMON : GunsmithPartQuality.byId(tag.getString(K_QUALITY));
    }

    public static double coefficientOf(ItemStack stack) {
        CompoundTag tag = stack.getTag();
        GunsmithPartQuality quality = qualityOf(stack);
        if (tag == null || !tag.contains(K_COEFFICIENT)) {
            return quality.midpointCoefficient();
        }
        double value = tag.getDouble(K_COEFFICIENT);
        return Math.max(quality.minCoefficient(), Math.min(quality.maxCoefficient(), value));
    }

    public static String formatCoefficient(double coefficient) {
        return "x" + String.format(Locale.ROOT, "%.3f", coefficient);
    }

    private static int customModelData(GunsmithPlatform platform, GunsmithPressPart part,
                                       GunsmithPartQuality quality) {
        return platform.index() * 100 + part.index() * 10 + quality.index() + 1;
    }

    private static double roundCoefficient(double coefficient) {
        return Math.round(coefficient * 1000.0D) / 1000.0D;
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
}
