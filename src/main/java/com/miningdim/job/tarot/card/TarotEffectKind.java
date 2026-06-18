package com.miningdim.job.tarot.card;

/**
 * 牌效原子操作类型 (TarotReader spec 第六章全表的可执行分解)。22 张牌的正位/逆位/闪耀效果由若干本类型的
 * "操作" (TarotEffectOp) 组合而成, 数值从 datapack JSON 按品质档缩放后填入。数据驱动: 加新效果= 加一个 kind
 * + 在 {@link com.miningdim.job.tarot.TarotEffectEngine} 的 dispatch 加一个分支, 不改 22 份 JSON 的结构。
 *
 * 稳定 id 字符串 = datapack JSON 的 "kind" 字段值; 未知 kind 由 {@link #byId} 抛出冒泡 (spec C9 不静默)。
 */
public enum TarotEffectKind {

    /** 给使用者本人加一个原版 MobEffect (effect=注册名, amplifier, durationTicks)。 */
    SELF_POTION("self_potion"),
    /** 周期瞬治使用者: 每 periodTicks 治疗 amount, 共 count 次 (愚者正位/逆位)。amount=瞬治量。 */
    SELF_HEAL_OVER_TIME("self_heal_over_time"),
    /** 立即瞬治使用者 amount 点 (星星正位等)。 */
    SELF_HEAL("self_heal"),
    /** 对使用者造成 amount 真实伤害, 绕过护甲/抗性/吸收 (逆位自惩; spec 实现红线)。 */
    SELF_TRUE_DAMAGE("self_true_damage"),
    /** 给使用者加黄心 (吸收) amount 点 (恋人/世界等)。 */
    SELF_ABSORPTION("self_absorption"),
    /** 周期给使用者补黄心: 每 periodTicks 把吸收补至至少 amount, 共 count 次 (世界闪耀每 5s 补 25)。 */
    SELF_PERIODIC_ABSORPTION("self_periodic_absorption"),
    /** 把使用者治疗至当前最大生命 (愚者闪耀 "回满血"; 不写死定值, 随最大生命浮动)。 */
    SELF_FULL_HEAL("self_full_heal"),
    /** 增/减使用者最大生命 amount (正=增, 负=减), durationTicks 后归还; 增封顶 capUp, 减下限 floorDown。 */
    SELF_MAX_HEALTH("self_max_health"),
    /** 清除使用者全部负面效果 (教皇/节制/星星)。 */
    SELF_CLEANSE("self_cleanse"),
    /** 以命相赌 (倒吊人逆位): chance 概率当场死亡; 成功则牺牲最大生命 amount (durationTicks 后归还), 后续 op 继续。 */
    SELF_DEATH_GAMBLE("self_death_gamble"),
    /** 复活契约 (死神逆位): durationTicks 内拦截 1 次致死并复活回 amount 血 (一次性)。 */
    SELF_DEATH_CONTRACT("self_death_contract"),
    /** 免疫击退窗 (倒吊人逆位/力量闪耀等): durationTicks 内 LivingKnockBackEvent 强度归零。 */
    SELF_KNOCKBACK_IMMUNITY("self_knockback_immunity"),
    /** 吸血窗 (倒吊人逆位/恶魔): durationTicks 内对敌造成伤害的 percent 回血给使用者。 */
    SELF_LIFESTEAL("self_lifesteal"),
    /** 反伤窗 (正义正位): durationTicks 内受伤把 percent 回击攻击者, 单次封顶 capUp。 */
    SELF_REFLECT("self_reflect"),
    /** 无敌窗 (愚者闪耀): durationTicks 内 LivingHurtEvent 对使用者伤害归零 (真免疫, 非抗性减伤)。 */
    SELF_INVULNERABLE("self_invulnerable"),
    /** 准星单体 (死神正位): 准星目标当前血 < threshold 处决, 否则 amount 穿刺; reach=radius。 */
    ENEMY_TARGET_DAMAGE("enemy_target_damage"),
    /** 均值化 (正义逆位): 准星目标与使用者当前血各设为均值, 单次最多 ±capUp; reach=radius。 */
    ENEMY_TARGET_AVERAGE_HEALTH("enemy_target_average_health"),
    /** 半径 radius 格内随机 1 敌受 amount 伤害, 无敌则使用者自身受双倍真伤 (恋人逆位)。 */
    AOE_ENEMY_RANDOM_DAMAGE("aoe_enemy_random_damage"),
    /** 半径 radius 格内的敌对生物各加一个 MobEffect (effect/amplifier/durationTicks)。 */
    AOE_ENEMY_POTION("aoe_enemy_potion"),
    /** 半径 radius 格内的敌对生物各受 amount 伤害 (高塔/审判逆位/死神闪耀)。 */
    AOE_ENEMY_DAMAGE("aoe_enemy_damage"),
    /** 半径 radius 格内的友方玩家各加一个 MobEffect (恋人正位/节制闪耀)。 */
    AOE_ALLY_POTION("aoe_ally_potion"),
    /** 半径 radius 格内的友方玩家各加黄心 amount (星星闪耀/世界闪耀)。 */
    AOE_ALLY_ABSORPTION("aoe_ally_absorption"),
    /** 半径 radius 格内的友方玩家各瞬治 amount (恋人逆位/星星逆位)。 */
    AOE_ALLY_HEAL("aoe_ally_heal");

    private final String id;

    TarotEffectKind(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    /** 按 datapack id 反查; 未知 kind 抛出冒泡 (spec 第十一章 C9: 缺字段/错字段报错不静默)。 */
    public static TarotEffectKind byId(String id) {
        for (TarotEffectKind k : values()) {
            if (k.id.equals(id)) {
                return k;
            }
        }
        throw new IllegalArgumentException("Unknown tarot effect kind: " + id);
    }
}
