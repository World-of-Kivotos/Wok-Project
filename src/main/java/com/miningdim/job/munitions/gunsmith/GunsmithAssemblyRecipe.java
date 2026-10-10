package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.ModMunitionsItems;
import com.miningdim.job.munitions.MunitionsConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class GunsmithAssemblyRecipe {

    /**
     * 互斥组件的拒绝文案 (平衡方案 E6, 参数: 两件组件的型号名)。lang 归属方尚未收录该键, 故经
     * translatableWithFallback 兜底, 与冲压台的 {@code RARITY_LOCKED_KEY} 同一做法; 装配台拒绝消息与装配预览
     * 共用这一对常量, 防两处文案漂移。
     */
    public static final String VARIANT_CONFLICT_KEY = "message.miningdim.gunsmith_assembly_bench.variant_conflict";
    public static final String VARIANT_CONFLICT_FALLBACK = "%s 与 %s 不能装在同一把枪上。";

    private GunsmithAssemblyRecipe() {
    }

    public static boolean isBlueprint(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.isEmpty()) {
            return false;
        }
        if (stack.is(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get())) {
            return true;
        }
        if (!GunsmithBlueprintItem.isBlueprintItem(stack)) {
            return false;
        }
        return GunsmithBlueprintItem.tryBlueprint(stack) != null;
    }

    public static GunsmithBlueprint blueprint(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.is(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get())) {
            return GunsmithBlueprint.M4A1;
        }
        if (!GunsmithBlueprintItem.isBlueprintItem(stack)) {
            throw new IllegalArgumentException("Item stack is not a gunsmith blueprint");
        }
        return GunsmithBlueprintItem.requireBlueprint(stack);
    }

    public static ResourceLocation assembledGunId(ItemStack stack) {
        Objects.requireNonNull(stack, "stack");
        if (stack.is(ModMunitionsItems.M4_ASSEMBLY_TEMPLATE.get())) {
            return GunsmithGunFactory.M4A1_ID;
        }
        return blueprint(stack).gunId();
    }

    public static ResourceLocation assembledGunId(ItemStack stack,
                                                  Map<GunsmithPressPart, ItemStack> parts) {
        GunsmithBlueprint blueprint = blueprint(stack);
        if (blueprint.platform() != GunsmithPlatform.AR) {
            return assembledGunId(stack);
        }
        Objects.requireNonNull(parts, "parts");
        ItemStack bolt = Objects.requireNonNull(parts.get(GunsmithPressPart.BOLT),
                "Assembly parts is missing bolt");
        if (!bolt.isEmpty()) {
            GunsmithPartItem.PartData boltData = GunsmithPartItem.requirePartData(bolt);
            if (boltData.platform() != blueprint.platform()
                    || boltData.part() != GunsmithPressPart.BOLT) {
                throw new IllegalArgumentException("Assembly bolt does not match blueprint platform");
            }
            if (boltData.variant().forcesBurstFireMode()) {
                return GunsmithGunFactory.burstGunId(blueprint);
            }
        }
        return assembledGunId(stack);
    }

    public static boolean matchesPart(ItemStack stack, GunsmithPressPart part) {
        return matchingPartData(stack, part) != null;
    }

    public static boolean matchesPart(ItemStack stack, GunsmithPressPart part, GunsmithPlatform platform) {
        Objects.requireNonNull(platform, "platform");
        GunsmithPartItem.PartData data = matchingPartData(stack, part);
        return data != null && data.platform() == platform;
    }

    /**
     * 槽位谓词的共用解析。走容错的 {@link GunsmithPartItem#tryPartData} 而非会抛的 requirePartData:
     * 本方法挂在装配台/维修台的 {@code Slot.mayPlace} 上, 玩家把一个 NBT 被改坏的枪匠部件拖进槽位就会
     * 触发, 解析异常会直接穿透包处理器崩服 —— 与审查 2 是同一条崩溃链, 只是从零件那一侧进来。
     * 畸形部件在这里只应判为"不合法", 硬校验留在真正写入成品枪的 {@link #assemble} 路径上。
     */
    @Nullable
    private static GunsmithPartItem.PartData matchingPartData(ItemStack stack, GunsmithPressPart part) {
        Objects.requireNonNull(stack, "stack");
        Objects.requireNonNull(part, "part");
        if (stack.isEmpty() || !stack.is(ModMunitionsItems.GUNSMITH_PART.get())) {
            return null;
        }
        GunsmithPartItem.PartData data = GunsmithPartItem.tryPartData(stack);
        return data != null && data.part() == part ? data : null;
    }

    public static ItemStack assemble(ItemStack baseGun, ItemStack blueprintStack,
                                     Map<GunsmithPressPart, ItemStack> parts) {
        Objects.requireNonNull(baseGun, "baseGun");
        Objects.requireNonNull(blueprintStack, "blueprintStack");
        if (baseGun.isEmpty()) {
            throw new IllegalArgumentException("Assembly base gun is empty");
        }
        GunsmithBlueprint blueprint = blueprint(blueprintStack);
        EnumMap<GunsmithPressPart, Double> coefficients = coefficients(parts, blueprint, true);
        // 互斥组合的硬校验落在写入路径上 (E6): 装配台开工前已用 findVariantConflict 拒过一次并提示玩家,
        // 这里兜住绕过装配台直接调用本方法的路径, 不让互斥组合写进成品 NBT。
        VariantConflict conflict = variantConflict(blueprint, variants(parts, blueprint, true));
        if (conflict != null) {
            throw new IllegalArgumentException("Assembly components are mutually exclusive: "
                    + conflict.first().id() + " + " + conflict.second().id());
        }
        ItemStack result = baseGun.copy();
        CompoundTag root = new CompoundTag();
        root.putInt(GunsmithGunStats.VERSION_KEY, GunsmithGunStats.CURRENT_VERSION);
        root.putString("template", blueprint.templateId());
        root.putString("platform", blueprint.platform().id());
        root.putString("gunId", assembledGunId(blueprintStack, parts).toString());
        root.put(GunsmithGunStats.PARTS_KEY, partTags(parts, blueprint.requiredParts()));
        root.put(GunsmithGunStats.STATS_KEY, stats(blueprint, coefficients));
        result.getOrCreateTag().put(GunsmithGunStats.ROOT_KEY, root);
        GunsmithGunDurability.initializeNew(result);
        return result;
    }

    public static Preview preview(GunsmithBlueprint blueprint, Map<GunsmithPressPart, ItemStack> parts,
                                  GunsmithBaseStats baseStats) {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(baseStats, "baseStats");
        EnumMap<GunsmithPressPart, Double> coefficients =
                coefficients(parts, blueprint, false);
        EnumMap<GunsmithPressPart, GunsmithPartVariant> variants = variants(parts, blueprint, false);
        double range = applyVariantRange(coefficient(blueprint, coefficients, GunsmithStat.RANGE),
                blueprint, parts, variants);
        double recoil = coefficient(blueprint, coefficients, GunsmithStat.RECOIL);
        double spread = coefficient(blueprint, coefficients, GunsmithStat.SPREAD);
        double handling = coefficient(blueprint, coefficients, GunsmithStat.HANDLING);
        double recoilMultiplier = variantProduct(blueprint, parts, variants, VariantStat.RECOIL);
        double spreadMultiplier = variantProduct(blueprint, parts, variants, VariantStat.SPREAD);
        double adsSpeedMultiplier = variantProduct(blueprint, parts, variants, VariantStat.ADS_SPEED);
        // 强制三连发的成品只有 burst 一种模式, TaCZ 1.1.8 的连发节奏取 burst_data、不读 RPM 缓存, 组件射速对它
        // 恒不生效 (E8)。预览这一行标的是半/全自动射速, 与成品 GunsmithGunStats.fireRate() 同口径记 0。
        boolean forcesBurst = variants.values().stream().anyMatch(GunsmithPartVariant::forcesBurstFireMode);
        double fireRateMultiplier = forcesBurst ? 1.0D
                : variantProduct(blueprint, parts, variants, VariantStat.FIRE_RATE);
        // 装配前的预览必须与成品枪 tooltip 同口径, 否则玩家看不到穿甲/弹速/额外垂直后坐就付了工费。
        // 这三项在成品侧 (GunsmithGunTooltip) 各占独立一行, 值就是组件型号乘子本身, 不与品质浮动系数
        // 合并 —— 这里照搬同一口径, 避免"预览一套算法、成品另一套算法"。
        double verticalRecoilMultiplier = variantProduct(blueprint, parts, variants, VariantStat.VERTICAL_RECOIL);
        double ammoSpeedMultiplier = variantProduct(blueprint, parts, variants, VariantStat.AMMO_SPEED);
        double armorIgnoreMultiplier = variantProduct(blueprint, parts, variants, VariantStat.ARMOR_IGNORE);
        // 预览的伤害必须过成品枪那一个总帽, 否则装配台显示 3.75 倍、成品实际 2.25 倍 (审查 27)。
        double qualityDamage = coefficient(blueprint, coefficients, GunsmithStat.DAMAGE);
        double damageMultiplier = GunsmithGunStats.capDamageMultiplier(
                qualityDamage * variantProduct(blueprint, parts, variants, VariantStat.DAMAGE));
        // 爆头与散布借 GunsmithStatMultipliers 的同一套换算 (E8), 与 GunsmithTaczStatsHandler 实际写进 TaCZ 的
        // 口径逐项一致: 爆头先过 1.8 品质复利帽再乘组件爆头倍率; 散布 = 护木控制 x 组件散布 x 握把控制,
        // 握把那一路 (aimInaccuracy) 与护木写进的是同一份散布缓存, 漏乘就会把全传奇 M4 的 -51% 显示成 -30%。
        GunsmithStatMultipliers qualityMultipliers = GunsmithStatMultipliers.of(damageMultiplier, qualityDamage,
                coefficient(blueprint, coefficients, GunsmithStat.HEADSHOT), range, handling, spread, recoil,
                MunitionsConfig.GUNSMITH_HEADSHOT_DAMAGE_CAP.get());
        double average = average(coefficients, blueprint.requiredParts());
        return new Preview(
                baseStats.damage() * damageMultiplier,
                baseStats.headshot() * qualityMultipliers.headshot()
                        * variantProduct(blueprint, parts, variants, VariantStat.HEADSHOT),
                range,
                baseStats.effectiveRange() * range,
                (1.0D / recoil * recoilMultiplier - 1.0D) * 100.0D,
                (verticalRecoilMultiplier - 1.0D) * 100.0D,
                (qualityMultipliers.inaccuracy() * spreadMultiplier * qualityMultipliers.aimInaccuracy() - 1.0D)
                        * 100.0D,
                (fireRateMultiplier - 1.0D) * 100.0D,
                (ammoSpeedMultiplier - 1.0D) * 100.0D,
                (armorIgnoreMultiplier - 1.0D) * 100.0D,
                GunsmithGunStats.effectiveAdsTime(baseStats.adsTime(), handling * adsSpeedMultiplier),
                average,
                variantConflict(blueprint, variants));
    }

    /**
     * 已放入组件里的第一对互斥组合 (E6), 没有则返回 null。
     *
     * 只读容错入口: 装配台开工前调用, 界面侧要单独判断时也可直接调; 零件 NBT 读不出来或平台/槽位对不上时
     * 按空槽处理, 不抛 ——
     * 那类畸形零件由 {@link #matchesPart} 与 {@link #assemble} 的硬校验另行拒绝, 这里只回答"互不互斥"。
     */
    @Nullable
    public static VariantConflict findVariantConflict(GunsmithBlueprint blueprint,
                                                      Map<GunsmithPressPart, ItemStack> parts) {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(parts, "parts");
        EnumMap<GunsmithPressPart, GunsmithPartVariant> variants = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            GunsmithPartItem.PartData data = matchingPartData(
                    Objects.requireNonNullElse(parts.get(part), ItemStack.EMPTY), part);
            variants.put(part, data != null && data.platform() == blueprint.platform()
                    ? data.variant() : GunsmithPartVariant.BASE);
        }
        return variantConflict(blueprint, variants);
    }

    @Nullable
    private static VariantConflict variantConflict(GunsmithBlueprint blueprint,
                                                   EnumMap<GunsmithPressPart, GunsmithPartVariant> variants) {
        List<GunsmithPressPart> installed = new ArrayList<>(blueprint.requiredParts());
        for (int first = 0; first < installed.size(); first++) {
            GunsmithPartVariant firstVariant = variants.get(installed.get(first));
            for (int second = first + 1; second < installed.size(); second++) {
                GunsmithPartVariant secondVariant = variants.get(installed.get(second));
                if (firstVariant.excludes(secondVariant)) {
                    return new VariantConflict(installed.get(first), firstVariant,
                            installed.get(second), secondVariant);
                }
            }
        }
        return null;
    }

    public static EnumMap<GunsmithPressPart, ItemStack> previewCompatibleParts(
            GunsmithBlueprint blueprint, Map<GunsmithPressPart, ItemStack> parts) {
        Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(parts, "parts");
        EnumMap<GunsmithPressPart, ItemStack> compatible = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            ItemStack stack = parts.get(part);
            compatible.put(part, stack != null
                    && matchesPart(stack, part, blueprint.platform()) ? stack : ItemStack.EMPTY);
        }
        return compatible;
    }

    private static EnumMap<GunsmithPressPart, GunsmithPartVariant> variants(
            Map<GunsmithPressPart, ItemStack> parts, GunsmithBlueprint blueprint, boolean requireComplete) {
        EnumMap<GunsmithPressPart, GunsmithPartVariant> variants = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            ItemStack stack = parts.get(part);
            if (stack == null || stack.isEmpty()) {
                if (requireComplete) {
                    throw new IllegalArgumentException("Assembly part stack is empty for " + part.id());
                }
                variants.put(part, GunsmithPartVariant.BASE);
                continue;
            }
            variants.put(part, GunsmithPartItem.requirePartData(stack).variant());
        }
        return variants;
    }

    private static GunsmithPartQuality quality(Map<GunsmithPressPart, ItemStack> parts,
                                                GunsmithPressPart part) {
        ItemStack stack = parts.get(part);
        return stack == null || stack.isEmpty() ? GunsmithPartQuality.COMMON
                : GunsmithPartItem.requirePartData(stack).quality();
    }

    private static EnumMap<GunsmithPressPart, Double> coefficients(Map<GunsmithPressPart, ItemStack> parts,
                                                                     GunsmithBlueprint blueprint,
                                                                     boolean requireComplete) {
        Objects.requireNonNull(parts, "parts");
        Objects.requireNonNull(blueprint, "blueprint");
        Set<GunsmithPressPart> requiredParts = blueprint.requiredParts();
        EnumMap<GunsmithPressPart, Double> coefficients = new EnumMap<>(GunsmithPressPart.class);
        for (GunsmithPressPart part : requiredParts) {
            if (!parts.containsKey(part)) {
                throw new IllegalArgumentException("Assembly parts is missing " + part.id());
            }
            ItemStack stack = Objects.requireNonNull(parts.get(part), "Assembly part stack for " + part.id());
            if (stack.isEmpty()) {
                if (requireComplete) {
                    throw new IllegalArgumentException("Assembly part stack is empty for " + part.id());
                }
                coefficients.put(part, 1.0D);
                continue;
            }
            GunsmithPartItem.PartData data = GunsmithPartItem.requirePartData(stack);
            if (data.platform() != blueprint.platform()) {
                throw new IllegalArgumentException("Assembly part platform must be " + blueprint.platform().id());
            }
            if (data.part() != part) {
                throw new IllegalArgumentException("Assembly slot " + part.id()
                        + " received " + data.part().id());
            }
            coefficients.put(part, data.coefficient());
        }
        return coefficients;
    }

    private static CompoundTag partTags(Map<GunsmithPressPart, ItemStack> parts,
                                        Set<GunsmithPressPart> requiredParts) {
        CompoundTag result = new CompoundTag();
        for (GunsmithPressPart part : GunsmithPressPart.values()) {
            if (!requiredParts.contains(part)) {
                continue;
            }
            GunsmithPartItem.PartData data = GunsmithPartItem.requirePartData(parts.get(part));
            CompoundTag partTag = new CompoundTag();
            partTag.putString("quality", data.quality().id());
            partTag.putString("variant", data.variant().id());
            partTag.putDouble("coefficient", data.coefficient());
            result.put(part.id(), partTag);
        }
        return result;
    }

    private static CompoundTag stats(GunsmithBlueprint blueprint,
                                     EnumMap<GunsmithPressPart, Double> coefficients) {
        CompoundTag stats = new CompoundTag();
        // NBT 只缓存由品质浮动系数推导的基础值。组件规则运行时读取，热更新不会使存量枪失效。
        stats.putDouble("damage", coefficient(blueprint, coefficients, GunsmithStat.DAMAGE));
        stats.putDouble("headshot", coefficient(blueprint, coefficients, GunsmithStat.HEADSHOT));
        stats.putDouble("range", coefficient(blueprint, coefficients, GunsmithStat.RANGE));
        stats.putDouble("recoil", coefficient(blueprint, coefficients, GunsmithStat.RECOIL));
        stats.putDouble("spread", coefficient(blueprint, coefficients, GunsmithStat.SPREAD));
        stats.putDouble("handling", coefficient(blueprint, coefficients, GunsmithStat.HANDLING));
        stats.putDouble("average", average(coefficients, blueprint.requiredParts()));
        return stats;
    }

    private static double applyVariantRange(double base, GunsmithBlueprint blueprint,
                                            Map<GunsmithPressPart, ItemStack> parts,
                                            EnumMap<GunsmithPressPart, GunsmithPartVariant> variants) {
        double result = base;
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            result = variants.get(part).applyRangeMultiplier(result, quality(parts, part));
        }
        return result;
    }

    private static double variantProduct(GunsmithBlueprint blueprint,
                                         Map<GunsmithPressPart, ItemStack> parts,
                                         EnumMap<GunsmithPressPart, GunsmithPartVariant> variants,
                                         VariantStat stat) {
        double result = 1.0D;
        for (GunsmithPressPart part : blueprint.requiredParts()) {
            GunsmithPartVariant variant = variants.get(part);
            GunsmithPartQuality quality = quality(parts, part);
            result *= switch (stat) {
                case DAMAGE -> variant.damageMultiplier(quality);
                case HEADSHOT -> variant.headshotMultiplier(quality);
                case SPREAD -> variant.spreadMultiplier(quality);
                case RECOIL -> variant.recoilMultiplier(quality);
                case VERTICAL_RECOIL -> variant.verticalRecoilMultiplier(quality);
                case ADS_SPEED -> variant.adsSpeedMultiplier(quality);
                case FIRE_RATE -> variant.fireRateMultiplier(quality);
                case AMMO_SPEED -> variant.ammoSpeedMultiplier(quality);
                case ARMOR_IGNORE -> variant.armorIgnoreMultiplier(quality);
            };
        }
        return result;
    }

    private static double coefficient(GunsmithBlueprint blueprint, EnumMap<GunsmithPressPart, Double> coefficients,
                                      GunsmithStat stat) {
        return stat.coefficient(blueprint.platform(), coefficients::get);
    }

    private static double average(EnumMap<GunsmithPressPart, Double> coefficients,
                                  Set<GunsmithPressPart> requiredParts) {
        double total = 0.0D;
        for (GunsmithPressPart part : requiredParts) {
            total += Objects.requireNonNull(coefficients.get(part), "coefficient for " + part.id());
        }
        return total / requiredParts.size();
    }

    private enum VariantStat {
        DAMAGE,
        HEADSHOT,
        SPREAD,
        RECOIL,
        VERTICAL_RECOIL,
        ADS_SPEED,
        FIRE_RATE,
        AMMO_SPEED,
        ARMOR_IGNORE
    }

    /**
     * 装配预览的完整属性表, 与成品枪 tooltip ({@link GunsmithGunTooltip#append}) 逐行同口径。
     *
     * 量纲分三类, 别混用:
     * damage / headshot / effectiveRange / adsTime 是已乘过基础枪数值的绝对量 (伤害点数、爆头倍率、
     * 米、秒); range 与 average 是无量纲系数; 所有 {@code ...Change} 分量是百分点 (已乘 100), 负号表示
     * 该项数值变小。
     *
     * 后坐两轴的划分容易读错, 在此固定口径: {@code recoilChange} 是两轴共用的后坐变化 (品质后坐系数
     * 与组件 recoil 乘子的合并结果), 对水平轴它就是全部结论; {@code verticalRecoilChange} 是在
     * 前者之上、只作用于垂直轴的组件额外乘子, 不含品质分量。想得到垂直轴总变化要把两者按倍率相乘,
     * 不是相加 —— 这与 {@link GunsmithGunStats#horizontalRecoilMultiplier()} 和
     * {@link GunsmithGunStats#verticalRecoilMultiplier()} 的关系一致。
     *
     * {@code damage} 已过 {@link GunsmithGunStats#capDamageMultiplier(double)} 总帽, 与成品枪一致;
     * {@code headshot} 已过 1.8 品质复利帽, {@code spreadChange} 含握把分量, {@code fireRateChange} 在强制三连发时
     * 恒为 0, 三项都与 {@link GunsmithGunStats} 及实际写进 TaCZ 的口径一致 (E8)。
     *
     * {@code conflict} 非 null 表示所放组件互斥 (E6), 这套组合装配台会拒绝开工; 其余数值仍按所放组件照算,
     * 只供界面说明, 不代表能装出来。
     */
    public record Preview(double damage, double headshot, double range, double effectiveRange, double recoilChange,
                          double verticalRecoilChange, double spreadChange, double fireRateChange,
                          double ammoSpeedChange, double armorIgnoreChange, double adsTime, double average,
                          @Nullable VariantConflict conflict) {

        /** 所放组件互斥, 装配台会拒绝开工。 */
        public boolean rejected() {
            return conflict != null;
        }

        public double recoil() {
            return recoilChange;
        }

        public double spread() {
            return spreadChange;
        }

        public double aimDownSight() {
            return adsTime;
        }

        public double overallCoefficient() {
            return average;
        }
    }

    /** 一对互斥组件及其所在槽位 (E6); {@code first} 在图纸槽位顺序里排在前面。 */
    public record VariantConflict(GunsmithPressPart firstPart, GunsmithPartVariant first,
                                  GunsmithPressPart secondPart, GunsmithPartVariant second) {

        public VariantConflict {
            Objects.requireNonNull(firstPart, "firstPart");
            Objects.requireNonNull(first, "first");
            Objects.requireNonNull(secondPart, "secondPart");
            Objects.requireNonNull(second, "second");
        }

        /** 给玩家看的拒绝原因, 装配台拒绝消息与装配预览共用。 */
        public Component message() {
            return Component.translatableWithFallback(VARIANT_CONFLICT_KEY, VARIANT_CONFLICT_FALLBACK,
                    Component.translatable(first.labelKey()), Component.translatable(second.labelKey()));
        }
    }
}
