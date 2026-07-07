package com.miningdim.champion;

import java.util.Collections;
import java.util.EnumSet;
import java.util.Set;

/**
 * 35 词条定义表 (ChampionStarAffix spec 第七章词条目录 + 第八章互斥矩阵)。每条词条 = 所属池 + 基础成本 c +
 * 最低★ + 5 品质数值表 (普通/中级/高级/超凡/闪耀) + 互斥族 + 是否技能 (占技能数上限)。
 *
 * 纯数据枚举, 无世界引用; spawn 期分配器 {@link PointBudget} 据此挑词条/扣点/校验互斥。词条的 5 档数值在
 * 不同词条语义不同 (减伤率 / FLAT HP / %maxHP/s / 移速%...), 故本表只承载"按品质取档的原始数值数组",
 * 数值的语义解释 (折算成减伤率/伤害名义值) 由 {@link ChampionAffixValues} 与受击结算层负责, 本枚举不解释。
 *
 * 互斥族 (spec 第八章): 互斥不是逐对枚举而是按"族"分组 —— 同一互斥族内至多取一条 (高速/超速 = MOVE_SPEED 族;
 * 巨大化/缩小化 = SIZE 族; 双倍/四倍 = MULTI_STRIKE 族)。重型护甲互斥全部机动 + 偏斜 + 刚毅, 巨大化互斥全部
 * 机动, 这类"跨池/跨族"互斥不能用单一族表达, 由 {@link MutexFlag} 标志位 + {@link PointBudget} 的组合校验处理。
 */
public enum AffixDef {

    // ============================================================
    // 7.1 生存 (10 条, 被动防御)
    // ============================================================

    /**
     * 复合装甲 (同源适应, 2026-07-07 用户定向加强): 减伤上限 35/45/55/65/75%; 按【伤害类别】(子弹/近战/爆炸/其它)
     * 分桶各自 ramp, 每受同类击 +上限/5, 受其它类别伤害即清空全部他桶 (装甲适应当前威胁、忘掉旧威胁) ——
     * 玩家换武器/丢雷 = 真重置 (原版 adaptable 式反制); 3s 无伤全重置。并入净减伤 75% 钳制。
     */
    COMPOSITE_ARMOR(AffixPool.SURVIVAL, 8, 1, false,
            new double[]{0.35, 0.45, 0.55, 0.65, 0.75}, null, MutexFlag.NONE),

    /** 超高分子聚乙烯护甲层: 子弹抗性 10/15/22/30/40%; 仅减 tacz:bullet_resistance; 并入净减伤钳制。 */
    UHMWPE_ARMOR(AffixPool.SURVIVAL, 7, 1, false,
            new double[]{0.10, 0.15, 0.22, 0.30, 0.40}, null, MutexFlag.NONE),

    /**
     * 重型护甲 (高级+): 子弹抗性 35/42/49% (高/超/闪) + 近战/爆炸单次 &lt;T 免疫, T=8/14/22。
     * 数值数组按品质索引: 普通/中级档无意义 (最低高级), 前两档填 0 占位, 高/超/闪 = 0.35/0.42/0.49。
     * 互斥全部机动 + 偏斜 + 刚毅 (MutexFlag.HEAVY_ARMOR)。
     */
    HEAVY_ARMOR(AffixPool.SURVIVAL, 26, 7, false,
            new double[]{0.0, 0.0, 0.35, 0.42, 0.49}, null, MutexFlag.HEAVY_ARMOR),

    /** 再生组织: 脱战回 3/4/5/6/8% maxHP/s; 任意受伤重置 5s。 */
    REGEN_TISSUE(AffixPool.SURVIVAL, 6, 1, false,
            new double[]{0.03, 0.04, 0.05, 0.06, 0.08}, null, MutexFlag.NONE),

    /** 易燃再生: 战斗回 FLAT 8/15/30/60/90 HP/s; 受任意伤害停回 1.5s。 */
    FLAMMABLE_REGEN(AffixPool.SURVIVAL, 10, 3, false,
            new double[]{8.0, 15.0, 30.0, 60.0, 90.0}, null, MutexFlag.NONE),

