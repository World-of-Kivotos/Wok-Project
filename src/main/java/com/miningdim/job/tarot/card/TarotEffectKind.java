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
    /**
     * 累计反击窗 (正义闪耀结算尾): durationTicks 内逐攻击者累计其对使用者造成的伤害; 窗口结束时对 radius 格内
     * 仍在场的每个攻击者各回击其累计伤害的 percent, 单次封顶 capUp。与 SELF_REFLECT 的即时反伤独立叠加
     * (闪耀同时挂 80% 即时反伤 + 本窗 40% 累计结算)。
     */
    SELF_REFLECT_ACCUM("self_reflect_accum"),
    /**
     * 延迟记账冻死窗 (倒吊人闪耀): durationTicks 内对使用者的伤害累加进挂起账本, 致命伤被冻结 (setHealth 不致死);
     * 窗口结束时结算挂起伤害的 percent (扣血), 若仍存活则额外回 amount 血 (spec: 结束结算 50%, 存活 +40)。
     */
    SELF_DELAYED_LEDGER("self_delayed_ledger"),
    /** 无敌窗 (愚者闪耀): durationTicks 内 LivingHurtEvent 对使用者伤害归零 (真免疫, 非抗性减伤)。 */
    SELF_INVULNERABLE("self_invulnerable"),
    /**
     * 绑定共享生死 (恋人闪耀): 准星锁定一个已同意 ({@code /tarot consent}) 的玩家, 双向绑定 durationTicks;
     * 任一方死亡则另一方 count ticks (3s) 后同死; 两者距离 > radius 格则提前解绑。需对方同意的握手在锁定期校验。
     */
    SHINY_BIND_SHARE_LIFE("shiny_bind_share_life"),
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
    /**
     * 处决斩杀 AoE (死神闪耀): radius 格内当前血占比 &lt; percent 的敌处决 (setHealth 0); 每处决一个玩家/精英
     * 给使用者回 threshold 血并把力量提升一级 (上限 amplifier, 4=V)。若无任何处决目标则对全体敌各 amount 穿刺。
     * "回血/叠层仅对玩家/精英" = 普通杂兵被处决不给回血/叠层 (spec 死神闪耀)。
     */
    AOE_EXECUTE_BELOW_PCT("aoe_execute_below_pct"),
    /** 半径 radius 格内的友方玩家各加一个 MobEffect (恋人正位/节制闪耀)。 */
    AOE_ALLY_POTION("aoe_ally_potion"),
    /** 半径 radius 格内的友方玩家各加黄心 amount (星星闪耀/世界闪耀)。 */
    AOE_ALLY_ABSORPTION("aoe_ally_absorption"),
    /** 半径 radius 格内的友方玩家各瞬治 amount (恋人逆位/星星逆位)。 */
    AOE_ALLY_HEAL("aoe_ally_heal"),
    /**
     * 周期 AoE 敌方持续伤害 (太阳正位/逆位/闪耀 "每秒灼半径 N 格敌 X"): 每 periodTicks 对 owner 半径 radius 格内的
     * 敌对生物各造成 amount 伤害, 持续 durationTicks (次数 = durationTicks / periodTicks)。amount 是 spec 的扁平每跳值,
     * 引擎施加期对每个目标按其最大生命的 {@link com.miningdim.job.tarot.TarotEffectEngine} DoT 占比上限 clamp
     * (防扁平值在低血杂兵上离谱, 红线参照精英怪 15%/s)。经 {@link com.miningdim.job.tarot.ScheduledEffectManager}
     * 周期调度, 每跳按 owner 当前坐标重新取半径内敌; 登出/死亡/换维度清队列。
     */
    AOE_ENEMY_DAMAGE_OVER_TIME("aoe_enemy_damage_over_time"),
    /**
     * 周期 AoE 友方持续治疗 (太阳闪耀 "每秒为友回 X"): 每 periodTicks 对 owner 半径 radius 格内的友方玩家各瞬治
     * amount, 持续 durationTicks (次数 = durationTicks / periodTicks)。amount 是 spec 的扁平每跳值, 引擎施加期按目标
     * 最大生命的回血占比上限 clamp。周期调度同 {@link #AOE_ENEMY_DAMAGE_OVER_TIME}。
     */
    AOE_ALLY_HEAL_OVER_TIME("aoe_ally_heal_over_time"),
    /**
     * 免疫窗 (太阳闪耀/世界闪耀 "免疫缓慢/失明/反胃/易伤..."; 力量闪耀/恶魔闪耀 "免疫易伤"): durationTicks 内对持窗
     * 玩家拒绝施加 effects 列出的 MobEffect ({@link com.miningdim.job.tarot.TarotCombatHandlers} 在
     * {@code MobEffectEvent.Applicable} setResult(DENY)), 且 immuneVulnerability=true 时在易伤单点仲裁
     * ({@link com.miningdim.effect.VulnerabilityHurtHandler}) 跳过对该玩家的易伤放大。窗口状态走
     * {@link com.miningdim.job.tarot.TarotCombatState}, 登出/死亡/换维度清。effects 可空 (仅免易伤)。
     */
    IMMUNITY("immunity");

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
