package com.miningdim.job.miner;

import net.minecraft.world.item.ItemStack;

import java.util.EnumMap;
import java.util.Map;

/**
 * 单个玩家的矿工服务端运行态 (Miner_Job_DesignSpec 第二/四章; 契约 keyMembers): 连锁慢充能池 +
 * 各主动技能 per-player CD 计时 + 偏好开关位。
 *
 * 归属说明: 与 economy.PlayerAbuseState 同范式 —— 矿工等级/经验进 JobProgress capability (持久),
 * 但技能 CD/充能/开关位是瞬态运行态, 不持久化, 由 {@link MinerSystem} 以 UUID 为键在内存维护。
 * 按 JobFramework 第五章纪律, 死亡/登出/换维度时清理 (MinerSystem 在相应事件 remove 本态)。
 *
 * 充能模型 (第四章慢充能): 池容量按等级 ({@link MinerSkills#chainChargePool}); 以 "整池回满时长"
 * 折算每 tick 回充速率, 用浮点累加器避免整数取整丢失低速回充。连锁每破坏一块消耗 1 点。
 *
 * 线程: 仅服务端主线程读写 (事件回调 / tick); 不做并发防御。
 */
public final class MinerChargeState {

    /** 连锁充能池当前量 (浮点累加, 取整后用于消耗判定)。 */
    private double charge;

    /** 上次充能推进的 server game time (用于按经过 tick 回充)。 */
    private long lastRechargeTick = Long.MIN_VALUE;

    /** 各主动技能下次就绪 tick (server game time)。未在表内 = 从未用过 = 立即可用。 */
    private final Map<MinerSkill, Long> cooldownReadyAt = new EnumMap<>(MinerSkill.class);

    /** 偏好/主动开关位 (自动入包/自动熔炼/...)。未在表内 = 关。连锁已改"按住激活"不再走本表 (见 {@link #chainHeldUntilTick})。 */
    private final Map<MinerSkill, Boolean> toggles = new EnumMap<>(MinerSkill.class);

    /**
     * 连锁"按住激活"的失效 tick (server game time)。FTB Ultimine 式: 连锁从持久开关改为按住键激活 —— 客户端按住期间
     * 每 {@link MinerSkills} 心跳重发 hold=true, 服务端把本值续到 收包 tick + {@link MinerConstants#CHAIN_HOLD_GRACE_TICKS};
     * 松开包立即置 {@link Long#MIN_VALUE} 失效。BreakEvent 判连锁激活即 {@code chainHeldUntilTick >= now} ({@link #chainHeldActive})。
     * 瞬态, 随运行态在死亡/登出/换维度清理 (与 CD/充能同纪律), 故无需专门在这几处清零 —— 整个 state 被 remove。
     */
    private long chainHeldUntilTick = Long.MIN_VALUE;

    /** 进行中的脱险读条起算 tick (Long.MIN_VALUE = 未在读条)。 */
    private long evacuateChannelStartTick = Long.MIN_VALUE;
    /** 读条打断判定用的起始位置。 */
    private double channelStartX;
    private double channelStartY;
    private double channelStartZ;

    /**
     * 省耐久 (L1 被动) 同 tick 回补登记 (Miner_Job_DesignSpec 第五章)。
     *
     * 为何用 "BreakEvent 抢拍 + 同 tick 末回补" 而非在 BreakEvent 直接改耐久: 原版 ServerPlayerGameMode.destroyBlock
     * 的事件顺序是 先发 BreakEvent -> 再 removeBlock -> 再 ItemStack.mineBlock (此时才扣耐久, 且 Unbreaking 是概率扣)。
     * 故 BreakEvent 内工具尚未为本次破坏扣耐久; 在此预减会与 mineBlock 抢拍且无法兼容 Unbreaking 的概率扣。
     * 改为: BreakEvent 命中省耐久概率时, 抢拍当前工具 stack 引用与扣耐久前的 damageValue; 同 tick 末 (ServerTickEvent END,
     * 晚于本 tick 内所有 mineBlock) 若同一 stack 的 damageValue 比抢拍值上升 (vanilla 确实扣了耐久), 回补到抢拍值
     * (净零损耗)。stack 引用同 tick 内稳定 (无重生/同步换栈), == 比较即可精确认栈, 与 Unbreaking 兼容 (回补到扣前值)。
     */
    private ItemStack durabilitySaveStack;
    private int durabilitySaveDamageBefore;

    // ---- 充能池 ----

    /** 当前充能 (取整, 供连锁预算)。 */
    public int currentCharge() {
        return (int) Math.floor(charge);
    }

    /** 直接设置充能 (新进实例满充 / 测试用); 钳制 [0, pool]。 */
    public void setCharge(double value, int pool) {
        this.charge = Math.max(0.0D, Math.min(pool, value));
    }

    /**
     * 消耗 amount 点充能 (连锁逐块调用)。返回实际消耗 (不足时消耗剩余全部并返回该量)。
     * amount < 0 抛 (异常自然冒泡, 不掩盖编程错)。
     */
    public int consumeCharge(int amount) {
        if (amount < 0) {
            throw new IllegalArgumentException("consume amount must be >= 0, got " + amount);
        }
        int available = currentCharge();
        int consumed = Math.min(available, amount);
        charge -= consumed;
        if (charge < 0.0D) {
            charge = 0.0D;
        }
        return consumed;
    }

