package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsConfig;
import com.tacz.guns.api.GunProperties;
import com.tacz.guns.api.event.common.AttachmentPropertyEvent;
import com.tacz.guns.api.modifier.CacheValue;
import com.tacz.guns.api.modifier.IAttachmentModifier;
import com.tacz.guns.api.modifier.JsonProperty;
import com.tacz.guns.api.modifier.ParameterizedCachePair;
import com.tacz.guns.config.sync.SyncConfig;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.resource.pojo.data.gun.ExtraDamage;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.resource.pojo.data.gun.InaccuracyType;
import com.tacz.guns.resource.pojo.data.attachment.Modifier;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

public final class GunsmithTaczStatsHandler {

    private GunsmithTaczStatsHandler() {
    }

    public static void register(IEventBus forgeBus) {
        installSpreadsheetBaseModifiers();
        installRecoilModifier();
        forgeBus.register(new GunsmithTaczStatsHandler());
    }

    /** 规则热重载后立即重建持枪实体的 TaCZ 属性缓存。 */
    public static void refreshHeldGun(LivingEntity entity) {
        Objects.requireNonNull(entity, "entity");
        AttachmentPropertyManager.postChangeEvent(entity, entity.getMainHandItem());
    }

    @SuppressWarnings("unchecked")
    private static void installRecoilModifier() {
        Map<String, IAttachmentModifier<?, ?>> modifiers = AttachmentPropertyManager.getModifiers();
        String propertyId = GunProperties.RECOIL.name();
        IAttachmentModifier<?, ?> registered = Objects.requireNonNull(modifiers.get(propertyId),
                "TaCZ has no registered recoil modifier");
        IAttachmentModifier<Pair<Modifier, Modifier>, ParameterizedCachePair<Float, Float>> recoilModifier =
                (IAttachmentModifier<Pair<Modifier, Modifier>, ParameterizedCachePair<Float, Float>>) registered;
        modifiers.put(propertyId, new GunsmithRecoilModifier(recoilModifier));
    }

    @SuppressWarnings("unchecked")
    private static void installSpreadsheetBaseModifiers() {
        installBaseModifier(GunProperties.DAMAGE, profile -> {
            double baseMultiplier = SyncConfig.DAMAGE_BASE_MULTIPLIER.get();
            LinkedList<ExtraDamage.DistanceDamagePair> curve = new LinkedList<>();
            for (GunsmithWeaponBaseProfile.DamagePoint point : profile.damageCurve()) {
                curve.add(new ExtraDamage.DistanceDamagePair(point.distance(),
                        (float) (point.damage() * baseMultiplier)));
            }
            return curve;
        });
        installBaseModifier(GunProperties.EFFECTIVE_RANGE,
                profile -> (float) profile.effectiveRange());
        installBaseModifier(GunProperties.HEADSHOT_MULTIPLIER,
                profile -> (float) (profile.headshotMultiplier()
                        * SyncConfig.HEAD_SHOT_BASE_MULTIPLIER.get()));
        installBaseModifier(GunProperties.ARMOR_IGNORE,
                profile -> (float) (profile.armorIgnore()
                        * SyncConfig.ARMOR_IGNORE_BASE_MULTIPLIER.get()));
    }