    /** 偏斜护盾: 每发子弹闪避 8/12/18/25/35%; 按期望值并入净减伤钳制; 不闪 AOE; 互斥重型。 */
    DEFLECTOR_SHIELD(AffixPool.SURVIVAL, 10, 2, false,
            new double[]{0.08, 0.12, 0.18, 0.25, 0.35}, null, MutexFlag.DEFLECTOR),

    /**
     * 刚毅护盾 (高级+): 单次伤害封顶 120/80/50 HP (高/超/闪); 品质越高封顶越低 = 越硬; 互斥重型。
     * 数值是 FLAT HP 封顶, 高/超/闪 = 120/80/50, 前两档填 0 占位 (最低高级)。
     */
    FORTITUDE_SHIELD(AffixPool.SURVIVAL, 22, 6, false,
            new double[]{0.0, 0.0, 120.0, 80.0, 50.0}, null, MutexFlag.FORTITUDE),

    /** 反震: 受击对周围 2/3.5/5/7/10% maxHP + 击退; 内 CD ≥3s; 半径 3-5; 反伤分量并入红线 2 多源封顶。 */
    THORNS(AffixPool.SURVIVAL, 9, 2, false,
            new double[]{0.02, 0.035, 0.05, 0.07, 0.10}, null, MutexFlag.NONE),

    /** 巨大化: +血量 30/50/80/120/180%; +体型 25/40/60/85/120%; 互斥全部机动 + 缩小化; 须同步提移速。 */
    GIGANTISM(AffixPool.SURVIVAL, 12, 3, false,
            new double[]{0.30, 0.50, 0.80, 1.20, 1.80}, null, MutexFlag.SIZE),

    /** 缩小化: -血量 25/32/40/48/58%; -体型 15/25/35/45/55%; 互斥巨大化; 强制 +1 机动 (仅最低档)。 */
    MINIATURIZATION(AffixPool.SURVIVAL, 10, 3, false,
            new double[]{0.25, 0.32, 0.40, 0.48, 0.58}, null, MutexFlag.SIZE),

    // ============================================================
    // 7.2 战斗 (10 条, 被动攻击修正)
    // ============================================================

    /** 燃烧: 每层 1/1.5/2/3/4% maxHP/s, 最大 5 层 3s 刷新; 刷新内 CD ≥1s/源; 并入 DoT ≤15% 聚合封顶。 */
    BURNING(AffixPool.COMBAT, 8, 1, false,
            new double[]{0.01, 0.015, 0.02, 0.03, 0.04}, null, MutexFlag.NONE),

    /** 穿甲: +真实伤害 4/6/9/13/18% maxHP; 无视护甲; 与普通伤害合计 ≤40% 单击上限; 真伤不入放宽。 */
    ARMOR_PIERCING(AffixPool.COMBAT, 10, 2, false,
            new double[]{0.04, 0.06, 0.09, 0.13, 0.18}, null, MutexFlag.NONE),

    /** 撕裂: 叠易伤 +5/8/12/16/20%/层; 复用易伤效果系统, 封顶 +100%。 */
    REND(AffixPool.COMBAT, 12, 3, false,
            new double[]{0.05, 0.08, 0.12, 0.16, 0.20}, null, MutexFlag.NONE),

    /** 重炮: +伤害/-攻速 30%/-25 … 100%/-40; 单击 ≤40% 上限; 前摇明显。数值数组 = 伤害增幅。 */
    HEAVY_CANNON(AffixPool.COMBAT, 10, 2, false,
            new double[]{0.30, 0.475, 0.65, 0.825, 1.00}, null, MutexFlag.NONE),

    /** 强酸: 玩家护甲耐久损耗 +2/4/6/10/15/击; 纯磨损接经济。数值数组 = 每击耐久损耗点数。 */
    CORROSIVE(AffixPool.COMBAT, 8, 3, false,
            new double[]{2.0, 4.0, 6.0, 10.0, 15.0}, null, MutexFlag.NONE),

