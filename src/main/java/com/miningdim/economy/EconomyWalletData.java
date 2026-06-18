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

    /** 玩家 UUID -> 钱包。无记录的玩家视为余额 0 (新玩家)。 */
    private final Map<UUID, PlayerWallet> wallets = new HashMap<>();

    /** 每日扣费累计: (玩家 UUID, dailyKey) -> 当日已扣量。键拼接 UUID 与 dailyKey, UTC 翻日清零。 */
    private final Map<String, DailyCharge> dailyCharges = new HashMap<>();

    /** 单条每日扣费计数 (当日累计 + 所属 UTC 日戳, 翻日清零)。 */
    private static final class DailyCharge {
        long amount;
        long dayStamp;

        DailyCharge(long amount, long dayStamp) {
            this.amount = amount;
            this.dayStamp = dayStamp;
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
        return data;
    }
}
