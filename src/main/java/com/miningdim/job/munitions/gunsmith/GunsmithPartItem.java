package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.ModMunitionsItems;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.TextColor;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.inventory.tooltip.TooltipComponent;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

public final class GunsmithPartItem extends Item {

    private static final String K_PLATFORM = "GunsmithPlatform";
    private static final String K_PART = "GunsmithPart";
    private static final String K_QUALITY = "GunsmithQuality";
    private static final String K_COEFFICIENT = "GunsmithCoefficient";
    private static final String K_VARIANT = "GunsmithVariant";
    private static final TextColor EFFECT_LABEL_COLOR = TextColor.fromRgb(0xD0D0D0);
    private static final TextColor EFFECT_NEUTRAL_COLOR = TextColor.fromRgb(0xA0A0A0);
    private static final TextColor EFFECT_BENEFIT_COLOR = TextColor.fromRgb(0x55D86A);
    private static final TextColor EFFECT_PENALTY_COLOR = TextColor.fromRgb(0xE05A5A);

    public GunsmithPartItem(Properties properties) {
        super(properties);
    }

    public static ItemStack createStack(Item item, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartQuality quality) {
        return createStack(item, platform, part, quality, GunsmithPartVariant.BASE,
                quality.midpointCoefficient());
    }

    public static ItemStack createStack(Item item, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartQuality quality,
                                        GunsmithPartVariant variant) {
        return createStack(item, platform, part, quality, variant, quality.midpointCoefficient());
    }

    public static ItemStack createRolledStack(Item item, GunsmithPlatform platform,
                                              GunsmithPressPart part, GunsmithPartQuality quality,
                                              net.minecraft.util.RandomSource random) {
        return createRolledStack(item, platform, part, quality, GunsmithPartVariant.BASE, random);
    }

    public static ItemStack createRolledStack(Item item, GunsmithPlatform platform,
                                              GunsmithPressPart part, GunsmithPartQuality quality,
                                              GunsmithPartVariant variant,
                                              net.minecraft.util.RandomSource random) {
        double coefficient = variant == GunsmithPartVariant.RED_EAST_HIGH_PRESSURE_GAS
                ? quality.midpointCoefficient() : quality.rollCoefficient(random);
        return createStack(item, platform, part, quality, variant, coefficient);
    }

    public static ItemStack createStack(Item item, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartQuality quality,
                                        double coefficient) {
        return createStack(item, platform, part, quality, GunsmithPartVariant.BASE, coefficient);
    }

    public static ItemStack createStack(Item item, GunsmithPlatform platform,
                                        GunsmithPressPart part, GunsmithPartQuality quality,
                                        GunsmithPartVariant variant, double coefficient) {
        Objects.requireNonNull(item, "item");
        Objects.requireNonNull(platform, "platform");
        Objects.requireNonNull(part, "part");
        Objects.requireNonNull(quality, "quality");
        Objects.requireNonNull(variant, "variant");
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
        tag.putString(K_PLATFORM, platform.id());
        tag.putString(K_PART, part.id());
        tag.putString(K_QUALITY, quality.id());
        tag.putString(K_VARIANT, variant.id());
        tag.putDouble(K_COEFFICIENT, roundedCoefficient);
        tag.putInt("CustomModelData", variant.customModelData(platform, part, quality));
        return stack;
    }

    public static void addCreativeStacks(CreativeModeTab.Output output) {
        for (GunsmithPlatform platform : GunsmithPlatform.values()) {
            for (GunsmithPressPart part : platform.supportedParts()) {
                for (GunsmithPartVariant variant : GunsmithPartVariant.values()) {
                    if (!variant.supports(platform, part)) {
                        continue;
                    }
                    for (GunsmithPartQuality quality : GunsmithPartQuality.values()) {
                        output.accept(createStack(ModMunitionsItems.GUNSMITH_PART.get(), platform, part,
                                quality, variant));
                    }
                }
            }
        }
    }