    /** 双倍打击: 1 击=2 跳; 互斥四倍; DoT 刷新受内 CD。数值数组 = 跳数。 */
    DOUBLE_STRIKE(AffixPool.COMBAT, 9, 3, false,
            new double[]{2.0, 2.0, 2.0, 2.0, 2.0}, null, MutexFlag.MULTI_STRIKE),

    /** 四倍痛处: 1 击=4 跳; 互斥双倍。数值数组 = 跳数。 */
    QUADRUPLE_STRIKE(AffixPool.COMBAT, 16, 5, false,
            new double[]{4.0, 4.0, 4.0, 4.0, 4.0}, null, MutexFlag.MULTI_STRIKE),

    /** 嗜血: 低血时增益至 +15/25/35/50/60% 攻速+伤害; 伤害受单击上限; 低血判定走血池。 */
    BLOODLUST(AffixPool.COMBAT, 10, 2, false,
            new double[]{0.15, 0.25, 0.35, 0.50, 0.60}, null, MutexFlag.NONE),

    /** 混沌重击: 周期攻击附击飞; 击飞内 CD ≥2s, 限高, 落地后 ≥1s 不可再击飞; 走 KnockbackSafetyGuard。 */
    CHAOS_STRIKE(AffixPool.COMBAT, 11, 4, false,
            new double[]{1.0, 1.0, 1.0, 1.0, 1.0}, null, MutexFlag.NONE),

    /** 寒霜: 每层冻伤 0.8/1.2/1.8/2.5/3.5% maxHP/s + 减速 4/6/8/10/12%, 最大 5 层; 总减速 ≤50%; 冻伤入 DoT。 */
    FROST(AffixPool.COMBAT, 10, 2, false,
            new double[]{0.008, 0.012, 0.018, 0.025, 0.035}, new double[]{0.04, 0.06, 0.08, 0.10, 0.12},
            MutexFlag.NONE),

    // ============================================================
    // 7.3 机动 (5 条, 被动自身位移); 全部带 MutexFlag.MOBILITY (重型/巨大化互斥全部机动)
    // ============================================================

    /** 高速移动: +移速 10/15/22/30/40%; 互斥超速; 硬钳结果移速 ≤玩家疾跑速度。 */
    SPRINT(AffixPool.MOBILITY, 6, 1, false,
            new double[]{0.10, 0.15, 0.22, 0.30, 0.40}, null, MutexFlag.MOVE_SPEED),

    /**
     * 超速移动: 加速段 +移速 100/130/160/200/250% (2026-07-07 真服手感二调: 原 25~85% 僵尸冲刺仍慢于疾跑玩家,
     * 追不上 = 力竭窗无意义; 现冲刺 2.0~3.5 倍速真突进) + 强化力竭窗; 互斥高速; 力竭窗硬减速 ≥50% 反制不变。
     */
    OVERDRIVE(AffixPool.MOBILITY, 10, 3, false,
            new double[]{1.00, 1.30, 1.60, 2.00, 2.50}, null, MutexFlag.MOVE_SPEED),

    /** 闪光: 瞬移到玩家旁 周期 9/8/7/5.5/4s; 到达前 0.5s 粒子预兆; 传送家族。数值数组 = 周期秒。 */
    BLINK(AffixPool.MOBILITY, 8, 2, false,
            new double[]{9.0, 8.0, 7.0, 5.5, 4.0}, null, MutexFlag.TELEPORT_FAMILY),

    /** 战术传送: 短瞬移进/退 周期 8/7/6/5/4s 4-8 格; 传送家族。数值数组 = 周期秒。 */
    TACTICAL_BLINK(AffixPool.MOBILITY, 8, 2, false,
            new double[]{8.0, 7.0, 6.0, 5.0, 4.0}, null, MutexFlag.TELEPORT_FAMILY),

    /** 灵体移动: 周期穿墙 2s每15s … 4s每8s; 实体化保底回退链; 传送家族。数值数组 = 穿墙时长秒。 */
    PHASE_WALK(AffixPool.MOBILITY, 12, 4, false,
            new double[]{2.0, 2.5, 3.0, 3.5, 4.0}, null, MutexFlag.TELEPORT_FAMILY),

