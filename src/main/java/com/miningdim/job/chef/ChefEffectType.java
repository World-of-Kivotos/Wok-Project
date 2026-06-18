package com.miningdim.job.chef;

/**
 * 厨师效果种类 (Chef_Job_DesignSpec 第六章已定 8 个 + 第十一章勾选的 "推荐" 候选)。
 *
 * 落地范围 (第十二章 PENDING 收口决定, 见 ChefSystem notes): 取第六章 8 个核心 + 第十一章标 "推荐" 且不破红线
 * 的候选 (耐饥/提神/夜照/披甲/凝脂/余韵/稳膛/夹生/烧焦/倒胃)。未取超模项 (沁脾 被审查官标 "砍或重砍") 与
 * "谨慎" 社交项 (盛宴/回礼 需结婚系统, 黑暗 赌博); 去重三决定: 余韵替代沁脾, 夜照独立 (不取温饱), 油腻并入夹生。
 *
 * 每项标注三个分类位 (决定结算路径与红线门控):
 *  - {@link #isCombat()}: 战斗向 (第六章红线: 仅 高/超凡/闪耀 解锁 + 一菜最多 1 个 + 进食可打断);
 *  - {@link #isNegative()}: 翻车负面 (仅低/中/高; 超凡/闪耀 noFailure 永不掷出);
 *  - {@link #isWindowed()}: 窗口/周期型 (非 eat-time 瞬时, 需 {@link ChefWindowEffectState} 状态机调度;
 *    false = eat-time 一次性结算, 由 {@link ChefConsumeHandler} 当场处理)。
 *
 * 逐级数值不在此硬编码 (C6): 数值快照在掷出时由 {@link SeasoningEffectRoller} 据品质档从 {@link ChefConfig}
 * 取列, 写进 {@link ChefEffectInstance#magnitude()}, 吃时按 type 的结算语义解释该 magnitude (见各 type 注释)。
 *
 * 稳定 id ({@link #id()}) 用于 NBT/lang/调料偏置池映射, 不随枚举重排漂移。
 */
public enum ChefEffectType {

    // ---- 第六章已定 8 个核心 (除多盐/失败品外多为 eat-time 瞬时) ----

    /** 增香: 放大原 mod 菜自带 buff 时长 (magnitude = 倍率 x100, 取百分比避免存浮点); 黑名单见 SeasoningBlacklist。 */
    AMPLIFY("amplify", false, false, false),
    /** 增量: 乘饱食 (magnitude = 倍率 x100; 受 20 饱食条上限自限)。 */
    NOURISH_FOOD("nourish_food", false, false, false),
    /** 回味: 乘饱和 (magnitude = 倍率 x100; 饱和 <= 饱食自限)。 */
    AFTERTASTE_SAT("aftertaste_sat", false, false, false),
    /** 饱食 (跳跃提升 60s): magnitude = 效果等级 (1-5 -> 跳跃 I-V), 纯 addEffect。 */
    SATED_JUMP("sated_jump", false, false, false),
    /** 膳香 (额外回血, 战斗向): magnitude = %最大血量基点 (千分比, 见 ChefConfig); 进食可打断兜底。 */
    NOURISH_HEAL("nourish_heal", true, false, false),
    /** 回甘 (净化 debuff, 战斗向解控): magnitude = 清除负面效果个数 (>=99 表示全部)。 */
    PURIFY("purify", true, false, false),
    /** 多盐 (饱和减半, 低/中翻车负面): magnitude 未用 (固定语义减半)。 */
    OVERSALT("oversalt", false, true, false),
    /** 失败品 (销毁菜肴, 低级翻车): magnitude 未用 (固定语义销毁)。 */
    SPOILED("spoiled", false, true, false),

    // ---- 第十一章勾选 "推荐" 候选 ----

    /** 耐饥 (减饥饿衰减, 窗口型): magnitude = 减衰减百分比基点 (千分比); 窗口时长见 ChefConfig。 */
    ENDURANCE("endurance", false, false, true),
    /** 提神 (清挖掘疲劳/缓慢 + 急速, eat-time): magnitude = 急速等级 (1-5 -> 急速 I-V)。 */
    REFRESH("refresh", false, false, false),
    /** 夜照 (纯夜视, eat-time addEffect): magnitude = 时长秒 (见 ChefConfig 逐级)。 */
    NIGHT_SIGHT("night_sight", false, false, false),
    /** 披甲 (黄心护盾, 战斗向窗口型): magnitude = %最大血量基点 (千分比); 刷新不叠。 */
    SHIELD("shield", true, false, true),
    /** 凝脂 (仅爆炸减伤, 战斗向窗口型): magnitude = 减伤百分比基点 (千分比); 经爆炸 Tag 限定。 */
    GREASE("grease", true, false, true),
    /** 余韵 (延迟微再生, 战斗向窗口型): magnitude = 总回血 %最大血量基点 (千分比); 周期 tick 摊还。 */
    AFTERTASTE_REGEN("aftertaste_regen", true, false, true),
    /** 稳膛 (抗击退 + 清缓慢, 战斗向窗口型): magnitude = 抗击退百分比基点 (千分比); 严禁 AttributeModifier, 走 LivingKnockBackEvent。 */
    STABLE_AIM("stable_aim", true, false, true),

    // ---- 第十一章勾选 "推荐" 翻车负面 (仅低/中/高) ----

    /** 夹生 (随机轻 debuff, eat-time 负面): magnitude = 触发概率基点 (千分比) | 时长由 ChefConfig。 */
    UNDERDONE("underdone", false, true, false),
    /** 烧焦 (%血自伤, eat-time 负面): magnitude = 自伤 %最大血量基点 (千分比); 留 1 血兜底。 */
    SCORCHED("scorched", false, true, false),
    /** 倒胃 (中毒 + 扣饱食, eat-time 负面, 可被回甘解): magnitude = 中毒等级 (1-2)。 */
    NAUSEA("nausea", false, true, false);

    private final String id;
    private final boolean combat;
    private final boolean negative;
    private final boolean windowed;

    ChefEffectType(String id, boolean combat, boolean negative, boolean windowed) {
        this.id = id;
        this.combat = combat;
        this.negative = negative;
        this.windowed = windowed;
    }

    public String id() {
        return id;
    }

    /** 战斗向 (第六章红线: 高品质门控 + 一菜一战斗效果 + 进食可打断)。 */
    public boolean isCombat() {
        return combat;
    }

    /** 翻车负面 (仅低/中/高; 超凡/闪耀 noFailure 永不掷出)。 */
    public boolean isNegative() {
        return negative;
    }

    /** 窗口/周期型 (需 ChefWindowEffectState 状态机, 非 eat-time 一次性)。 */
    public boolean isWindowed() {
        return windowed;
    }

    /** 按稳定 id 反查; 未知返回 null (调用方短路, 不静默掩盖坏 NBT)。 */
    public static ChefEffectType fromId(String id) {
        for (ChefEffectType t : values()) {
            if (t.id.equals(id)) {
                return t;
            }
        }
        return null;
    }
}
