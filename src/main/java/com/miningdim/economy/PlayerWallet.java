package com.miningdim.economy;

import net.minecraft.nbt.CompoundTag;

/**
 * 单个玩家的双货币余额本体 (经济文档 0.3-44 "余额 -> Capability/SavedData" + 一/1.2 双货币)。
 *
 * 持有信用点 (CREDIT) 与青辉石 (AZURE) 两个 long 余额, 各自非负不变量。本类是纯数据载体:
 * 余额变更只经 {@link #tryDebit} (先校验后扣, 杜绝双花/透支) 与 {@link #credit} (入账, 溢出抛领域异常),
 * 不在内部 try/catch 生吞 —— 非法金额/溢出自然冒泡, 由调用方 (EconomyService -> 最外层) 兜底
 * (CLAUDE.md 异常纪律)。NBT 读写范式照抄 {@link PlayerAbuseState#save()} / {@link PlayerAbuseState#load}。
 *
 * 线程: 仅服务端主线程读写 (持有它的 {@link EconomyWalletData} 是 SavedData, 只在主线程访问)。
 */
public final class PlayerWallet {

    private static final String K_CREDIT = "credit";
    private static final String K_AZURE = "azure";

    /** 信用点余额 (非负)。 */
    private long credit;

    /** 青辉石余额 (非负)。 */
    private long azure;

    public PlayerWallet() {
    }

    /** 某货币当前余额。 */
    public long balance(Currency currency) {
        return switch (currency) {
            case CREDIT -> credit;
            case AZURE -> azure;
        };
    }

    /**
     * 先校验后扣 (杜绝双花/透支): 余额足则扣 amount 并返 true, 不足则不动余额返 false。
     *
     * @param amount 扣费量 (必须 &gt; 0)
     * @return 扣成功返 true; 余额不足不扣返 false
     * @throws EconomyException Reason {@link EconomyException.Reason#ILLEGAL_AMOUNT} 若 amount &lt;= 0
     */
    public boolean tryDebit(Currency currency, long amount) {
        requirePositive(amount, "debit");
        long bal = balance(currency);
        if (bal < amount) {
            return false;
        }
        setBalance(currency, bal - amount);
        return true;
    }

    /**
     * 入账 (faucet): 余额加 amount。
     *
     * @param amount 入账量 (必须 &gt; 0)
     * @throws EconomyException Reason {@link EconomyException.Reason#ILLEGAL_AMOUNT} 若 amount &lt;= 0;
     *                          Reason {@link EconomyException.Reason#BALANCE_OVERFLOW} 若入账后超 long 上界
     *                          (用 {@link Math#addExact} 检出, 防 7.3 M0 统计被脏数据击穿, 不静默回绕)
     */
    public void credit(Currency currency, long amount) {
        requirePositive(amount, "credit");
        long bal = balance(currency);
        long next;
        try {
            next = Math.addExact(bal, amount);
        } catch (ArithmeticException overflow) {
            // 把 JDK 的算术溢出转成货币层领域异常自然冒泡 (不回绕成负数击穿 M0 统计)。
            throw new EconomyException(EconomyException.Reason.BALANCE_OVERFLOW,
                    "Crediting " + amount + " " + currency + " overflows balance " + bal);
        }
        setBalance(currency, next);
    }

    /**
     * 管理员覆写余额 (运营调账 / 联调测试专用)。语义是"把余额直接设为 value", 不是增量。
     *
     * 与业务通道的边界: {@link #credit} / {@link #tryDebit} 受衰减主闸、每日计数、先校验后扣等约束, 是玩家行为
     * 驱动的资金变更; 本法刻意绕开全部这些约束, 只供 OP 命令层调用 (业务代码严禁调用, 否则等于开一个不受主闸
     * 约束的印钞口)。之所以另开方法而不把 private setBalance 直接放开: 私有版本无非负校验 (内部调用方已保证),
     * 直接放开会让外部传负值破坏本类"余额非负"不变量。
     *
     * @param value 目标余额 (必须 &gt;= 0; 允许 0 用于清零)
     * @throws EconomyException Reason {@link EconomyException.Reason#ILLEGAL_AMOUNT} 若 value &lt; 0
     */
    public void overwriteBalance(Currency currency, long value) {
        if (value < 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    "overwrite balance must be >= 0, got " + value);
        }
        setBalance(currency, value);
    }

    private void setBalance(Currency currency, long value) {
        switch (currency) {
            case CREDIT -> credit = value;
            case AZURE -> azure = value;
        }
    }

    private static void requirePositive(long amount, String op) {
        if (amount <= 0L) {
            throw new EconomyException(EconomyException.Reason.ILLEGAL_AMOUNT,
                    op + " amount must be > 0, got " + amount);
        }
    }

    // ---- NBT 读写 (随 EconomyWalletData 落 DimensionDataStorage; 范式同 PlayerAbuseState) ----

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putLong(K_CREDIT, credit);
        tag.putLong(K_AZURE, azure);
        return tag;
    }

    public static PlayerWallet load(CompoundTag tag) {
        PlayerWallet w = new PlayerWallet();
        w.credit = tag.getLong(K_CREDIT);
        w.azure = tag.getLong(K_AZURE);
        return w;
    }
}