    // ============================================================
    // 7.4 技能 (10 条, 主动有 CD 须预兆, 占技能数上限; isSkill = true)
    // ============================================================

    /** 电磁蓄力: 单点 AOE 18/26/36/46/55% maxHP; 强制蓄力特效 + 落点指示; 可躲按红线 3 放宽。 */
    ELECTRO_CHARGE(AffixPool.SKILL, 14, 4, true,
            new double[]{0.18, 0.26, 0.36, 0.46, 0.55}, null, MutexFlag.NONE),

    /** 天雷: 多点 AOE 2点@12% … 6点@32% maxHP; 每点 ≤可躲技能上限; 须分散落点。数值数组 = 每点 %maxHP。 */
    THUNDER(AffixPool.SKILL, 18, 5, true,
            new double[]{0.12, 0.17, 0.22, 0.27, 0.32}, new double[]{2.0, 3.0, 4.0, 5.0, 6.0},
            MutexFlag.NONE),

    /**
     * 小男孩 (超凡+): 打断门槛 = 到场玩家数 × 120 伤; 未打断 AOE 70/85% maxHP (超凡/闪耀); 命中后 2s 免疫缓冲。
     * 数值数组按品质: 仅超凡/闪耀有意义 = 0.70/0.85, 前三档填 0 占位 (最低超凡)。
     */
    LITTLE_BOY(AffixPool.SKILL, 28, 7, true,
            new double[]{0.0, 0.0, 0.0, 0.70, 0.85}, null, MutexFlag.NONE),

    /**
     * 命定之死 (超凡+): 标记玩家限时须对 BOSS 打出 = 该玩家近10s 实测 DPS × 窗口 × 1.6 (封顶 ≤理论满输出
     * 120%) 否则处决; 标记期对 BOSS 伤害衰减 30%; 与反击单元不并行。数值数组 = DPS 倍率系数 (恒 1.6)。
     */
    DEATH_MARK(AffixPool.SKILL, 30, 8, true,
            new double[]{0.0, 0.0, 0.0, 1.6, 1.6}, null, MutexFlag.DEATH_MARK),

    /** 视觉干扰: 周期失明 1s每12s (普通) … 2.5s每7s (闪耀仅★9+); 原版 Blindness; 并入控制聚合层。数值=失明时长秒。 */
    VISUAL_DISRUPTION(AffixPool.SKILL, 12, 4, true,
            new double[]{1.0, 1.5, 2.0, 2.25, 2.5}, null, MutexFlag.NONE),

    /** 自我修复单元: 定身修复 FLAT 40/—/80/150/300 HP/s; 受任意伤害暂停 1.5s; 近战击退打断; 血池权威。 */
    SELF_REPAIR(AffixPool.SKILL, 14, 4, true,
            new double[]{40.0, 0.0, 80.0, 150.0, 300.0}, null, MutexFlag.NONE),

    /** 反击单元: 锁定高亮+警告 反伤比 40/55/70/85/100%; 三层封顶见红线 2; 窗口 ≤5s; 与命定不并行。 */
    COUNTER_UNIT(AffixPool.SKILL, 12, 3, true,
            new double[]{0.40, 0.55, 0.70, 0.85, 1.00}, null, MutexFlag.DEATH_MARK),

    /** 凯撒实验型转换器: 与玩家换位 CD 20/17/14/12/10s; 换位走 KnockbackSafetyGuard; 传送家族计入全局位移源 ≤2。 */
    CAESAR_SWAP(AffixPool.SKILL, 14, 5, true,
            new double[]{20.0, 17.0, 14.0, 12.0, 10.0}, null, MutexFlag.TELEPORT_FAMILY),

    /** 利刃华尔兹: 锁定瞬移突袭 3/4/5/6/7 次每次一击; 每击 ≤单击上限且整套总伤 ≤60% maxHP; 传送家族计入 ≤2。 */
    BLADE_WALTZ(AffixPool.SKILL, 16, 5, true,
            new double[]{3.0, 4.0, 5.0, 6.0, 7.0}, null, MutexFlag.TELEPORT_FAMILY),

