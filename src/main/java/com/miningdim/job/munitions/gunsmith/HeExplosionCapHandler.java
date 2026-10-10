package com.miningdim.job.munitions.gunsmith;

import com.miningdim.job.munitions.MunitionsConfig;
import com.tacz.guns.api.GunProperties;
import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.api.event.common.AttachmentPropertyEvent;
import com.tacz.guns.api.item.IGun;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.resource.modifier.AttachmentCacheProperty;
import com.tacz.guns.resource.modifier.AttachmentPropertyManager;
import com.tacz.guns.resource.modifier.custom.ExplosionModifier;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.ExplosionData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.IEventBus;
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * HE 当量上限 (平衡方案 C1, 自检为 C4): 配件开启的 TaCZ 子弹爆炸, 每颗弹丸的爆炸伤害不超过它自己的直伤。
 *
 * 口径 (TaCZ 1.1.8, javap 核实): HE 弹头改装 (ammo_mod_he) 只把 explode 设为 true, 爆炸参数取枪数据
 * bullet.explosion 的模板 (枪数据没写这一段时取 ExplosionModifier 的缺省模板 2 伤 / 0.5 格), 按弹丸逐颗结算。
 * 默认比例下 M1014 每颗上限 5.0, 一发合计不超过整发直伤 40。
 *
 * 做法: 在 AttachmentPropertyEvent (LOWEST) 里改写这份属性缓存的 EXPLOSION 项。只处理"缓存爆炸为真、枪原生不爆炸",
 * 即由配件开启的爆炸; 伤害改为 min(模板, 原生子弹伤害 ÷ max(1, 弹丸数) × heExplosionPerProjectileCapRatio),
 * 半径、击退、延时、破坏方块沿用模板; 比例为 0 时整个关掉配件爆炸。RPG7、M320 等原生爆炸武器原样放行。
 * EntityKineticBullet 构造时按 ExplosionModifier.ID 从射手缓存读爆炸数据 (再乘 TaCZ 的全局伤害倍率, 直伤同样乘,
 * 比例不变), 所以改写对之后射出的每颗弹丸生效。
 *
 * 不受 gunsmithEnabled 门控: 作用于全部 TaCZ 枪 (含非枪匠枪), 配件配方照常可造 (Munitions_Job_DesignSpec 三章)。
 * 与 GunsmithTaczStatsHandler 同在 LOWEST, 两者改的缓存项互不相交, 先后无关。
 *
 * 类加载: 外层只放纯函数, 不引用任何 TaCZ 类, dev GameTest (不加载 TaCZ) 可以直接调用;
 * 自检与监听全在 {@link TaczHooks}, 只有 {@link #register} 触到它, 而 register 只在 TaCZ 已加载时由 MunitionsSystem 调用。
 */
public final class HeExplosionCapHandler {

    private HeExplosionCapHandler() {
    }

    /** 启动自检后注册缓存改写监听; 只能在 TaCZ 已加载时调用。 */
    public static void register(IEventBus forgeBus) {
        TaczHooks.register(forgeBus);
    }

    /**
     * 每颗弹丸的爆炸伤害上限 = 原生子弹伤害 ÷ max(1, 弹丸数) × 比例 (比例小于 0 按 0)。
     * 霰弹的 bullet.damage 是整发总伤, TaCZ 按弹丸数均分给每颗 (EntityKineticBullet.applyShotgunDamageSpread),
     * 所以这里的商就是单颗弹丸的直伤。
     */
    public static float perProjectileCap(float gunBaseDamage, int pelletCount, double ratio) {
        return (float) (gunBaseDamage / Math.max(1, pelletCount) * Math.max(0.0D, ratio));
    }

    /**
     * 属性缓存的 EXPLOSION 项该改成什么; 返回 null 表示原样保留。
     * 缓存不爆炸 (没装爆炸配件) 或枪原生就爆炸 (RPG7、M320) 时不动; 帽值不大于 0 (比例 0) 时关掉爆炸;
     * 其余取 min(模板伤害, 帽值)。
     */
    @Nullable
    public static Rewrite rewrite(boolean cachedExplode, float cachedDamage, boolean nativeExplode,
                                  float gunBaseDamage, int pelletCount, double ratio) {
        if (!cachedExplode || nativeExplode) {
            return null;
        }
        float cap = perProjectileCap(gunBaseDamage, pelletCount, ratio);
        if (!(cap > 0.0F)) {
            return new Rewrite(false, 0.0F);
        }
        return new Rewrite(true, Math.min(cachedDamage, cap));
    }

    /** 改写后的爆炸开关与每颗弹丸的爆炸伤害 (与 TaCZ 缓存同口径, 未乘全局伤害倍率)。 */
    public record Rewrite(boolean explode, float damage) {
    }

    /** TaCZ 相关部分: 启动自检 + 缓存改写。只经 {@link HeExplosionCapHandler#register} 触达。 */
    private static final class TaczHooks {

        private TaczHooks() {
        }

        private static void register(IEventBus forgeBus) {
            requirePrerequisites();
            forgeBus.addListener(EventPriority.LOWEST, false, AttachmentPropertyEvent.class,
                    TaczHooks::onAttachmentProperty);
        }

        private static void onAttachmentProperty(AttachmentPropertyEvent event) {
            AttachmentCacheProperty cache = event.getCacheProperty();
            if (cache == null) {
                return;
            }
            ExplosionData cached = cache.getCache(GunProperties.EXPLOSION);
            if (cached == null || !cached.isExplode()) {
                return;
            }
            ItemStack gun = event.getGunItem();
            IGun iGun = IGun.getIGunOrNull(gun);
            if (iGun == null) {
                return;
            }
            // 与 AttachmentPropertyManager.postChangeEvent 构建这份缓存时取的是同一份枪数据。
            BulletData bullet = TimelessAPI.getCommonGunIndex(iGun.getGunId(gun))
                    .map(index -> index.getGunData().getBulletData())
                    .orElse(null);
            if (bullet == null) {
                return;
            }
            ExplosionData nativeExplosion = bullet.getExplosionData();
            Rewrite rewrite = HeExplosionCapHandler.rewrite(true, cached.getDamage(),
                    nativeExplosion != null && nativeExplosion.isExplode(),
                    bullet.getDamageAmount(), bullet.getBulletAmount(),
                    MunitionsConfig.HE_EXPLOSION_PER_PROJECTILE_CAP_RATIO.get());
            if (rewrite == null) {
                return;
            }
            cache.setCache(GunProperties.EXPLOSION, new ExplosionData(rewrite.explode(), cached.getRadius(),
                    rewrite.damage(), cached.isKnockback(), cached.getDelay(), cached.isDestroyBlock()));
        }

        /**
         * 启动期钉死本方案依赖的 TaCZ 前提 (C4)。TaCZ 升级若改掉其中任何一条, 缓存改写会静默失效
         * (当量帽随之失效) 或在换枪、开火路径上抛 NoSuchMethodError; 宁可在这里炸掉,
         * 同 GunsmithTaczStatsHandler.requireSharedInaccuracyCacheKey 的做法。
         */
        private static void requirePrerequisites() {
            // 1. 缓存键: 子弹按 ExplosionModifier.ID 读, 本类按 GunProperties.EXPLOSION 写, 必须是同一个键;
            //    该键还得真有修改器注册, 否则缓存里没有这一项, getCache 直接 NPE。
            String key = GunProperties.EXPLOSION.name();
            if (!key.equals(ExplosionModifier.ID)) {
                throw new IllegalStateException("TaCZ explosion cache key changed: GunProperties.EXPLOSION is '"
                        + key + "' but bullets read '" + ExplosionModifier.ID + "'; HE explosion cap would be ignored");
            }
            if (!AttachmentPropertyManager.getModifiers().containsKey(key)) {
                throw new IllegalStateException("TaCZ has no attachment modifier registered for '" + key
                        + "'; HE explosion cap cannot rewrite the explosion cache");
            }
            if (!GunProperties.EXPLOSION.type().isAssignableFrom(ExplosionData.class)) {
                throw new IllegalStateException("TaCZ explosion cache no longer holds ExplosionData (holds "
                        + GunProperties.EXPLOSION.type().getName() + ")");
            }
            // 2. ExplosionData 6 参构造 (explode, radius, damage, knockback, delay, destroyBlock): 先按签名取,
            //    再造一份核对 getter 回读, 防签名不变而参数顺序被调换 (半径与伤害互换会把帽值写进半径)。
            requireExplosionDataConstructor();
            // 3. 读取入口: EntityKineticBullet#getGunId (配件爆炸按子弹 gunId 反查原生爆炸数据, 平衡方案 C2 依赖它);
            //    BulletData 的弹丸数、伤害、爆炸数据 (本类每次缓存重建都调用)。
            requireMethod(EntityKineticBullet.class, "getGunId", ResourceLocation.class);
            requireMethod(BulletData.class, "getBulletAmount", int.class);
            requireMethod(BulletData.class, "getDamageAmount", float.class);
            requireMethod(BulletData.class, "getExplosionData", ExplosionData.class);
        }

        private static void requireExplosionDataConstructor() {
            ExplosionData probe;
            try {
                Constructor<ExplosionData> constructor = ExplosionData.class.getConstructor(boolean.class,
                        float.class, float.class, boolean.class, float.class, boolean.class);
                probe = constructor.newInstance(true, 1.0F, 2.0F, true, 3.0F, false);
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("TaCZ ExplosionData(boolean, float, float, boolean, float, boolean)"
                        + " constructor is missing; HE explosion cap cannot rebuild the explosion cache", e);
            }
            if (!probe.isExplode() || probe.getRadius() != 1.0F || probe.getDamage() != 2.0F
                    || !probe.isKnockback() || probe.getDelay() != 3.0F || probe.isDestroyBlock()) {
                throw new IllegalStateException("TaCZ ExplosionData constructor no longer takes"
                        + " (explode, radius, damage, knockback, delay, destroyBlock) in that order");
            }
        }

        private static void requireMethod(Class<?> owner, String name, Class<?> returnType) {
            Method method;
            try {
                method = owner.getMethod(name);
            } catch (NoSuchMethodException e) {
                throw new IllegalStateException("TaCZ " + owner.getName() + "#" + name
                        + "() is missing; HE explosion balance relies on it", e);
            }
            if (method.getReturnType() != returnType) {
                throw new IllegalStateException("TaCZ " + owner.getName() + "#" + name + "() now returns "
                        + method.getReturnType().getName() + ", expected " + returnType.getName());
            }
        }
    }
}
