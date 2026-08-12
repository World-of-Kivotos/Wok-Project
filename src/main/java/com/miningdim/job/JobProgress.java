package com.miningdim.job;

import net.minecraft.nbt.CompoundTag;

/**
 * 单职业进度数据对象 (JobFramework_Shared_Foundation_DesignSpec 第 2.2 节)。纯数据, 无世界引用。
 *
 * 承载 level/xp/dailyXp/dayStamp 四个公共字段 + 职业特有附加字段 (同一对象内按 JobId 取用):
 *  - ENGINEER: nanoReactorCdEndTick (纳米反应堆 CD 截止 tick)
 *  - 其余职业的特有字段 (TAROT pity/卡 CD、MINER 技能 CD/充能/开关位、CHEF/FARMER 无额外) 走物品 NBT 或
 *    后续按需扩字段; 框架 spec 明确 CHEF/FARMER 无额外字段, 故此处只落 ENGINEER 已规格化的一个附加字段,
 *    不预置无读取方的死字段 (YAGNI; 需要时加一个 long + 一对 NBT 键即可, 零结构改动)。
 *
 * level/xp 关系: xp 是 "累计有效经验"; level 由 {@link JobXpCurve#levelForTotalXp(long)} 从 xp 派生,
 * 入账时 (grantXp) 自动重算并钳制在 [1,10]。新建默认 level=1/xp=0 (框架 spec 第 2.2 节旧存档缺键默认)。
 *
 * 序列化经 {@link #serializeNBT()}/{@link #deserializeNBT(CompoundTag)}, 由 {@link JobData} 遍历 EnumMap
 * 统一持久化; {@link #copyFrom(JobProgress)} 供 PlayerEvent.Clone 全量复制 (第 2.4 节)。
 */
public final class JobProgress {

    private int level = JobXpCurve.MIN_LEVEL;
    // xp/dailyXp 内部以 double 累计精确有效经验, 仅在读出/派生等级时 floor: 跨多笔小额 grant 的小数被进位
    // 累加 (例 x0.4 档单笔 0.8 有效), 而非每笔 floor 吞光 (修复软上限带在小额入账下产出恒 0 的漏洞)。
    private double xp = 0.0D;
    private double dailyXp = 0.0D;
    private long dayStamp = Long.MIN_VALUE;

    /** 仅 ENGINEER 取用: 纳米反应堆 CD 截止 server tick (第 2.2 节)。其余职业不读取此字段。 */
    private long nanoReactorCdEndTick = 0L;

    public int level() {
        return level;
    }

    /** 累计有效经验整数读出 (内部 double 精确累计, 读出 floor)。 */
    public long xp() {
        return (long) Math.floor(xp);
    }

    public long xp(JobId job) {
        return job == JobId.FARMER ? Math.round(xp) : xp();
    }

    /** 当日已结算有效经验整数读出 (内部 double 精确累计, 读出 floor)。 */
    public long dailyXp() {
        return (long) Math.floor(dailyXp);
    }

    public long dailyXp(JobId job) {
        return job == JobId.FARMER ? Math.round(dailyXp) : dailyXp();
    }

    public long dayStamp() {
        return dayStamp;
    }

    public long nanoReactorCdEndTick() {
        return nanoReactorCdEndTick;
    }

    public void setNanoReactorCdEndTick(long tick) {
        this.nanoReactorCdEndTick = tick;
    }

    /**
     * 入账一笔原始经验, 经每日软上限衰减折算成有效经验后累加, 并重算等级 (框架 spec 第四章)。
     *
     * 翻日: 传入的 todayStamp (UTC epochDay, 由调用方经 {@code AbuseGuard.currentPlayerDayStamp} 取) 与本
     * progress 的 dayStamp 不同则先把 dailyXp 归零再入账 (跨 UTC 日衰减额度刷新)。per-job 独立衰减
     * (框架 spec 第四章推荐): 各职业各自一份 dailyXp/dayStamp, 不共享日预算。
     *
     * @param rawXp      本次原始经验 (>=0)
     * @param todayStamp 当前 UTC 日戳 (epochDay)
     * @return 本次实际折算入账的有效经验 (>=0); 满级后仍累加 xp 但 level 封顶 10
     */
    public long grantXp(long rawXp, long todayStamp) {
        prepareGrant(rawXp, todayStamp);
        return grantXpInternal(null, JobXpCurve.applyDailyDecayExact(dailyXp, rawXp));
    }

    public long grantXp(JobId job, long rawXp, long todayStamp) {
        prepareGrant(rawXp, todayStamp);
        return grantXpInternal(job, JobXpPolicies.applyDailyDecayExact(job, dailyXp, rawXp));
    }