    @SuppressWarnings("unchecked")
    private static <T, K> void installBaseModifier(com.tacz.guns.api.GunProperty<K> property,
                                                    Function<GunsmithWeaponBaseProfile, K> baseValue) {
        Map<String, IAttachmentModifier<?, ?>> modifiers = AttachmentPropertyManager.getModifiers();
        IAttachmentModifier<T, K> delegate = (IAttachmentModifier<T, K>) Objects.requireNonNull(
                modifiers.get(property.name()), "TaCZ has no registered " + property.name() + " modifier");
        modifiers.put(property.name(), new GunsmithBaseProfileModifier<>(delegate, baseValue));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onAttachmentProperty(AttachmentPropertyEvent event) {
        // 功能门 (审查 G-1): 事件体内查 config (SERVER config 于世界加载后可用, 注册期不可读);
        // 关闭时存量枪械的成品属性系数一并失效。
        if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
            return;
        }
        ItemStack gun = event.getGunItem();
        GunsmithGunStats stats = GunsmithGunStats.tryFrom(gun);
        if (stats == null) {
            return;
        }

        AttachmentCacheProperty cache = Objects.requireNonNull(event.getCacheProperty(),
                "TaCZ attachment property event has no cache");
        GunsmithStatMultipliers multipliers =
                GunsmithStatMultipliers.of(stats, MunitionsConfig.GUNSMITH_HEADSHOT_DAMAGE_CAP.get());
        multiplyDamage(cache, multipliers.damage());
        multiplyFloat(cache, GunProperties.HEADSHOT_MULTIPLIER, multipliers.headshot());
        multiplyFloat(cache, GunProperties.EFFECTIVE_RANGE, multipliers.effectiveRange());
        multiplyFloat(cache, GunProperties.AMMO_SPEED, multipliers.ammoSpeed());
        multiplyFloat(cache, GunProperties.ARMOR_IGNORE, multipliers.armorIgnore());
        multiplyFloat(cache, GunProperties.ADS_TIME, multipliers.adsTime());
        multiplyInaccuracy(cache, GunProperties.INACCURACY, multipliers.inaccuracy());
        multiplyInaccuracy(cache, GunProperties.AIM_INACCURACY, multipliers.aimInaccuracy());
        multiplyInteger(cache, GunProperties.ROUNDS_PER_MINUTE, multipliers.fireRate());
    }

    private static void multiplyFloat(AttachmentCacheProperty cache,
                                      com.tacz.guns.api.GunProperty<Float> property,
                                      double multiplier) {
        Float value = Objects.requireNonNull(cache.getCache(property),
                "TaCZ attachment cache has no value for " + property.name());
        cache.setCache(property, (float) (value * multiplier));
    }

    private static void multiplyDamage(AttachmentCacheProperty cache, double multiplier) {
        LinkedList<ExtraDamage.DistanceDamagePair> damagePairs = Objects.requireNonNull(
                cache.getCache(GunProperties.DAMAGE), "TaCZ attachment cache has no damage pairs");
        if (damagePairs.isEmpty()) {
            throw new IllegalStateException("TaCZ attachment cache has an empty damage curve");
        }
        LinkedList<ExtraDamage.DistanceDamagePair> adjusted = new LinkedList<>();
        for (ExtraDamage.DistanceDamagePair pair : damagePairs) {
            ExtraDamage.DistanceDamagePair damagePair = Objects.requireNonNull(pair,
                    "TaCZ attachment cache contains a null damage pair");
            adjusted.add(new ExtraDamage.DistanceDamagePair(damagePair.getDistance(),
                    (float) (damagePair.getDamage() * multiplier)));
        }
        cache.setCache(GunProperties.DAMAGE, adjusted);
    }

    private static void multiplyInteger(AttachmentCacheProperty cache,
                                        com.tacz.guns.api.GunProperty<Integer> property,
                                        double multiplier) {
        Integer value = Objects.requireNonNull(cache.getCache(property),
                "TaCZ attachment cache has no value for " + property.name());
        cache.setCache(property, Math.max(1, (int) Math.round(value * multiplier)));
    }

    private static void multiplyInaccuracy(AttachmentCacheProperty cache,
                                           com.tacz.guns.api.GunProperty<Map<InaccuracyType, Float>> property,
                                           double multiplier) {
        Map<InaccuracyType, Float> value = Objects.requireNonNull(cache.getCache(property),
                "TaCZ attachment cache has no value for " + property.name());
        EnumMap<InaccuracyType, Float> adjusted = new EnumMap<>(InaccuracyType.class);
        for (Map.Entry<InaccuracyType, Float> entry : value.entrySet()) {
            adjusted.put(Objects.requireNonNull(entry.getKey(), "TaCZ inaccuracy cache has a null type"),
                    (float) (Objects.requireNonNull(entry.getValue(),
                            "TaCZ inaccuracy cache has a null value") * multiplier));
        }
        cache.setCache(property, adjusted);
    }

