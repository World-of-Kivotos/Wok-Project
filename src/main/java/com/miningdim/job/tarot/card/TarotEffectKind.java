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
    /**
     * 教皇专用净化结算: 清除使用者全部负面, 每清除一个按 amount 增加最大生命, 总增量不超过 capUp,
     * durationTicks 后归还。必须在同一个原子操作内先计数再净化, 避免 SELF_CLEANSE 把计数依据提前清空。
     */
    SELF_CLEANSE_MAX_HEALTH("self_cleanse_max_health"),
    /** 魔术师闪耀: 沿水平视线瞬移 amount 格; 潜行使用时向后, 普通使用时向前。 */
    SELF_BLINK("self_blink"),
    /** 战车: 沿视线冲锋 distance 格并把沿途敌人按 force 击退。 */
    SELF_DASH("self_dash"),
    /** 女祭司: 预知扫描敌人、显示生命；正位额外提供一次首击减伤，逆位改为向目标施加易伤。 */
    SELF_PREMONITION_SCAN("self_premonition_scan"),
    /** 战车逆位: 强制向前失控冲锋，沿途伤敌并击退；撞墙时承受真实自伤。 */
    SELF_UNCONTROLLED_DASH("self_uncontrolled_dash"),
    /** 力量逆位: 野性过载，获得动态力量、吸血与击退免疫，低生命时收益进一步提高。 */
    SELF_WILD_OVERDRIVE("self_wild_overdrive"),
    /** 命运之轮正位: 从定稿增益池随机一项, chance 决定强档。 */
    SELF_RANDOM_BUFF("self_random_buff"),
    /** 命运之轮逆位: chance 概率治疗, 否则真实自伤。 */
    SELF_FORTUNE_GAMBLE("self_fortune_gamble"),
    /** 命运之轮闪耀: 全部现有正面效果升至允许的最高级并延长。 */
    SELF_REFRESH_BENEFICIAL("self_refresh_beneficial"),
    /** 延迟施加自身药水效果 (恶魔/世界的结束代价)。 */
    SELF_DELAYED_POTION("self_delayed_potion"),
    /** 周期真实自伤 (恶魔逆位每 5 秒自损)。 */
    SELF_PERIODIC_TRUE_DAMAGE("self_periodic_true_damage"),
    /** 只净化 count 个负面效果。 */
    SELF_CLEANSE_LIMITED("self_cleanse_limited"),
    /** 每 periodTicks 净化 1 个负面, 至多 count 次。 */
    SELF_PERIODIC_CLEANSE("self_periodic_cleanse"),
    /** 只清除 effects 指定的负面效果。 */
    SELF_CLEANSE_EFFECTS("self_cleanse_effects"),
    /** 星星逆位力竭: durationTicks 内无法受到治疗。 */
    SELF_HEALING_BLOCK("self_healing_block"),
    /** 隐士闪耀: 不可攻击、不可被生物索敌, 并执行玩家/矿物提灯高亮。 */
    SELF_HERMIT_SHINY("self_hermit_shiny"),
    /** 月亮闪耀: 保持可攻击, 但生物不会索敌。 */
    SELF_UNTARGETABLE("self_untargetable"),
    /** 女祭司闪耀: 清空使用者全部非闪耀塔罗牌 CD, 保留 GCD 与所有闪耀级 CD。 */
    CLEAR_NORMAL_TAROT_COOLDOWNS("clear_normal_tarot_cooldowns"),
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
    /** 给准星敌人施加药水效果 (正义/死神的单体后效)。 */
    ENEMY_TARGET_POTION("enemy_target_potion"),
    /** 高塔: 在准星落点生成伤害区域、位移敌人, 可附带失明/清黄心与增益。 */
    TARGET_TOWER_STRIKE("target_tower_strike"),
    /** 半径 radius 格内随机 1 敌受 amount 伤害, 无敌则使用者自身受双倍真伤 (恋人逆位)。 */
    AOE_ENEMY_RANDOM_DAMAGE("aoe_enemy_random_damage"),
    /** 半径 radius 格内的敌对生物各加一个 MobEffect (effect/amplifier/durationTicks)。 */
    AOE_ENEMY_POTION("aoe_enemy_potion"),
    /** 半径 radius 格内的敌对生物各受 amount 伤害 (高塔/审判逆位/死神闪耀)。 */
    AOE_ENEMY_DAMAGE("aoe_enemy_damage"),
    /** 半径内敌人拖至使用者身前。 */
    AOE_ENEMY_PULL("aoe_enemy_pull"),
    /** 半径内敌人随机错位瞬移。 */
    AOE_ENEMY_RANDOM_TELEPORT("aoe_enemy_random_teleport"),
    /** 固定伤害 + 目标已损生命百分比追加伤害。 */
    AOE_ENEMY_MISSING_HEALTH_DAMAGE("aoe_enemy_missing_health_damage"),
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
    /** 半径内友方各净化 count 个负面 (count<0 表示全部)。 */
    AOE_ALLY_CLEANSE_LIMITED("aoe_ally_cleanse_limited"),
    /** 周期为半径内友方每人净化 1 个负面。 */
    AOE_ALLY_PERIODIC_CLEANSE("aoe_ally_periodic_cleanse"),
    /** 周期把友方高血者的 amount 生命转移给低血者。 */
    AOE_ALLY_BALANCE_HEALTH("aoe_ally_balance_health"),
    /** 把半径内玩家组成 durationTicks 的伤害分摊组。 */
    AOE_ALLY_DAMAGE_SHARE("aoe_ally_damage_share"),
    /** 把低于 percent 最大生命的存活队友直接回满并给予 amount 黄心。 */
    AOE_ALLY_EMERGENCY_HEAL("aoe_ally_emergency_heal"),
    /** 低于 percent 最大生命的友方额外治疗 amount。 */
    AOE_ALLY_LOW_HEALTH_HEAL("aoe_ally_low_health_heal"),
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
