package com.miningdim.champion.integration;

import com.tacz.guns.api.TimelessAPI;
import com.tacz.guns.entity.EntityKineticBullet;
import com.tacz.guns.resource.index.CommonGunIndex;
import com.tacz.guns.resource.pojo.data.gun.BulletData;
import com.tacz.guns.resource.pojo.data.gun.ExplosionData;
import com.tacz.guns.resource.pojo.data.gun.GunData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraftforge.fml.ModList;

import java.util.Optional;

/**
 * 配件开启的 TaCZ 子弹爆炸探针 (2026-09 精英批次; ChampionStarAffix spec 9.2)。供 {@link ChampionBloodPoolHandler}
 * 在复合装甲分桶时把 HE 这类"枪原生不爆炸、靠配件让每颗弹丸命中后再炸一次"的爆炸归入子弹桶, 规则本身在纯逻辑
 * {@link com.miningdim.champion.ChampionDamageReduction#categorize}。
 *
 * 判据 (三条同时满足): 伤害带 IS_EXPLOSION 标签; 直接实体是 TaCZ 子弹 {@code EntityKineticBullet} (TaCZ 的
 * ProjectileExplosion 把子弹作为爆炸源传给原版 Explosion, 原版 explosion 伤害源的直接实体即这颗子弹); 该子弹
 * gunId 对应的枪原生 GunData 里 ExplosionData 为空或 explode=false (爆炸是配件打开的)。RPG7、M320 等原生爆炸
 * 武器不命中本判据, 仍归爆炸桶; 查不到枪数据时同样按原生爆炸处理 (保守口径: 归爆炸桶)。
 *
 * TaCZ 是 compileOnly 可选依赖: 外层类不引用任何 com.tacz.* 类型, 先查 {@code ModList.isLoaded("tacz")},
 * 未加载时恒返回 false; 真正触达 TaCZ 类的逻辑放在嵌套类 {@link TaczSide} 里, 只有 TaCZ 已加载时才会被类加载,
 * 没装枪械 mod 的服务器与 dev GameTest 永不 classload com.tacz.*。
 */
public final class TaczAttachmentExplosionProbe {

    private static final String TACZ_MODID = "tacz";

    /** TaCZ 是否已加载 (类初始化时读一次; 模组列表在运行期不变)。 */
    private static final boolean TACZ_LOADED = ModList.get().isLoaded(TACZ_MODID);

    private TaczAttachmentExplosionProbe() {
    }

    /** TaCZ 是否已加载 (GameTest 断言 dev 环境走的是未加载分支)。 */
    public static boolean taczLoaded() {
        return TACZ_LOADED;
    }

    /**
     * 本次伤害是否"配件开启的子弹爆炸"。未装 TaCZ、非爆炸、直接实体不是 TaCZ 子弹、枪原生会爆炸或查不到枪数据时
     * 一律返回 false。
     *
     * @param source 伤害源
     * @return 是否配件开启的子弹爆炸
     */
    public static boolean isAttachmentBulletExplosion(DamageSource source) {
        if (!TACZ_LOADED || source == null || !source.is(DamageTypeTags.IS_EXPLOSION)) {
            return false;
        }
        return TaczSide.isAttachmentBulletExplosion(source.getDirectEntity());
    }

    /** 触达 TaCZ 类型的一侧: 独立的嵌套类文件, 只在 {@link #TACZ_LOADED} 为真时经上方调用被类加载。 */
    private static final class TaczSide {

        private TaczSide() {
        }

        static boolean isAttachmentBulletExplosion(Entity directEntity) {
            if (!(directEntity instanceof EntityKineticBullet bullet)) {
                return false;
            }
            ResourceLocation gunId = bullet.getGunId();
            if (gunId == null) {
                return false;
            }
            Optional<CommonGunIndex> index = TimelessAPI.getCommonGunIndex(gunId);
            if (index.isEmpty()) {
                return false;
            }
            GunData gunData = index.get().getGunData();
            if (gunData == null) {
                return false;
            }
            BulletData bulletData = gunData.getBulletData();
            if (bulletData == null) {
                return false;
            }
            // 读的是枪包原生 GunData (不含配件改写的属性缓存): 原生不爆炸 = 本次爆炸由配件打开。
            ExplosionData nativeExplosion = bulletData.getExplosionData();
            return nativeExplosion == null || !nativeExplosion.isExplode();
        }
    }
}