    private static final class GunsmithRecoilModifier implements
            IAttachmentModifier<Pair<Modifier, Modifier>, ParameterizedCachePair<Float, Float>> {

        private final IAttachmentModifier<Pair<Modifier, Modifier>, ParameterizedCachePair<Float, Float>> delegate;

        private GunsmithRecoilModifier(
                IAttachmentModifier<Pair<Modifier, Modifier>, ParameterizedCachePair<Float, Float>> delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public String getOptionalFields() {
            return delegate.getOptionalFields();
        }

        @Override
        public JsonProperty<Pair<Modifier, Modifier>> readJson(String json) {
            return delegate.readJson(json);
        }

        @Override
        public CacheValue<ParameterizedCachePair<Float, Float>> initCache(ItemStack gun, GunData gunData) {
            CacheValue<ParameterizedCachePair<Float, Float>> initialized = delegate.initCache(gun, gunData);
            ParameterizedCachePair<Float, Float> value = Objects.requireNonNull(initialized.getValue(),
                    "TaCZ recoil modifier initialized an empty cache");
            RecoilFormula formula = recoilFormula(gun);
            // TaCZ 仅在至少存在一个原生配件修正时调用 modifier.eval()。枪械没有后坐配件时，
            // 空修正列表会被 AttachmentCacheProperty 提前跳过，因此枪匠倍率必须先写入初始缓存；
            // 有原生配件时 eval() 会用“原生修正 + 枪匠修正”重建缓存，不会重复计算本倍率。
            ParameterizedCachePair<Float, Float> seeded = ParameterizedCachePair.of(
                    formula.pitchModifiers(), formula.yawModifiers(),
                    value.left().getDefaultValue(), value.right().getDefaultValue());
            return new GunsmithRecoilCacheValue(seeded, formula);
        }

        @Override
        public void eval(List<Pair<Modifier, Modifier>> attachmentModifiers,
                         CacheValue<ParameterizedCachePair<Float, Float>> cacheValue) {
            GunsmithRecoilCacheValue gunsmithCache = (GunsmithRecoilCacheValue) cacheValue;
            List<Pair<Modifier, Modifier>> combined = new ArrayList<>(attachmentModifiers);
            // TaCZ RecoilModifier 的 Pair 顺序是 pitch(垂直)、yaw(水平)。
            combined.add(Pair.of(new ScalingModifier(gunsmithCache.formula().pitchBase()),
                    new ScalingModifier(gunsmithCache.formula().yawBase())));
            // 势力组件额外制造的后坐在 TaCZ 原生配件/脚本之后相加，避免先锋 A3 一类
            // 极端制退器把红冬导气的自身惩罚同比压掉。
            combined.add(Pair.of(new AdditiveRecoilModifier(gunsmithCache.formula().pitchExtra()),
                    new AdditiveRecoilModifier(gunsmithCache.formula().yawExtra())));
            delegate.eval(combined, cacheValue);
        }

        @Override
        public List<DiagramsData> getPropertyDiagramsData(ItemStack gun, GunData gunData,
                                                           AttachmentCacheProperty cache) {
            return delegate.getPropertyDiagramsData(gun, gunData, cache);
        }

        @Override
        public int getDiagramsDataSize() {
            return delegate.getDiagramsDataSize();
        }

        private static RecoilFormula recoilFormula(ItemStack gun) {
            if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
                return RecoilFormula.IDENTITY;
            }
            GunsmithGunStats stats = GunsmithGunStats.tryFrom(gun);
            if (stats == null) {
                return RecoilFormula.IDENTITY;
            }
            GunsmithStatMultipliers multipliers =
                    GunsmithStatMultipliers.of(stats, MunitionsConfig.GUNSMITH_HEADSHOT_DAMAGE_CAP.get());
            double baseControl = 1.0D / stats.recoil();
            return new RecoilFormula(
                    GunsmithStatMultipliers.attachmentScaledRecoilBase(
                            baseControl, multipliers.verticalRecoil()),
                    GunsmithStatMultipliers.nonReducibleRecoilExtra(
                            baseControl, multipliers.verticalRecoil()),
                    GunsmithStatMultipliers.attachmentScaledRecoilBase(
                            baseControl, multipliers.recoil()),
                    GunsmithStatMultipliers.nonReducibleRecoilExtra(
                            baseControl, multipliers.recoil()));
        }
    }

