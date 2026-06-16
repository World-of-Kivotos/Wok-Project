package com.miningdim.pressure;

import com.miningdim.core.IMiningNetwork;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;

import java.util.List;

/**
 * danger 分段映射表 (设计文档 10.4): danger -> 刷怪间隔 / 单波数量 / 允许怪物类型 / 环境光照削弱。
 * 评估后查表得当前刷怪节奏 (每评估周期刷新)。阈值与数值照搬 10.4 表 (PENDING待校验初值)。
 *
 * 光照削弱 (lightDimFactor) 是客户端感知效果 (迷雾/粒子/音效), 不真实改世界 lightmap (10.4 注),
 * 仅作为 DangerSyncS2C.lightDimFactor 下发给客户端渲染。
 */
public enum SpawnTier {

    /** [0.00, 0.20): 安全期/低压, 不主动刷怪。 */
    SAFE(0.00f, -1, 0, 0,
            List.of(),
            IMiningNetwork.DangerTier.SAFE),

    /** [0.20, 0.40): 轻压力, 间隔 400, 单波 1, zombie/spider。 */
    LIGHT(0.20f, 400, 1, 1,
            List.of(EntityType.ZOMBIE, EntityType.SPIDER),
            IMiningNetwork.DangerTier.ALERT),

    /** [0.40, 0.60): 中压, 间隔 280, 单波 1-2, + skeleton, 光照 -1 感知。 */
    MEDIUM(0.40f, 280, 1, 2,
            List.of(EntityType.ZOMBIE, EntityType.SPIDER, EntityType.SKELETON),
            IMiningNetwork.DangerTier.ALERT),

    /** [0.60, 0.80): 高压, 间隔 180, 单波 2-3, + creeper (走 9.7), 身后刷怪启用, 光照 -2 感知。 */
    HIGH(0.60f, 180, 2, 3,
            List.of(EntityType.ZOMBIE, EntityType.SPIDER, EntityType.SKELETON, EntityType.CREEPER),
            IMiningNetwork.DangerTier.HIGH),

    /** [0.80, 1.00]: 满压, 间隔 120, 单波 3-4, + 偶发 cave_spider/witch, 光照 -3 感知, 受实例硬上限封顶。 */
    EXTREME(0.80f, 120, 3, 4,
            List.of(EntityType.ZOMBIE, EntityType.SPIDER, EntityType.SKELETON, EntityType.CREEPER,
                    EntityType.CAVE_SPIDER, EntityType.WITCH),
            IMiningNetwork.DangerTier.HIGH);

    private final float minDanger;
    private final int spawnIntervalTicks;
    private final int waveMin;
    private final int waveMax;
    private final List<EntityType<? extends Mob>> allowedTypes;
    private final IMiningNetwork.DangerTier hudTier;

    SpawnTier(float minDanger, int spawnIntervalTicks, int waveMin, int waveMax,
              List<EntityType<? extends Mob>> allowedTypes, IMiningNetwork.DangerTier hudTier) {
        this.minDanger = minDanger;
        this.spawnIntervalTicks = spawnIntervalTicks;
        this.waveMin = waveMin;
        this.waveMax = waveMax;
        this.allowedTypes = allowedTypes;
        this.hudTier = hudTier;
    }

    /** 该档下界 danger (含)。 */
    public float minDanger() {
        return minDanger;
    }

    /** 刷怪间隔 (tick); SAFE 档为 -1 表示不主动刷怪。 */
    public int spawnIntervalTicks() {
        return spawnIntervalTicks;
    }

    /** 单波最小数量。 */
    public int waveMin() {
        return waveMin;
    }

    /** 单波最大数量。 */
    public int waveMax() {
        return waveMax;
    }

    /** 本档允许的怪物类型 (查表)。 */
    public List<EntityType<? extends Mob>> allowedTypes() {
        return allowedTypes;
    }

    /** 是否主动刷怪 (SAFE 档不刷)。 */
    public boolean spawns() {
        return spawnIntervalTicks > 0;
    }

    /** 本档是否启用身后刷怪 (HIGH 起, 10.4 表)。 */
    public boolean behindPlayerEnabled() {
        return this == HIGH || this == EXTREME;
    }

    /** 对应网络 HUD 危险视觉档 (15.4.2 tier)。 */
    public IMiningNetwork.DangerTier hudTier() {
        return hudTier;
    }

    /**
     * 环境光照削弱客户端感知系数 [0,1] (10.4: 0 / -1 / -2 / -3 感知映射)。
     * 0 不压暗; 0.33/0.66/1.0 对应 -1/-2/-3 感知强度, 供 DangerSyncS2C.lightDimFactor 下发。
     */
    public float lightDimFactor() {
        return switch (this) {
            case SAFE, LIGHT -> 0.0f;
            case MEDIUM -> 0.33f;
            case HIGH -> 0.66f;
            case EXTREME -> 1.0f;
        };
    }

    /** danger 值 -> 所在档 (10.4 区间查表, 自上而下取首个不超过 danger 的下界)。 */
    public static SpawnTier forDanger(float danger) {
        SpawnTier[] tiers = values();
        for (int i = tiers.length - 1; i >= 0; i--) {
            if (danger >= tiers[i].minDanger) {
                return tiers[i];
            }
        }
        return SAFE;
    }
}
