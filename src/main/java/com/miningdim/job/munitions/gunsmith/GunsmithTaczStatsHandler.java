package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsConfig;
import com.tacz.guns.api.GunProperties;
import com.tacz.guns.api.event.common.AttachmentPropertyEvent;
import com.tacz.guns.api.modifier.CacheValue;
import com.tacz.guns.api.modifier.IAttachmentModifier;
import com.tacz.guns.api.modifier.JsonProperty;
import com.tacz.guns.api.modifier.ParameterizedCachePair;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.resource.pojo.data.gun.ExtraDamage;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import com.tacz.guns.resource.pojo.data.gun.InaccuracyType;
import com.tacz.guns.resource.pojo.data.attachment.Modifier;
import it.unimi.dsi.fastutil.Pair;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class GunsmithTaczStatsHandler {

    private GunsmithTaczStatsHandler() {
    }

    public static void register(IEventBus forgeBus) {
        installRecoilModifier();
        forgeBus.register(new GunsmithTaczStatsHandler());
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

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onAttachmentProperty(AttachmentPropertyEvent event) {
        // 功能门 (审查 G-1): 事件体内查 config (SERVER config 于世界加载后可用, 注册期不可读);
        // 关闭时存量枪械的成品属性系数一并失效。
        if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
            return;
        }
        ItemStack gun = event.getGunItem();
        GunsmithGunStats stats = GunsmithGunStats.from(gun);
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
        multiplyFloat(cache, GunProperties.ADS_TIME, multipliers.adsTime());
        multiplyInaccuracy(cache, GunProperties.INACCURACY, multipliers.inaccuracy());
        multiplyInaccuracy(cache, GunProperties.AIM_INACCURACY, multipliers.aimInaccuracy());
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
            return new GunsmithRecoilCacheValue(value, recoilMultiplier(gun));
        }

        @Override
        public void eval(List<Pair<Modifier, Modifier>> attachmentModifiers,
                         CacheValue<ParameterizedCachePair<Float, Float>> cacheValue) {
            GunsmithRecoilCacheValue gunsmithCache = (GunsmithRecoilCacheValue) cacheValue;
            List<Pair<Modifier, Modifier>> combined = new ArrayList<>(attachmentModifiers);
            combined.add(Pair.of(new ScalingModifier(gunsmithCache.multiplier()),
                    new ScalingModifier(gunsmithCache.multiplier())));
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

        private static double recoilMultiplier(ItemStack gun) {
            if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
                return 1.0D;
            }
            GunsmithGunStats stats = GunsmithGunStats.from(gun);
            if (stats == null) {
                return 1.0D;
            }
            return GunsmithStatMultipliers.of(stats, MunitionsConfig.GUNSMITH_HEADSHOT_DAMAGE_CAP.get()).recoil();
        }
    }

    private static final class GunsmithRecoilCacheValue
            extends CacheValue<ParameterizedCachePair<Float, Float>> {

        private final double multiplier;

        private GunsmithRecoilCacheValue(ParameterizedCachePair<Float, Float> value, double multiplier) {
            super(value);
            this.multiplier = multiplier;
        }

        private double multiplier() {
            return multiplier;
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
}
