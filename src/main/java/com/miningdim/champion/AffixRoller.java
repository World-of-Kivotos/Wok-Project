package com.miningdim.champion;

import net.minecraft.util.RandomSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * spawn 期词条掷取纯逻辑 (ChampionStarAffix spec 第四章双层平衡 + 第八章互斥 + 第十四章实现拆分 1)。
 *
 * 给定星级, 在四池点数预算内贪心掷出一组合法词条选择 {@link AffixSelection}: 逐池按预算掏点买词条, 每条经
 * {@link AffixQuality#clampTo} 随星降级品质, 再过 {@link #wouldRemainLegal} 预检 (最低★/品质/互斥/上限),
 * 通过才纳入。最终用 {@link PointBudget#allocate} 终校验 (合法集合必过, 非法即装配 bug 自然冒泡)。
 *
 * 纯函数/不碰 Champions: 输出 {@link AffixSelection} 列表, 由 b 阶段 (integration 层) 映射成真 IAffix 盖章。
 * GameTest 直接断言"掷出的集合 allocate 不抛 + 各池不超预算"。roll 顺序 (notes): roll 词条 -> clampTo 降级 ->
 * AffixSelection 构造 (内校验 >=minUsable) -> wouldRemainLegal 预检 -> 纳入; 全程不靠 catch 兜底
 * (PointBudget 非法抛 IllegalArgumentException 是装配 bug 信号, 不该被 roller 喂出来)。
 *
 * 注 SELF_REPAIR 中级档值=0 坑 (notes): roll 到的品质若落在词条某档值=0 (前导占位档), AffixSelection 构造期
 * 会因 &lt; minUsableQuality 抛; roller 在掷品质后先 clamp 到 max(随星档, 词条最低可用档) 规避, 不喂非法档。
 */
public final class AffixRoller {

    /**
     * 当前已有运行期 handler 真生效的词条白名单 (排除哑词条; Stage1 落地范围)。spawn 期只 roll 本集合内的词条,
     * 保证每个 roll 出的词条都真有效果 —— 不浪费点数预算去 roll 一个【数据齐全但运行期零消费】的哑词条
     * (玩家看到词条名却无任何机制效果)。
     *
     * 入选标准 = 该 {@link AffixDef} 在 integration/aggregate 运行期被某个 handler 真实读取并落成可观测的战斗结果
     * (改伤害 / 改净减伤 / 挂 DoT/易伤 / 损护甲耐久), 且【可被 spawn 期独立 roll 出】(不依赖未实现的哑词条作互斥前置),
     * 逐条核实其消费方:
     *  - 减伤 5 (生存池): COMPOSITE_ARMOR/UHMWPE_ARMOR/HEAVY_ARMOR/DEFLECTOR_SHIELD/FORTITUDE_SHIELD
     *    —— {@link com.miningdim.champion.integration.ChampionBloodPoolHandler#collectAffixReduction} 按 def 分派进
     *    {@link ChampionDamageReduction} 折算净减伤率 / FLAT 削顶, 受击单点 (LivingHurtEvent) 真扣影子血/改 amount。
     *  - 即时伤害 3 (战斗池): HEAVY_CANNON/BLOODLUST/ARMOR_PIERCING —— {@code ChampionAttackHandler.applyInstantDamage}
     *    经 {@link ChampionAttackValues#singleHitTotalPct} 合并放大/真伤, 叠到 event.getAmount()。
     *  - DoT 2 (战斗池): BURNING/FROST —— {@code ChampionAttackHandler.applyDotsAndSlow} 刷层进 PlayerDotSources,
     *    {@code ChampionDotTickHandler} 每秒 {@link ChampionAttackValues#burningTickHp}/frostFreezeTickHp 真扣血 (寒霜另施减速)。
     *  - 易伤 1 (战斗池): REND —— {@code ChampionAttackHandler.applyRend} 折 amplifier 挂全局易伤效果真放大下次受击。
     *  - 护甲磨损 1 (战斗池): CORROSIVE —— {@code ChampionAttackHandler.applyCorrosive} 损玩家护甲槽耐久。
     *
     * 故意排除的词条共 19 条 = 35 总 - 16 白名单 (数据/签名粒子/spawn 预算校验俱全, 但运行期【无任何 handler 读取其
     * 效果】或【无法独立 roll】, 属 Stage2 未来工作: 巨大化/缩小化体型 / 10 主动技能 / 自身位移传送 / 召唤 / 周期 AOE
     * 等待实现): 机动池 4 (OVERDRIVE/BLINK/TACTICAL_BLINK/PHASE_WALK; 高速移动 SPRINT 已 Stage2 批1 落地移入白名单)、
     * 全部技能池 10 (ELECTRO_CHARGE/THUNDER/LITTLE_BOY/DEATH_MARK/VISUAL_DISRUPTION/SELF_REPAIR/COUNTER_UNIT/
     * CAESAR_SWAP/BLADE_WALTZ/SUMMON_SUPPORT)、生存池 2 (GIGANTISM 哑 + MINIATURIZATION; 再生组织/易燃再生/反震 已
     * Stage2 批1 落地移入)、战斗池 3 (DOUBLE_STRIKE/QUADRUPLE_STRIKE/CHAOS_STRIKE)。其中:
     *  - DOUBLE_STRIKE/QUADRUPLE_STRIKE: {@link ChampionStrikeGate#strikeJumps} 仅在 GameTest 调用, 任何 integration
     *    handler 都【未】按跳数拆分施加 (近战伤害不因双倍/四倍而翻倍), 故运行期零可观测效果 -> 哑。
     *  - CHAOS_STRIKE: {@code applyChaosKnockback} 仅落账限频闸, 明确【不】push (KnockbackSafetyGuard 未落地), 玩家
     *    不被击飞, 故运行期零可观测效果 -> 哑。
     *  - COUNTER_UNIT/VISUAL_DISRUPTION 等: 控制/反伤聚合器 (PlayerControlAggregator/RetaliationAggregator) 虽是基建,
     *    但【无 handler】按这些 def 申请控制/反伤 (FROST 减速走控制聚合; THORNS 反伤已 Stage2 批1 接 RetaliationAggregator),
     *    故这些 def 运行期零消费 -> 哑。
     *  - MINIATURIZATION (生存池): 其体型折算净减伤 handler 已实 (ChampionBloodPoolHandler case MINIATURIZATION), 且
     *    Stage2 批1 起有合法机动伙伴 (SPRINT 已移入白名单, 满足 spec 第八章"缩小化须搭配 +1 机动"的 {@link PointBudget}
     *    硬校验)。但其【-血量惩罚】仍属 spawn 期血池模型改动 (批2 与巨大化 +血量一同接), 未接前移入白名单会 roll 出
     *    "有体型减伤却无血量惩罚"的纯 buff 失衡怪, 故暂仍排除, 待批2 -血量惩罚落地后与巨大化一同移入。
     *
     * 白名单语义 (非黑名单): 新增词条若未在此显式登记, 默认【不】被 roll —— 防 Stage2 往 AffixDef 加新哑词条时静默
     * 漏排, 逼实现 handler 后再把它移入本集合。{@link AffixDef#values()} 总集 - 本白名单 = 当前不可 roll 词条全集。
     */
    public static final Set<AffixDef> IMPLEMENTED_AFFIXES = Collections.unmodifiableSet(EnumSet.of(
            // 生存池减伤 (ChampionBloodPoolHandler + ChampionDamageReduction); 缩小化暂排除 (强制机动伙伴未实现, 见类注释)
            AffixDef.COMPOSITE_ARMOR,
            AffixDef.UHMWPE_ARMOR,
            AffixDef.HEAVY_ARMOR,
            AffixDef.DEFLECTOR_SHIELD,
            AffixDef.FORTITUDE_SHIELD,
            // 战斗池即时伤害 (ChampionAttackHandler.applyInstantDamage)
            AffixDef.HEAVY_CANNON,
            AffixDef.BLOODLUST,
            AffixDef.ARMOR_PIERCING,
            // 战斗池 DoT (ChampionAttackHandler.applyDotsAndSlow + ChampionDotTickHandler)
            AffixDef.BURNING,
            AffixDef.FROST,
            // 战斗池易伤 (ChampionAttackHandler.applyRend)
            AffixDef.REND,
            // 战斗池护甲磨损 (ChampionAttackHandler.applyCorrosive)
            AffixDef.CORROSIVE,
            // 生存池自身被动 (ChampionSelfEffectHandler; Stage2 批1): 脱战 %maxHP 回血 / 战斗 FLAT 回血 / 受击反震反伤
            AffixDef.REGEN_TISSUE,
            AffixDef.FLAMMABLE_REGEN,
            AffixDef.THORNS,
            // 机动池自身位移 (ChampionSelfEffectHandler; Stage2 批1): 移速加成瞬态 modifier
            AffixDef.SPRINT));

    private AffixRoller() {
    }

    /**
     * 为某星级掷一组合法词条选择 (四池预算内贪心)。
     *
     * 算法: 候选池 = 该星可解锁的全部词条 (满足 minStar + 品质覆盖)。逐池按声明顺序 (生存/战斗/机动/技能) 各自
     * 在该池预算内贪心选词条, 每次随机挑一个尚未选、加入后仍合法 (预算+上限+互斥+技能上限) 的候选, 直到该池
     * 无可加候选或撞总词条上限。掷品质: 在 [词条最低可用档, 该星最高品质] 区间随机, 高品质更贵故可能因预算挤出。
     *
     * @param rank 星级
     * @param rng  随机源 (服务端 level.random)
     * @return 合法词条选择列表 (可能为空, 如 1★ 预算极小; allocate 必过)
     */
    public static List<AffixSelection> roll(StarRank rank, RandomSource rng) {
        if (rank == null) {
            throw new IllegalArgumentException("rank must not be null");
        }
        if (rng == null) {
            throw new IllegalArgumentException("rng must not be null");
        }

        List<AffixSelection> chosen = new ArrayList<>();
        // 逐池掷取: 池声明顺序 (生存优先保证基础生存, 再战斗, 再机动, 最后技能)。
        for (AffixPool pool : AffixPool.values()) {
            rollPool(rank, pool, rng, chosen);
        }
        return chosen;
    }

    /** 在某池预算内贪心追加词条到 chosen (受总词条上限/技能上限/互斥约束)。 */
    private static void rollPool(StarRank rank, AffixPool pool, RandomSource rng, List<AffixSelection> chosen) {
        List<AffixDef> candidates = new ArrayList<>();
        for (AffixDef def : AffixDef.values()) {
            // 候选三门槛: 属本池 + 该星解锁 (minStar/品质) + 已有运行期 handler (排除哑词条, 见 IMPLEMENTED_AFFIXES)。
            // 哑词条被排除后, 机动池/技能池在任何星级候选恒空 (当前 0 条实现), rollPool 自然不为该池纳入任何词条 ——
            // 候选空时下方贪心循环 tryOrder.isEmpty() 直接 return, 不抛不死循环 (该池剩余预算按 spec 转基础膨胀, 与
            // 词条未排满时同一口径); 总词条/技能上限够也无所谓, 减少的只是无效哑词条占位。
            if (def.pool() == pool && def.isUnlockedAt(rank) && IMPLEMENTED_AFFIXES.contains(def)) {
                candidates.add(def);
            }
        }

        EnumSet<AffixDef> alreadyChosen = EnumSet.noneOf(AffixDef.class);
        for (AffixSelection sel : chosen) {
            alreadyChosen.add(sel.affix());
        }

        // 贪心: 每轮从剩余候选随机挑一个能合法加入的纳入, 直到无可加。candidates.size() 为轮数上界 (每轮至少剔一)。
        int rounds = candidates.size();
        for (int r = 0; r < rounds; r++) {
            if (chosen.size() >= rank.maxAffixes()) {
                return; // 撞总词条上限。
            }
            // 从尚未选的候选里随机取序尝试。
            List<AffixDef> tryOrder = new ArrayList<>();
            for (AffixDef def : candidates) {
                if (!alreadyChosen.contains(def)) {
                    tryOrder.add(def);
                }
            }
            if (tryOrder.isEmpty()) {
                return;
            }
            AffixDef pick = tryOrder.get(rng.nextInt(tryOrder.size()));

            AffixQuality quality = rollQuality(rank, pick, rng);
            AffixSelection sel = new AffixSelection(pick, quality);

            if (wouldRemainLegal(rank, chosen, sel)) {
                chosen.add(sel);
                alreadyChosen.add(pick);
            } else {
                // 该词条任何品质都加不进 (预算/互斥/上限), 从候选剔除避免死循环。
                candidates.remove(pick);
                rounds = candidates.size();
                r = -1;
            }
        }
    }

    /**
     * 掷某词条在该星的实际品质: 在 [词条最低可用档, 该星最高品质] 区间随机, 规避前导 0 占位档 (notes SELF_REPAIR 坑)。
     * 该星最高品质先经 {@link AffixQuality#clampTo} 保证 ≤ rank.maxQuality, 再抬到词条最低可用档 (避免越下界抛)。
     */
    private static AffixQuality rollQuality(StarRank rank, AffixDef affix, RandomSource rng) {
        int minIdx = affix.minUsableQuality().ordinal();
        int maxIdx = rank.maxQuality().ordinal();
        if (maxIdx < minIdx) {
            // 不应出现 (isUnlockedAt 已保证该星最高品质覆盖最低可用档); 防御性返回最低可用档。
            return affix.minUsableQuality();
        }
        int idx = minIdx + rng.nextInt(maxIdx - minIdx + 1);
        return AffixQuality.values()[idx];
    }

    /**
     * 预检: 把 candidate 加入 chosen 后是否仍整体合法 (复用 {@link PointBudget#allocate} 的全套校验)。
     * 合法返 true, 非法 (超预算/超上限/互斥冲突) 返 false。本预检让 roller 只产出合法集合, 终 allocate 必过。
     *
     * 这是唯一用 try/catch 的点, 且语义正确: 此处 catch 不是吞业务异常掩盖缺陷, 而是把 allocate 的"合法性
     * 判定"当布尔谓词用 (allocate 以抛/不抛表达合法/非法)。chosen 与 candidate 都是 roller 自造的合法值对象,
     * IllegalArgumentException 在此只承载"加入后越界"信号, 不掩盖任何外部脏数据。
     */
    private static boolean wouldRemainLegal(StarRank rank, List<AffixSelection> chosen, AffixSelection candidate) {
        List<AffixSelection> trial = new ArrayList<>(chosen);
        trial.add(candidate);
        try {
            PointBudget.allocate(rank, trial);
            return true;
        } catch (IllegalArgumentException illegalCombination) {
            return false;
        }
    }
}