    private void prepareGrant(long rawXp, long todayStamp) {
        if (rawXp < 0L) {
            throw new IllegalArgumentException("rawXp must be >= 0, got " + rawXp);
        }
        if (dayStamp != todayStamp) {
            dayStamp = todayStamp;
            dailyXp = 0.0D;
        }
    }

    private long grantXpInternal(JobId job, double effective) {
        // 精确 double 折算 (拆分不变 + 小额进位): 以当日已累计有效经验为衰减指针, 累进当日与累计两份 double。
        long displayedXpBefore = job == null ? xp() : xp(job);
        dailyXp += effective;
        xp += effective;
        long displayedXp = job == null ? xp() : xp(job);
        this.level = JobXpCurve.levelForTotalXp(displayedXp);
        // 返回本次实际入账的 "整数有效经验" 增量 (按累计 floor 前后差额, 与显示口径一致)。
        return displayedXp - displayedXpBefore;
    }

    /** OP /job set 直接设级: 把 xp 设为达到该级所需累计经验并同步 level (不经衰减, 管理用; 清整数到整级)。 */
    public void setLevel(int newLevel) {
        if (newLevel < JobXpCurve.MIN_LEVEL || newLevel > JobXpCurve.MAX_LEVEL) {
            throw new IllegalArgumentException("level out of [1,10]: " + newLevel);
        }
        this.xp = JobXpCurve.cumulativeXpForLevel(newLevel);
        this.level = newLevel;
    }

    /**
     * 当日已结算有效经验距末档软上限 ({@link JobXpCurve#DAILY_SOFTCAP}) 的剩余额度 (/job list 显示用);
     * 已达末档返回 0。引用单一权威常量而非复制 3800 魔数, 与衰减末档边界永不漂移。
     */
    public long dailyRemaining() {
        return Math.max(0L, JobXpCurve.DAILY_SOFTCAP - dailyXp());
    }

    public long dailyRemaining(JobId job) {
        return Math.max(0L, JobXpPolicies.dailySoftCap(job) - dailyXp(job));
    }

    /** 全字段拷入本对象 (PlayerEvent.Clone 复制: 死亡重生/换维度均保留全部职业进度)。 */
    public void copyFrom(JobProgress other) {
        this.level = other.level;
        this.xp = other.xp;
        this.dailyXp = other.dailyXp;
        this.dayStamp = other.dayStamp;
        this.nanoReactorCdEndTick = other.nanoReactorCdEndTick;
    }

    // ---- 持久化 (第 2.2 节; deserialize 缺键给默认 level=1/xp=0) ----

    private static final String K_LEVEL = "level";
    private static final String K_XP = "xp";
    private static final String K_DAILY_XP = "dailyXp";
    private static final String K_DAY_STAMP = "dayStamp";
    private static final String K_NANO_REACTOR_CD = "nanoReactorCdEndTick";

    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        tag.putInt(K_LEVEL, level);
        // xp/dailyXp 存 double 保留小数位 (跨笔小额进位的精确累计); 旧 long 存档经 getDouble 数值兼容读回。
        tag.putDouble(K_XP, xp);
        tag.putDouble(K_DAILY_XP, dailyXp);
        tag.putLong(K_DAY_STAMP, dayStamp);
        tag.putLong(K_NANO_REACTOR_CD, nanoReactorCdEndTick);
        return tag;
    }

    public void deserializeNBT(CompoundTag tag) {
        deserializeNBT(tag, null);
    }

    public void deserializeNBT(CompoundTag tag, JobId job) {
        // 缺键给默认: 旧存档/新职业 key 首次出现时 level=1/xp=0 (框架 spec 第 2.2 节)。
        // getDouble 用 TAG_ANY_NUMERIC 读, 对旧版本以 long 存的 xp/dailyXp 数值兼容 (LongTag.getAsDouble)。
        this.level = tag.contains(K_LEVEL) ? tag.getInt(K_LEVEL) : JobXpCurve.MIN_LEVEL;
        this.xp = tag.contains(K_XP) ? tag.getDouble(K_XP) : 0.0D;
        this.dailyXp = tag.contains(K_DAILY_XP) ? tag.getDouble(K_DAILY_XP) : 0.0D;
        this.dayStamp = tag.contains(K_DAY_STAMP) ? tag.getLong(K_DAY_STAMP) : Long.MIN_VALUE;
        this.nanoReactorCdEndTick = tag.contains(K_NANO_REACTOR_CD) ? tag.getLong(K_NANO_REACTOR_CD) : 0L;
        // 防御: level 与 xp 不一致 (手改存档) 时以 xp 为准重算, 保证 level 永远 = 曲线派生值。
        this.level = JobXpCurve.levelForTotalXp(
                job == JobId.FARMER ? Math.round(this.xp) : (long) Math.floor(this.xp));
    }
}
