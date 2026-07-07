package com.miningdim.economy;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 全服双货币账本持久层 (经济文档 0.3-44 "余额 -> SavedData, 单服" + 7.3 M0 货币总量)。
 * 挂在矿山维度的 DimensionDataStorage, 数据文件名 {@value #DATA_NAME}。
 *
 * 以玩家 UUID 为键 (非 ServerPlayer 引用) 持有 {@link PlayerWallet}: UUID 键支持离线玩家入账
 * (经济文档 risks "离线玩家入账问题" —— 任务奖励/卖单成交可能落在离线玩家头上)。还持有每日扣费计数
 * (供 {@link IEconomyService#tryChargeDaily}), 与职业经验软上限共用 UTC 翻日时钟 (0.3-78)。
 *
 * 线程纪律 (D8 / 同 {@link com.miningdim.persistence.MiningSavedData}): 仅服务端主线程读写; 任何字段
 * 变更后必须 {@link #setDirty()} 否则不落盘。1.20.1 的 computeIfAbsent 为三参 (load, create, name);
 * SavedData.Factory 是 1.20.2+ 才引入, 本目标版本不可用 (已校验)。
 */
public final class EconomyWalletData extends SavedData {

    /** DimensionDataStorage 数据文件名。 */
    public static final String DATA_NAME = "miningdim_economy";

    private static final String K_WALLETS = "wallets";
    private static final String K_UUID = "uuid";
    private static final String K_WALLET = "wallet";

    private static final String K_DAILY = "dailyCharges";
    private static final String K_DAILY_KEY = "key";
    private static final String K_DAILY_AMOUNT = "amount";
    private static final String K_DAILY_STAMP = "dayStamp";

    /** faucet 条目的衰减主闸小数余量 carry (第十一章决策 2; 仅 faucet 侧用, 扣费侧无此键)。 */
    private static final String K_FAUCET_CARRY = "creditCarry";

    /** 玩家 UUID -> 钱包。无记录的玩家视为余额 0 (新玩家)。 */
    private final Map<UUID, PlayerWallet> wallets = new HashMap<>();

    private static final String K_FAUCET = "dailyFaucets";

    /** 每日扣费累计: (玩家 UUID, dailyKey) -> 当日已扣量。键拼接 UUID 与 dailyKey, UTC 翻日清零。 */
    private final Map<String, DailyCharge> dailyCharges = new HashMap<>();

    /**
     * 每日入账累计 (faucet 侧, 经济文档 8.5): (玩家 UUID, faucetKey) -> 当日已入账的"原始信用点"累计。
     * 与扣费侧 {@link #dailyCharges} 对称但语义相反 (累计 faucet 入账而非扣费), 复用同一 UTC 翻日时钟。
     * 供 {@link #recordFaucetGrant} 在发币前取本次入账前的累计 n0, 交 {@link AbuseGuard#faucetCreditAfterDecay}
     * 算衰减后实发额; 矿工卖矿 / 农夫卖菜等所有 faucet 共用同一 faucetKey 命名空间即并入全服每人每日统一软上限。
     */
    private final Map<String, DailyCharge> dailyFaucets = new HashMap<>();

    /**
     * 单条每日计数 (当日累计 + 所属 UTC 日戳, 翻日清零)。
     *
     * faucet 侧附带 {@link #creditCarry} 小数余量 (第十一章决策 2 "小额不被逐笔取整吞光"): 衰减主闸算出的精确实发额是
     * 小数 (例深档单矿 5×0.36=1.8), 整数部分落账本, 小数部分累进本字段, 跨笔累加满 1 再随下次入账落账。扣费侧 (dailyCharges)
     * 不用此字段恒 0 (扣费是整数量纲, 无小数衰减)。随存档持久化保证跨重启不丢余量。
     */
    private static final class DailyCharge {
        long amount;
        long dayStamp;
        double creditCarry;

        DailyCharge(long amount, long dayStamp) {
            this(amount, dayStamp, 0.0D);
        }

        DailyCharge(long amount, long dayStamp, double creditCarry) {
            this.amount = amount;
            this.dayStamp = dayStamp;
            this.creditCarry = creditCarry;
        }
    }

    public EconomyWalletData() {
    }

    /**
     * 取/建矿山维度的账本持久数据。必须传矿山维度的 ServerLevel, 数据随该维度存档落盘
     * (与 {@link com.miningdim.persistence.MiningSavedData#get} 同维度同范式)。
     */
    public static EconomyWalletData get(ServerLevel miningLevel) {
        return miningLevel.getDataStorage().computeIfAbsent(
                EconomyWalletData::load, EconomyWalletData::new, DATA_NAME);
    }

    /** 取某玩家钱包 (无则建空钱包并入表, 标脏)。 */
    public PlayerWallet wallet(UUID playerId) {
        PlayerWallet w = wallets.get(playerId);
        if (w == null) {
            w = new PlayerWallet();
            wallets.put(playerId, w);
            setDirty();
        }
        return w;
    }

    /** 某玩家某货币余额 (无记录返回 0; 只读, 不建钱包不标脏)。 */
    public long balance(UUID playerId, Currency currency) {
        PlayerWallet w = wallets.get(playerId);
        return w == null ? 0L : w.balance(currency);
    }

    /**
     * 先校验后扣 (sink): 委派 {@link PlayerWallet#tryDebit}; 扣成功标脏返 true, 余额不足返 false (不标脏)。
     */
    public boolean tryDebit(UUID playerId, Currency currency, long amount) {
        boolean ok = wallet(playerId).tryDebit(currency, amount);
        if (ok) {
            setDirty();
        }
        return ok;
    }

    /** 入账 (faucet): 委派 {@link PlayerWallet#credit} (溢出抛 BALANCE_OVERFLOW 冒泡), 标脏。 */
    public void credit(UUID playerId, Currency currency, long amount) {
        wallet(playerId).credit(currency, amount);
        setDirty();
    }

    /**
     * 含每日上限的事务扣费: 当日经同一 (playerId, dailyKey) 累计扣费 + 本次 &lt;= dailyCap 且余额足才扣。
     * UTC 翻日先清零该计数 (与经验软上限共用 epochDay 口径)。
     *
     * @param todayStamp 当前 UTC 日戳 (epochDay; 由调用方传入, 与 AbuseGuard.currentPlayerDayStamp 同口径)
     * @return 扣成功返 true; 超每日上限或余额不足返 false (不扣不计)
     */
    public boolean tryChargeDaily(UUID playerId, Currency currency, long amount,
                                  String dailyKey, long dailyCap, long todayStamp) {
        if (amount <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "daily charge amount must be > 0, got " + amount);
        }
        String key = playerId + "|" + dailyKey;
        DailyCharge dc = dailyCharges.get(key);
        long spentToday = (dc == null || dc.dayStamp != todayStamp) ? 0L : dc.amount;
        if (spentToday + amount > dailyCap) {
            return false;
        }
        // 先扣余额 (先校验后扣); 余额不足则不计每日计数。
        if (!wallet(playerId).tryDebit(currency, amount)) {
            return false;
        }
        dailyCharges.put(key, new DailyCharge(spentToday + amount, todayStamp));
        setDirty();
        return true;
    }

    /**
     * 累计一次 faucet 当日"原始信用点"入账并返回本次入账前的累计值 n0 (经济文档 8.5 全服每人每日信用点软上限)。
     * UTC 翻日先清零该 (playerId, faucetKey) 计数 (与扣费侧 {@link #tryChargeDaily} 共用 epochDay 口径)。
     *
     * 本法只维护"原始入账累计"计数器, 不做衰减也不动余额 (衰减由 {@link AbuseGuard#faucetCreditAfterDecay} 算,
     * 实发由调用方经 {@link #credit} 落账)。累计的是 rawAmount (拟入账原始额) 而非实发额, 使衰减档随玩家当日 faucet
     * 总产出单调推进 (实发额因衰减低于 raw, 若累计实发额会令衰减永不深入)。
     *
     * @param playerId   玩家 UUID
     * @param faucetKey  faucet 计数键 (矿工卖矿 / 农夫卖菜等共用命名空间即并入同一软上限)
     * @param rawAmount  本次拟入账的原始信用点 (&gt; 0)
     * @param todayStamp 当前 UTC 日戳 (epochDay; 与 AbuseGuard.currentPlayerDayStamp 同口径)
     * @return 本次入账前当日已累计的原始信用点 n0 (翻日后为 0)
     */
    public long recordFaucetGrant(UUID playerId, String faucetKey, long rawAmount, long todayStamp) {
        if (rawAmount <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "faucet grant amount must be > 0, got " + rawAmount);
        }
        String key = playerId + "|" + faucetKey;
        DailyCharge dc = dailyFaucets.get(key);
        boolean newDay = dc == null || dc.dayStamp != todayStamp;
        long before = newDay ? 0L : dc.amount;
        // 翻日: 重置累计与 carry; 同日: 累加 raw, carry 原样保留 (carry 由 creditFaucetWithCarry 推进)。
        double carry = newDay ? 0.0D : dc.creditCarry;
        dailyFaucets.put(key, new DailyCharge(before + rawAmount, todayStamp, carry));
        setDirty();
        return before;
    }

    /**
     * 把衰减主闸算出的精确实发额 (小数) 累进 carry 并落整数部分到账本 (第十一章决策 2 "小额不被逐笔取整吞光")。
     * 与 {@link #recordFaucetGrant} 操作同一 (playerId, faucetKey) 条目: recordFaucetGrant 先推进当日原始累计并返回 n0,
     * 调用方据 n0 经 {@link AbuseGuard#faucetCreditAfterDecayExact} 算精确实发 exactEffective, 再调本法落账。
     *
     * carry 机制 (对标 {@link com.miningdim.job.JobProgress} 以 double 累计有效经验、仅读出时 floor): 把 exactEffective
     * 加入条目 carry, 取整数部分 payout 作本次实发, 余下小数留 carry 跨笔累进。深档单矿仅 1.8 实发时不再逐笔 floor 归零,
     * 而是 1.8 -> 落 1 留 0.8, 下次再来 1.8 -> 2.6 落 2 留 0.6, 主闸深档薄收益不被吞光。整数 payout 由本法不直接动余额,
     * 返回给调用方经 {@link #credit} 落账 (统一走 Math.addExact 防溢出, 经济文档 7.3)。
     *
     * @param playerId        玩家 UUID
     * @param faucetKey       faucet 计数键 (须与本次 recordFaucetGrant 同键, 锁定同一当日条目)
     * @param exactEffective  衰减主闸精确实发额 (&gt;= 0; 由 faucetCreditAfterDecayExact 算)
     * @param todayStamp      当前 UTC 日戳 (与 recordFaucetGrant 同口径)
     * @return 本次落账的整数实发信用点 (&gt;= 0; 小数余量留 carry)
     */
    public long creditFaucetWithCarry(UUID playerId, String faucetKey, double exactEffective, long todayStamp) {
        if (exactEffective < 0.0D) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "faucet exactEffective must be >= 0, got " + exactEffective);
        }
        String key = playerId + "|" + faucetKey;
        DailyCharge dc = dailyFaucets.get(key);
        // recordFaucetGrant 必先于本法对同键调用 (grantDaily 内顺序保证), 故条目必存在且同日; 防御性按缺失/翻日归零 carry。
        double carryBefore = (dc == null || dc.dayStamp != todayStamp) ? 0.0D : dc.creditCarry;
        double pooled = carryBefore + exactEffective;
        long payout = (long) Math.floor(pooled);
        double carryAfter = pooled - payout;
        if (dc != null && dc.dayStamp == todayStamp) {
            dc.creditCarry = carryAfter;
        } else {
            // 极端时序 (无前置 recordFaucetGrant): 以本次 carry 建条目, amount 留 0 (raw 计数由 recordFaucetGrant 专管)。
            dailyFaucets.put(key, new DailyCharge(0L, todayStamp, carryAfter));
        }
        setDirty();
        return payout;
    }

    /**
     * 青辉石 faucet 每人每日产出硬上限入账 (经济文档 8.5 战斗 faucet 并入每人每日上限; economy-02 修复)。
     * 复用与信用点 faucet 同一 {@link #dailyFaucets} (playerId, faucetKey) 当日累计计数器 + UTC 翻日范式, 但语义是
     * "硬截断"而非信用点侧的"逐档衰减": 当日经同一 (playerId, faucetKey) 累计入账已达 dailyCap 则本批一律不发, 未达则
     * 只发到刚好填满 dailyCap 的部分 (超出 amount 被截断丢弃), 把当日累计推进到 min(prior+amount, dailyCap)。
     *
     * 为何硬截断而非信用点的衰减: 青辉石量纲小 (单次 2-10) 且无 per-unit steering 需求, "超额直接不发"比"逐档小数衰减"
     * 对玩家更直观, 也无需 carry 跨笔累进 (整数量纲)。本法直接 {@link #credit} 落 AZURE 余额并标脏, 返回实际入账量。
     *
     * @param playerId   玩家 UUID
     * @param faucetKey  青辉石 faucet 计数键 ({@link EconomyConstants#AZURE_DAILY_FAUCET_KEY})
     * @param amount     本次拟入账的青辉石原始量 (&gt; 0)
     * @param dailyCap   每人每日青辉石产出硬上限 (&gt; 0; 当日累计达此值后本批被截断)
     * @param todayStamp 当前 UTC 日戳 (epochDay; 与 {@link AbuseGuard#currentPlayerDayStamp} 同口径)
     * @return 本次实际入账的青辉石 (0 表示当日已撞上限; &gt;0 且 &lt; amount 表示被截断到上限)
     */
    public long creditAzureDaily(UUID playerId, String faucetKey, long amount, long dailyCap, long todayStamp) {
        if (amount <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "azure daily faucet amount must be > 0, got " + amount);
        }
        if (dailyCap <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "azure daily faucet cap must be > 0, got " + dailyCap);
        }
        String key = playerId + "|" + faucetKey;
        DailyCharge dc = dailyFaucets.get(key);
        boolean newDay = dc == null || dc.dayStamp != todayStamp;
        long grantedToday = newDay ? 0L : dc.amount;
        long room = dailyCap - grantedToday;
        if (room <= 0L) {
            return 0L; // 当日已撞上限: 本批全截断, 不入账 (不标脏, 无状态变更)。
        }
        long credited = Math.min(amount, room);
        dailyFaucets.put(key, new DailyCharge(grantedToday + credited, todayStamp));
        credit(playerId, Currency.AZURE, credited); // credit 内部已 setDirty。
        return credited;
    }

    @Override
    public CompoundTag save(CompoundTag tag) {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, PlayerWallet> e : wallets.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putUUID(K_UUID, e.getKey());
            entry.put(K_WALLET, e.getValue().save());
            list.add(entry);
        }
        tag.put(K_WALLETS, list);

        ListTag daily = new ListTag();
        for (Map.Entry<String, DailyCharge> e : dailyCharges.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString(K_DAILY_KEY, e.getKey());
            entry.putLong(K_DAILY_AMOUNT, e.getValue().amount);
            entry.putLong(K_DAILY_STAMP, e.getValue().dayStamp);
            daily.add(entry);
        }
        tag.put(K_DAILY, daily);

        ListTag faucets = new ListTag();
        for (Map.Entry<String, DailyCharge> e : dailyFaucets.entrySet()) {
            CompoundTag entry = new CompoundTag();
            entry.putString(K_DAILY_KEY, e.getKey());
            entry.putLong(K_DAILY_AMOUNT, e.getValue().amount);
            entry.putLong(K_DAILY_STAMP, e.getValue().dayStamp);
            entry.putDouble(K_FAUCET_CARRY, e.getValue().creditCarry);
            faucets.add(entry);
        }
        tag.put(K_FAUCET, faucets);
        return tag;
    }

    public static EconomyWalletData load(CompoundTag tag) {
        EconomyWalletData data = new EconomyWalletData();
        ListTag list = tag.getList(K_WALLETS, Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.hasUUID(K_UUID)) {
                data.wallets.put(entry.getUUID(K_UUID), PlayerWallet.load(entry.getCompound(K_WALLET)));
            }
        }
        ListTag daily = tag.getList(K_DAILY, Tag.TAG_COMPOUND);
        for (int i = 0; i < daily.size(); i++) {
            CompoundTag entry = daily.getCompound(i);
            data.dailyCharges.put(entry.getString(K_DAILY_KEY),
                    new DailyCharge(entry.getLong(K_DAILY_AMOUNT), entry.getLong(K_DAILY_STAMP)));
        }
        ListTag faucets = tag.getList(K_FAUCET, Tag.TAG_COMPOUND);
        for (int i = 0; i < faucets.size(); i++) {
            CompoundTag entry = faucets.getCompound(i);
            // K_FAUCET_CARRY 缺失 (旧存档) 时 getDouble 返回 0.0, 向后兼容无 carry 的历史 faucet 条目。
            data.dailyFaucets.put(entry.getString(K_DAILY_KEY),
                    new DailyCharge(entry.getLong(K_DAILY_AMOUNT), entry.getLong(K_DAILY_STAMP),
                            entry.getDouble(K_FAUCET_CARRY)));
        }
        return data;
    }
}
