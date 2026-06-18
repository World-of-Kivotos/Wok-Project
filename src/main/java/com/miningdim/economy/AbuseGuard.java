package com.miningdim.economy;

import com.miningdim.core.Difficulty;
import com.miningdim.core.IMiningConfig;
import com.miningdim.core.InstanceState;
import com.miningdim.core.MiningServices;
import com.miningdim.economy.EconomyConstants.HighValueOre;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反滥用经济闸门核心裁决与副作用执行 (设计文档第十八章 18.2-18.6)。
 *
 * 职责边界 (CLAUDE.md 异常纪律): 本类只做裁决 (返回 {@link GateResult} / 计数) 与玩家库存扣费等
 * 确定性副作用; 不在内部 try/catch 生吞异常, 非法状态自然冒泡, 由 {@link EconomySystem} 在事件
 * 最外层兜底 (经 com.miningdim.error.MiningErrors)。
 *
 * 跨子系统协作只经 core 门面: 读 {@link IMiningConfig} (经 {@link MiningServices#config()})、
 * 读 {@link InstanceState} (经传入)。danger 当前无 core 门面 (它属未交付的 Capability 子系统),
 * 故 18.5 重入冷却所需的 danger 离开值由本子系统在 {@link PlayerAbuseState#lastLeaveDanger()} 自存,
 * danger 实际写回由压力子系统就绪后经其门面执行; 本类只提供 {@link #computeReentryDanger} 纯函数裁决值。
 *
 * 线程: 全部主线程调用 (事件回调 / ServerTickEvent)。per-instance 重置状态用并发容器仅为防御性,
 * 写仍只在主线程 (与 InstanceManager 12.4 纪律一致)。
 */
public final class AbuseGuard {

    /**
     * 每实例重置闸门状态 (18.2 计数字段)。设计文档 18.2 标注存于 InstanceState (D6), 但 core 的
     * {@link InstanceState} 为阶段0 定稿不可变结构, 未含 lastResetTick/resetCountToday/resetDayStamp 字段,
     * 任务约束禁止改 core。故本子系统以 instanceId 为键侧存这些重置计数, 语义与 18.2 完全一致;
     * 待 InstanceState 扩展或经 IInstanceManager 暴露重置元数据后, 可迁回实例本体。
     */
    private static final class InstanceResetState {
        long lastResetTick = Long.MIN_VALUE;
        int resetCountToday = 0;
        long resetDayStamp = Long.MIN_VALUE;
    }

    private final Map<Long, InstanceResetState> instanceResetStates = new ConcurrentHashMap<>();

    /** 18.3 高价矿物方块 -> 逻辑分类的查表 (一次性构建, 命中即计数)。 */
    private final Map<Block, HighValueOre> oreClassification;

    public AbuseGuard() {
        Map<Block, HighValueOre> m = new HashMap<>();
        m.put(EconomyConstants.ORE_DIAMOND, HighValueOre.DIAMOND);
        m.put(EconomyConstants.ORE_DEEPSLATE_DIAMOND, HighValueOre.DIAMOND);
        m.put(EconomyConstants.ORE_GOLD, HighValueOre.GOLD);
        m.put(EconomyConstants.ORE_DEEPSLATE_GOLD, HighValueOre.GOLD);
        m.put(EconomyConstants.ORE_NETHER_GOLD, HighValueOre.GOLD);
        m.put(EconomyConstants.ORE_ANCIENT_DEBRIS, HighValueOre.NETHERITE_SCRAP);
        this.oreClassification = Map.copyOf(m);
    }

    // ============================================================
    // 18.2 实例重置闸门: 冷却 -> 每日上限 -> 成本校验 -> 扣费
    // ============================================================

    /**
     * 重置前置闸门裁决 + 扣费 (18.2 判定顺序: 冷却 -> 每日上限 -> 成本校验 -> 扣费)。
     * 全部 PASS 才返回 {@link GateResult#PASS} 并已扣除成本; 任一不过返回对应拒绝码且不扣费 (先校验后扣)。
     *
     * 调用时机: ResetService 在真正执行区块删除/重生成前调用本法 (作为最外层进入条件)。
     * 翻日清零由 {@link #rolloverResetCounters} 在周期 tick 批量做, 本法只读当前计数。
     *
     * @param instance  目标实例
     * @param requester 发起重置的玩家 (扣费对象)
     * @param nowTick   当前 server game time
     * @return 闸门结果; PASS 表示已扣费并可执行重置
     */
    public GateResult checkAndChargeReset(InstanceState instance, ServerPlayer requester, long nowTick) {
        InstanceResetState rs = instanceResetStates.computeIfAbsent(instance.instanceId(), k -> new InstanceResetState());

        // 1) 冷却: 优先读配置秒值换算 tick (16.2.9 与 18.2 同源), 配置为 0 时退化为 EconomyConstants 默认。
        int cooldownTicks = resolveResetCooldownTicks();
        if (rs.lastResetTick != Long.MIN_VALUE && nowTick - rs.lastResetTick < cooldownTicks) {
            return GateResult.REJECT_COOLDOWN;
        }

        // 2) 每日上限。
        long today = currentDayStamp(requester, EconomyConstants.RESET_DAY_MODE);
        if (rs.resetDayStamp != today) {
            // 防御: 周期 tick 翻日尚未跑到本实例时, 请求路径内即时纠正读到的计数 (不引入额外写竞态)。
            rs.resetDayStamp = today;
            rs.resetCountToday = 0;
        }
        if (rs.resetCountToday >= EconomyConstants.RESET_DAILY_LIMIT_PER_INSTANCE) {
            return GateResult.REJECT_DAILY_LIMIT;
        }

        // 3) 成本校验 (先查后扣, 杜绝双花 20.2)。
        Item costItem = resolveResetCostItem();
        int costAmount = EconomyConstants.RESET_COST_AMOUNT;
        if (costAmount > 0) {
            int owned = countItem(requester, costItem);
            if (owned < costAmount) {
                return GateResult.REJECT_COST_UNPAID;
            }
            // 4) 扣费。
            int removed = chargeItem(requester, costItem, costAmount);
            if (removed < costAmount) {
                // 并发竞态下扣到的少于应扣: 回补已扣部分并拒绝 (不可双花, 不可白扣)。
                refundItem(requester, costItem, removed);
                return GateResult.REJECT_COST_UNPAID;
            }
        }

        // 记账: 重置时间 + 当日计数。
        rs.lastResetTick = nowTick;
        rs.resetCountToday += 1;
        return GateResult.PASS;
    }

    /** 周期 tick 批量翻日清零重置计数 (18.2: 翻日统一在 InstanceManager 巡检里批量做)。 */
    public void rolloverResetCounters(ServerPlayer anyOnlinePlayerForGameTime, long instanceId) {
        InstanceResetState rs = instanceResetStates.get(instanceId);
        if (rs == null) {
            return;
        }
        long today = currentDayStamp(anyOnlinePlayerForGameTime, EconomyConstants.RESET_DAY_MODE);
        if (rs.resetDayStamp != today) {
            rs.resetDayStamp = today;
            rs.resetCountToday = 0;
        }
    }

    /** 实例销毁时清理其重置状态 (避免侧存表随销毁实例泄漏)。 */
    public void onInstanceReleased(long instanceId) {
        instanceResetStates.remove(instanceId);
    }

    private int resolveResetCooldownTicks() {
        IMiningConfig cfg = MiningServices.config();
        int seconds = cfg.resetCooldownSeconds();
        if (seconds <= 0) {
            return EconomyConstants.RESET_COOLDOWN_TICKS;
        }
        return seconds * EconomyConstants.TICKS_PER_SECOND;
    }

    private Item resolveResetCostItem() {
        // 用 Forge 注册表 (非 deprecated 的 vanilla BuiltInRegistries): 未知 id 时 getValue 返回 null,
        // 配置物品无效则回退默认钻石 (无效配置由 ConfigSystem 接线后在校验层暴露; 此处保证闸门有可用成本物)。
        Item item = net.minecraftforge.registries.ForgeRegistries.ITEMS.getValue(EconomyConstants.RESET_COST_ITEM);
        if (item == null) {
            return EconomyConstants.RESET_COST_ITEM_DEFAULT;
        }
        return item;
    }

    // ============================================================
    // 18.3 矿物产出软上限与收购价递减
    // ============================================================

    /**
     * 把一次挖出的方块计入玩家当日高价矿计数 (若属高价矿)。AFK 冻结态不计入 (18.4 第3条)。
     * 返回该矿种计入后的当日累计值; 非高价矿返回 -1 (调用方据此判断是否需要软上限提示)。
     *
     * @param state     玩家反滥用态
     * @param minedBlock 被挖方块
     * @return 计入后当日累计值; 非高价矿或 AFK 冻结返回 -1
     */
    public int recordMinedOre(PlayerAbuseState state, Block minedBlock) {
        return recordMinedOreDrops(state, minedBlock, 1);
    }

    /**
     * 按"产出物个数"把一批高价矿产出计入玩家当日计数 (方案 B; Miner_Job_DesignSpec 第六/十章: 当日上限按产出物
     * 个数计, 而非挖了几块)。供矿工连锁/隧道连带破坏的产出回放 ({@link IEconomyService#recordMinedOreDrops}) 与原版
     * 单块挖矿 (count=1, 经 {@link #recordMinedOre}) 共用同一口径与同一隐藏软上限, 杜绝连锁绕过软上限的印钞口。
     *
     * AFK 冻结态不计 (18.4 第3条)。非高价矿或 producedCount &lt;= 0 不计。
     *
     * @param state         玩家反滥用态
     * @param minedBlock    被破坏的方块 (决定矿种)
     * @param producedCount 本次该方块产出的物品总个数 (含时运/连锁额外掉落)
     * @return 计入后当日累计值; 非高价矿 / AFK 冻结 / producedCount&lt;=0 返回 -1
     */
    public int recordMinedOreDrops(PlayerAbuseState state, Block minedBlock, int producedCount) {
        HighValueOre ore = oreClassification.get(minedBlock);
        if (ore == null) {
            return -1;
        }
        if (state.afkFrozen()) {
            // AFK 期间挖到的高价矿不计入经济统计 (18.4); 仍正常掉落 (不阻挖, 软上限本就不阻挖)。
            return -1;
        }
        if (producedCount <= 0) {
            return -1;
        }
        return state.addDailyOreCount(ore, producedCount);
    }

    /** 把方块分类为高价矿种; 非高价矿返回 null。供事件层在计数后取种类做软上限提示。 */
    public HighValueOre classify(Block block) {
        return oreClassification.get(block);
    }

    /** 某矿种的每日软上限 (18.3 economy.daily.*)。 */
    public int dailySoftCap(HighValueOre ore) {
        switch (ore) {
            case DIAMOND:
                return EconomyConstants.DAILY_SOFTCAP_DIAMOND;
            case GOLD:
                return EconomyConstants.DAILY_SOFTCAP_GOLD;
            case NETHERITE_SCRAP:
                return EconomyConstants.DAILY_SOFTCAP_NETHERITE_SCRAP;
            default:
                throw new IllegalArgumentException("Unknown high-value ore: " + ore);
        }
    }

    /**
     * 收购价递减曲线 (18.3): price(n) = basePrice * max(floorRatio, decayBase^max(0, n - softCap))。
     * 纯函数, 确定性, 供外部经济插件接入时计算第 n 块的收购价; 服务器未接经济插件时本闸门退化为计数
     * (本法不被掉落路径调用, 不改变掉落量, 仅供查询)。
     *
     * @param ore       矿种
     * @param countSoFar 当日已产出该矿的累计数 n (含本块)
     * @param basePrice  基础收购价
     * @return 第 n 块的递减后收购价
     */
    public double buyPrice(HighValueOre ore, int countSoFar, double basePrice) {
        int softCap = dailySoftCap(ore);
        int over = Math.max(0, countSoFar - softCap);
        double decayed = Math.pow(EconomyConstants.ECONOMY_DECAY_BASE, over);
        double ratio = Math.max(EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO, decayed);
        return basePrice * ratio;
    }

    /** 是否已超某矿种当日软上限 (超限即触发递减/提示, 但不阻挖)。 */
    public boolean overSoftCap(HighValueOre ore, int countSoFar) {
        return countSoFar > dailySoftCap(ore);
    }

    /**
     * 全服每人每日信用点 faucet 软上限的衰减系数 (经济文档 8.5: 所有 faucet 并入同一软上限 + 0.97 衰减 / 0.25 地板,
     * 复用 UTC 翻日)。与矿物收购 {@link #buyPrice} 同构, 但衰减档以"软上限的整数倍"递进 (非单信用点): 当日累计入账
     * 信用点每超出一个完整 dailyCap 档, 衰减底数再乘一次 0.97, 夹 0.25 地板。
     *
     * 为何按 dailyCap 档而非单信用点递进: 信用点面值是 ×10 锚 (8.1), 单信用点 0.97 会令几十信用点即跌破 0.25 地板,
     * 与"软上限内全额、超额温和递减"的设计相悖; 按 cap 档递进使首个 cap 全额、第二个 cap 档 ×0.97、远超后夹地板,
     * 与卖矿"软上限内不衰减、超后逐步递减"的体感一致 (8.5 复用同一衰减语言)。纯函数, 确定性。
     *
     * @param creditsBeforeThisGrant 本次入账前当日已累计入账的原始信用点 n0
     * @param rawCreditAmount        本次拟入账的原始信用点 (&gt; 0)
     * @param dailyCap               每日信用点软上限 (&gt; 0; 累计入账超出后逐档衰减)
     * @return 本次实际应入账的信用点 (衰减后向下取整, &gt;= 0)
     */
    public long faucetCreditAfterDecay(long creditsBeforeThisGrant, long rawCreditAmount, long dailyCap) {
        long newCumulative = creditsBeforeThisGrant + rawCreditAmount;
        long over = Math.max(0L, newCumulative - dailyCap);
        // 衰减档 = 超出软上限的完整 dailyCap 个数 (0 = 仍在首个 cap 内, 全额)。
        long tier = over / dailyCap;
        double decayed = Math.pow(EconomyConstants.ECONOMY_DECAY_BASE, tier);
        double ratio = Math.max(EconomyConstants.ECONOMY_PRICE_FLOOR_RATIO, decayed);
        return (long) Math.floor(rawCreditAmount * ratio);
    }

    // ============================================================
    // 18.4 AFK / 挂机检测 (挂 danger 降频 tick 同批执行, 纯读字段)
    // ============================================================

    /**
     * 评估玩家是否进入 AFK 冻结态 (18.4): 无挖掘 (距 lastBreakTick > noBreakTicks) 且无显著位移
     * (滑动窗口位移 < noMoveBlocks) 同时满足判 AFK。任一信号活跃则解冻。
     *
     * 本法更新 {@link PlayerAbuseState#afkFrozen()} 并维护位移锚点, 返回是否处于冻结态。
     * 纯字段计算 + 无世界写, 可在 danger 评估回调内完成 (18.4)。
     *
     * @param state   玩家反滥用态
     * @param curX    玩家当前 X
     * @param curZ    玩家当前 Z
     * @param nowTick 当前 server game time
     * @return 评估后是否处于 AFK 冻结态
     */
    public boolean evaluateAfk(PlayerAbuseState state, double curX, double curZ, long nowTick) {
        // 位移信号: 与锚点的水平距离平方是否超过 noMoveBlocks^2; 超过则刷新锚点 (有显著位移 -> 解冻)。
        boolean moved;
        if (!state.hasMoveAnchor()) {
            state.resetMoveAnchor(curX, curZ, nowTick);
            moved = true;
        } else {
            double dx = curX - state.anchorX();
            double dz = curZ - state.anchorZ();
            double distSq = dx * dx + dz * dz;
            double thresholdSq = EconomyConstants.AFK_NO_MOVE_BLOCKS * EconomyConstants.AFK_NO_MOVE_BLOCKS;
            moved = distSq >= thresholdSq;
            if (moved) {
                state.resetMoveAnchor(curX, curZ, nowTick);
            }
        }

        // 挖掘信号: 距上次有效挖掘是否超过 noBreakTicks。lastBreakTick 未初始化 (从未挖) 视为"长时间无挖掘"。
        long sinceBreak = state.lastBreakTick() == Long.MIN_VALUE
                ? Long.MAX_VALUE
                : nowTick - state.lastBreakTick();
        boolean noBreak = sinceBreak > EconomyConstants.AFK_NO_BREAK_TICKS;
        boolean noMove = !moved;

        boolean afk = noBreak && noMove;
        state.setAfkFrozen(afk);
        return afk;
    }

    // ============================================================
    // 18.5 danger 重入冷却 (防"进-退-再进")
    // ============================================================

    /**
     * 计算重入某实例时的初始 danger (18.5 L2): 若在 reentry.cooldownTicks 内重入"同一实例",
     * 取 max(衰减后值, 离开值 * retainRatio); 冷却外或换实例则取衰减后值 (不继承)。纯函数, 确定性。
     *
     * @param state          玩家反滥用态 (含 lastInstanceId/lastLeaveTick/lastLeaveDanger)
     * @param targetInstanceId 本次进入的实例 id
     * @param decayedDanger  按 D7 离区衰减后的当前 danger 值
     * @param nowTick        当前 server game time
     * @return 重入应起算的 danger 值
     */
    public float computeReentryDanger(PlayerAbuseState state, long targetInstanceId,
                                      float decayedDanger, long nowTick) {
        boolean sameInstance = state.lastInstanceId() == targetInstanceId && targetInstanceId >= 0;
        boolean withinCooldown = state.lastLeaveTick() != Long.MIN_VALUE
                && nowTick - state.lastLeaveTick() < EconomyConstants.REENTRY_COOLDOWN_TICKS;
        if (sameInstance && withinCooldown) {
            float retained = (float) (state.lastLeaveDanger() * EconomyConstants.REENTRY_RETAIN_RATIO);
            return Math.max(decayedDanger, retained);
        }
        return decayedDanger;
    }

    // ============================================================
    // 18.6 死亡惩罚
    // ============================================================

    /**
     * 在玩家死亡时记账死亡惩罚 (18.6): 设置死亡再入全局冷却; 若 death.lockInstanceTicks>0 则对死亡所在
     * 实例加再入锁; danger 处理 (RESET_TO_ZERO/KEEP) 由压力子系统执行, 本法只决定 danger 是否应清零并
     * 返回该裁决。掉落物处理 (KEEP_IN_PLACE/DESPAWN_FAST/VOID) 由 {@link EconomySystem} 在 LivingDropsEvent
     * 按 {@link EconomyConstants#DEATH_DROP_MODE} 落地, 本法不直接碰掉落集合。
     *
     * @param state           死者反滥用态
     * @param diedInInstanceId 死亡时所在实例 id (-1 = 不在实例内)
     * @param nowTick         当前 server game time
     * @return true=死亡应清零 danger (death.dangerOnDeath==RESET_TO_ZERO)
     */
    public boolean applyDeathPenalty(PlayerAbuseState state, long diedInInstanceId, long nowTick) {
        state.setDeathReentryUntilTick(nowTick + EconomyConstants.DEATH_REENTRY_COOLDOWN_TICKS);
        if (EconomyConstants.DEATH_LOCK_INSTANCE_TICKS > 0 && diedInInstanceId >= 0) {
            state.lockInstance(diedInInstanceId, nowTick + EconomyConstants.DEATH_LOCK_INSTANCE_TICKS);
        }
        return EconomyConstants.DEATH_DANGER_MODE == EconomyConstants.DangerOnDeath.RESET_TO_ZERO;
    }

    /**
     * 进入任意实例前的死亡再入冷却闸门 (18.6 death.reentryCooldownTicks) + 实例锁 (death.lockInstanceTicks)。
     * 冷却/锁未结束返回拒绝码, 否则 PASS。
     *
     * @param state            玩家反滥用态
     * @param targetInstanceId 拟进入的实例 id
     * @param nowTick          当前 server game time
     */
    public GateResult checkReentryGate(PlayerAbuseState state, long targetInstanceId, long nowTick) {
        if (nowTick < state.deathReentryUntilTick()) {
            return GateResult.REJECT_DEATH_REENTRY_COOLDOWN;
        }
        if (state.lockedInstanceId() == targetInstanceId
                && targetInstanceId >= 0
                && nowTick < state.lockedInstanceUntilTick()) {
            return GateResult.REJECT_DEATH_REENTRY_COOLDOWN;
        }
        return GateResult.PASS;
    }

    // ============================================================
    // 翻日口径与库存工具
    // ============================================================

    /**
     * 当前日戳 (18.2 翻日口径)。REAL = UTC epochDay; GAME = 该玩家所在维度 dayTime / 24000。
     * GAME 模式需要一个在线玩家解析所在 level 的 dayTime; requester 为 null 时退化为 REAL (无世界上下文)。
     */
    public long currentDayStamp(ServerPlayer requester, EconomyConstants.DayMode mode) {
        if (mode == EconomyConstants.DayMode.GAME && requester != null) {
            long dayTime = requester.serverLevel().getDayTime();
            return Math.floorDiv(dayTime, 24000L);
        }
        // REAL: 服务端真实 UTC 日序。
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }

    /** 当前每日矿物计数翻日所用的玩家级日戳 (18.3 dailyOreCount 翻日)。固定 REAL 口径与排行对齐。 */
    public long currentPlayerDayStamp() {
        return Instant.now().atZone(ZoneOffset.UTC).toLocalDate().toEpochDay();
    }

    /** 统计玩家库存中某物品总数 (count 模式: clearOrCountMatchingItems 传 0 = 仅计数)。 */
    private int countItem(ServerPlayer player, Item item) {
        return player.getInventory().clearOrCountMatchingItems(
                stack -> stack.is(item), 0, new SimpleContainer(0));
    }

    /** 从玩家库存扣除最多 amount 个某物品, 返回实际扣除数 (clearOrCountMatchingItems 传正数 = 移除)。 */
    private int chargeItem(ServerPlayer player, Item item, int amount) {
        return player.getInventory().clearOrCountMatchingItems(
                stack -> stack.is(item), amount, new SimpleContainer(0));
    }

    /** 回补 amount 个某物品到玩家库存 (扣费竞态回滚用); 满则掉落到脚下, 不丢失。 */
    private void refundItem(ServerPlayer player, Item item, int amount) {
        if (amount <= 0) {
            return;
        }
        ItemStack refund = new ItemStack(item, amount);
        boolean added = player.getInventory().add(refund);
        if (!added || !refund.isEmpty()) {
            player.drop(refund, false);
        }
    }

    /** 闸门裁决结果码。每个拒绝码对应 {@link com.miningdim.error.MiningMessages} 的一条玩家文案。 */
    public enum GateResult {
        PASS,
        REJECT_COOLDOWN,
        REJECT_DAILY_LIMIT,
        REJECT_COST_UNPAID,
        REJECT_DEATH_REENTRY_COOLDOWN
    }
}