    /** 支援: 召唤 1/2/2/3/3 只 同时存活 2/3/4/5/6 CD 30/26/22/18/14s; 三重封顶 (红线 8)。数值数组 = 召唤数。 */
    SUMMON_SUPPORT(AffixPool.SKILL, 16, 4, true,
            new double[]{1.0, 2.0, 2.0, 3.0, 3.0}, new double[]{2.0, 3.0, 4.0, 5.0, 6.0}, MutexFlag.NONE);

    /**
     * 跨池/跨族互斥标志 (spec 第八章): 同一互斥族 (MOVE_SPEED/SIZE/MULTI_STRIKE/TELEPORT_FAMILY/DEATH_MARK)
     * 内至多取一; HEAVY_ARMOR/FORTITUDE/DEFLECTOR 是单向跨族禁配标志, 由 {@link PointBudget} 组合校验。
     */
    public enum MutexFlag {
        /** 无互斥标志。 */
        NONE,
        /** 高速 ⨉ 超速 (同族至多一)。 */
        MOVE_SPEED,
        /** 巨大化 ⨉ 缩小化 (同族至多一); 另与全部机动跨池互斥, 由组合校验处理。 */
        SIZE,
        /** 双倍 ⨉ 四倍 (同族至多一)。 */
        MULTI_STRIKE,
        /** 传送家族 (闪光/战术/灵体/凯撒/利刃) 跨池全局同时 ≤2。 */
        TELEPORT_FAMILY,
        /** 命定之死 ⨉ 反击单元 (同一怪不并行计时, 同族至多一)。 */
        DEATH_MARK,
        /** 重型护甲: 互斥全部机动 + 偏斜 + 刚毅 (单向跨族禁配, 组合校验)。 */
        HEAVY_ARMOR,
        /** 刚毅护盾: 互斥重型护甲 (组合校验)。 */
        FORTITUDE,
        /** 偏斜护盾: 互斥重型护甲 (组合校验)。 */
        DEFLECTOR
    }

    private final AffixPool pool;
    private final int baseCost;
    private final int minStar;
    private final boolean skill;
    private final double[] primaryValues;
    private final double[] secondaryValues;
    private final MutexFlag mutexFlag;

    AffixDef(AffixPool pool, int baseCost, int minStar, boolean skill,
             double[] primaryValues, double[] secondaryValues, MutexFlag mutexFlag) {
        this.pool = pool;
        this.baseCost = baseCost;
        this.minStar = minStar;
        this.skill = skill;
        this.primaryValues = primaryValues;
        this.secondaryValues = secondaryValues;
        this.mutexFlag = mutexFlag;
    }

    /** 所属池 (生存/战斗/机动/技能)。 */
    public AffixPool pool() {
        return pool;
    }

    /** 基础成本 c (实际成本 = c × 品质系数, spec 第四章)。 */
    public int baseCost() {
        return baseCost;
    }

    /** 最低星级 (低于此星不可 roll 本词条)。 */
    public int minStar() {
        return minStar;
    }

    /** 是否技能词条 (占技能数上限)。 */
    public boolean isSkill() {
        return skill;
    }

