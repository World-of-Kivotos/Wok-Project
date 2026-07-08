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

    /**
     * 冠军技能 AOE 判决伤害 (批4 波2: 电磁蓄力/天雷/小男孩共用)。三条设计约束:
     *  - 【不入】bypasses_armor: spec 红线 3 原文"伤害以 %maxHP 名义值下发、经玩家护甲减免后生效", 精装玩家天然吃得少;
     *  - 判决非近战: {@code ChampionAttackHandler} 豁免本类型, AOE 命中不触发燃烧/寒霜/撕裂/损甲等 on-hit rider;
     *  - 可被 2s 免疫缓冲拦截 ({@code AoeImmunityBuffer} 不豁免本类型): 首发照常结算并 grant 缓冲, 窗内第二发
     *    大额 AOE 被掐 0 —— 红线 3 "叠杀由缓冲挡" 的本体。
     */
    public static final ResourceKey<DamageType> CHAMPION_SKILL_AOE = ResourceKey.create(
            Registries.DAMAGE_TYPE, new ResourceLocation(MiningConstants.MODID, "champion_skill_aoe"));

    private ChampionDamageTypes() {
    }
}