    /**
     * 按经过 tick 回充充能池 (整池回满时长按等级)。首次调用建立时间锚, 之后按 (now - last) 比例回充。
     * 钳制不超过池容量。
     *
     * @param level 矿工等级 (决定池容量与回满时长)
     * @param now   当前 server game time
     */
    public void tickRecharge(int level, long now) {
        int pool = MinerSkills.chainChargePool(level);
        if (pool <= 0) {
            charge = 0.0D;
            lastRechargeTick = now;
            return;
        }
        if (lastRechargeTick == Long.MIN_VALUE) {
            lastRechargeTick = now;
            return;
        }
        long elapsed = now - lastRechargeTick;
        if (elapsed <= 0) {
            return;
        }
        int refillFullTicks = Math.max(1, MinerSkills.chainRefillFullTicks(level));
        // 每 tick 回充速率 = 池容量 / 整池回满时长。
        double perTick = (double) pool / (double) refillFullTicks;
        charge = Math.min(pool, charge + perTick * elapsed);
        lastRechargeTick = now;
    }

    // ---- CD ----

    /** 某主动技能就绪 tick (未用过返回 Long.MIN_VALUE = 立即可用)。 */
    public long cooldownReadyAt(MinerSkill skill) {
        return cooldownReadyAt.getOrDefault(skill, Long.MIN_VALUE);
    }

    /** 某主动技能当前是否就绪 (now >= readyAt)。 */
    public boolean cooldownReady(MinerSkill skill, long now) {
        return now >= cooldownReadyAt(skill);
    }

    /** 触发某技能后置 CD: readyAt = now + cdTicks。 */
    public void startCooldown(MinerSkill skill, long now, int cdTicks) {
        cooldownReadyAt.put(skill, now + cdTicks);
    }

    // ---- 开关位 ----

    public boolean toggled(MinerSkill skill) {
        return toggles.getOrDefault(skill, Boolean.FALSE);
    }

    /** 翻转开关位, 返回翻转后状态。 */
    public boolean flipToggle(MinerSkill skill) {
        boolean next = !toggled(skill);
        toggles.put(skill, next);
        return next;
    }

    public void setToggle(MinerSkill skill, boolean on) {
        toggles.put(skill, on);
    }

    // ---- 连锁"按住激活" (hold, 取代旧持久开关) ----

    /** 续期连锁按住激活到 untilTick (收 hold=true 心跳时调; untilTick 通常为 now + 宽限)。 */
    public void setChainHeld(long untilTick) {
        this.chainHeldUntilTick = untilTick;
    }

    /** 立即失效连锁按住激活 (收 hold=false 松开包时调)。 */
    public void clearChainHeld() {
        this.chainHeldUntilTick = Long.MIN_VALUE;
    }

    /** 连锁按住是否仍激活: now 未越过续期失效点 (heldUntilTick >= now)。松开/超时/从未按住 -> false。 */
    public boolean chainHeldActive(long now) {
        return chainHeldUntilTick >= now;
    }

    /** 连锁按住激活的失效 tick (测试/诊断用; 从未按住为 {@link Long#MIN_VALUE})。 */
    public long chainHeldUntilTick() {
        return chainHeldUntilTick;
    }

    // ---- 脱险读条 ----

    public boolean evacuating() {
        return evacuateChannelStartTick != Long.MIN_VALUE;
    }

    public long evacuateChannelStartTick() {
        return evacuateChannelStartTick;
    }

    public void beginEvacuateChannel(long now, double x, double y, double z) {
        this.evacuateChannelStartTick = now;
        this.channelStartX = x;
        this.channelStartY = y;
        this.channelStartZ = z;
    }

    public void cancelEvacuateChannel() {
        this.evacuateChannelStartTick = Long.MIN_VALUE;
    }

    /** 读条期间偏离起点的距离平方 (打断判定用)。 */
    public double channelMoveDistSq(double x, double y, double z) {
        double dx = x - channelStartX;
        double dy = y - channelStartY;
        double dz = z - channelStartZ;
        return dx * dx + dy * dy + dz * dz;
    }

    // ---- 省耐久同 tick 回补登记 ----

    /**
     * 登记一次省耐久抢拍 (BreakEvent 命中省耐久概率时调用): 记下扣耐久前的工具 stack 与 damageValue,
     * 待同 tick 末 {@link #consumeDurabilitySave(ItemStack)} 核对回补。重复登记 (同 tick 连续破坏) 以最后一次为准。
     *
     * @param tool         玩家主手工具 (须为可损耐久物品; 调用方已校验)
     * @param damageBefore mineBlock 扣耐久前的 damageValue
     */
    public void armDurabilitySave(ItemStack tool, int damageBefore) {
        this.durabilitySaveStack = tool;
        this.durabilitySaveDamageBefore = damageBefore;
    }

    public boolean hasArmedDurabilitySave() {
        return durabilitySaveStack != null;
    }

    /**
     * 同 tick 末核对回补: 若登记过且当前主手仍是同一 stack 且其 damageValue 比抢拍值上升 (vanilla 已扣耐久),
     * 把 damageValue 回补到抢拍值 (净零损耗), 返回实际回补的耐久点数; 否则不回补返回 0。无论是否回补都清空登记。
     *
     * @param currentMainHand 当前主手物品 (用于同栈校验; 换栈/丢弃则不回补)
     * @return 实际回补的耐久点数 (>= 0)
     */
    public int consumeDurabilitySave(ItemStack currentMainHand) {
        ItemStack armed = durabilitySaveStack;
        int before = durabilitySaveDamageBefore;
        durabilitySaveStack = null;
        durabilitySaveDamageBefore = 0;
        if (armed == null || armed != currentMainHand) {
            return 0; // 未登记 / 同 tick 内换栈或丢弃: 不回补 (不臆测应回补到谁身上)。
        }
        int now = armed.getDamageValue();
        if (now <= before) {
            return 0; // vanilla 本次未扣耐久 (如 Unbreaking 触发省扣): 无须回补。
        }
        armed.setDamageValue(before);
        return now - before;
    }
}