    /** Seeds the spreadsheet base value before TaCZ evaluates its ordinary attachment modifiers. */
    private static final class GunsmithBaseProfileModifier<T, K> implements IAttachmentModifier<T, K> {

        private final IAttachmentModifier<T, K> delegate;
        private final Function<GunsmithWeaponBaseProfile, K> baseValue;

        private GunsmithBaseProfileModifier(IAttachmentModifier<T, K> delegate,
                                            Function<GunsmithWeaponBaseProfile, K> baseValue) {
            this.delegate = Objects.requireNonNull(delegate);
            this.baseValue = Objects.requireNonNull(baseValue);
        }

        @Override
        public String getId() {
            return delegate.getId();
        }

        @Override
        public String getOptionalFields() {
            return delegate.getOptionalFields();
        }

        @Override
        public JsonProperty<T> readJson(String json) {
            return delegate.readJson(json);
        }

        @Override
        public CacheValue<K> initCache(ItemStack gun, GunData gunData) {
            CacheValue<K> initialized = delegate.initCache(gun, gunData);
            if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
                return initialized;
            }
            GunsmithGunStats stats = GunsmithGunStats.tryFrom(gun);
            if (stats == null) {
                return initialized;
            }
            Optional<GunsmithWeaponBaseProfile> profile =
                    GunsmithWeaponBaseProfile.find(stats.blueprint());
            profile.ifPresent(value -> initialized.setValue(baseValue.apply(value)));
            return initialized;
        }

        @Override
        public void eval(List<T> attachmentModifiers, CacheValue<K> cacheValue) {
            delegate.eval(attachmentModifiers, cacheValue);
        }

        @Override
        public List<DiagramsData> getPropertyDiagramsData(ItemStack gun, GunData gunData,
                                                           AttachmentCacheProperty cache) {
            return delegate.getPropertyDiagramsData(gun, gunData, cache);
        }

        @Override
        public int getDiagramsDataSize() {
            return delegate.getDiagramsDataSize();
        }
    }

    private static final class GunsmithRecoilCacheValue
            extends CacheValue<ParameterizedCachePair<Float, Float>> {

        private final RecoilFormula formula;

        private GunsmithRecoilCacheValue(ParameterizedCachePair<Float, Float> value,
                                         RecoilFormula formula) {
            super(value);
            this.formula = Objects.requireNonNull(formula, "formula");
        }

        private RecoilFormula formula() {
            return formula;
        }
    }

    private record RecoilFormula(double pitchBase, double pitchExtra,
                                 double yawBase, double yawExtra) {

        private static final RecoilFormula IDENTITY = new RecoilFormula(1.0D, 0.0D, 1.0D, 0.0D);

        private List<Modifier> pitchModifiers() {
            return List.of(new ScalingModifier(pitchBase), new AdditiveRecoilModifier(pitchExtra));
        }

        private List<Modifier> yawModifiers() {
            return List.of(new ScalingModifier(yawBase), new AdditiveRecoilModifier(yawExtra));
        }
    }

    private static final class ScalingModifier extends Modifier {

        private final double multiplier;

        private ScalingModifier(double multiplier) {
            this.multiplier = multiplier;
        }

        @Override
        public double getMultiplier() {
            return multiplier;
        }
    }

    private static final class AdditiveRecoilModifier extends Modifier {

        private final String function;

        private AdditiveRecoilModifier(double extraMultiplier) {
            this.function = "y = math.max(0.0, x + r * " + Double.toString(extraMultiplier) + ")";
        }

        @Override
        public String getFunction() {
            return function;
        }
    }
}
