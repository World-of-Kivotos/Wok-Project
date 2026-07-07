package com.miningdim.champion;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.core.particles.ParticleTypes;

/**
 * 精英怪词条 -> 签名环境粒子的映射 (显示层视觉反馈)。原版 Champions 自带词条经 {@code IAffix.onClientUpdate}
 * 客户端自绘粒子, 但我方词条是纯标记 (MiningAffix 不重写战斗钩子) 且设置 hasSub:false, 该客户端更新链对其不触发,
 * 故改由服务端 {@link com.miningdim.champion.integration.ChampionParticleHandler} 用 sendParticles 主动播。
 *
 * 本类是纯映射 (零世界/Champions 引用, GameTest 可断言 35 词条全有非空粒子 + 关键映射主题正确)。粒子全用
 * vanilla {@link ParticleTypes} 的 SimpleParticleType, 无需注册自定义粒子。
 */
public final class ChampionAffixParticles {

    private ChampionAffixParticles() {
    }

    /**
     * 词条 -> 签名环境粒子 (按机制选主题: 燃烧火/寒霜雪/天雷电/护甲附魔闪/再生心 等)。全 35 词条显式覆盖,
     * default 仅防御性兜底 (新增词条未登记时退 CRIT, 不抛免崩显示层)。
     */
    public static ParticleOptions ambientParticle(AffixDef def) {
        switch (def) {
            case COMPOSITE_ARMOR:
            case UHMWPE_ARMOR:
            case FORTITUDE_SHIELD:
            case COUNTER_UNIT:
                return ParticleTypes.ENCHANTED_HIT;
            case HEAVY_ARMOR:
            case THORNS:
            case ARMOR_PIERCING:
            case DOUBLE_STRIKE:
                return ParticleTypes.CRIT;
            case REGEN_TISSUE:
                return ParticleTypes.HEART;
            case FLAMMABLE_REGEN:
                return ParticleTypes.SMALL_FLAME;
            case DEFLECTOR_SHIELD:
                return ParticleTypes.ENCHANT;
            case GIGANTISM:
            case MINIATURIZATION:
                return ParticleTypes.POOF;
            case BURNING:
                return ParticleTypes.FLAME;
            case REND:
            case BLOODLUST:
                return ParticleTypes.DAMAGE_INDICATOR;
            case HEAVY_CANNON:
                return ParticleTypes.LARGE_SMOKE;
            case CORROSIVE:
                return ParticleTypes.ITEM_SLIME;
            case QUADRUPLE_STRIKE:
            case BLADE_WALTZ:
                return ParticleTypes.SWEEP_ATTACK;
            case CHAOS_STRIKE:
                return ParticleTypes.ANGRY_VILLAGER;
            case FROST:
                return ParticleTypes.SNOWFLAKE;
            case SPRINT:
            case OVERDRIVE:
                return ParticleTypes.CLOUD;
            case BLINK:
            case CAESAR_SWAP:
                return ParticleTypes.PORTAL;
            case TACTICAL_BLINK:
                return ParticleTypes.REVERSE_PORTAL;
            case PHASE_WALK:
                return ParticleTypes.WITCH;
            case ELECTRO_CHARGE:
                return ParticleTypes.END_ROD;
            case THUNDER:
                return ParticleTypes.ELECTRIC_SPARK;
            case LITTLE_BOY:
                return ParticleTypes.EXPLOSION;
            case DEATH_MARK:
                return ParticleTypes.SOUL;
            case VISUAL_DISRUPTION:
                return ParticleTypes.SMOKE;
            case SELF_REPAIR:
                return ParticleTypes.HAPPY_VILLAGER;
            case SUMMON_SUPPORT:
                return ParticleTypes.SOUL_FIRE_FLAME;
            default:
                return ParticleTypes.CRIT;
        }
    }
}
