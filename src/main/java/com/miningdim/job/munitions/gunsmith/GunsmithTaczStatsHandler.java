package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsConfig;
import com.tacz.guns.api.GunProperties;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.entity.IGunOperator;
import com.tacz.guns.api.event.common.AttachmentPropertyEvent;
import com.tacz.guns.api.event.common.GunFireEvent;
import com.tacz.guns.api.event.common.GunShootEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.api.item.gun.FireMode;
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
import net.minecraft.Util;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.LogicalSide;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.function.Function;

public final class GunsmithTaczStatsHandler {

    /** 每个射手上一发放行 (服务端 GunShootEvent 未被取消) 的服务端时间, 供射速复核; 只在服务端线程读写。 */
    private static final Map<LivingEntity, Long> LAST_SHOT_MILLIS = new WeakHashMap<>();

    private GunsmithTaczStatsHandler() {
    }

    public static void register(IEventBus forgeBus) {
        requireSharedInaccuracyCacheKey();
        installSpreadsheetBaseModifiers();
        installRecoilModifier();
        forgeBus.register(new GunsmithTaczStatsHandler());
    }

    /**
     * 启动期钉死"两个散布键其实是一个"这一前提 (审查发现 25)。
     *
     * TaCZ 1.1.8 里 AIM_INACCURACY 与 INACCURACY 都是 GunProperty.of("inaccuracy", ...),
     * AttachmentCacheProperty 以 property.name() 作键, 所以散布只能写一次。若 TaCZ 升级后把两个键拆开,
     * 单写会静默丢掉 ADS 分量, 玩家在服上吃到错误散布却无人察觉 —— 宁可在这里炸掉。
     */
    private static void requireSharedInaccuracyCacheKey() {
        String hipKey = GunProperties.INACCURACY.name();
        String aimKey = GunProperties.AIM_INACCURACY.name();
        if (!hipKey.equals(aimKey)) {
            throw new IllegalStateException("TaCZ no longer shares one inaccuracy cache key (" + hipKey
                    + " vs " + aimKey + "); gunsmith must write the aim entry separately again");
        }
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

    /**
     * 记下每一份 TaCZ 属性缓存由哪一把枪构建, 供开火前核对 (V04, 见 {@link GunsmithTaczCacheGuard})。
     * 不看功能门: 门在运行期翻转后, 开火前的核对仍要拿到准确记录。
     */
    @SubscribeEvent
    public void onAttachmentPropertyBuilt(AttachmentPropertyEvent event) {
        AttachmentCacheProperty cache = event.getCacheProperty();
        if (cache != null) {
            GunsmithTaczCacheGuard.recordBuild(cache, event.getGunItem());
        }
    }

    /**
     * V04: 开火前核对射手身上的属性缓存是否由本次开火的这把枪构建, 不是就先重建再放行。
     * 同图纸、无皮肤的两把枪在同一快捷栏槽原地互换时 TaCZ 客户端不发 draw, 改版客户端更可以干脆不发,
     * 所以服务端必须自己核对。GunShootEvent 在 LivingEntityShoot 里早于 logicGun.shoot 抛出,
     * postChangeEvent 同步重建, 本发子弹 (伤害曲线、爆头、穿甲、射程、散布、弹速) 读到的就是这把枪的属性。
     * 客户端同样处理, 让本机后坐与开镜表现与服务端一致; 其他玩家开火经 ServerMessageGunShoot 转发到本机的那份
     * 事件带的是解包出来的副本, 其缓存也不归本机结算, 不碰。
     *
     * 服务端另有一项射速复核: TaCZ 的服务端射击冷却在本事件之前就按旧缓存的射速判过了, 重建后若旧缓存的间隔更短,
     * 按新缓存再判一次, 不够就作废本发 (判定见 {@link GunsmithTaczCacheGuard#rebuiltCacheRejectsShot})。
     * 作废的一发不丢弹: 闭膛枪在本事件之前只是把弹匣里的一发推上膛, 弹仍在膛内。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGunShoot(GunShootEvent event) {
        if (!MunitionsConfig.GUNSMITH_ENABLED.get()) {
            return;
        }
        LivingEntity shooter = event.getShooter();
        boolean clientSide = event.getLogicalSide() == LogicalSide.CLIENT;
        if (clientSide && !(shooter instanceof Player player && player.isLocalPlayer())) {
            return;
        }
        ItemStack gun = event.getGunItemStack();
        AttachmentCacheProperty staleCache = IGunOperator.fromLivingEntity(shooter).getCacheProperty();
        if (!GunsmithTaczCacheGuard.needsRebuild(staleCache, gun, clientSide)) {
            return;
        }
        // 客户端的冷却本来就归客户端自己, 只在服务端复核; 射手原本没有缓存时 TaCZ 按 gunData 判冷却, 无从放宽。
        long staleInterval = !clientSide && staleCache != null ? cachedShootIntervalMillis(shooter, gun) : -1L;
        AttachmentPropertyManager.postChangeEvent(shooter, gun);
        if (staleInterval > 0L && GunsmithTaczCacheGuard.rebuiltCacheRejectsShot(staleInterval,
                cachedShootIntervalMillis(shooter, gun), LAST_SHOT_MILLIS.get(shooter), Util.getMillis())) {
            event.setCanceled(true);
        }
    }

    /**
     * 记下每个射手上一发真正放行的服务端时间, 对应 TaCZ 在 GunShootEvent 之后写 shootTimestamp 的时机。
     * LOWEST 且不收已取消的事件: 被任何一方拦下的一发都不算。不看功能门, 门在运行期打开后复核仍有准确记录。
     */
    @SubscribeEvent(priority = EventPriority.LOWEST)
    public void onGunShootAllowed(GunShootEvent event) {
        if (event.getLogicalSide() == LogicalSide.SERVER) {
            LAST_SHOT_MILLIS.put(event.getShooter(), Util.getMillis());
        }
    }

    /**
     * V04 连发后续各轮: 三连发只有第 1 轮紧跟 GunShootEvent 同步执行, 第 2、3 轮推迟到之后的服务端 tick
     * (TaCZ CycleTaskHelper), 每轮构造子弹时重新读射手缓存 (伤害曲线、爆头、穿甲、有效射程、穿透、爆炸)。
     * 两轮之间 TaCZ 只核对主手仍是同一个 ItemStack 对象: 改包客户端可以把 A 换进槽里发一次切射击模式 (缓存重建为 A,
     * 该包没有冷却), 再把同一个 B 换回去, 身份核对照样通过。GunFireEvent 每轮都在 reduceAmmoOnce 与子弹构造之前抛出,
     * 带的正是 TaCZ 刚核对过的主手对象, 所以逐轮在这里再核对一次。正常连射对象不变, 只是一次查表, 不会重建。
     * 连发各轮的间隔取 gunData 的 burst 数据, 不读缓存, 这里不必复核射速。
     */
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onGunFire(GunFireEvent event) {
        if (!MunitionsConfig.GUNSMITH_ENABLED.get() || event.getLogicalSide() != LogicalSide.SERVER) {
            return;
        }
        LivingEntity shooter = event.getShooter();
        ItemStack gun = event.getGunItemStack();
        if (GunsmithTaczCacheGuard.needsRebuild(
                IGunOperator.fromLivingEntity(shooter).getCacheProperty(), gun, false)) {
            AttachmentPropertyManager.postChangeEvent(shooter, gun);
        }
    }

    /**
     * TaCZ 1.1.8 LivingEntityShoot.getShootCoolDown 按射手当下缓存算出的射击间隔 (毫秒), 与 GunData.getShootInterval 同一口径
     * (缓存射速钳在 1..1200, 再乘枪温系数)。返回 -1 表示这一发的服务端冷却不读缓存, 无需复核:
     * 服务端冷却检查被关掉、三连发 (冷却取 gunData 的连发最小间隔)、或查不到枪数据。
     */
    private static long cachedShootIntervalMillis(LivingEntity shooter, ItemStack gun) {
        IGun iGun = IGun.getIGunOrNull(gun);
        if (iGun == null || !SyncConfig.SERVER_SHOOT_COOLDOWN_V.get()) {
            return -1L;
        }
        FireMode fireMode = iGun.getFireMode(gun);
        if (fireMode == FireMode.BURST) {
            return -1L;
        }
        return TimelessAPI.getCommonGunIndex(iGun.getGunId(gun))
                .map(index -> index.getGunData().getShootInterval(shooter, fireMode, gun))
                .orElse(-1L);
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
        // 只写一次: AIM_INACCURACY 与 INACCURACY 共用同一份缓存, 写两次等于把散布乘数平方。
        multiplyInaccuracy(cache, GunProperties.INACCURACY, multipliers.combinedInaccuracy());
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
            RecoilFormula formula = recoilFormula(gun);
            // 非枪匠枪 / 枪匠关闭 / 系数恰好为 1 时公式恒等, 原样交回 TaCZ 缓存 (审查发现 72)。
            // 否则全服每一把 TaCZ 枪都会被塞进恒等式脚本, 每次开火白跑一次 LuaJ 编译。
            if (formula.isIdentity()) {
                return initialized;
            }
            ParameterizedCachePair<Float, Float> value = Objects.requireNonNull(initialized.getValue(),
                    "TaCZ recoil modifier initialized an empty cache");
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
            // 恒等公式下 initCache 交回的是 TaCZ 原始 CacheValue, 这里不能强转。
            if (!(cacheValue instanceof GunsmithRecoilCacheValue gunsmithCache)) {
                delegate.eval(attachmentModifiers, cacheValue);
                return;
            }
            RecoilFormula formula = gunsmithCache.formula();
            List<Pair<Modifier, Modifier>> combined = new ArrayList<>(attachmentModifiers);
            // TaCZ RecoilModifier 的 Pair 顺序是 pitch(垂直)、yaw(水平)。
            combined.add(Pair.of(new ScalingModifier(formula.pitchBase()),
                    new ScalingModifier(formula.yawBase())));
            // 势力组件额外制造的后坐在 TaCZ 原生配件/脚本之后相加，避免先锋 A3 一类
            // 极端制退器把红冬导气的自身惩罚同比压掉。
            if (formula.pitchExtra() > 0.0D || formula.yawExtra() > 0.0D) {
                combined.add(Pair.of(RecoilFormula.extraModifier(formula.pitchExtra()),
                        RecoilFormula.extraModifier(formula.yawExtra())));
            }
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

        private boolean isIdentity() {
            return IDENTITY.equals(this);
        }

        private List<Modifier> pitchModifiers() {
            return axisModifiers(pitchBase, pitchExtra);
        }

        private List<Modifier> yawModifiers() {
            return axisModifiers(yawBase, yawExtra);
        }

        /**
         * extra 为 0 时不挂 AdditiveRecoilModifier: 它的 getFunction() 恒非空, TaCZ 的 ParameterizedCache
         * 只按 isNotEmpty 收脚本, 于是恒等式 "x + r * 0.0" 会进脚本表, 每次开火都白跑一次 LuaJ 字符串编译。
         */
        private static List<Modifier> axisModifiers(double base, double extra) {
            return extra > 0.0D
                    ? List.of(new ScalingModifier(base), new AdditiveRecoilModifier(extra))
                    : List.of(new ScalingModifier(base));
        }

        /**
         * pitch/yaw 在 eval() 里必须成对提交, 单轴没有额外后坐时交一个中性 Modifier
         * (addend 0 / percent 0 / multiplier 1 / function null), 避免为它单独编译一段恒等脚本。
         */
        private static Modifier extraModifier(double extra) {
            return extra > 0.0D ? new AdditiveRecoilModifier(extra) : new Modifier();
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
