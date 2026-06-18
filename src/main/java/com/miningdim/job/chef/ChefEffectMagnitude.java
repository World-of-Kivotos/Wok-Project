package com.miningdim.job.chef;

/**
 * 把一个效果种类 + 品质档映射到要写进 {@link ChefEffectInstance#magnitude()} 的数值快照
 * (Chef_Job_DesignSpec 第六章; 数值取自 {@link ChefConfig}, 非硬编码 C6)。
 *
 * magnitude 语义随 type 不同 (见 {@link ChefEffectType} 各项): 倍率类存 x100, 战斗向 %血/减伤存千分比,
 * 等级类存 1-based 等级, 时长类存秒, 概率类存千分比。吃时由 {@link ChefConsumeHandler} 按 type 还原解释。
 */
final class ChefEffectMagnitude {

    private ChefEffectMagnitude() {
    }

    static int snapshot(ChefEffectType type, ChefQuality q) {
        return switch (type) {
            case AMPLIFY -> ChefConfig.amplifyMul(q);
            case NOURISH_FOOD -> ChefConfig.nourishFoodMul(q);
            case AFTERTASTE_SAT -> ChefConfig.aftertasteSatMul(q);
            // 饱食->跳跃, 提神->急速: 等级 = tier+1 (低 I .. 闪耀 V)。
            case SATED_JUMP, REFRESH -> q.tier() + 1;
            case NOURISH_HEAL -> ChefConfig.healPerMille(q);
            // 回甘清 debuff 个数: 1/2/3/4/全部 (闪耀=99 哨兵表示全部)。仅高品质有非零值。
            case PURIFY -> switch (q) {
                case HIGH -> 3;
                case EXTRAORDINARY -> 4;
                case RADIANT -> 99;
                default -> 0;
            };
            case SHIELD -> ChefConfig.shieldPerMille(q);
            case GREASE -> ChefConfig.greasePerMille(q);
            case AFTERTASTE_REGEN -> ChefConfig.regenPerMille(q);
            case STABLE_AIM -> ChefConfig.stableAimPerMille(q);
            case ENDURANCE -> ChefConfig.endurancePctPerMille(q);
            case NIGHT_SIGHT -> ChefConfig.nightSeconds(q);
            // 负面: 夹生存触发概率千分比, 烧焦存自伤 %千分比, 倒胃存中毒等级, 多盐/失败品固定语义 (magnitude=0)。
            case UNDERDONE -> switch (q) {
                case LOW -> ChefConfig.UNDERDONE_CHANCE_LOW.get();
                case MEDIUM -> ChefConfig.UNDERDONE_CHANCE_MEDIUM.get();
                case HIGH -> ChefConfig.UNDERDONE_CHANCE_HIGH.get();
                default -> 0;
            };
            case SCORCHED -> switch (q) {
                case LOW -> ChefConfig.SCORCHED_PCT_LOW.get();
                case MEDIUM -> ChefConfig.SCORCHED_PCT_MEDIUM.get();
                case HIGH -> ChefConfig.SCORCHED_PCT_HIGH.get();
                default -> 0;
            };
            // 倒胃: 低=毒II, 中/高=毒I (存 amplifier+1: 2 或 1)。
            case NAUSEA -> q == ChefQuality.LOW ? 2 : 1;
            case OVERSALT, SPOILED -> 0;
        };
    }

    /** 夹生轻 debuff 时长 (秒, 按品质)。供 ChefConsumeHandler 取用 (概率在 magnitude, 时长在此)。 */
    static int underdoneSeconds(ChefQuality q) {
        return switch (q) {
            case LOW -> ChefConfig.UNDERDONE_SEC_LOW.get();
            case MEDIUM -> ChefConfig.UNDERDONE_SEC_MEDIUM.get();
            case HIGH -> ChefConfig.UNDERDONE_SEC_HIGH.get();
            default -> 0;
        };
    }

    /**
     * 倒胃中毒时长 (秒, 按品质)。供 ChefConsumeHandler 取用 (中毒等级在 magnitude, 时长在此)。
     * spec 第十一章: 低 8s / 中 6s / 高 4s; 超凡/闪耀零翻车不掷出倒胃 (default 0)。
     */
    static int nauseaSeconds(ChefQuality q) {
        return switch (q) {
            case LOW -> ChefConfig.NAUSEA_SEC_LOW.get();
            case MEDIUM -> ChefConfig.NAUSEA_SEC_MEDIUM.get();
            case HIGH -> ChefConfig.NAUSEA_SEC_HIGH.get();
            default -> 0;
        };
    }
}
