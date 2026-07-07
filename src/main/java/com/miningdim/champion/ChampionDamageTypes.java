package com.miningdim.champion;

import com.miningdim.core.MiningConstants;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.damagesource.DamageType;

/**
 * 精英怪自定义伤害类型键 (数据驱动注册: data/miningdim/damage_type/*.json; 本类只持 ResourceKey 供
 * {@code level.damageSources().source(key, entity)} 构造 DamageSource)。
 *
 * CHAMPION_THORNS (反震真伤, 2026-07-07 用户定向): 反震反伤原走 vanilla thorns 类型会被玩家护甲吃掉 ~80%
 * (5.2 名义落地 ~1 点, 惩罚名存实亡), 改自定义类型并入 minecraft:bypasses_armor + bypasses_enchantments 标签 =
 * 无视护甲/保护附魔的真伤 (名义 % 即实付; 数值本就保守 2~10% 每 ≥3s, 且经 RetaliationAggregator 30%/s 多源封顶,
 * 合 spec "真伤保守封顶"铁律)。仍受抗性药水/无敌帧管辖 (未入 bypasses_effects/bypasses_invulnerability)。
 *
 * CHAMPION_EXECUTION (命定之死处决真伤, spec 7.4): 标记窗内未达阈值的玩家被处决, 施 maxHealth × 1.0 真伤。同入
 * bypasses_armor + bypasses_enchantments (护甲/保护附魔削不掉这份满血伤害 = 达标未果必死), 但【故意不入】
 * bypasses_invulnerability —— 保留玩家无敌帧/不死图腾的救命窗口 (处决是可被图腾救的必死判决, 非无条件删档)。
 */
public final class ChampionDamageTypes {

    /** 反震真伤 (bypasses_armor + bypasses_enchantments; 死亡讯息键 death.attack.miningdim.champion_thorns)。 */
    public static final ResourceKey<DamageType> CHAMPION_THORNS = ResourceKey.create(
            Registries.DAMAGE_TYPE, new ResourceLocation(MiningConstants.MODID, "champion_thorns"));

    /** 命定之死处决真伤 (bypasses_armor + bypasses_enchantments; 死亡讯息键 death.attack.miningdim.champion_execution)。 */
    public static final ResourceKey<DamageType> CHAMPION_EXECUTION = ResourceKey.create(
            Registries.DAMAGE_TYPE, new ResourceLocation(MiningConstants.MODID, "champion_execution"));

    private ChampionDamageTypes() {
    }
}
