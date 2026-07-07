package com.miningdim.champion;

import net.minecraft.nbt.CompoundTag;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 精英怪【自研冠军数据】(Champions mod 依赖的替代品; ChampionStarAffix spec 第六/七章)。挂在 Mob 实体上的
 * Forge capability 数据: 该怪是否冠军 (星级 ≥1) + 装配的词条→品质映射 + 总有效血 (贡献池分母)。
 *
 * 取代 Champions 的 IChampion capability + 我方 DATA_KEY NBT: 此前"给实体标记冠军 + 存星级/词条"靠 Champions
 * 的 capability 与其 rank 系统, 效果 handler 读 IChampion.getServer().getAffixes()/getData。自研后本类是【唯一权威】——
 * spawn 期 promoter 经 {@link #promote} 写入, 全部效果 handler 经 {@link MiningChampions#get} 读 star/affixes,
 * 不再触任何 top.theillusivec4.champions.* (故 integration 层从"只能真服验"变为 dev GameTest 可验)。
 *
 * 纯数据 + NBT 序列化, 无世界/实体引用 (与 {@code MiningPlayerData} 同范式), GameTest 直接断言。词条以 def→品质
 * 直存 (非 Champions 的 registryName 字符串), 星级即 {@link StarRank} 星值; 数值语义解释仍下沉 {@link AffixDef}/
 * {@link ChampionAffixValues} 等纯逻辑。非冠军 (star=0) 不写 NBT (防每只普通怪 NBT 膨胀)。
 */
public final class MiningChampionData {

    /** 星级 0 = 非冠军 (默认态; 每只 Mob 挂本 capability 但仅 promoter 盖章的才 star≥1)。 */
    public static final int NOT_CHAMPION = 0;

    private static final String NBT_STAR = "star";
    private static final String NBT_EFFECTIVE_HP = "effective_hp";
    private static final String NBT_AFFIXES = "affixes";
    private static final String NBT_SUMMONED = "summoned_by_affix";

    private int star = NOT_CHAMPION;
    private final EnumMap<AffixDef, AffixQuality> affixes = new EnumMap<>(AffixDef.class);
    private double effectiveHp = 0.0D;
    private boolean summonedByAffix = false;

    /** 是否已被盖章为冠军 (star ∈ [1,10])。非冠军的默认 capability 恒 false。 */
    public boolean isChampion() {
        return star >= StarRank.MIN_STAR && star <= StarRank.MAX_STAR;
    }

    /** 星级 (1-10; 0 = 非冠军)。 */
    public int star() {
        return star;
    }

    /** 总有效血 (贡献池盖章门槛分母; 6★+ = 血池 maxHp, 1-5★ = 星表基础有效血, 巨大化后为实际有效血)。 */
    public double effectiveHp() {
        return effectiveHp;
    }

    /** 装配词条→品质 (不可变视图; 遍历顺序 = AffixDef 声明序)。 */
    public Map<AffixDef, AffixQuality> affixes() {
        return Collections.unmodifiableMap(affixes);
    }

    /** 某词条的品质 (未装配返 null)。 */
    public AffixQuality quality(AffixDef def) {
        return affixes.get(def);
    }

    /** 是否装配某词条。 */
    public boolean has(AffixDef def) {
        return affixes.containsKey(def);
    }

    /**
     * 是否支援召唤词条召出的召唤物 (spec 7.4 支援 [红队] 经济闸: summonedByAffix=true 不参与货币/经验/掉落/
     * 贡献结算, BOSS 血条亦不出条)。随 NBT 持久 —— 区块卸载重载后排除口径不丢。
     */
    public boolean isSummonedByAffix() {
        return summonedByAffix;
    }

    /** 盖章为词条召唤物 (支援召唤 handler 在 promote 后调用; clear/重新 promote 会复位)。 */
    public void markSummonedByAffix() {
        this.summonedByAffix = true;
    }

    /**
     * spawn 期盖章 (promoter 调用): 设星级 + 词条→品质 + 有效血。覆盖旧态 (重生/重复盖章防残留)。
     *
     * @param star           星级 (须 ∈ [1,10])
     * @param newAffixes     词条→品质映射 (拷入, 不持外部引用)
     * @param effectiveHp    总有效血 (须 &gt;0)
     */
    public void promote(int star, Map<AffixDef, AffixQuality> newAffixes, double effectiveHp) {
        if (star < StarRank.MIN_STAR || star > StarRank.MAX_STAR) {
            throw new IllegalArgumentException("champion star out of [1,10]: " + star);
        }
        if (newAffixes == null) {
            throw new IllegalArgumentException("affixes must not be null");
        }
        if (!(effectiveHp > 0.0D) || Double.isNaN(effectiveHp)) {
            throw new IllegalArgumentException("effectiveHp must be > 0, got " + effectiveHp);
        }
        this.star = star;
        this.affixes.clear();
        this.affixes.putAll(newAffixes);
        this.effectiveHp = effectiveHp;
        this.summonedByAffix = false; // 重新盖章即普通冠军; 召唤物身份由 markSummonedByAffix 在 promote 后补盖。
    }

    /** 清为非冠军态 (deserialize 前重置 / 显式清除)。 */
    public void clear() {
        this.star = NOT_CHAMPION;
        this.affixes.clear();
        this.effectiveHp = 0.0D;
        this.summonedByAffix = false;
    }

    /**
     * 序列化 NBT (随实体存盘, 冠军跨存档/区块卸载重载保留)。非冠军 (star=0) 返回空 tag —— 每只普通 Mob 都挂本
     * capability, 空写防全世界怪 NBT 膨胀。
     */
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        if (!isChampion()) {
            return tag;
        }
        tag.putInt(NBT_STAR, star);
        tag.putDouble(NBT_EFFECTIVE_HP, effectiveHp);
        if (summonedByAffix) {
            tag.putBoolean(NBT_SUMMONED, true); // 仅召唤物写键 (普通冠军不膨胀 NBT)。
        }
        CompoundTag affixTag = new CompoundTag();
        for (Map.Entry<AffixDef, AffixQuality> e : affixes.entrySet()) {
            affixTag.putInt(e.getKey().name(), e.getValue().ordinal());
        }
        tag.put(NBT_AFFIXES, affixTag);
        return tag;
    }

    /**
     * 反序列化 NBT (存盘读回)。脏/缺失 star 视为非冠军; 未知词条名 (版本漂移删词条) / 越界品质 ordinal 静默跳过
     * 该条 (不抛, 不让单条脏词条毁掉整只冠军还原)。
     */
    public void deserializeNBT(CompoundTag tag) {
        clear();
        if (tag == null || !tag.contains(NBT_STAR)) {
            return;
        }
        int s = tag.getInt(NBT_STAR);
        if (s < StarRank.MIN_STAR || s > StarRank.MAX_STAR) {
            return; // 脏星级: 当非冠军。
        }
        this.star = s;
        this.effectiveHp = tag.getDouble(NBT_EFFECTIVE_HP);
        this.summonedByAffix = tag.getBoolean(NBT_SUMMONED);
        CompoundTag affixTag = tag.getCompound(NBT_AFFIXES);
        AffixQuality[] qualities = AffixQuality.values();
        for (String key : affixTag.getAllKeys()) {
            AffixDef def = affixByName(key);
            if (def == null) {
                continue; // 未知词条名 (版本漂移): 跳过。
            }
            int ordinal = affixTag.getInt(key);
            if (ordinal < 0 || ordinal >= qualities.length) {
                continue; // 越界品质 ordinal: 跳过。
            }
            affixes.put(def, qualities[ordinal]);
        }
    }

    /** 词条名 -> AffixDef (未知返 null, 不抛; 供 NBT 还原容忍版本漂移删词条)。 */
    private static AffixDef affixByName(String name) {
        for (AffixDef def : AffixDef.values()) {
            if (def.name().equals(name)) {
                return def;
            }
        }
        return null;
    }
}
