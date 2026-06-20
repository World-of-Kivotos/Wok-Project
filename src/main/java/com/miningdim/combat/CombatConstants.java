package com.miningdim.combat;

/**
 * 战斗基建常量 (玩家受击减伤的全局帽等)。单一来源, 与精英怪侧 {@link com.miningdim.champion.ChampionRedlines}
 * 的 49% 净减伤帽分属两套 (玩家受击 vs 精英受击)。
 */
public final class CombatConstants {

    private CombatConstants() {
    }

    /**
     * 玩家受击的【等效总减伤上限】(全局帽)。乘法连乘本已防 100% 无敌, 此帽再防来源过多堆到极端 (如 5 个 50%
     * 源乘到 96.9%)。0.85 = 最多减 85% (永远至少吃 15% 伤)。DRAFT, 可调; 见 PlayerDamageReduction。
     */
    public static final double PLAYER_MAX_REDUCTION = 0.85D;

    /** 减伤后最少保留的伤害系数 = 1 - {@link #PLAYER_MAX_REDUCTION} (最终伤害 ≥ 原始 × 此值)。 */
    public static final double PLAYER_MIN_KEEP = 1.0D - PLAYER_MAX_REDUCTION;
}