    @Nullable
    public static PartData tryPartData(ItemStack stack) {
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
        MutableComponent name = Component.empty()
                .append(Component.translatable(data.variant() == GunsmithPartVariant.BASE
                        ? data.platform().labelKey() : data.variant().labelKey()))
                .append(data.variant() == GunsmithPartVariant.BASE
                        ? Component.translatable(data.part().labelKey()) : Component.empty())
                .append(Component.literal(" "))
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
                Component.translatable(data.part().labelKey())).withStyle(ChatFormatting.GRAY));
        if (data.variant() != GunsmithPartVariant.BASE) {
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.variant",
                    Component.translatable(data.variant().labelKey())).withStyle(ChatFormatting.GOLD));
        }
        String descriptionKey = data.variant() == GunsmithPartVariant.BASE
                ? "tooltip.miningdim.gunsmith_part.description.base." + data.part().id()
                : "tooltip.miningdim.gunsmith_part.description.variant." + data.variant().id();
        tooltip.add(Component.translatable(descriptionKey).withStyle(ChatFormatting.DARK_GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.quality",
                styledQualityName(data.quality())).withStyle(ChatFormatting.GRAY));
        tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.coefficient",
                Component.literal(formatCoefficient(data.coefficient())))
                .withStyle(coefficientStyle(data.coefficient())));
        double durabilityMultiplier = data.variant().maximumDurabilityMultiplier();
        if (data.variant().hasStatEffects(data.quality()) || data.variant().forcesBurstFireMode()
                || Math.abs(durabilityMultiplier - 1.0D) > 0.0005D) {
            tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.effects")
                    .withStyle(ChatFormatting.GOLD));
            if (data.variant().forcesBurstFireMode()) {
                tooltip.add(Component.translatable("tooltip.miningdim.gunsmith_part.effect.fire_mode",
                                Component.translatable("gunsmith.fire_mode.three_round_burst")
                                        .withStyle(ChatFormatting.GREEN))
                        .withStyle(style -> style.withColor(EFFECT_LABEL_COLOR)));
            }
            addEffect(tooltip, "tooltip.miningdim.gunsmith_part.effect.damage",
                    data.variant().damageMultiplier(data.quality()), true);
            addEffect(tooltip, "tooltip.miningdim.gunsmith_part.effect.headshot",
                    data.variant().headshotMultiplier(data.quality()), true);
            addEffect(tooltip, "tooltip.miningdim.gunsmith_part.effect.fire_rate",
                    data.variant().fireRateMultiplier(data.quality()), true);
            addRangeEffect(tooltip, data.variant(), data.quality());
            addEffect(tooltip, "tooltip.miningdim.gunsmith_part.effect.ammo_speed",
                    data.variant().ammoSpeedMultiplier(data.quality()), true);
            addEffect(tooltip, "tooltip.miningdim.gunsmith_part.effect.armor_ignore",
                    data.variant().armorIgnoreMultiplier(data.quality()), true);
            addEffect(tooltip, "tooltip.miningdim.gunsmith_part.effect.spread",
                    data.variant().spreadMultiplier(data.quality()), false);
            addEffect(tooltip, "tooltip.miningdim.gunsmith_part.effect.recoil",
                    data.variant().recoilMultiplier(data.quality()), false);
            addEffect(tooltip, "tooltip.miningdim.gunsmith_part.effect.vertical_recoil",
                    data.variant().verticalRecoilMultiplier(data.quality()), false);
            addEffect(tooltip, "tooltip.miningdim.gunsmith_part.effect.ads_speed",
                    data.variant().adsSpeedMultiplier(data.quality()), true);
            if (Math.abs(durabilityMultiplier - 1.0D) > 0.0005D) {
                tooltip.add(Component.translatable(
                                "tooltip.miningdim.gunsmith_part.effect.maximum_durability",
                                Component.literal(String.format(Locale.ROOT, "%+.0f%%",
                                                (durabilityMultiplier - 1.0D) * 100.0D))
                                        .withStyle(durabilityMultiplier < 1.0D
                                                ? ChatFormatting.RED : ChatFormatting.GREEN))
                        .withStyle(style -> style.withColor(EFFECT_LABEL_COLOR)));
            }
        }
    }

    @Override
    public Optional<TooltipComponent> getTooltipImage(ItemStack stack) {
        PartData data = tryPartData(stack);
        return data == null
                ? Optional.empty()
                : Optional.of(new GunsmithFactionTooltip(
                        data.variant().rarity(), data.variant().faction()));
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

    public static GunsmithPlatform platformOf(ItemStack stack) {
        return requirePartData(stack).platform();
    }

    public static GunsmithPressPart partOf(ItemStack stack) {
        return requirePartData(stack).part();
    }

    public static GunsmithPartQuality qualityOf(ItemStack stack) {
        return requirePartData(stack).quality();
    }

    public static double coefficientOf(ItemStack stack) {
        return requirePartData(stack).coefficient();
    }

    public static GunsmithPartVariant variantOf(ItemStack stack) {
        return requirePartData(stack).variant();
    }

    public static GunsmithPartRarity rarityOf(ItemStack stack) {
        return requirePartData(stack).variant().rarity();
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
        GunsmithPartQuality quality = quality(tag);
        // 旧枪匠部件没有型号字段，按普通组件读取，保持存量物品兼容。
        GunsmithPartVariant variant = tag.contains(K_VARIANT, Tag.TAG_STRING)
                ? GunsmithPartVariant.byId(tag.getString(K_VARIANT)) : GunsmithPartVariant.BASE;
        // 曾错误发布为 AR/RECEIVER 的存量组件在这里归一到现有 BOLT 槽。别名归一 (含 byId 的型号别名)
        // 一律只作用于返回的 PartData, 不回写 NBT: 本方法经 tryPartData 被 getName/appendHoverText 与
        // WebUI 物品详情等只读路径调用, 其中 tooltip 跑在客户端渲染线程。在只读路径上改 tag 会让客户端
        // 副本单方面偏离服务端, 落在方块实体里时又不会 setChanged 标脏, 读档后重复改写。
        if (platform == GunsmithPlatform.AR && part == GunsmithPressPart.RECEIVER) {
            part = GunsmithPressPart.BOLT;
        }
        if (!platform.supports(part)) {
            throw new IllegalArgumentException("Gunsmith platform " + platform.id()
                    + " does not allow part " + part.id());
        }
        if (!variant.supports(platform, part)) {
            throw new IllegalArgumentException("Gunsmith variant does not support encoded platform/part");
        }
        if (!tag.contains(K_COEFFICIENT, Tag.TAG_DOUBLE)) {
            throw new IllegalArgumentException("Gunsmith part has no double coefficient");
        }
        double coefficient = tag.getDouble(K_COEFFICIENT);
        requireCoefficient(coefficient, quality);
        return new PartData(platform, part, quality, variant, coefficient);
    }

    public static String formatCoefficient(double coefficient) {
        return "x" + String.format(Locale.ROOT, "%.3f", coefficient);
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

    private static String formatPercent(double multiplier) {
        return String.format(Locale.ROOT, "%+.0f%%", (multiplier - 1.0D) * 100.0D);
    }

    private static void addEffect(List<Component> tooltip, String translationKey,
                                  double multiplier, boolean increaseIsBeneficial) {
        MutableComponent value = Component.literal(formatPercent(multiplier))
                .withStyle(style -> style.withColor(effectColor(multiplier, increaseIsBeneficial)));
        tooltip.add(Component.translatable(translationKey, value)
                .withStyle(style -> style.withColor(EFFECT_LABEL_COLOR)));
    }

    /**
     * 射程是唯一带 REPLACE 语义的分量 ({@link GunsmithComponentRule.RangeOperation})。REPLACE 态下配置值
     * 是"替换后的绝对射程系数", 会把其余槽位累积的射程整体顶掉, 按 +x% 相对倍率展示是错的: 一条把射程
     * 钉死在 1.0 的规则会被显示成 "+0%", 玩家读成"这件组件对射程没影响", 实际上它废掉了枪管的加成 (审查 67)。
     */
    private static void addRangeEffect(List<Component> tooltip, GunsmithPartVariant variant,
                                       GunsmithPartQuality quality) {
        double configured = variant.rangeMultiplier(quality);
        if (GunsmithComponentRules.get(variant).rangeOperation()
                != GunsmithComponentRule.RangeOperation.REPLACE) {
            addEffect(tooltip, "tooltip.miningdim.gunsmith_part.effect.range", configured, true);
            return;
        }
        MutableComponent value = Component.literal(formatCoefficient(configured))
                .withStyle(style -> style.withColor(effectColor(configured, true)));
        tooltip.add(Component.translatableWithFallback(
                        "tooltip.miningdim.gunsmith_part.effect.range_replace",
                        "  Effective range: set to %s", value)
                .withStyle(style -> style.withColor(EFFECT_LABEL_COLOR)));
    }

    private static TextColor effectColor(double multiplier, boolean increaseIsBeneficial) {
        if (Math.abs(multiplier - 1.0D) <= 0.0005D) {
            return EFFECT_NEUTRAL_COLOR;
        }
        return (multiplier > 1.0D) == increaseIsBeneficial ? EFFECT_BENEFIT_COLOR : EFFECT_PENALTY_COLOR;
    }

    static Component styledQualityName(GunsmithPartQuality quality) {
        return Component.translatable(quality.labelKey()).withStyle(qualityStyle(quality));
    }

    private static ChatFormatting coefficientStyle(double coefficient) {
        if (coefficient > 1.0D) {
            return ChatFormatting.GREEN;
        }
        if (coefficient < 1.0D) {
            return ChatFormatting.RED;
        }
        return ChatFormatting.AQUA;
    }

    private static ChatFormatting qualityStyle(GunsmithPartQuality quality) {
        return switch (quality) {
            case COMMON -> ChatFormatting.WHITE;
            case IMPROVED -> ChatFormatting.GREEN;
            case MILSPEC -> ChatFormatting.BLUE;
            case PRECISION -> ChatFormatting.LIGHT_PURPLE;
            case LEGENDARY -> ChatFormatting.GOLD;
        };
    }

    public record PartData(GunsmithPlatform platform, GunsmithPressPart part,
                           GunsmithPartQuality quality, GunsmithPartVariant variant, double coefficient) {
    }
}
