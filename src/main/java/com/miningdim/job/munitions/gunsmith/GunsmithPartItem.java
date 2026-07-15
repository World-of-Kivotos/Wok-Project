package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.ModMunitionsItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class GunsmithPartItem extends Item {

    private static final String K_PLATFORM = "GunsmithPlatform";
    private static final String K_PART = "GunsmithPart";
    private static final String K_VARIANT = "GunsmithVariant";
    private static final String K_QUALITY = "GunsmithQuality";
    private static final String K_COEFFICIENT = "GunsmithCoefficient";
    private static final String K_DATA_VERSION = "GunsmithPartDataVersion";
    private static final int CURRENT_DATA_VERSION = 2;

    public GunsmithPartItem(Properties properties) {
        super(properties);
    }

    public static ItemStack createStack(Item item, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartQuality quality) {
        return createStack(item, platform, part, GunsmithPartVariant.BASIC,
                quality, quality.midpointCoefficient());
    }

    public static ItemStack createStack(Item item, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartVariant variant,
                                        GunsmithPartQuality quality) {
        return createStack(item, platform, part, variant, quality, quality.midpointCoefficient());
    }

    public static ItemStack createRolledStack(Item item, GunsmithPlatform platform,
                                              GunsmithPressPart part, GunsmithPartQuality quality,
                                              net.minecraft.util.RandomSource random) {
        return createRolledStack(item, platform, part, GunsmithPartVariant.BASIC, quality, random);
    }

    public static ItemStack createRolledStack(Item item, GunsmithPlatform platform,
                                              GunsmithPressPart part, GunsmithPartVariant variant,
                                              GunsmithPartQuality quality,
                                              net.minecraft.util.RandomSource random) {
        return createStack(item, platform, part, variant, quality, quality.rollCoefficient(random));
    }

    public static ItemStack createStack(Item item, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartQuality quality,
                                        double coefficient) {
        return createStack(item, platform, part, GunsmithPartVariant.BASIC, quality, coefficient);
    }

    public static ItemStack createStack(Item item, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartVariant variant,
                                        GunsmithPartQuality quality, double coefficient) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(variant, "variant");
        Objects.requireNonNull(quality, "quality");
        if (!platform.supports(part)) {
            throw new IllegalArgumentException("Gunsmith platform " + platform.id()
                    + " does not allow part " + part.id());
        }
        if (!variant.supports(platform, part)) {
            throw new IllegalArgumentException("Gunsmith variant " + variant.id()
                    + " does not allow " + platform.id() + "/" + part.id());
        }
        double roundedCoefficient = roundCoefficient(coefficient);
        requireCoefficient(roundedCoefficient, quality);
        ItemStack stack = new ItemStack(item);
        CompoundTag tag = stack.getOrCreateTag();
        tag.putInt(K_DATA_VERSION, CURRENT_DATA_VERSION);
        tag.putString(K_PLATFORM, platform.id());
        tag.putString(K_PART, part.id());
        tag.putString(K_VARIANT, variant.id());
        tag.putString(K_QUALITY, quality.id());
        tag.putDouble(K_COEFFICIENT, roundedCoefficient);
        tag.putInt("CustomModelData", customModelData(platform, part, variant, quality));
        return stack;
    }

    public static void addCreativeStacks(CreativeModeTab.Output output) {
        for (GunsmithPlatform platform : GunsmithPlatform.values()) {
            for (GunsmithPressPart part : platform.supportedParts()) {
                for (GunsmithPartVariant variant : GunsmithPartVariant.availableFor(platform, part)) {
                    for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                        output.accept(createStack(ModMunitionsItems.GUNSMITH_PART.get(),
                                platform, part, variant, quality));
                    }
                }
            }
        }
    }

    @Nullable
    private static PartData tryPartData(ItemStack stack) {
        // 渲染线程 (getName/appendHoverText) 不能抛异常, 否则崩客户端; 服务端装配/冲压路径仍走
        // requirePartData 硬校验。裸/损坏 NBT 仅 op /give 可造。(审查 GS-2)
        try {
            return requirePartData(stack);
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    @Override
    public Component getName(ItemStack stack) {
        PartData data = tryPartData(stack);
        if (data == null) {
            return super.getName(stack);
        }
        MutableComponent name = Component.empty();
        if (data.variant() == GunsmithPartVariant.BASIC) {
            name.append(Component.translatable(data.platform().labelKey()))
                    .append(Component.translatable(data.part().labelKey()));
        } else {
            name.append(Component.translatable(data.variant().labelKey()));
        }
        name.append(Component.literal(" "))
                .append(Component.translatable(data.quality().labelKey()));
        return name.withStyle(qualityStyle(data.quality()));
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level,
                                List<Component> tooltip, TooltipFlag flag) {
        PartData data = tryPartData(stack);
        if (data == null) {
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.invalid")
                    .withStyle(ChatFormatting.RED));
            return;
        }
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.platform",
                Component.translatable(data.platform().labelKey())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.part",
                Component.translatable(data.part().slotKey())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.variant",
                Component.translatable(data.variant().labelKey())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.quality",
                Component.translatable(data.quality().labelKey())).withStyle(qualityStyle(data.quality())));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.coefficient",
                Component.literal(formatCoefficient(data.coefficient()))).withStyle(ChatFormatting.AQUA));
        if (data.variant() != GunsmithPartVariant.BASIC) {
            tooltip.add(Component.translatable(data.variant().descriptionKey()).withStyle(ChatFormatting.DARK_GRAY));
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.fire_rate",
                    Component.literal(formatPercentChange(data.variant().fireRateMultiplier(data.coefficient()) - 1.0D)))
                    .withStyle(ChatFormatting.GREEN));
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.vertical_recoil",
                    Component.literal(formatPercentChange(
                            data.variant().verticalRecoilMultiplier(data.coefficient()) - 1.0D)))
                    .withStyle(ChatFormatting.RED));
        }
    }

    public static boolean isGunsmithPart(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty() || !stack.is(ModMunitionsItems.GUNSMITH_PART.get())) {
            return false;
        }
        requirePartData(stack);
        return true;
    }

    public static boolean matches(ItemStack stack, GunsmithPlatform platform, GunsmithPressPart part) {
        return isGunsmithPart(stack) && platformOf(stack) == platform && partOf(stack) == part;
    }

    public static boolean matches(ItemStack stack, GunsmithPlatform platform, GunsmithPressPart part,
                                  GunsmithPartVariant variant) {
        return matches(stack, platform, part) && variantOf(stack) == variant;
    }

    public static GunsmithPlatform platformOf(ItemStack stack) {
        return requirePartData(stack).platform();
    }

    public static GunsmithPressPart partOf(ItemStack stack) {
        return requirePartData(stack).part();
    }

    public static GunsmithPartQuality qualityOf(ItemStack stack) {
        return requirePartData(stack).quality();
    }

    public static GunsmithPartVariant variantOf(ItemStack stack) {
        return requirePartData(stack).variant();
    }

    public static double coefficientOf(ItemStack stack) {
        return requirePartData(stack).coefficient();
    }

    public static PartData requirePartData(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) {
            throw new IllegalArgumentException("Gunsmith part stack is empty");
        }
        if (!stack.is(ModMunitionsItems.GUNSMITH_PART.get())) {
            throw new IllegalArgumentException("Stack is not a gunsmith part");
        }
        CompoundTag tag = stack.getTag();
        if (tag == null) {
            throw new IllegalArgumentException("Gunsmith part has no NBT data");
        }
        GunsmithPlatform platform = platform(tag);
        GunsmithPressPart part = part(tag);
        if (!platform.supports(part)) {
            throw new IllegalArgumentException("Gunsmith platform " + platform.id()
                    + " does not allow part " + part.id());
        }
        int dataVersion = dataVersion(tag);
        GunsmithPartVariant variant = variant(tag, dataVersion);
        if (!variant.supports(platform, part)) {
            throw new IllegalArgumentException("Gunsmith variant " + variant.id()
                    + " does not allow " + platform.id() + "/" + part.id());
        }
        GunsmithPartQuality quality = quality(tag);
        if (!tag.contains(K_COEFFICIENT, Tag.TAG_DOUBLE)) {
            throw new IllegalArgumentException("Gunsmith part has no double coefficient");
        }
        double coefficient = tag.getDouble(K_COEFFICIENT);
        requireCoefficient(coefficient, quality);
        return new PartData(platform, part, variant, quality, coefficient);
    }

    public static String formatCoefficient(double coefficient) {
        return "x" + String.format(Locale.ROOT, "%.3f", coefficient);
    }

    private static int customModelData(GunsmithPlatform platform, GunsmithPressPart part,
                                       GunsmithPartVariant variant, GunsmithPartQuality quality) {
        int baseModelData = platform.index() * 100 + part.index() * 10 + quality.index() + 1;
        return variant.index() * 1_000_000 + baseModelData;
    }

    private static double roundCoefficient(double coefficient) {
        return Math.round(coefficient * 1000.0D) / 1000.0D;
    }

    private static GunsmithPlatform platform(CompoundTag tag) {
        if (!tag.contains(K_PLATFORM, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Gunsmith part has no platform id");
        }
        String id = tag.getString(K_PLATFORM);
        return GunsmithPlatform.byId(id);
    }

    private static GunsmithPressPart part(CompoundTag tag) {
        if (!tag.contains(K_PART, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Gunsmith part has no part id");
        }
        String id = tag.getString(K_PART);
        return GunsmithPressPart.byId(id);
    }

    private static int dataVersion(CompoundTag tag) {
        if (!tag.contains(K_DATA_VERSION)) {
            if (tag.contains(K_VARIANT)) {
                throw new IllegalArgumentException("Legacy gunsmith part unexpectedly contains a variant id");
            }
            // Version 1 components predate the explicit data-version and variant fields.
            return 1;
        }
        if (!tag.contains(K_DATA_VERSION, Tag.TAG_INT)) {
            throw new IllegalArgumentException("Gunsmith part has no integer data version");
        }
        int version = tag.getInt(K_DATA_VERSION);
        if (version != CURRENT_DATA_VERSION) {
            throw new IllegalArgumentException("Unsupported gunsmith part data version: " + version);
        }
        return version;
    }

    private static GunsmithPartVariant variant(CompoundTag tag, int dataVersion) {
        if (dataVersion == 1) {
            // Every component created by the version 1 format was a basic component.
            return GunsmithPartVariant.BASIC;
        }
        if (!tag.contains(K_VARIANT, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Gunsmith part has no string variant id");
        }
        return GunsmithPartVariant.byId(tag.getString(K_VARIANT));
    }

    private static GunsmithPartQuality quality(CompoundTag tag) {
        if (!tag.contains(K_QUALITY, Tag.TAG_STRING)) {
            throw new IllegalArgumentException("Gunsmith part has no quality id");
        }
        return GunsmithPartQuality.byId(tag.getString(K_QUALITY));
    }

    private static void requireCoefficient(double coefficient, GunsmithPartQuality quality) {
        if (!Double.isFinite(coefficient)
                || coefficient < quality.minCoefficient()
                || coefficient > quality.maxCoefficient()) {
            throw new IllegalArgumentException("Gunsmith coefficient is outside the quality range: " + coefficient);
        }
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

    private static String formatPercentChange(double change) {
        return String.format(Locale.ROOT, "%+.1f%%", change * 100.0D);
    }

    public record PartData(GunsmithPlatform platform, GunsmithPressPart part,
                           GunsmithPartVariant variant, GunsmithPartQuality quality, double coefficient) {
    }
}