    /**
     * 词条显示名的语言键 (自研 boss 条/探测列表 Component.translatable 用; 取代 Champions IAffix.toLanguageKey)。
     * 键 = {@code affix.champions.<枚举名小写>} —— 复用 assets/miningdim/lang 里已备好的 35 条中/英文词条名
     * (键名含 "champions" 仅为历史翻译键, 由我方语言文件提供, 客户端解析不依赖 Champions mod 存在)。
     */
    public String displayNameKey() {
        return "affix.champions." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /** 互斥标志。 */
    public MutexFlag mutexFlag() {
        return mutexFlag;
    }

    /**
     * 该品质档的主数值 (spec 第七章 5 档 普通/中级/高级/超凡/闪耀)。语义随词条不同 (减伤率/FLAT HP/%maxHP...),
     * 由受击结算层解释。
     *
     * @param quality 品质 (其 valueIndex 即 5 档数组索引)
     * @return 主数值
     */
    public double valueFor(AffixQuality quality) {
        return primaryValues[quality.valueIndex()];
    }

    /**
     * 该品质档的副数值 (仅部分词条有: 寒霜=减速%/天雷=点数/支援=同时存活数; 无副数值的词条返回 0)。
     *
     * @param quality 品质
     * @return 副数值 (无副数值时 0)
     */
    public double secondaryValueFor(AffixQuality quality) {
        if (secondaryValues == null) {
            return 0.0D;
        }
        return secondaryValues[quality.valueIndex()];
    }

    /** 是否定义了副数值。 */
    public boolean hasSecondaryValues() {
        return secondaryValues != null;
    }

    /**
     * 该词条在某品质下的点数成本 = ceil(baseCost × 品质系数) (spec 第四章; ceil 防小数成本破整数点池预算)。
     *
     * @param quality 品质
     * @return 整数点数成本
     */
    public int costAt(AffixQuality quality) {
        return (int) Math.ceil(baseCost * quality.costMultiplier());
    }

    /** 满足最低星 + 该星最高品质能取到本词条的最低品质即可装配 (品质随星解锁 + 词条最低★ 双门槛)。 */
    public boolean isUnlockedAt(StarRank rank) {
        if (rank.star() < minStar) {
            return false;
        }
        // 部分词条最低品质 > 普通 (重型护甲/刚毅最低高级, 小男孩/命定最低超凡), 须该星最高品质覆盖其最低可用档。
        return rank.maxQuality().ordinal() >= minUsableQuality().ordinal();
    }

    /**
     * 上限品质向下取最近可用档: 从 upper 逐档下探, 返回首个主数值非 0 的品质。前导 0 档由 {@link #minUsableQuality}
     * 保证不会探穿下界; 【中段 0 档】(自我修复 中级=0, spec "40/—/80/150/300" 的 "—" = 该档不存在) 由本法跳过 ——
     * roll/命令品质兜底若落在 0 档会产出"花点无效果"的死词条 (批3 接入自我修复时踩到)。upper 低于最低可用档属
     * 调用方 bug, 抛不掩盖 (调用方应先抬到 minUsableQuality)。
     */
    public AffixQuality usableQualityAtOrBelow(AffixQuality upper) {
        if (upper == null) {
            throw new IllegalArgumentException("upper must not be null");
        }
        for (int i = upper.ordinal(); i >= 0; i--) {
            if (primaryValues[i] != 0.0D) {
                return AffixQuality.values()[i];
            }
        }
        throw new IllegalArgumentException("no usable quality at or below " + upper + " for " + name());
    }

    /**
     * 本词条可取的最低品质档 (前导 0 占位档不可取): 扫描主数值数组首个非 0 档作为最低可用品质。
     * 重型护甲/刚毅最低高级, 小男孩/命定最低超凡 —— 这些词条前导档填 0, 由本法反解最低可用品质。
     */
    public AffixQuality minUsableQuality() {
        for (AffixQuality q : AffixQuality.values()) {
            if (primaryValues[q.valueIndex()] != 0.0D) {
                return q;
            }
        }
        // 全 0 不应出现 (每词条至少一档有效); 异常自然冒泡防静默装配空词条。
        throw new IllegalStateException("affix has no usable quality tier: " + name());
    }

    /** 全部生存池词条 (不可变视图)。 */
    public static Set<AffixDef> survivalAffixes() {
        return affixesIn(AffixPool.SURVIVAL);
    }

    /** 全部战斗池词条。 */
    public static Set<AffixDef> combatAffixes() {
        return affixesIn(AffixPool.COMBAT);
    }

    /** 全部机动池词条。 */
    public static Set<AffixDef> mobilityAffixes() {
        return affixesIn(AffixPool.MOBILITY);
    }

    /** 全部技能池词条。 */
    public static Set<AffixDef> skillAffixes() {
        return affixesIn(AffixPool.SKILL);
    }

    private static Set<AffixDef> affixesIn(AffixPool pool) {
        EnumSet<AffixDef> set = EnumSet.noneOf(AffixDef.class);
        for (AffixDef a : values()) {
            if (a.pool == pool) {
                set.add(a);
            }
        }
        return Collections.unmodifiableSet(set);
    }
}
